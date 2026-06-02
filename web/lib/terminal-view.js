import { DisposableStore } from "./disposable.js";
import { TerminalWriteQueue } from "./terminal-write-queue.js";

export class TerminalView {
  constructor({ TerminalCtor, FitAddonCtor, WebglAddonCtor, element, options }) {
    if (!TerminalCtor) throw new Error("Terminal constructor is required");
    if (!FitAddonCtor?.FitAddon) throw new Error("FitAddon constructor is required");
    if (!element) throw new Error("terminal mount element is required");

    this.disposables = new DisposableStore();
    this.term = new TerminalCtor(options);
    this.fitAddon = new FitAddonCtor.FitAddon();
    this.term.loadAddon(this.fitAddon);
    this.term.open(element);

    if (WebglAddonCtor?.WebglAddon) {
      try {
        const webglAddon = new WebglAddonCtor.WebglAddon();
        this.term.loadAddon(webglAddon);

        let addonDisposable;
        const contextLossDisposable = this.disposables.add(webglAddon.onContextLoss(() => {
          console.warn("WebGL context lost, disposing WebGL addon");
          if (addonDisposable) {
            addonDisposable.dispose();
            addonDisposable = null;
          }
          if (contextLossDisposable) {
            contextLossDisposable.dispose();
          }
        }));
        addonDisposable = this.disposables.add(webglAddon);
        console.log("WebGL renderer enabled");
      } catch (err) {
        console.error("Failed to load WebglAddon, falling back to DOM renderer:", err);
      }
    }

    this.writeQueue = new TerminalWriteQueue({
      write: (data) => this.term.write(data),
    });
    this.disposables.add(this.writeQueue);
    this.disposables.add(this.term);
  }

  get cols() {
    return this.term.cols;
  }

  get rows() {
    return this.term.rows;
  }

  get buffer() {
    return this.term.buffer;
  }

  get options() {
    return this.term.options;
  }

  enqueueWrite(data) {
    this.writeQueue.enqueue(data);
  }

  reset() {
    this.writeQueue.clear();
    this.term.reset();
  }

  fit() {
    this.fitAddon.fit();
  }

  focus() {
    this.term.focus();
  }

  scrollToBottom() {
    this.term.scrollToBottom();
  }

  scrollToLine(line) {
    this.term.scrollToLine(line);
  }

  scrollLines(lines) {
    this.term.scrollLines(lines);
  }

  clearSelection() {
    this.term.clearSelection();
  }

  getSelection() {
    return this.term.getSelection();
  }

  select(col, row, length) {
    this.term.select(col, row, length);
  }

  onData(listener) {
    return this.term.onData(listener);
  }

  onScroll(listener) {
    return this.term.onScroll(listener);
  }

  onRender(listener) {
    return this.term.onRender(listener);
  }

  stats() {
    return this.writeQueue.stats();
  }

  dispose() {
    this.disposables.dispose();
  }
}
