import { describe, it, expect } from 'vitest';
import { parseSessionId, sortSessionsByAttention } from './session';

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

describe('sortSessionsByAttention', () => {
  it('puts approval_required and error notifications at the top', () => {
    const sessions = [
      { id: 'idle', agentState: 'idle' },
      { id: 'running', agentState: 'running' },
      { id: 'approval', agentState: 'approval_required' },
      { id: 'notify', notification: { level: 'error' } },
    ];
    const sorted = sortSessionsByAttention(sessions);
    expect(sorted.map((s: any) => s.id)).toEqual(['approval', 'notify', 'running', 'idle']);
  });

  it('orders by notification > running > idle', () => {
    const sessions = [
      { id: 'idle' },
      { id: 'notify', notification: { level: 'info' } },
      { id: 'running', state: 'running' },
    ];
    const sorted = sortSessionsByAttention(sessions);
    expect(sorted.map((s: any) => s.id)).toEqual(['notify', 'running', 'idle']);
  });
});
