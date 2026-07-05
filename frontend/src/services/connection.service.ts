/**
 * 连接业务服务。
 * 统一管理 P2P、RelayMux 与 Manager WebSocket 连接。
 * 负责把网络层事件同步到应用状态（store.p2pActive 等）。
 */

import { store } from '../store';
import { parseSessionId } from '../utils/session';
import { p2pManager } from '../lib/p2p';
import { relayMuxSessionManager } from '../lib/relay-mux-session-manager';
import type { RelayMuxChannel } from '../lib/relay-mux-session';

class ConnectionService {
  initialize(): void {
    this.setupMuxTransportProvider();
    this.setupP2PEventListeners();
    this.setupSessionInvalidationListener();
  }

  private setupSessionInvalidationListener() {
    if (typeof window === 'undefined') return;
    window.addEventListener('webterm:session-invalidated', () => {
      this.closeAll();
    });
  }

  private setupMuxTransportProvider() {
    relayMuxSessionManager.setTransportProvider((deviceId) =>
      p2pManager.createMuxTransport(deviceId),
    );
  }

  private setupP2PEventListeners() {
    p2pManager.addEventListener('p2p:connected', (event) => {
      store.p2pActive = true;
      const deviceId = (event as CustomEvent<{ deviceId: string | null }>).detail.deviceId;
      if (deviceId) {
        relayMuxSessionManager.reconnectDevice(deviceId, 'p2p connected');
      }
    });

    p2pManager.addEventListener('p2p:disconnected', (event) => {
      store.p2pActive = false;
      const deviceId = (event as CustomEvent<{ deviceId: string | null }>).detail.deviceId;
      if (deviceId) {
        relayMuxSessionManager.reconnectDevice(deviceId, 'p2p disconnected');
      }
    });
  }

  /** 发起 P2P 连接到指定设备。 */
  connectToDevice(deviceId: string): void {
    p2pManager.connectToDevice(deviceId);
  }

  /** 打开 manager 通道（统一走 mux，底层根据是否 P2P 自动选择 WS 或 DataChannel）。 */
  openManagerChannel(deviceId: string): RelayMuxChannel {
    return relayMuxSessionManager.openManagerChannel(deviceId);
  }

  /** 打开 terminal 通道（优先 P2P mock；否则统一走 mux）。 */
  openTerminalChannel(deviceId: string, sessionId: string): RelayMuxChannel | WebSocket {
    const { deviceId: parsedDeviceId, localId } = parseSessionId(sessionId);
    const effectiveDeviceId = parsedDeviceId || deviceId || '';

    if (p2pManager.isP2PActive()) {
      const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
      const deviceParam = effectiveDeviceId
        ? `?deviceId=${encodeURIComponent(effectiveDeviceId)}`
        : '';
      const wsUrl = `${proto}://${window.location.host}/ws/sessions/${encodeURIComponent(
        localId,
      )}${deviceParam}`;
      return p2pManager.createWebSocketMock(wsUrl, ['binary', 'json']) as unknown as WebSocket;
    }

    return relayMuxSessionManager.openTerminalChannel(effectiveDeviceId, sessionId);
  }

  /** 断开指定设备的连接。 */
  closeDevice(deviceId: string): void {
    relayMuxSessionManager.closeDevice(deviceId);
  }

  /** 关闭所有连接。 */
  closeAll(): void {
    p2pManager.disconnect();
    store.p2pActive = false;
    relayMuxSessionManager.closeAll();
  }

  /** 返回 P2P 当前是否活跃。 */
  isP2PActive(): boolean {
    return p2pManager.isP2PActive();
  }

  /** 处理对端 ICE candidate。 */
  handleRemoteCandidate(candidate: RTCIceCandidateInit): void {
    p2pManager.handleRemoteCandidate(candidate);
  }
}

export const connectionService = new ConnectionService();
