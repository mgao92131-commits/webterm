export class TerminalWriteQueue {
  constructor({ write, scheduler = (fn) => requestAnimationFrame(fn), maxChunkBytes = 64 * 1024 }) {
    if (typeof write !== "function") throw new TypeError("write is required");
    this.write = write;
    this.scheduler = scheduler;
    this.maxChunkBytes = maxChunkBytes;
    this.queue = [];
    this.scheduled = false;
    this.writing = false;
    this.disposed = false;
    this.flushCount = 0;
    this.lastFlushBytes = 0;
  }

  enqueue(data, callback) {
    if (this.disposed) return;
    const text = String(data || "");
    if (!text) {
      if (callback) callback();
      return;
    }
    this.queue.push({ text, callback });
    this.schedule();
  }

  flush() {
    if (this.disposed) return;
    this.scheduled = false;
    if (this.writing || !this.queue.length) return;

    let bytes = 0;
    let text = "";
    const callbacks = [];
    while (this.queue.length) {
      const next = this.queue[0];
      const nextBytes = byteLength(next.text);
      const budget = this.maxChunkBytes - bytes;
      if (text && nextBytes > budget) break;

      if (nextBytes > budget) {
        const chunk = takeBytePrefix(next.text, budget);
        if (!chunk) break;
        text += chunk;
        bytes += byteLength(chunk);
        next.text = next.text.slice(chunk.length);
        break;
      }

      text += next.text;
      bytes += nextBytes;
      if (next.callback) callbacks.push(next.callback);
      this.queue.shift();
    }

    if (!text) return;
    this.lastFlushBytes = bytes;
    this.flushCount += 1;
    this.writing = true;
    this.write(text, () => {
      this.writing = false;
      if (this.disposed) return;
      for (const cb of callbacks) {
        try { cb(); } catch (_) {}
      }
      if (this.queue.length) this.schedule();
    });
  }

  clear() {
    this.queue.length = 0;
    this.scheduled = false;
  }

  stats() {
    return {
      queuedFrames: this.queue.length,
      queuedBytes: this.queue.reduce((total, item) => total + byteLength(item.text), 0),
      writing: this.writing,
      flushCount: this.flushCount,
      lastFlushBytes: this.lastFlushBytes,
    };
  }

  dispose() {
    this.disposed = true;
    this.clear();
  }

  schedule() {
    if (this.scheduled || this.writing) return;
    this.scheduled = true;
    this.scheduler(() => this.flush());
  }
}

function byteLength(value) {
  if (typeof Buffer !== "undefined") return Buffer.byteLength(value, "utf8");
  return new TextEncoder().encode(value).length;
}

function takeBytePrefix(value, maxBytes) {
  if (maxBytes <= 0) return "";
  if (byteLength(value) <= maxBytes) return value;

  let low = 0;
  let high = value.length;
  while (low < high) {
    const mid = Math.ceil((low + high) / 2);
    if (byteLength(value.slice(0, mid)) <= maxBytes) {
      low = mid;
    } else {
      high = mid - 1;
    }
  }

  if (low > 0 && isHighSurrogate(value.charCodeAt(low - 1))) {
    low -= 1;
  }
  return value.slice(0, low);
}

function isHighSurrogate(code) {
  return code >= 0xd800 && code <= 0xdbff;
}
