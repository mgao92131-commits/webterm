import test from 'node:test';
import assert from 'node:assert/strict';
import {
  MSG_EXIT,
  MSG_INFO,
  MSG_OUTPUT,
  MSG_PONG,
  MSG_STATE,
  BINARY_SUBPROTOCOL,
  JSON_SUBPROTOCOL,
  encodeEmpty,
  encodeJSON,
  encodeOutput,
  encodeState,
  readUint64BE,
  selectWebSocketProtocol,
} from './protocol-binary.js';

test('binary protocol encodes output with big-endian seq and payload', () => {
  const frame = encodeOutput(258, Buffer.from('ok'));
  assert.equal(frame[0], MSG_OUTPUT);
  assert.equal(readUint64BE(frame, 1), 258);
  assert.equal(frame.subarray(9).toString('utf8'), 'ok');
});

test('binary protocol encodes state with big-endian seq and payload', () => {
  const frame = encodeState(259, Buffer.from('snapshot'));
  assert.equal(frame[0], MSG_STATE);
  assert.equal(readUint64BE(frame, 1), 259);
  assert.equal(frame.subarray(9).toString('utf8'), 'snapshot');
});

test('binary protocol encodes json control frames', () => {
  const info = encodeJSON(MSG_INFO, { id: 's1' });
  assert.equal(info[0], MSG_INFO);
  assert.deepEqual(JSON.parse(info.subarray(1).toString('utf8')), { id: 's1' });

  const exit = encodeJSON(MSG_EXIT, { code: 7 });
  assert.equal(exit[0], MSG_EXIT);
  assert.deepEqual(JSON.parse(exit.subarray(1).toString('utf8')), { code: 7 });
});

test('binary protocol encodes empty pong frame', () => {
  assert.deepEqual([...encodeEmpty(MSG_PONG)], [MSG_PONG]);
});

test('websocket protocol selection prefers binary and rejects unknown protocols', () => {
  assert.equal(selectWebSocketProtocol(new Set([JSON_SUBPROTOCOL, BINARY_SUBPROTOCOL])), BINARY_SUBPROTOCOL);
  assert.equal(selectWebSocketProtocol(new Set([JSON_SUBPROTOCOL])), JSON_SUBPROTOCOL);
  assert.equal(selectWebSocketProtocol(new Set(['unknown.protocol'])), false);
});
