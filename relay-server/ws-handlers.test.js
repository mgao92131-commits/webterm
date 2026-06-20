import test from 'node:test';
import assert from 'node:assert/strict';
import { prefixSessionManagerMessage } from './client-tunnel.js';
import { createWsHandlers } from './ws-handlers.js';
import {
  MSG_TYPE_WS_DATA,
  WS_DATA_TEXT,
  WS_DATA_BINARY,
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

test('prefixSessionManagerMessage rewrites local session ids for relay clients', () => {
  const messages = [
    { type: 'sessions', data: [{ id: 's1', termTitle: 'zsh' }, { id: 'd1:s2' }] },
    { type: 'session', data: { id: 's1', termTitle: 'vim' } },
    { type: 'session-closed', id: 's1' },
    { type: 'devices', devices: [{ deviceId: 'd1' }] },
  ];

  const rewritten = messages.map((message) => JSON.parse(
    prefixSessionManagerMessage(JSON.stringify(message), 'd1')
  ));

  assert.deepEqual(rewritten, [
    { type: 'sessions', data: [{ id: 'd1:s1', termTitle: 'zsh' }, { id: 'd1:s2' }] },
    { type: 'session', data: { id: 'd1:s1', termTitle: 'vim' } },
    { type: 'session-closed', id: 'd1:s1' },
    { type: 'devices', devices: [{ deviceId: 'd1' }] },
  ]);
  assert.equal(prefixSessionManagerMessage('not json', 'd1'), 'not json');
});
