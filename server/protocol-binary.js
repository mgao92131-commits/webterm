export const MSG_INPUT = 0x01;
export const MSG_OUTPUT = 0x02;
export const MSG_RESIZE = 0x03;
export const MSG_HELLO = 0x04;
export const MSG_INFO = 0x05;
export const MSG_EXIT = 0x06;
export const MSG_PING = 0x07;
export const MSG_PONG = 0x08;
export const MSG_TITLE = 0x09;

export const BINARY_SUBPROTOCOL = 'webterm.binary.v1';
export const JSON_SUBPROTOCOL = 'webterm.json.v1';

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
