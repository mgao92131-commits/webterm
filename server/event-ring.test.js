import test from 'node:test';
import assert from 'node:assert/strict';
import { EventRing } from './event-ring.js';

test('event ring replays frames after seq', () => {
  const ring = new EventRing(10, 1024);
  const a = ring.push('a');
  const b = ring.push('b');
  const c = ring.push('c');
  assert.equal(a.seq, 1);
  assert.deepEqual(ring.after(b.seq).map(({ seq, data, text }) => ({ seq, data, text })), [
    { seq: c.seq, data: 'c', text: 'c' },
  ]);
  assert.deepEqual([...ring.after(b.seq)[0].bytes], [99]);
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

test('event ring trims many frames without shifting active seq order', () => {
  const ring = new EventRing(3, 1024);
  for (let i = 0; i < 10; i++) ring.push(String(i));
  assert.deepEqual(ring.after(0).map((f) => f.data), ['7', '8', '9']);
  assert.equal(ring.canReplayFrom(7), true);
  assert.equal(ring.canReplayFrom(6), false);
});

test('event ring trims by byte count', () => {
  const ring = new EventRing(10, 3);
  ring.push('aa');
  ring.push('bb');
  ring.push('c');
  assert.deepEqual(ring.after(0).map((f) => f.data), ['bb', 'c']);
});

test('event ring returns replay frames with decoded text from bytes', () => {
  const ring = new EventRing(10, 1024);
  const pushed = ring.push('α');
  assert.equal(pushed.data, 'α');
  assert.equal(pushed.text, 'α');
  const [frame] = ring.after(0);
  assert.equal(frame.data, 'α');
  assert.equal(frame.text, 'α');
  assert.ok(Buffer.isBuffer(frame.bytes));
});
