import test from 'node:test';
import assert from 'node:assert/strict';
import { createWsHandlers } from './ws-handlers.js';
import {
  MSG_TYPE_WS_DATA,
  WS_DATA_TEXT,
  WS_DATA_BINARY,
  encodeTunnelFrame,
} from '../shared/tunnel-protocol.js';

function createHandlersWithTunnel(clientWs) {
  const activeWsTunnels = new Map([
    ['tc_test', { clientWs }],
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
