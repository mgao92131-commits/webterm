import test from 'node:test';
import assert from 'node:assert/strict';
import { handleAgentTunnelMessage, normalizeCloseCode, prefixSessionManagerMessage } from './client-tunnel.js';
import { createWsHandlers } from './ws-handlers.js';
import {
  MSG_TYPE_WS_DATA,
  MSG_TYPE_HTTP_CHUNK,
  WS_DATA_TEXT,
  WS_DATA_BINARY,
  HTTP_CHUNK_DATA,
  HTTP_CHUNK_FIN,
  HTTP_RESPONSE,
  encodeTunnelFrame,
} from '../shared/tunnel-protocol.js';

function createHandlersWithTunnel(clientWs, tunnelOptions = {}) {
  const activeWsTunnels = new Map([
    ['tc_test', { clientWs, ...tunnelOptions }],
  ]);
  const handlers = createWsHandlers({
    auth: {},
    registry: {},
    pendingHttpResponses: new Map(),
    activeWsTunnels,
    pendingP2pOffers: new Map(),
    findBySecretHash: () => null,
    updateLastSeen: () => {},
    text: () => {},
  });
  return handlers;
}

test('relay forwards tunnel text frames as websocket text', () => {
  const sent = [];
  const clientWs = {
    readyState: 1,
    send(data) {
      sent.push(data);
    },
  };
  const { handleBinaryDemux } = createHandlersWithTunnel(clientWs);

  handleBinaryDemux(encodeTunnelFrame(
    MSG_TYPE_WS_DATA,
    'tc_test',
    WS_DATA_TEXT,
    Buffer.from('{"type":"info"}', 'utf8'),
  ));

  assert.equal(sent.length, 1);
  assert.equal(typeof sent[0], 'string');
  assert.equal(sent[0], '{"type":"info"}');
});

test('relay forwards tunnel binary frames as websocket binary', () => {
  const sent = [];
  const clientWs = {
    readyState: 1,
    send(data) {
      sent.push(data);
    },
  };
  const { handleBinaryDemux } = createHandlersWithTunnel(clientWs);
  const payload = Buffer.from([0x02, 0x00, 0x01]);

  handleBinaryDemux(encodeTunnelFrame(
    MSG_TYPE_WS_DATA,
    'tc_test',
    WS_DATA_BINARY,
    payload,
  ));

  assert.equal(sent.length, 1);
  assert.ok(Buffer.isBuffer(sent[0]));
  assert.deepEqual(sent[0], payload);
});

test('relay can prefix session manager ids on tunneled text frames', () => {
  const sent = [];
  const clientWs = {
    readyState: 1,
    send(data) {
      sent.push(JSON.parse(data));
    },
  };
  const { handleBinaryDemux } = createHandlersWithTunnel(clientWs, {
    transformOutboundText(text) {
      const message = JSON.parse(text);
      if (message.type === 'sessions') {
        message.data = message.data.map((session) => ({ ...session, id: `d1:${session.id}` }));
      } else if (message.type === 'session') {
        message.data = { ...message.data, id: `d1:${message.data.id}` };
      } else if (message.type === 'session-closed') {
        message.id = `d1:${message.id}`;
      }
      return JSON.stringify(message);
    },
  });

  for (const message of [
    { type: 'sessions', data: [{ id: 's1', termTitle: 'zsh' }] },
    { type: 'session', data: { id: 's1', termTitle: 'vim' } },
    { type: 'session-closed', id: 's1' },
  ]) {
    handleBinaryDemux(encodeTunnelFrame(
      MSG_TYPE_WS_DATA,
      'tc_test',
      WS_DATA_TEXT,
      Buffer.from(JSON.stringify(message), 'utf8'),
    ));
  }

  assert.deepEqual(sent, [
    { type: 'sessions', data: [{ id: 'd1:s1', termTitle: 'zsh' }] },
    { type: 'session', data: { id: 'd1:s1', termTitle: 'vim' } },
    { type: 'session-closed', id: 'd1:s1' },
  ]);
});

