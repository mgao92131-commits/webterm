export class EventRing {
  constructor(maxFrames = 20000, maxBytes = 5 * 1024 * 1024) {
    this.maxFrames = maxFrames;
    this.maxBytes = maxBytes;
    this.frames = [];
    this.bytes = 0;
    this.nextSeq = 1;
  }

  push(data) {
    const text = String(data ?? '');
    const bytes = Buffer.from(text, 'utf8');
    const frame = {
      seq: this.nextSeq++,
      data: text,
      text,
      bytes,
      byteLength: bytes.length,
    };
    this.frames.push(frame);
    this.bytes += frame.byteLength;
    this.trim();
    return frame;
  }

  after(seq) {
    return this.frames
      .filter((frame) => frame.seq > seq)
      .map(({ seq, data, text, bytes }) => ({ seq, data, text, bytes }));
  }

  canReplayFrom(seq) {
    if (!this.frames.length) return true;
    return seq >= this.frames[0].seq - 1;
  }

  latestSeq() {
    return this.nextSeq - 1;
  }

  trim() {
    while (this.frames.length > this.maxFrames || this.bytes > this.maxBytes) {
      const frame = this.frames.shift();
      this.bytes -= frame.byteLength;
    }
  }
}
