import { CONFIG } from '../config';
import {
  MUX_SUBPROTOCOL,
  MSG_TYPE_WS_DATA,
  WS_DATA_BINARY,
  WS_DATA_TEXT,
  decodeTextPayload,
  decodeTunnelFrame,
  encodeTextPayload,
  encodeTunnelFrame,
} from './mux-protocol';

type Listener = EventListenerOrEventListenerObject;

interface ChannelSpec {
  id: string;
  path: string;
  protocols: string[];
}

export interface RelayMuxTransport extends EventTarget {
  readonly readyState: number;
  sendText(value: string): void;
  sendBinary(value: Uint8Array): void;
  close(code?: number, reason?: string): void;
}

export type RelayMuxTransportFactory = (deviceId: string) => RelayMuxTransport;

class WebSocketRelayMuxTransport extends EventTarget implements RelayMuxTransport {
  private ws: WebSocket;

  constructor(deviceId: string) {
    super();
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${proto}://${window.location.host}/ws/sessions?deviceId=${encodeURIComponent(deviceId)}`;
    const ws = new WebSocket(url, [MUX_SUBPROTOCOL]);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;

    ws.addEventListener('open', () => this.dispatchEvent(new Event('open')));
    ws.addEventListener('message', (event) => this.dispatchEvent(new MessageEvent('message', { data: event.data })));
    ws.addEventListener('close', (event) => {
      this.dispatchEvent(new CloseEvent('close', {
        code: event.code,
        reason: event.reason,
        wasClean: event.wasClean,
      }));
    });
    ws.addEventListener('error', () => this.dispatchEvent(new Event('error')));
  }

  get readyState(): number {
    return this.ws.readyState;
  }

  sendText(value: string): void {
    this.ws.send(value);
  }

  sendBinary(value: Uint8Array): void {
    this.ws.send(value);
  }

  close(code = 1000, reason = ''): void {
    this.ws.close(code, reason);
  }
}

export function webSocketRelayMuxTransportFactory(deviceId: string): RelayMuxTransport {
  return new WebSocketRelayMuxTransport(deviceId);
}

export class RelayMuxChannel extends EventTarget {
  readonly id: string;
  readonly path: string;
  readonly protocols: string[];
  readyState: number = WebSocket.CONNECTING;

  constructor(private session: RelayMuxSession, spec: ChannelSpec) {
    super();
    this.id = spec.id;
    this.path = spec.path;
    this.protocols = spec.protocols;
  }

  send(data: string | ArrayBuffer | Blob | Uint8Array) {
    if (this.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket is not in OPEN state');
    }
    this.session.sendChannelData(this.id, data);
  }

  close(code = 1000, reason = '') {
    if (this.readyState === WebSocket.CLOSED || this.readyState === WebSocket.CLOSING) {
      return;
    }
    this.readyState = WebSocket.CLOSING;
    this.session.closeChannel(this.id);
    this.markClosed(code, reason);
  }

  addEventListener(type: string, callback: Listener | null, options?: boolean | AddEventListenerOptions): void {
    super.addEventListener(type, callback, options);
  }

  removeEventListener(type: string, callback: Listener | null, options?: boolean | EventListenerOptions): void {
    super.removeEventListener(type, callback, options);
  }

  markOpen() {
    if (this.readyState === WebSocket.OPEN) return;
    this.readyState = WebSocket.OPEN;
    this.dispatchEvent(new Event('open'));
  }

  markError(message: string) {
    this.dispatchEvent(new ErrorEvent('error', { message }));
  }

  markClosed(code = 1000, reason = '') {
    if (this.readyState === WebSocket.CLOSED) return;
    this.readyState = WebSocket.CLOSED;
    this.dispatchEvent(new CloseEvent('close', { code, reason, wasClean: code === 1000 }));
  }

  emitData(payload: Uint8Array, binary: boolean) {
    const data = binary ? payload.buffer.slice(payload.byteOffset, payload.byteOffset + payload.byteLength) : decodeTextPayload(payload);
    this.dispatchEvent(new MessageEvent('message', { data }));
  }
}

export class RelayMuxSession {
  private transport: RelayMuxTransport | null = null;
  private channels = new Map<string, RelayMuxChannel>();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempts = 0;
  private manualClose = false;
  private generation = 0;

  constructor(
    private deviceId: string,
    private transportFactory: RelayMuxTransportFactory = webSocketRelayMuxTransportFactory,
  ) {}

  openChannel(id: string, path: string, protocols: string[] = []): RelayMuxChannel {
    let channel = this.channels.get(id);
    if (!channel || channel.readyState === WebSocket.CLOSED) {
      channel = new RelayMuxChannel(this, { id, path, protocols });
      this.channels.set(id, channel);
    }
    const hadOpenTransport = this.transport?.readyState === WebSocket.OPEN;
    this.ensureConnected();
    if (hadOpenTransport && this.transport?.readyState === WebSocket.OPEN) {
      this.sendWSConnect(channel);
    }
    return channel;
  }

  closeChannel(id: string) {
    this.sendControl({ type: 'ws-close', tunnelConnectionId: id });
    this.channels.delete(id);
    if (this.channels.size === 0) {
      this.stop();
    }
  }

