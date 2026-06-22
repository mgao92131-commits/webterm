export class EventRing {
  constructor(maxFrames = 20000, maxBytes = 5 * 1024 * 1024) {
    this.maxFrames = maxFrames;
    this.maxBytes = maxBytes;
    this.frames = [];
    this.head = 0;
    this.bytes = 0;
    this.nextSeq = 1;
  }

  push(data) {
    const text = String(data ?? '');
    const bytes = Buffer.from(text, 'utf8');
    const seq = this.nextSeq++;
    const frame = {
      seq,
      bytes,
      byteLength: bytes.length,
    };
    this.frames.push(frame);
    this.bytes += frame.byteLength;
    this.trim();
    return { seq, data: text, text, bytes, byteLength: bytes.length };
  }

  after(seq) {
    const frames = this.activeFrames();
    let low = 0;
    let high = frames.length;
    while (low < high) {
      const mid = Math.floor((low + high) / 2);
      if (frames[mid].seq <= seq) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return frames.slice(low).map(({ seq, bytes }) => {
      const text = bytes.toString('utf8');
      return { seq, data: text, text, bytes };
    });
  }

  canReplayFrom(seq) {
    if (!this.length()) return true;
    return seq >= this.frames[this.head].seq - 1;
  }

  latestSeq() {
    return this.nextSeq - 1;
  }

  trim() {
    while (this.length() > this.maxFrames || this.bytes > this.maxBytes) {
      const frame = this.frames[this.head++];
      this.bytes -= frame.byteLength;
    }
    this.compactIfNeeded();
  }

  length() {
    return this.frames.length - this.head;
  }

  activeFrames() {
    return this.head === 0 ? this.frames : this.frames.slice(this.head);
  }

  compactIfNeeded() {
    if (this.head === 0) return;
    if (this.head < 1024 && this.head * 2 < this.frames.length) return;
    this.frames = this.frames.slice(this.head);
    this.head = 0;
  }
}
