import { CONFIG } from '../config';
import { httpClient } from '../api/client';
import { decodeBase64Utf8 } from './p2p-utils';
import { decodeTunnelFrame } from './mux-protocol';
import { P2PWebSocketMock } from './p2p-ws-mock';
import type { RelayMuxTransport } from './relay-mux-session';
import {
  MSG_TYPE_WS_DATA, MSG_TYPE_HTTP_CHUNK,
  WS_DATA_TEXT, WS_DATA_BINARY,
  HTTP_CHUNK_DATA, HTTP_CHUNK_FIN,
} from '@shared/constants.js';

export class P2PConnectionManager extends EventTarget {

  private pc: RTCPeerConnection | null = null;
  private dc: RTCDataChannel | null = null;
  private targetDeviceId: string | null = null;
  private connectionState: 'disconnected' | 'connecting' | 'connected' = 'disconnected';
  private connectTimeoutTimer: any = null;
  private disconnectedGraceTimer: any = null;

  private pendingHttpResponses = new Map<string, {
    resolve: (val: any) => void;
    reject: (err: any) => void;
    timer: any;
    chunks: Uint8Array[];
    statusCode?: number;
    headers?: any;
  }>();

  private activeWsMocks = new Map<string, any>();

  constructor() {
    super();
  }

  public isP2PActive(): boolean {
    return this.connectionState === 'connected' && this.dc?.readyState === 'open';
  }

  public createMuxTransport(deviceId: string): RelayMuxTransport | null {
    if (!this.isP2PActive() || this.targetDeviceId !== deviceId || !this.dc) {
      return null;
    }
    return new P2PDataChannelTransport(this.dc);
  }

  public registerWsMock(id: string, wsMock: any) {
    this.activeWsMocks.set(id, wsMock);
  }

  public unregisterWsMock(id: string) {
    this.activeWsMocks.delete(id);
  }

