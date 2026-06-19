// P2P WebSocket Mock — bridges browser WebSocket API over a WebRTC DataChannel tunnel.
// Works with P2PConnectionManager to create WS-like connections over P2P.

import {
  MSG_TYPE_WS_DATA, MSG_TYPE_HTTP_CHUNK,
  WS_DATA_TEXT, WS_DATA_BINARY,
  HTTP_CHUNK_DATA, HTTP_CHUNK_FIN,
} from '@shared/constants.js';

export function encodeTunnelFrame(msgType: number, id: string, extraByte: number, payload: Uint8Array | null): Uint8Array {
  const encoder = new TextEncoder();
  const idBytes = encoder.encode(id);
  const payloadBytes = payload || new Uint8Array(0);

  const frame = new Uint8Array(1 + 1 + idBytes.length + 1 + payloadBytes.length);
  frame[0] = msgType;
  frame[1] = idBytes.length;
  frame.set(idBytes, 2);
  frame[2 + idBytes.length] = extraByte;
  frame.set(payloadBytes, 3 + idBytes.length);
  return frame;
}

export function decodeTunnelFrame(data: ArrayBuffer) {
  const buf = new Uint8Array(data);
  if (buf.length < 3) return null;
  const msgType = buf[0];
  const idLen = buf[1];
  if (buf.length < 2 + idLen + 1) return null;

  const decoder = new TextDecoder();
  const id = decoder.decode(buf.subarray(2, 2 + idLen));
  const extraByte = buf[2 + idLen];
  const payload = buf.subarray(3 + idLen);
  return { msgType, id, extraByte, payload };
}

export function decodeBase64Utf8(base64: string): string {
  const binaryStr = atob(base64);
  const bytes = new Uint8Array(binaryStr.length);
  for (let i = 0; i < binaryStr.length; i++) {
    bytes[i] = binaryStr.charCodeAt(i);
  }
  return new TextDecoder().decode(bytes);
}
