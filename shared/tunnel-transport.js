// Shared tunnel transport abstractions.
// Works with Node.js Buffer; browser equivalent uses the same protocol.

import {
  MSG_TYPE_WS_DATA,
  WS_DATA_TEXT, WS_DATA_BINARY,
  WS_CLOSE,
  encodeTunnelFrame,
} from './tunnel-protocol.js';

const SEND_FALLBACK = {
  sendJSON: () => false,
  sendBinary: () => false,
};

export class VirtualSocket {
  constructor(tunnelConnectionId, transport, onClose) {
    this.tunnelConnectionId = tunnelConnectionId;
    this.transport = transport || SEND_FALLBACK;
    this.onClose = onClose;
    this.readyState = 1;
    this.listeners = { message: [], close: [], error: [] };
  }

  on(event, fn) {
    if (this.listeners[event]) this.listeners[event].push(fn);
  }

  send(data, cb) {
    if (this.readyState !== 1) {
      if (cb) cb(new Error('Socket is not open'));
      return;
    }
    try {
      const frame = encodeTunnelFrame(
        MSG_TYPE_WS_DATA, this.tunnelConnectionId,
        Buffer.isBuffer(data) ? WS_DATA_BINARY : WS_DATA_TEXT,
        data
      );
      this.transport.sendBinary(frame);
      if (cb) cb(null);
    } catch (err) {
      if (cb) cb(err);
    }
  }

  close(code, reason) {
    if (this.readyState === 3) return;
    this.readyState = 3;
    for (const fn of this.listeners.close) {
      try { fn(); } catch {}
    }
    if (this.onClose) this.onClose();
    try {
      this.transport.sendJSON({
        type: WS_CLOSE, tunnelConnectionId: this.tunnelConnectionId,
        code: code || 1000, reason: reason || ''
      });
    } catch {}
  }

  emitMessage(raw, isBinary) {
    if (this.readyState !== 1) return;
    for (const fn of this.listeners.message) {
      try { fn(raw, isBinary); } catch {}
    }
  }
}
