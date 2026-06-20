// Client connection transports for TerminalSession.
// Supports auto-detection between JSON and binary subprotocols.

import {
  MSG_INFO, MSG_EXIT, MSG_PONG,
  BINARY_SUBPROTOCOL, JSON_SUBPROTOCOL,
  encodeEmpty, encodeJSON, encodeOutput, encodeState,
} from './protocol-binary.js';

const OUTPUT_BATCH_DELAY_MS = 12;
const OUTPUT_BATCH_MAX_BYTES = 64 * 1024;

export class AutoDetectClientConnection {
  constructor(ws, session, { protocolHint = '' } = {}) {
    this.ws = ws;
    this.session = session;
    this.mode = protocolMode(protocolHint);
    this.delegate = this.mode === 'binary'
      ? new BinaryClientConnection(ws)
      : new JsonClientConnection(ws);
    this.ready = false;
  }

  send(message) {
    return this.delegate.send(message);
  }

  handleMessage(raw, isBinary = false) {
    if (!this.mode) {
      this.mode = isBinary ? 'binary' : 'json';
      if (this.mode === 'binary') this.delegate = new BinaryClientConnection(this.ws);
    }
    if (this.mode === 'binary') {
      if (!isBinary && typeof raw === 'string') return;
      this.session.handleBinaryClientMessage(this, raw);
      return;
    }
    let msg;
    try {
      msg = JSON.parse(Buffer.from(raw).toString('utf8'));
    } catch {
      return;
    }
    this.session.handleClientMessage(this, msg);
  }

  close() {
    this.delegate.close();
  }
}

class JsonClientConnection {
  constructor(ws) {
    this.ws = ws;
    this.queue = [];
    this.sending = false;
    this.maxQueue = 200;
  }

  send(message) {
    if (this.ws.readyState !== 1) return false;
    this.queue.push(JSON.stringify(jsonWireMessage(message)));
    if (this.queue.length > this.maxQueue) return false;
    this.flush();
    return true;
  }

  flush() {
    if (this.sending || !this.queue.length) return;
    if (this.ws.readyState !== 1) return;
    this.sending = true;
    const data = this.queue.shift();
    this.ws.send(data, (err) => {
      this.sending = false;
      if (err) {
        this.close();
        return;
      }
      this.flush();
    });
  }

  close() {
    try {
      this.ws.close();
    } catch {
      // already closed
    }
  }
}

class BinaryClientConnection {
  constructor(ws) {
    this.ws = ws;
    this.queue = [];
    this.sending = false;
    this.maxQueue = 2048;
    this.pendingOutputBuffers = [];
    this.pendingOutputBytes = 0;
    this.pendingOutputSeq = 0;
    this.outputBatchTimer = null;
  }

  send(message) {
    if (this.ws.readyState !== 1) return false;
    if (message.type === 'output' && message.batch === true) {
      return this.sendBatchedOutput(message);
    }
    this.flushPendingOutput();
    const frame = this.encode(message);
    if (!frame) return true;
    this.queue.push(frame);
    if (this.queue.length > this.maxQueue) return false;
    this.flush();
    return true;
  }

  encode(message) {
    if (message.type === 'output') {
      return encodeOutput(message.seq, message.bytes ?? message.data);
    }
    if (message.type === 'state') {
      return encodeState(message.seq, message.bytes ?? message.data);
    }
    if (message.type === 'info') {
      return encodeJSON(MSG_INFO, message.data);
    }
    if (message.type === 'exit') {
      return encodeJSON(MSG_EXIT, { code: Number(message.code || 0) });
    }
    if (message.type === 'pong') {
      return encodeEmpty(MSG_PONG);
    }
    return null;
  }

  sendBatchedOutput(message) {
    const bytes = Buffer.isBuffer(message.bytes)
      ? message.bytes
      : Buffer.from(String(message.bytes ?? message.data ?? ''), 'utf8');
    if (!bytes.length) return true;
    this.pendingOutputBuffers.push(bytes);
    this.pendingOutputBytes += bytes.length;
    this.pendingOutputSeq = Number(message.seq || this.pendingOutputSeq || 0);
    if (this.pendingOutputBytes >= OUTPUT_BATCH_MAX_BYTES) {
      this.flushPendingOutput();
    } else if (!this.outputBatchTimer) {
      this.outputBatchTimer = setTimeout(() => {
        this.outputBatchTimer = null;
        this.flushPendingOutput();
      }, OUTPUT_BATCH_DELAY_MS);
    }
    return this.queue.length <= this.maxQueue;
  }

  flushPendingOutput() {
    if (this.outputBatchTimer) {
      clearTimeout(this.outputBatchTimer);
      this.outputBatchTimer = null;
    }
    if (!this.pendingOutputBuffers.length) return;
    const frame = encodeOutput(this.pendingOutputSeq, Buffer.concat(this.pendingOutputBuffers, this.pendingOutputBytes));
    this.pendingOutputBuffers = [];
    this.pendingOutputBytes = 0;
    this.pendingOutputSeq = 0;
    this.queue.push(frame);
    this.flush();
  }

  flush() {
    if (this.sending || !this.queue.length) return;
    if (this.ws.readyState !== 1) return;
    this.sending = true;
    const data = this.queue.shift();
    this.ws.send(data, (err) => {
      this.sending = false;
      if (err) {
        this.close();
        return;
      }
      this.flush();
    });
  }

  close() {
    this.flushPendingOutput();
    if (this.outputBatchTimer) {
      clearTimeout(this.outputBatchTimer);
      this.outputBatchTimer = null;
    }
    try {
      this.ws.close();
    } catch {
      // already closed
    }
  }
}

function protocolMode(protocolHint) {
  const protocol = String(protocolHint || '').trim().toLowerCase();
  if (protocol === BINARY_SUBPROTOCOL) return 'binary';
  if (protocol === JSON_SUBPROTOCOL) return 'json';
  return '';
}

function jsonWireMessage(message) {
  if (message.type === 'output') {
    return { type: 'output', seq: message.seq, data: message.data ?? '' };
  }
  if (message.type === 'replay') {
    return {
      type: 'replay',
      from: message.from,
      frames: (message.frames || []).map((frame) => ({ seq: frame.seq, data: frame.data ?? frame.text ?? '' })),
      seq: message.seq,
    };
  }
  return message;
}
