export class EventRing {
  constructor(maxFrames = 20000, maxBytes = 5 * 1024 * 1024) {
    this.maxFrames = maxFrames;
    this.maxBytes = maxBytes;
    this.frames = [];
    this.bytes = 0;
    this.nextSeq = 1;
  }

  push(data) {
    const frame = {
      seq: this.nextSeq++,
      data,
      bytes: Buffer.byteLength(data, 'utf8'),
    };
    this.frames.push(frame);
    this.bytes += frame.bytes;
    this.trim();
    return frame;
  }

  after(seq) {
    return this.frames.filter((frame) => frame.seq > seq).map(({ seq, data }) => ({ seq, data }));
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
      this.bytes -= frame.bytes;
    }
  }
}