  sendChannelData(id: string, data: string | ArrayBuffer | Blob | Uint8Array) {
    if (typeof data === 'string') {
      this.sendFrame(id, encodeTextPayload(data), false);
      return;
    }
    if (data instanceof Uint8Array) {
      this.sendFrame(id, data, true);
      return;
    }
    if (data instanceof ArrayBuffer) {
      this.sendFrame(id, new Uint8Array(data), true);
      return;
    }
    data.arrayBuffer().then((buffer) => this.sendFrame(id, new Uint8Array(buffer), true));
  }

  stop() {
    this.manualClose = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    const transport = this.transport;
    this.transport = null;
    if (transport && transport.readyState !== WebSocket.CLOSED && transport.readyState !== WebSocket.CLOSING) {
      transport.close(1000, 'mux stopped');
    }
    for (const channel of this.channels.values()) {
      channel.markClosed(1000, 'mux stopped');
    }
    this.channels.clear();
  }

  reconnect(reason = 'mux transport changed') {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    const transport = this.transport;
    this.transport = null;
    this.generation++;
    for (const channel of this.channels.values()) {
      if (channel.readyState !== WebSocket.CLOSED) {
        channel.readyState = WebSocket.CONNECTING;
      }
    }
    if (transport && transport.readyState !== WebSocket.CLOSED && transport.readyState !== WebSocket.CLOSING) {
      transport.close(1000, reason);
    }
    if (this.channels.size > 0) {
      this.ensureConnected();
    }
  }

  private ensureConnected() {
    if (this.transport && (this.transport.readyState === WebSocket.OPEN || this.transport.readyState === WebSocket.CONNECTING)) {
      return;
    }
    this.manualClose = false;
    const transport = this.transportFactory(this.deviceId);
    this.transport = transport;
    const generation = ++this.generation;

    const handleOpen = () => {
      if (this.generation !== generation || this.transport !== transport) return;
      this.reconnectAttempts = 0;
      for (const channel of this.channels.values()) {
        channel.readyState = WebSocket.CONNECTING;
        this.sendWSConnect(channel);
      }
    };

    transport.addEventListener('open', handleOpen);

    transport.addEventListener('message', async (event) => {
      if (this.generation !== generation || this.transport !== transport) return;
      const data = (event as MessageEvent).data;
      if (typeof data === 'string') {
        this.handleControl(data);
        return;
      }
      const buffer = data instanceof Blob ? await data.arrayBuffer() : data;
      this.handleBinary(buffer);
    });

    transport.addEventListener('close', (event) => {
      if (this.generation !== generation || this.transport !== transport) return;
      this.transport = null;
      const closeEvent = event as CloseEvent;
      for (const channel of this.channels.values()) {
        channel.readyState = WebSocket.CONNECTING;
        channel.dispatchEvent(new CloseEvent('close', { code: closeEvent.code, reason: closeEvent.reason, wasClean: closeEvent.wasClean }));
      }
      if (!this.manualClose && this.channels.size > 0) {
        this.scheduleReconnect();
      }
    });

    transport.addEventListener('error', () => {
      if (this.generation !== generation || this.transport !== transport) return;
      for (const channel of this.channels.values()) {
        channel.dispatchEvent(new Event('error'));
      }
    });

    if (transport.readyState === WebSocket.OPEN) {
      queueMicrotask(handleOpen);
    }
  }

  private sendWSConnect(channel: RelayMuxChannel) {
    this.sendControl({
      type: 'ws-connect',
      tunnelConnectionId: channel.id,
      path: channel.path,
      protocols: channel.protocols,
    });
  }

  private sendControl(value: any) {
    if (!this.transport || this.transport.readyState !== WebSocket.OPEN) {
      return;
    }
    this.transport.sendText(JSON.stringify(value));
  }

  private sendFrame(id: string, payload: Uint8Array, binary: boolean) {
    if (!this.transport || this.transport.readyState !== WebSocket.OPEN) {
      throw new Error('mux transport is not open');
    }
    const frame = encodeTunnelFrame(MSG_TYPE_WS_DATA, id, binary ? WS_DATA_BINARY : WS_DATA_TEXT, payload);
    this.transport.sendBinary(frame);
  }

  private handleControl(raw: string) {
    let msg: any;
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }
    const id = String(msg.tunnelConnectionId || '');
    const channel = this.channels.get(id);
    if (!channel) return;
    if (msg.type === 'ws-connected') {
      channel.markOpen();
    } else if (msg.type === 'ws-error') {
      channel.markError(String(msg.message || 'mux channel error'));
      channel.markClosed(Number(msg.code || 1011), String(msg.message || 'mux channel error'));
      this.channels.delete(id);
    } else if (msg.type === 'ws-close') {
      channel.markClosed(1000, 'mux channel closed');
      this.channels.delete(id);
    }
  }

  private handleBinary(data: ArrayBuffer) {
    let frame;
    try {
      frame = decodeTunnelFrame(data);
    } catch {
      return;
    }
    if (frame.msgType !== MSG_TYPE_WS_DATA) return;
    const channel = this.channels.get(frame.id);
    if (!channel) return;
    channel.emitData(frame.payload, frame.extraByte === WS_DATA_BINARY);
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) return;
    const backoff = CONFIG.reconnectBackoff;
    const cap = Math.min(backoff.baseMs * Math.pow(backoff.multiplier, this.reconnectAttempts++), backoff.capMs);
    const delay = Math.max(backoff.relayMinDelayMs, Math.random() * cap);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.ensureConnected();
    }, delay);
  }
}
