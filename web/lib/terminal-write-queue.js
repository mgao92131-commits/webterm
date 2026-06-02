export class TerminalWriteQueue {
  constructor({ write, scheduler = (fn) => requestAnimationFrame(fn), maxChunkBytes = 256 * 1024 }) {
    if (typeof write !== "function") throw new TypeError("write is required");
    this.write = write;
    this.scheduler = scheduler;
    this.maxChunkBytes = maxChunkBytes;
    this.queue = [];
    this.scheduled = false;
    this.disposed = false;
    this.flushCount = 0;
    this.lastFlushBytes = 0;
  }

  enqueue(data) {
    if (this.disposed) return;
    const text = String(data || "");
    if (!text) return;
    this.queue.push(text);
    this.schedule();
  }

  flush() {
    if (this.disposed) return;
    this.scheduled = false;
    if (!this.queue.length) return;

    let bytes = 0;
    let text = "";
    while (this.queue.length) {
      const next = this.queue[0];
      const nextBytes = byteLength(next);
      if (text && bytes + nextBytes > this.maxChunkBytes) break;
      text += next;
      bytes += nextBytes;
      this.queue.shift();
    }

    this.lastFlushBytes = bytes;
    this.flushCount += 1;
    this.write(text);
    if (this.queue.length) this.schedule();
  }

  clear() {
    this.queue.length = 0;
    this.scheduled = false;
  }

  stats() {
    return {
      queuedFrames: this.queue.length,
      queuedBytes: this.queue.reduce((total, item) => total + byteLength(item), 0),
      flushCount: this.flushCount,
      lastFlushBytes: this.lastFlushBytes,
    };
  }

  dispose() {
    this.disposed = true;
    this.clear();
  }

  schedule() {
    if (this.scheduled) return;
    this.scheduled = true;
    this.scheduler(() => this.flush());
  }
}

function byteLength(value) {
  if (typeof Buffer !== "undefined") return Buffer.byteLength(value, "utf8");
  return new TextEncoder().encode(value).length;
}
