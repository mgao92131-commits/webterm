import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MSG_TYPE_WS_DATA, WS_DATA_TEXT, decodeTunnelFrame } from './mux-protocol';
import { RelayMuxSession, type RelayMuxTransport } from './relay-mux-session';

class TestCloseEvent extends Event {
  code: number;
  reason: string;
  wasClean: boolean;

  constructor(type: string, init: { code?: number; reason?: string; wasClean?: boolean } = {}) {
    super(type);
    this.code = init.code ?? 1000;
    this.reason = init.reason ?? '';
    this.wasClean = init.wasClean ?? this.code === 1000;
  }
}

class TestMessageEvent extends Event {
  data: unknown;

  constructor(type: string, init: { data?: unknown } = {}) {
    super(type);
    this.data = init.data;
  }
}

class FakeWebSocket extends EventTarget {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  static instances: FakeWebSocket[] = [];

  readyState = FakeWebSocket.CONNECTING;
  binaryType = '';
  sent: Array<string | Uint8Array | ArrayBuffer> = [];
  closeCalls: Array<{ code: number; reason: string }> = [];

  constructor(
    readonly url: string,
    readonly protocols?: string[],
  ) {
    super();
    FakeWebSocket.instances.push(this);
  }

  send(data: string | Uint8Array | ArrayBuffer) {
    if (this.readyState !== FakeWebSocket.OPEN) {
      throw new Error('fake websocket is not open');
    }
    this.sent.push(data);
  }

  close(code = 1000, reason = '') {
    this.closeCalls.push({ code, reason });
    if (this.readyState === FakeWebSocket.CLOSED) return;
    this.readyState = FakeWebSocket.CLOSED;
    this.dispatchEvent(new TestCloseEvent('close', { code, reason, wasClean: code === 1000 }));
  }

  open() {
    this.readyState = FakeWebSocket.OPEN;
    this.dispatchEvent(new Event('open'));
  }

  receiveText(value: unknown) {
    this.dispatchEvent(new TestMessageEvent('message', { data: JSON.stringify(value) }));
  }

  receiveBinary(value: Uint8Array) {
    this.dispatchEvent(new TestMessageEvent('message', { data: value.buffer }));
  }
}

class FakeTransport extends EventTarget implements RelayMuxTransport {
  readyState: number = WebSocket.CONNECTING;
  sentText: string[] = [];
  sentBinary: Uint8Array[] = [];
  closeCalls: Array<{ code: number; reason: string }> = [];

  sendText(value: string): void {
    this.sentText.push(value);
  }

  sendBinary(value: Uint8Array): void {
    this.sentBinary.push(value);
  }

  close(code = 1000, reason = ''): void {
    this.closeCalls.push({ code, reason });
    this.readyState = WebSocket.CLOSED;
    this.dispatchEvent(new TestCloseEvent('close', { code, reason }));
  }

  open(): void {
    this.readyState = WebSocket.OPEN;
    this.dispatchEvent(new Event('open'));
  }

  receiveText(value: unknown): void {
    this.dispatchEvent(new TestMessageEvent('message', { data: JSON.stringify(value) }));
  }
}

function controlAt(ws: FakeWebSocket, index: number) {
  const value = ws.sent[index];
  expect(typeof value).toBe('string');
  return JSON.parse(value as string);
}

function binaryAt(ws: FakeWebSocket, index: number) {
  const value = ws.sent[index];
  expect(value).toBeInstanceOf(Uint8Array);
  return decodeTunnelFrame(value as Uint8Array);
}