  public async connectToDevice(deviceId: string): Promise<void> {
    if (this.targetDeviceId === deviceId && this.connectionState !== 'disconnected') {
      return;
    }

    this.disconnect();
    this.targetDeviceId = deviceId;
    this.connectionState = 'connecting';
    console.log(`[P2P] Starting WebRTC connection to device: ${deviceId}`);

    // P2P 连接超时降级机制
    this.connectTimeoutTimer = setTimeout(() => {
      if (this.connectionState === 'connecting') {
        console.warn('[P2P] WebRTC connection timeout, falling back to relay.');
        this.disconnect();
      }
    }, CONFIG.p2pConnectTimeoutMs);

    try {
      const pc = new RTCPeerConnection({
        iceServers: CONFIG.stunServers
      });
      this.pc = pc;

      // 监听 PeerConnection 连接状态变化，捕获物理层断连并重置假死状态
      pc.addEventListener('connectionstatechange', () => {
        console.log(`[P2P] RTCPeerConnection state changed to: ${pc.connectionState}`);
        if (pc.connectionState === 'connected') {
          this.clearDisconnectedGraceTimer();
        } else if (pc.connectionState === 'disconnected') {
          this.scheduleDisconnectedGraceTimeout(pc);
        } else if (pc.connectionState === 'failed' || pc.connectionState === 'closed') {
          this.disconnect();
        }
      });

      // 监听本地 ICE candidates，并立即向中转信令接口进行推流 (Trickle ICE)
      pc.addEventListener('icecandidate', (event) => {
        if (event.candidate && this.targetDeviceId === deviceId) {
          httpClient('/api/p2p/ice', {
            method: 'POST',
            body: JSON.stringify({
              candidate: event.candidate,
              deviceId
            })
          }).catch(err => {
            console.error('[P2P] Failed to send local ICE candidate:', err);
          });
        }
      });

      // 创建可靠 ordered DataChannel
      const dc = pc.createDataChannel('tunnel', { negotiated: false });
      this.dc = dc;
      dc.binaryType = 'arraybuffer';

      this.setupDataChannel(dc);

      // 创建 SDP Offer 并保存本地状态
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);

      if (this.targetDeviceId !== deviceId) {
        pc.close();
        return;
      }

      // 携带 SDP Offer 发送 HTTP 握手请求，无须等待 ICE 收集完毕直接发送得到 Answer
      const response = await httpClient('/api/p2p/offer', {
        method: 'POST',
        body: JSON.stringify({
          sdp: pc.localDescription!.sdp,
          deviceId
        })
      });

      if (this.targetDeviceId !== deviceId) {
        pc.close();
        return;
      }

      // 应用 Answer SDP
      await pc.setRemoteDescription(new RTCSessionDescription({
        type: 'answer',
        sdp: response.sdp
      }));

      console.log('[P2P] WebRTC local description and candidates applied, trickle ICE running...');
    } catch (err) {
      console.error('[P2P] WebRTC setup negotiation failed:', err);
      this.disconnect();
    }
  }

  public handleRemoteCandidate(candidate: any) {
    if (this.pc && (this.connectionState === 'connecting' || this.connectionState === 'connected')) {
      console.log('[P2P] Adding remote ICE candidate');
      this.pc.addIceCandidate(new RTCIceCandidate({
        candidate: candidate.candidate,
        sdpMid: candidate.sdpMid || '0',
        sdpMLineIndex: candidate.sdpMLineIndex !== undefined ? candidate.sdpMLineIndex : 0
      })).catch(err => {
        console.error('[P2P] Failed to add remote ICE candidate:', err);
      });
    }
  }

  private setupDataChannel(dc: RTCDataChannel) {
    dc.addEventListener('open', () => {
      console.log('[P2P] DataChannel opened successfully');
      this.connectionState = 'connected';
      this.dispatchEvent(new CustomEvent('p2p:connected', { detail: { deviceId: this.targetDeviceId } }));
      this.clearDisconnectedGraceTimer();
      if (this.connectTimeoutTimer) {
        clearTimeout(this.connectTimeoutTimer);
        this.connectTimeoutTimer = null;
      }
    });

    dc.addEventListener('close', () => {
      console.log('[P2P] DataChannel closed');
      this.disconnect();
    });

    dc.addEventListener('message', (event) => {
      const data = event.data;
      if (typeof data === 'string') {
        try {
          const msg = JSON.parse(data);
          if (msg.requestId) {
            const pending = this.pendingHttpResponses.get(msg.requestId);
            if (pending) {
              if (msg.type === 'http-response') {
                pending.statusCode = msg.statusCode;
                pending.headers = msg.headers;
                if (!msg.hasChunks) {
                  const body = msg.bodyEncoding === 'base64'
                    ? decodeBase64Utf8(msg.body)
                    : msg.body;
                  pending.resolve({ statusCode: msg.statusCode, headers: msg.headers, body });
                  clearTimeout(pending.timer);
                  this.pendingHttpResponses.delete(msg.requestId);
                }
              } else if (msg.type === 'http-error') {
                pending.reject(new Error(msg.message || 'P2P HTTP request failed'));
                clearTimeout(pending.timer);
                this.pendingHttpResponses.delete(msg.requestId);
              }
            }
          } else if (msg.tunnelConnectionId) {
            const wsMock = this.activeWsMocks.get(msg.tunnelConnectionId);
            if (wsMock) {
              if (msg.type === 'ws-connected') {
                wsMock.onConnected();
              } else if (msg.type === 'ws-close') {
                wsMock.onClose(msg.code || 1000, msg.reason || '');
              } else if (msg.type === 'ws-error') {
                wsMock.onError(msg.message || 'P2P WS Error');
              }
            }
          }
        } catch (err) {
          console.error('[P2P] Failed to parse control message:', err);
        }
      } else {
        // 二进制协议帧解包
        const frame = decodeTunnelFrame(data);
        if (!frame) return;
        const { msgType, id, extraByte, payload } = frame;

        if (msgType === MSG_TYPE_WS_DATA) {
          const wsMock = this.activeWsMocks.get(id);
          if (wsMock) {
            if (extraByte === WS_DATA_BINARY) {
              const ab = payload.buffer.slice(payload.byteOffset, payload.byteOffset + payload.byteLength);
              wsMock.onMessage(ab);
            } else {
              const decoder = new TextDecoder();
              const text = decoder.decode(payload);
              wsMock.onMessage(text);
            }
          }
        } else if (msgType === MSG_TYPE_HTTP_CHUNK) {
          const pending = this.pendingHttpResponses.get(id);
          if (pending) {
            if (extraByte === HTTP_CHUNK_DATA) {
              pending.chunks.push(payload);
            } else if (extraByte === HTTP_CHUNK_FIN) {
              const totalLength = pending.chunks.reduce((acc, chunk) => acc + chunk.length, 0);
              const combined = new Uint8Array(totalLength);
              let offset = 0;
              for (const chunk of pending.chunks) {
                combined.set(chunk, offset);
                offset += chunk.length;
              }
              const decoder = new TextDecoder();
              const body = decoder.decode(combined);
              pending.resolve({ statusCode: pending.statusCode, headers: pending.headers, body });
              clearTimeout(pending.timer);
              this.pendingHttpResponses.delete(id);
            }
          }
        }
      }
    });
  }

  public sendControlMessage(msg: any): Promise<void> {
    return new Promise((resolve, reject) => {
      if (!this.isP2PActive()) {
        reject(new Error('P2P connection not active'));
        return;
      }
      try {
        this.dc!.send(JSON.stringify(msg));
        resolve();
      } catch (err) {
        reject(err);
      }
    });
  }

  public sendBinaryFrame(frame: Uint8Array): void {
    if (!this.isP2PActive()) return;
    try {
      const data = frame.buffer.slice(frame.byteOffset, frame.byteOffset + frame.byteLength) as ArrayBuffer;
      this.dc!.send(data);
    } catch (err) {
      console.error('[P2P] Failed to send binary frame:', err);
    }
  }

  public sendRequest(path: string, options: RequestInit = {}): Promise<any> {
    return new Promise((resolve, reject) => {
      if (!this.isP2PActive()) {
        reject(new Error('P2P connection not active'));
        return;
      }

      const requestId = 'req_' + Math.random().toString(36).substring(2, 15);

      const timer = setTimeout(() => {
        if (this.pendingHttpResponses.has(requestId)) {
          this.pendingHttpResponses.delete(requestId);
          reject(new Error('P2P request timeout'));
        }
      }, CONFIG.p2pRequestTimeoutMs);

      this.pendingHttpResponses.set(requestId, {
        resolve,
        reject,
        timer,
        chunks: []
      });

      const headers = {
        'Content-Type': 'application/json',
        ...(options.headers as Record<string, string> || {})
      };

      const msg = {
        type: 'http-request',
        requestId,
        method: options.method || 'GET',
        path,
        headers: headers,
        bodyEncoding: 'none',
        body: options.body || '',
        hasChunks: false
      };

      this.dc!.send(JSON.stringify(msg));
    });
  }

  public createWebSocketMock(wsUrl: string, protocols?: string | string[]): P2PWebSocketMock {
    return new P2PWebSocketMock(wsUrl, this, protocols);
  }

  public disconnect() {
    console.log('[P2P] Disconnecting WebRTC direct connection');
    const previousDeviceId = this.targetDeviceId;
    const wasConnected = this.connectionState === 'connected';
    this.connectionState = 'disconnected';

    this.clearDisconnectedGraceTimer();
    if (this.connectTimeoutTimer) {
      clearTimeout(this.connectTimeoutTimer);
      this.connectTimeoutTimer = null;
    }

    if (this.dc) {
      try { this.dc.close(); } catch {}
      this.dc = null;
    }

    if (this.pc) {
      try { this.pc.close(); } catch {}
      this.pc = null;
    }

    for (const [id, pending] of this.pendingHttpResponses.entries()) {
      clearTimeout(pending.timer);
      pending.reject(new Error('P2P disconnected'));
    }
    this.pendingHttpResponses.clear();

    for (const wsMock of this.activeWsMocks.values()) {
      try { wsMock.onClose(1006, 'P2P disconnected'); } catch {}
    }
    this.activeWsMocks.clear();

    if (wasConnected && previousDeviceId) {
      this.dispatchEvent(new CustomEvent('p2p:disconnected', { detail: { deviceId: previousDeviceId } }));
    }
  }

  private scheduleDisconnectedGraceTimeout(pc: RTCPeerConnection) {
    if (this.disconnectedGraceTimer) return;
    this.disconnectedGraceTimer = setTimeout(() => {
      this.disconnectedGraceTimer = null;
      if (this.pc === pc && pc.connectionState === 'disconnected') {
        console.warn('[P2P] WebRTC remained disconnected, falling back to relay.');
        this.disconnect();
      }
    }, CONFIG.p2pDisconnectedGraceMs);
  }

  private clearDisconnectedGraceTimer() {
    if (this.disconnectedGraceTimer) {
      clearTimeout(this.disconnectedGraceTimer);
      this.disconnectedGraceTimer = null;
    }
  }
}

export const p2pManager = new P2PConnectionManager();

class P2PDataChannelTransport extends EventTarget implements RelayMuxTransport {
  constructor(private dc: RTCDataChannel) {
    super();
    dc.addEventListener('open', () => this.dispatchEvent(new Event('open')));
    dc.addEventListener('message', (event) => this.dispatchEvent(new MessageEvent('message', { data: event.data })));
    dc.addEventListener('close', () => this.dispatchEvent(new CloseEvent('close', { code: 1000, reason: 'p2p datachannel closed', wasClean: true })));
    dc.addEventListener('error', () => this.dispatchEvent(new Event('error')));
  }

  get readyState(): number {
    switch (this.dc.readyState) {
      case 'connecting':
        return WebSocket.CONNECTING;
      case 'open':
        return WebSocket.OPEN;
      case 'closing':
        return WebSocket.CLOSING;
      default:
        return WebSocket.CLOSED;
    }
  }

  sendText(value: string): void {
    this.dc.send(value);
  }

  sendBinary(value: Uint8Array): void {
    const data = value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength) as ArrayBuffer;
    this.dc.send(data);
  }

  close(): void {
    this.dc.close();
  }
}
