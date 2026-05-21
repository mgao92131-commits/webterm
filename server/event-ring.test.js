import test from 'node:test';
import assert from 'node:assert/strict';
import { EventRing } from './event-ring.js';

test('event ring replays frames after seq', () => {
  const ring = new EventRing(10, 1024);
  const a = ring.push('a');
  const b = ring.push('b');
  const c = ring.push('c');
  assert.equal(a.seq, 1);
  assert.deepEqual(ring.after(b.seq), [{ seq: c.seq, data: 'c' }]);
  assert.equal(ring.canReplayFrom(0), true);
});

test('event ring trims by frame count', () => {
  const ring = new EventRing(2, 1024);
  ring.push('a');
  ring.push('b');
  ring.push('c');
  assert.equal(ring.canReplayFrom(0), false);
  assert.deepEqual(ring.after(1).map((f) => f.data), ['b', 'c']);
});
