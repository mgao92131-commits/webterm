import { store, api } from '../store';

export const MSG_TYPE_WS_DATA = 0x01;
export const MSG_TYPE_HTTP_CHUNK = 0x02;

export const WS_DATA_TEXT = 0x01;
export const WS_DATA_BINARY = 0x02;

export const HTTP_CHUNK_DATA = 0x01;
export const HTTP_CHUNK_FIN = 0x02;

// 解决 atob 无法解码含有 UTF-8 多字节字符（如中文）的 base64 字符串问题
export function decodeBase64Utf8(base64: string): string {
  const binaryStr = atob(base64);
  const bytes = new Uint8Array(binaryStr.length);
  for (let i = 0; i < binaryStr.length; i++) {
    bytes[i] = binaryStr.charCodeAt(i);
  }
  return new TextDecoder().decode(bytes);
}

export function encodeTunnelFrame(msgType: number, id: string, extraByte: number, payload: Uint8Array | null): Uint8Array {
  const encoder = new TextEncoder();
  const idBytes = encoder.encode(id);
  const payloadBytes = payload || new Uint8Array(0);
  
  const frame = new Uint8Array(1 + 1 + idBytes.length + 1 + payloadBytes.length);
  frame[0] = msgType;
  frame[1] = idBytes.length;
  frame.set(idBytes, 2);
  frame[2 + idBytes.length] = extraByte;
  frame.set(payloadBytes, 3 + idBytes.length);
  return frame;
}

export function decodeTunnelFrame(data: ArrayBuffer) {
  const buf = new Uint8Array(data);
  if (buf.length < 3) return null;
  const msgType = buf[0];
  const idLen = buf[1];
  if (buf.length < 2 + idLen + 1) return null;
  
  const decoder = new TextDecoder();
  const id = decoder.decode(buf.subarray(2, 2 + idLen));
  const extraByte = buf[2 + idLen];
  const payload = buf.subarray(3 + idLen);
  return { msgType, id, extraByte, payload };
}

export class P2PConnectionManager {
  private pc: RTCPeerConnection | null = null;
  private dc: RTCDataChannel | null = null;
  private targetDeviceId: string | null = null;
  private connectionState: 'disconnected' | 'connecting' | 'connected' = 'disconnected';
  private connectTimeoutTimer: any = null;
  
  private pendingHttpResponses = new Map<string, {
    resolve: (val: any) => void;
    reject: (err: any) => void;
    timer: any;
    chunks: Uint8Array[];
    statusCode?: number;
    headers?: any;
  }>();
  
  private activeWsMocks = new Map<string, any>();

  public isP2PActive(): boolean {
    return this.connectionState === 'connected' && this.dc?.readyState === 'open';
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

    // 3秒连接超时降级机制
    this.connectTimeoutTimer = setTimeout(() => {
      if (this.connectionState === 'connecting') {
        console.warn('[P2P] WebRTC connection timeout, falling back to relay.');
        this.disconnect();
      }
    }, 3000);

    try {
      const pc = new RTCPeerConnection({
        iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
      });
      this.pc = pc;

      // 监听本地 ICE candidates，并立即向中转信令接口进行推流 (Trickle ICE)
      pc.addEventListener('icecandidate', (event) => {
        if (event.candidate && this.targetDeviceId === deviceId) {
          api('/api/p2p/ice', {
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
      const response = await api('/api/p2p/offer', {
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
      store.p2pActive = true; // 同步前端 UI 状态指示灯
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
                  // 使用能解码 UTF-8 多字节字符的辅助函数
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
              // 终端回传二进制帧，提取 ArrayBuffer 直接投递，不要过 TextDecoder
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
      this.dc!.send(frame);
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
      }, 30000);

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
    this.connectionState = 'disconnected';
    store.p2pActive = false; // 同步前端 UI 状态指示灯
    
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
  }
}

export const p2pManager = new P2PConnectionManager();

export class P2PWebSocketMock extends EventTarget {
  public readyState: number = 0; // CONNECTING
  private tunnelConnectionId: string;
  private p2pManager: P2PConnectionManager;
  private path: string;
  private protocols: string[];

  constructor(wsUrl: string, p2pManager: P2PConnectionManager, protocols?: string | string[]) {
    super();
    this.p2pManager = p2pManager;
    this.protocols = typeof protocols === 'string' ? [protocols] : (protocols || []);
    
    // 转换为标准 http 协议以解析 pathname 与 search
    const url = new URL(wsUrl.replace(/^ws/, 'http'));
    this.path = url.pathname + url.search;
    this.tunnelConnectionId = 'tc_' + Math.random().toString(36).substring(2, 15);
    
    setTimeout(() => this.connect(), 0);
  }

  private async connect() {
    this.p2pManager.registerWsMock(this.tunnelConnectionId, this);
    try {
      await this.p2pManager.sendControlMessage({
        type: 'ws-connect',
        tunnelConnectionId: this.tunnelConnectionId,
        path: this.path,
        headers: {},
        protocols: this.protocols
      });
    } catch (err) {
      this.readyState = 3; // CLOSED
      this.dispatchEvent(new Event('error'));
      this.dispatchEvent(new CloseEvent('close', { code: 1006, reason: 'P2P WS connection failed' }));
    }
  }

  public onConnected() {
    this.readyState = 1; // OPEN
    this.dispatchEvent(new Event('open'));
  }

  public onMessage(data: any) {
    this.dispatchEvent(new MessageEvent('message', { data }));
  }

  public onClose(code: number, reason: string) {
    this.readyState = 3; // CLOSED
    this.p2pManager.unregisterWsMock(this.tunnelConnectionId);
    this.dispatchEvent(new CloseEvent('close', { code, reason }));
  }

  public onError(message: string) {
    this.dispatchEvent(new MessageEvent('error', { data: message }));
  }

  public send(data: string) {
    if (this.readyState !== 1) {
      throw new Error('WebSocket is not in OPEN state');
    }
    const encoder = new TextEncoder();
    const payload = encoder.encode(data);
    const frame = encodeTunnelFrame(MSG_TYPE_WS_DATA, this.tunnelConnectionId, WS_DATA_TEXT, payload);
    this.p2pManager.sendBinaryFrame(frame);
  }

  public close(code = 1000, reason = '') {
    if (this.readyState === 2 || this.readyState === 3) return;
    this.readyState = 2; // CLOSING
    this.p2pManager.sendControlMessage({
      type: 'ws-close',
      tunnelConnectionId: this.tunnelConnectionId,
      code,
      reason
    }).finally(() => {
      this.onClose(code, reason);
    });
  }
}