describe('RelayMuxSession', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(Math, 'random').mockReturnValue(0);
    FakeWebSocket.instances = [];
    vi.stubGlobal('window', { location: { protocol: 'http:', host: 'relay.example.test' } });
    vi.stubGlobal('WebSocket', FakeWebSocket);
    vi.stubGlobal('CloseEvent', TestCloseEvent);
    vi.stubGlobal('MessageEvent', TestMessageEvent);
    vi.stubGlobal('ErrorEvent', Event);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('waits for ws-connected before opening a virtual channel', () => {
    const session = new RelayMuxSession('d1');
    const channel = session.openChannel('term:s1', '/ws/sessions/s1', ['webterm.binary.v1']);
    const ws = FakeWebSocket.instances[0];

    const openSpy = vi.fn();
    channel.addEventListener('open', openSpy);

    expect(ws.url).toBe('ws://relay.example.test/ws/sessions?deviceId=d1');
    expect(channel.readyState).toBe(FakeWebSocket.CONNECTING);

    ws.open();

    expect(controlAt(ws, 0)).toEqual({
      type: 'ws-connect',
      tunnelConnectionId: 'term:s1',
      path: '/ws/sessions/s1',
      protocols: ['webterm.binary.v1'],
    });
    expect(openSpy).not.toHaveBeenCalled();

    ws.receiveText({ type: 'ws-connected', tunnelConnectionId: 'term:s1' });

    expect(channel.readyState).toBe(FakeWebSocket.OPEN);
    expect(openSpy).toHaveBeenCalledTimes(1);
  });

  it('encodes channel payloads as tunnel frames after the virtual channel opens', () => {
    const session = new RelayMuxSession('d1');
    const channel = session.openChannel('term:s1', '/ws/sessions/s1');
    const ws = FakeWebSocket.instances[0];
    ws.open();
    ws.receiveText({ type: 'ws-connected', tunnelConnectionId: 'term:s1' });

    channel.send('hello');

    const frame = binaryAt(ws, 1);
    expect(frame.msgType).toBe(MSG_TYPE_WS_DATA);
    expect(frame.id).toBe('term:s1');
    expect(frame.extraByte).toBe(WS_DATA_TEXT);
    expect(new TextDecoder().decode(frame.payload)).toBe('hello');
  });

  it('closes one virtual channel without closing the physical websocket', () => {
    const session = new RelayMuxSession('d1');
    const manager = session.openChannel('manager:d1', '/ws/sessions');
    const terminal = session.openChannel('term:s1', '/ws/sessions/s1');
    const ws = FakeWebSocket.instances[0];
    ws.open();
    ws.receiveText({ type: 'ws-connected', tunnelConnectionId: 'manager:d1' });
    ws.receiveText({ type: 'ws-connected', tunnelConnectionId: 'term:s1' });

    terminal.close();

    expect(manager.readyState).toBe(FakeWebSocket.OPEN);
    expect(terminal.readyState).toBe(FakeWebSocket.CLOSED);
    expect(ws.readyState).toBe(FakeWebSocket.OPEN);
    expect(ws.closeCalls).toEqual([]);
    expect(controlAt(ws, 2)).toEqual({ type: 'ws-close', tunnelConnectionId: 'term:s1' });
  });

  it('reconnects active channels and ignores stale socket callbacks', async () => {
    const session = new RelayMuxSession('d1');
    const channel = session.openChannel('manager:d1', '/ws/sessions');
    const first = FakeWebSocket.instances[0];
    first.open();
    first.receiveText({ type: 'ws-connected', tunnelConnectionId: 'manager:d1' });

    first.close(1006, 'network break');
    vi.advanceTimersByTime(250);
    await vi.runOnlyPendingTimersAsync();

    const second = FakeWebSocket.instances[1];
    expect(second).toBeTruthy();
    second.open();
    expect(controlAt(second, 0).tunnelConnectionId).toBe('manager:d1');

    first.receiveText({ type: 'ws-error', tunnelConnectionId: 'manager:d1', message: 'stale' });
    expect(channel.readyState).toBe(FakeWebSocket.CONNECTING);

    second.receiveText({ type: 'ws-connected', tunnelConnectionId: 'manager:d1' });
    expect(channel.readyState).toBe(FakeWebSocket.OPEN);
  });

  it('can run on an injected transport instead of constructing a WebSocket directly', () => {
    const transports: FakeTransport[] = [];
    const session = new RelayMuxSession('d1', () => {
      const transport = new FakeTransport();
      transports.push(transport);
      return transport;
    });

    const channel = session.openChannel('manager:d1', '/ws/sessions', ['webterm.json.v1']);

    expect(FakeWebSocket.instances).toHaveLength(0);
    expect(transports).toHaveLength(1);

    const transport = transports[0];
    transport.open();
    expect(JSON.parse(transport.sentText[0])).toEqual({
      type: 'ws-connect',
      tunnelConnectionId: 'manager:d1',
      path: '/ws/sessions',
      protocols: ['webterm.json.v1'],
    });

    transport.receiveText({ type: 'ws-connected', tunnelConnectionId: 'manager:d1' });
    channel.send('manager ping');

    expect(channel.readyState).toBe(WebSocket.OPEN);
    expect(transport.sentBinary).toHaveLength(1);
    expect(decodeTunnelFrame(transport.sentBinary[0]).id).toBe('manager:d1');
  });

  it('reconnects with a fresh transport without closing virtual channels', () => {
    const transports: FakeTransport[] = [];
    const session = new RelayMuxSession('d1', () => {
      const transport = new FakeTransport();
      transports.push(transport);
      return transport;
    });

    const channel = session.openChannel('manager:d1', '/ws/sessions', ['webterm.json.v1']);
    const openSpy = vi.fn();
    const closeSpy = vi.fn();
    channel.addEventListener('open', openSpy);
    channel.addEventListener('close', closeSpy);

    const first = transports[0];
    first.open();
    first.receiveText({ type: 'ws-connected', tunnelConnectionId: 'manager:d1' });
    expect(channel.readyState).toBe(WebSocket.OPEN);
    expect(openSpy).toHaveBeenCalledTimes(1);

    session.reconnect('transport changed');

    expect(first.closeCalls).toEqual([{ code: 1000, reason: 'transport changed' }]);
    expect(channel.readyState).toBe(WebSocket.CONNECTING);
    expect(closeSpy).not.toHaveBeenCalled();
    expect(transports).toHaveLength(2);

    const second = transports[1];
    second.open();
    expect(JSON.parse(second.sentText[0])).toEqual({
      type: 'ws-connect',
      tunnelConnectionId: 'manager:d1',
      path: '/ws/sessions',
      protocols: ['webterm.json.v1'],
    });
    second.receiveText({ type: 'ws-connected', tunnelConnectionId: 'manager:d1' });

    expect(channel.readyState).toBe(WebSocket.OPEN);
    expect(openSpy).toHaveBeenCalledTimes(2);
  });
});
