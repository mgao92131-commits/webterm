export interface WriteQueueOptions {
  write: (data: string, callback: () => void) => void;
  scheduler?: (fn: () => void) => void;
  maxChunkBytes?: number;
}

export interface QueueItem {
  text: string;
  callback?: () => void;
}

export interface WriteQueueStats {
  queuedFrames: number;
  queuedBytes: number;
  writing: boolean;
  flushCount: number;
  lastFlushBytes: number;
}

export class TerminalWriteQueue {
  private write: (data: string, callback: () => void) => void;
  private scheduler: (fn: () => void) => void;
  private maxChunkBytes: number;
  private queue: QueueItem[] = [];
  private scheduled = false;
  private writing = false;
  private disposed = false;
  private flushCount = 0;
  private lastFlushBytes = 0;

  constructor(options: WriteQueueOptions) {
    if (typeof options.write !== "function") throw new TypeError("write is required");
    this.write = options.write;
    this.scheduler = options.scheduler || ((fn) => requestAnimationFrame(fn));
    this.maxChunkBytes = options.maxChunkBytes ?? 64 * 1024;
  }

  enqueue(data: string, callback?: () => void): void {
    if (this.disposed) return;
    const text = String(data || "");
    if (!text) {
      if (callback) callback();
      return;
    }
    this.queue.push({ text, callback });
    this.schedule();
  }

  flush(): void {
    if (this.disposed) return;
    this.scheduled = false;
    if (this.writing || !this.queue.length) return;

    let bytes = 0;
    let text = "";
    const callbacks: (() => void)[] = [];
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

  clear(): void {
    this.queue.length = 0;
    this.scheduled = false;
  }

  stats(): WriteQueueStats {
    return {
      queuedFrames: this.queue.length,
      queuedBytes: this.queue.reduce((total, item) => total + byteLength(item.text), 0),
      writing: this.writing,
      flushCount: this.flushCount,
      lastFlushBytes: this.lastFlushBytes,
    };
  }

  dispose(): void {
    this.disposed = true;
    this.clear();
  }

  private schedule(): void {
    if (this.scheduled || this.writing) return;
    this.scheduled = true;
    this.scheduler(() => this.flush());
  }
}

function byteLength(value: string): number {
  if (typeof Buffer !== "undefined") return Buffer.byteLength(value, "utf8");
  return new TextEncoder().encode(value).length;
}

function takeBytePrefix(value: string, maxBytes: number): string {
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

function isHighSurrogate(code: number): boolean {
  return code >= 0xd800 && code <= 0xdbff;
}