test('relay keeps pending http responses open until chunk fin', () => {
  const timer = setTimeout(() => {}, 1000);
  const res = {
    head: null,
    writes: [],
    ended: false,
    writeHead(statusCode, headers) {
      this.head = { statusCode, headers };
    },
    write(payload) {
      this.writes.push(Buffer.from(payload));
    },
    end(payload) {
      this.ended = true;
      if (payload) this.write(payload);
    },
  };
  const pendingHttpResponses = new Map([
    ['req_test', {
      res,
      timer,
      deviceId: 'd1',
      method: 'GET',
      path: '/api/files/big.bin',
    }],
  ]);
  const { handleAgentConnection, handleBinaryDemux } = createWsHandlers({
    auth: {},
    registry: {
      getAgent: () => null,
      registerAgent: () => {},
      pushDevicesToUser: () => {},
    },
    pendingHttpResponses,
    activeWsTunnels: new Map(),
    pendingP2pOffers: new Map(),
    findBySecretHash: () => ({ id: 1, userId: 1, username: 'u1', deviceName: 'd1' }),
    updateLastSeen: () => {},
    text: () => {},
  });
  const agentWs = fakeWs();

  handleAgentConnection(agentWs);
  agentWs.emit('message', Buffer.from(JSON.stringify({
    type: 'agent-register',
    secret: 'secret',
  })), false);
  agentWs.emit('message', Buffer.from(JSON.stringify({
    type: HTTP_RESPONSE,
    requestId: 'req_test',
    statusCode: 200,
    headers: { 'content-type': 'application/octet-stream' },
    hasChunks: true,
  })), false);

  assert.deepEqual(res.head, {
    statusCode: 200,
    headers: { 'content-type': 'application/octet-stream' },
  });
  assert.equal(res.ended, false);
  assert.equal(pendingHttpResponses.has('req_test'), true);

  handleBinaryDemux(encodeTunnelFrame(
    MSG_TYPE_HTTP_CHUNK,
    'req_test',
    HTTP_CHUNK_DATA,
    Buffer.from('abc'),
  ));
  handleBinaryDemux(encodeTunnelFrame(
    MSG_TYPE_HTTP_CHUNK,
    'req_test',
    HTTP_CHUNK_FIN,
    Buffer.alloc(0),
  ));

  clearTimeout(timer);
  assert.equal(Buffer.concat(res.writes).toString('utf8'), 'abc');
  assert.equal(res.ended, true);
  assert.equal(pendingHttpResponses.has('req_test'), false);
});

test('client websocket tunnels keep outbound text transform options', () => {
  const activeWsTunnels = new Map();
  const agent = {
    deviceId: 'd1',
    ws: {
      readyState: 1,
      sent: [],
      send(data) {
        this.sent.push(data);
      },
    },
  };
  const clientWs = fakeWs();
  const transformOutboundText = (text) => prefixSessionManagerMessage(text, 'd1');
  const { handleClientWsTunnel, handleBinaryDemux } = createWsHandlers({
    auth: {},
    registry: {},
    pendingHttpResponses: new Map(),
    activeWsTunnels,
    pendingP2pOffers: new Map(),
    findBySecretHash: () => null,
    updateLastSeen: () => {},
    text: () => {},
  });

  handleClientWsTunnel(clientWs, agent, '/ws/sessions', { headers: {} }, { transformOutboundText });

  const [tunnelId, tunnel] = activeWsTunnels.entries().next().value;
  tunnel.isConnected = true;

  const sent = [];
  tunnel.clientWs.send = (data) => sent.push(JSON.parse(data));
  handleBinaryDemux(encodeTunnelFrame(
    MSG_TYPE_WS_DATA,
    tunnelId,
    WS_DATA_TEXT,
    Buffer.from(JSON.stringify({ type: 'session', data: { id: 's1', termTitle: 'vim' } }), 'utf8'),
  ));

  assert.deepEqual(sent, [
    { type: 'session', data: { id: 'd1:s1', termTitle: 'vim' } },
  ]);
});

test('prefixSessionManagerMessage rewrites local session ids for relay clients', () => {
  const messages = [
    { type: 'sessions', data: [{ id: 's1', termTitle: 'zsh' }, { id: 'd1:s2' }] },
    { type: 'session', data: { id: 's1', termTitle: 'vim' } },
    { type: 'session-closed', id: 's1' },
    { type: 'devices', devices: [{ deviceId: 'd1', status: 'online', online: true }] },
  ];

  const rewritten = messages.map((message) => JSON.parse(
    prefixSessionManagerMessage(JSON.stringify(message), 'd1')
  ));

  assert.deepEqual(rewritten, [
    { type: 'sessions', data: [{ id: 'd1:s1', termTitle: 'zsh' }, { id: 'd1:s2' }] },
    { type: 'session', data: { id: 'd1:s1', termTitle: 'vim' } },
    { type: 'session-closed', id: 'd1:s1' },
    { type: 'devices', devices: [{ deviceId: 'd1', status: 'online', online: true }] },
  ]);
  assert.equal(prefixSessionManagerMessage('not json', 'd1'), 'not json');
});

test('agent websocket errors use valid websocket close codes', () => {
  const closed = [];
  const activeWsTunnels = new Map([
    ['tc_test', {
      clientWs: {
        readyState: 1,
        close(code, reason) {
          closed.push({ code, reason });
        },
      },
      deviceId: 'd1',
      connectionTimer: null,
    }],
  ]);
  handleAgentTunnelMessage({
    type: 'ws-error',
    tunnelConnectionId: 'tc_test',
    code: 404,
    message: 'Session s1 not found',
  }, { getAgent: () => null }, activeWsTunnels);

  assert.deepEqual(closed, [{ code: 4404, reason: 'Session s1 not found' }]);
  assert.equal(activeWsTunnels.has('tc_test'), false);
});

test('normalizeCloseCode preserves valid websocket close codes', () => {
  assert.equal(normalizeCloseCode(1000), 1000);
  assert.equal(normalizeCloseCode(4008), 4008);
  assert.equal(normalizeCloseCode(404), 4404);
  assert.equal(normalizeCloseCode('not-a-number', 4500), 4500);
});

function fakeWs() {
  const handlers = new Map();
  return {
    readyState: 1,
    send() {},
    close() {},
    on(event, handler) {
      handlers.set(event, handler);
    },
    emit(event, ...args) {
      handlers.get(event)?.(...args);
    },
  };
}
