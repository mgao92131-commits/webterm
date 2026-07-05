import {
  MUX_SUBPROTOCOL,
  BINARY_SUBPROTOCOL,
  JSON_SUBPROTOCOL,
  MSG_TYPE_WS_DATA,
  WS_DATA_TEXT,
  WS_DATA_BINARY,
} from '@shared/constants.js';

export { MUX_SUBPROTOCOL, BINARY_SUBPROTOCOL, JSON_SUBPROTOCOL, MSG_TYPE_WS_DATA, WS_DATA_TEXT, WS_DATA_BINARY };

export interface TunnelFrame {
  msgType: number;
  id: string;
  extraByte: number;
  payload: Uint8Array;
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function encodeTunnelFrame(msgType: number, id: string, extraByte: number, payload: Uint8Array): Uint8Array {
  const idBytes = encoder.encode(id);
  if (idBytes.length > 255) {
    throw new Error('tunnel id is too long');
  }
  const frame = new Uint8Array(1 + 1 + idBytes.length + 1 + payload.length);
  frame[0] = msgType;
  frame[1] = idBytes.length;
  frame.set(idBytes, 2);
  frame[2 + idBytes.length] = extraByte;
  frame.set(payload, 3 + idBytes.length);
  return frame;
}

export function decodeTunnelFrame(data: ArrayBuffer | Uint8Array): TunnelFrame {
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
  if (bytes.length < 3) {
    throw new Error('invalid tunnel frame');
  }
  const idLen = bytes[1];
  if (bytes.length < 2 + idLen + 1) {
    throw new Error('invalid tunnel frame');
  }
  return {
    msgType: bytes[0],
    id: decoder.decode(bytes.slice(2, 2 + idLen)),
    extraByte: bytes[2 + idLen],
    payload: bytes.slice(3 + idLen),
  };
}

export function encodeTextPayload(value: string): Uint8Array {
  return encoder.encode(value);
}

export function decodeTextPayload(value: Uint8Array): string {
  return decoder.decode(value);
}
