import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { relayMuxSessionManager } from './relay-mux-session-manager';
import type { RelayMuxTransport } from './relay-mux-session';

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

class FakeTransport extends EventTarget implements RelayMuxTransport {
  readyState: number = WebSocket.OPEN;
  sentText: string[] = [];
  closeCalls: Array<{ code?: number; reason?: string }> = [];

  sendText(value: string): void {
    this.sentText.push(value);
  }

  sendBinary(): void {}

  close(code?: number, reason?: string): void {
    this.closeCalls.push({ code, reason });
    this.readyState = WebSocket.CLOSED;
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
  sent: Array<string | Uint8Array> = [];

  constructor(
    readonly url: string,
    readonly protocols?: string[],
  ) {
    super();
    FakeWebSocket.instances.push(this);
  }

  send(value: string | Uint8Array): void {
    this.sent.push(value);
  }

  close(): void {
    this.readyState = FakeWebSocket.CLOSED;
  }
}

describe('RelayMuxSessionManager transport selection', () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    relayMuxSessionManager.closeAll();
    relayMuxSessionManager.setTransportProvider(null);
    vi.stubGlobal('window', { location: { protocol: 'http:', host: 'relay.example.test' } });
    vi.stubGlobal('WebSocket', FakeWebSocket);
    vi.stubGlobal('CloseEvent', TestCloseEvent);
    vi.stubGlobal('MessageEvent', TestMessageEvent);
  });

  afterEach(() => {
    relayMuxSessionManager.closeAll();
    relayMuxSessionManager.setTransportProvider(null);
    vi.unstubAllGlobals();
  });

  it('uses the injected transport when the provider returns one', async () => {
    const transport = new FakeTransport();
    relayMuxSessionManager.setTransportProvider((deviceId) => (deviceId === 'd1' ? transport : null));

    relayMuxSessionManager.openManagerChannel('d1');
    await Promise.resolve();

    expect(FakeWebSocket.instances).toHaveLength(0);
    expect(JSON.parse(transport.sentText[0])).toEqual({
      type: 'ws-connect',
      tunnelConnectionId: 'manager:d1',
      path: '/ws/sessions',
      protocols: ['webterm.json.v1'],
    });
  });

  it('falls back to the relay websocket when the provider returns null', () => {
    relayMuxSessionManager.setTransportProvider(() => null);

    relayMuxSessionManager.openManagerChannel('d2');

    expect(FakeWebSocket.instances).toHaveLength(1);
    expect(FakeWebSocket.instances[0].url).toBe('ws://relay.example.test/ws/sessions?deviceId=d2');
  });

  it('reconnects an existing device session when the selected transport changes', async () => {
    const first = new FakeTransport();
    const second = new FakeTransport();
    let current = first;
    relayMuxSessionManager.setTransportProvider(() => current);

    relayMuxSessionManager.openManagerChannel('d1');
    await Promise.resolve();

    expect(first.sentText).toHaveLength(1);
    current = second;
    relayMuxSessionManager.reconnectDevice('d1', 'p2p connected');
    await Promise.resolve();

    expect(first.closeCalls).toEqual([{ code: 1000, reason: 'p2p connected' }]);
    expect(second.sentText).toHaveLength(1);
    expect(JSON.parse(second.sentText[0])).toEqual({
      type: 'ws-connect',
      tunnelConnectionId: 'manager:d1',
      path: '/ws/sessions',
      protocols: ['webterm.json.v1'],
    });
  });
});
