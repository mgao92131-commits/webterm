import {
  MSG_INPUT, MSG_OUTPUT, MSG_RESIZE, MSG_HELLO,
  MSG_INFO, MSG_EXIT, MSG_PING, MSG_PONG, MSG_TITLE,
  BINARY_SUBPROTOCOL, JSON_SUBPROTOCOL,
} from '../shared/constants.js';

export {
  MSG_INPUT, MSG_OUTPUT, MSG_RESIZE, MSG_HELLO,
  MSG_INFO, MSG_EXIT, MSG_PING, MSG_PONG, MSG_TITLE,
  BINARY_SUBPROTOCOL, JSON_SUBPROTOCOL,
};

export function selectWebSocketProtocol(protocols) {
  if (protocols?.has?.(BINARY_SUBPROTOCOL)) return BINARY_SUBPROTOCOL;
  if (protocols?.has?.(JSON_SUBPROTOCOL)) return JSON_SUBPROTOCOL;
  return false;
}

export function encodeOutput(seq, data) {
  const bytes = Buffer.isBuffer(data) ? data : Buffer.from(String(data ?? ''), 'utf8');
  const frame = Buffer.allocUnsafe(1 + 8 + bytes.length);
  frame[0] = MSG_OUTPUT;
  writeUint64BE(frame, Number(seq), 1);
  bytes.copy(frame, 9);
  return frame;
}

export function encodeJSON(type, value) {
  const payload = Buffer.from(JSON.stringify(value ?? {}), 'utf8');
  return Buffer.concat([Buffer.from([type]), payload]);
}

export function encodeEmpty(type) {
  return Buffer.from([type]);
}

export function decodeJSONPayload(payload) {
  if (!payload?.length) return {};
  const text = Buffer.from(payload).toString('utf8').trim();
  if (!text || text === 'null') return {};
  return JSON.parse(text);
}

export function readUint64BE(buffer, offset = 0) {
  let value = 0;
  for (let index = 0; index < 8; index += 1) {
    value = value * 256 + buffer[offset + index];
  }
  return value;
}

export function writeUint64BE(buffer, value, offset = 0) {
  let next = Math.max(0, Math.trunc(Number(value) || 0));
  for (let index = 7; index >= 0; index -= 1) {
    buffer[offset + index] = next & 0xff;
    next = Math.floor(next / 256);
  }
}
