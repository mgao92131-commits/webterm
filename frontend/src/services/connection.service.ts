/**
 * 连接业务服务。
 * 统一管理 RelayMux 与 Manager WebSocket 连接。
 */

import { relayMuxSessionManager } from '../lib/relay-mux-session-manager';
import type { RelayMuxChannel } from '../lib/relay-mux-session';

class ConnectionService {
  initialize(): void {
    this.setupSessionInvalidationListener();
  }

  private setupSessionInvalidationListener() {
    if (typeof window === 'undefined') return;
    window.addEventListener('webterm:session-invalidated', () => {
      this.closeAll();
    });
  }

  /** 打开 manager 通道（统一走 relay mux WebSocket）。 */
  openManagerChannel(deviceId: string): RelayMuxChannel {
    return relayMuxSessionManager.openManagerChannel(deviceId);
  }

  /** 打开 terminal 通道（统一走 relay mux WebSocket 的二进制虚拟通道）。 */
  openTerminalChannel(deviceId: string, sessionId: string): RelayMuxChannel {
    return relayMuxSessionManager.openTerminalChannel(deviceId, sessionId);
  }

  /** 断开指定设备的连接。 */
  closeDevice(deviceId: string): void {
    relayMuxSessionManager.closeDevice(deviceId);
  }

  /** 关闭所有连接。 */
  closeAll(): void {
    relayMuxSessionManager.closeAll();
  }
}

export const connectionService = new ConnectionService();
