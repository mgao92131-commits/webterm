import assert from "node:assert/strict";
import test from "node:test";
import { TerminalWriteQueue } from "./terminal-write-queue.js";

test("TerminalWriteQueue batches writes until scheduled flush", () => {
  const scheduled = [];
  const writes = [];
  const queue = new TerminalWriteQueue({
    write: (data) => writes.push(data),
    scheduler: (fn) => scheduled.push(fn),
  });

  queue.enqueue("hello");
  queue.enqueue(" ");
  queue.enqueue("world");

  assert.equal(writes.length, 0);
  assert.equal(scheduled.length, 1);

  scheduled.shift()();
  assert.deepEqual(writes, ["hello world"]);
  assert.deepEqual(queue.stats(), {
    queuedFrames: 0,
    queuedBytes: 0,
    writing: true,
    flushCount: 1,
    lastFlushBytes: 11,
  });
});

test("TerminalWriteQueue splits large batches by byte threshold", () => {
  const scheduled = [];
  const writes = [];
  const completions = [];
  const queue = new TerminalWriteQueue({
    write: (data, callback) => {
      writes.push(data);
      completions.push(callback);
    },
    scheduler: (fn) => scheduled.push(fn),
    maxChunkBytes: 3,
  });

  queue.enqueue("ab");
  queue.enqueue("cd");
  scheduled.shift()();

  assert.deepEqual(writes, ["ab"]);
  assert.equal(scheduled.length, 0);
  completions.shift()();
  scheduled.shift()();

  assert.deepEqual(writes, ["ab", "cd"]);
});

test("TerminalWriteQueue splits a single oversized frame", () => {
  const scheduled = [];
  const completions = [];
  const writes = [];
  let done = false;
  const queue = new TerminalWriteQueue({
    write: (data, callback) => {
      writes.push(data);
      completions.push(callback);
    },
    scheduler: (fn) => scheduled.push(fn),
    maxChunkBytes: 3,
  });

  queue.enqueue("abcdef", () => {
    done = true;
  });

  scheduled.shift()();
  assert.deepEqual(writes, ["abc"]);
  assert.equal(done, false);

  completions.shift()();
  scheduled.shift()();
  assert.deepEqual(writes, ["abc", "def"]);
  assert.equal(done, false);

  completions.shift()();
  assert.equal(done, true);
});

test("TerminalWriteQueue ignores writes after dispose", () => {
  const scheduled = [];
  const writes = [];
  const queue = new TerminalWriteQueue({
    write: (data) => writes.push(data),
    scheduler: (fn) => scheduled.push(fn),
  });

  queue.enqueue("before");
  queue.dispose();
  queue.enqueue("after");
  scheduled.shift()();

  assert.deepEqual(writes, []);
});
