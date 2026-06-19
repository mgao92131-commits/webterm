// P2P WebSocket Mock — bridges browser WebSocket API over a WebRTC DataChannel.
// Creates a WebSocket-like object that tunnels data through the P2P connection.

import { encodeTunnelFrame } from './p2p-utils';
import { MSG_TYPE_WS_DATA, WS_DATA_TEXT } from '@shared/constants.js';

export class P2PWebSocketMock extends EventTarget {
  public readyState: number = 0; // CONNECTING
  private tunnelConnectionId: string;
  private p2pManager: any;
  private path: string;
  private protocols: string[];

  constructor(wsUrl: string, p2pManager: any, protocols?: string | string[]) {
    super();
    this.p2pManager = p2pManager;
    this.protocols = typeof protocols === 'string' ? [protocols] : (protocols || []);

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
