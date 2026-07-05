import { describe, it, expect } from 'vitest';
import { parseSessionId } from './session';

describe('parseSessionId', () => {
  it('returns localId only for plain id', () => {
    expect(parseSessionId('s1')).toEqual({ localId: 's1' });
  });

  it('splits composite id into deviceId and localId', () => {
    expect(parseSessionId('d1:s1')).toEqual({ deviceId: 'd1', localId: 's1' });
  });

  it('handles multiple colons by splitting on the first one', () => {
    expect(parseSessionId('d1:s1:extra')).toEqual({ deviceId: 'd1', localId: 's1:extra' });
  });

  it('treats empty deviceId as still present', () => {
    expect(parseSessionId(':s1')).toEqual({ deviceId: '', localId: 's1' });
  });
});
