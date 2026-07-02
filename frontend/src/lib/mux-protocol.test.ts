import { describe, expect, it } from 'vitest';
import {
  MSG_TYPE_WS_DATA,
  WS_DATA_BINARY,
  WS_DATA_TEXT,
  decodeTextPayload,
  decodeTunnelFrame,
  encodeTextPayload,
  encodeTunnelFrame,
} from './mux-protocol';

describe('mux-protocol', () => {
  it('round-trips text tunnel frames', () => {
    const frame = encodeTunnelFrame(
      MSG_TYPE_WS_DATA,
      'term:s1',
      WS_DATA_TEXT,
      encodeTextPayload('hello relay mux'),
    );

    const decoded = decodeTunnelFrame(frame);

    expect(decoded.msgType).toBe(MSG_TYPE_WS_DATA);
    expect(decoded.id).toBe('term:s1');
    expect(decoded.extraByte).toBe(WS_DATA_TEXT);
    expect(decodeTextPayload(decoded.payload)).toBe('hello relay mux');
  });

  it('round-trips binary tunnel frames', () => {
    const payload = new Uint8Array([0, 1, 2, 255]);
    const frame = encodeTunnelFrame(MSG_TYPE_WS_DATA, 'manager:d1', WS_DATA_BINARY, payload);

    const decoded = decodeTunnelFrame(frame);

    expect(decoded.id).toBe('manager:d1');
    expect(decoded.extraByte).toBe(WS_DATA_BINARY);
    expect(Array.from(decoded.payload)).toEqual([0, 1, 2, 255]);
  });

  it('rejects malformed tunnel frames', () => {
    expect(() => decodeTunnelFrame(new Uint8Array([MSG_TYPE_WS_DATA]))).toThrow('invalid tunnel frame');
    expect(() => decodeTunnelFrame(new Uint8Array([MSG_TYPE_WS_DATA, 8, 1]))).toThrow('invalid tunnel frame');
  });

  it('rejects oversized tunnel ids', () => {
    expect(() => encodeTunnelFrame(MSG_TYPE_WS_DATA, 'x'.repeat(256), WS_DATA_TEXT, new Uint8Array())).toThrow(
      'tunnel id is too long',
    );
  });
});
