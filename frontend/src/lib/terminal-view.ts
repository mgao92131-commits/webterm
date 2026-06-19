import { DisposableStore, IDisposable } from "./disposable";
import { TerminalWriteQueue, WriteQueueStats } from "./terminal-write-queue";
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { WebglAddon } from '@xterm/addon-webgl';

export interface TerminalViewParams {
  TerminalCtor?: any;
  FitAddonCtor?: any;
  WebglAddonCtor?: any;
  element: HTMLElement;
  options?: any;
}

export class TerminalView implements IDisposable {
  private disposables: DisposableStore;
  public term: any;
  private fitAddon: any;
  private writeQueue: TerminalWriteQueue;

  constructor(params: TerminalViewParams) {
    const ActualTerminal = params.TerminalCtor || Terminal;
    const ActualFitAddon = params.FitAddonCtor || FitAddon;
    const ActualWebglAddon = params.WebglAddonCtor || WebglAddon;

    if (!ActualTerminal) throw new Error("Terminal constructor is required");
    if (!ActualFitAddon) throw new Error("FitAddon constructor is required");
    if (!params.element) throw new Error("terminal mount element is required");

    this.disposables = new DisposableStore();
    
    // 如果是真实的 FitAddon 类，直接实例化；否则假定是 FitAddonCtor.FitAddon
    this.fitAddon = params.FitAddonCtor ? new params.FitAddonCtor.FitAddon() : new ActualFitAddon();
    this.term = new ActualTerminal(params.options);
    
    this.term.loadAddon(this.fitAddon);
    this.term.open(params.element);

    if (ActualWebglAddon) {
      try {
        const webglAddon = params.WebglAddonCtor ? new params.WebglAddonCtor.WebglAddon() : new ActualWebglAddon();
        this.term.loadAddon(webglAddon);

        let addonDisposable: IDisposable | null = null;
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
      } catch (err) {
        console.error("Failed to load WebglAddon, falling back to DOM renderer:", err);
      }
    }

    this.writeQueue = new TerminalWriteQueue({
      write: (data, callback) => this.term.write(data, callback),
    });
    this.disposables.add(this.writeQueue);
    this.disposables.add(this.term);
  }

  get cols(): number {
    return this.term.cols;
  }

  get rows(): number {
    return this.term.rows;
  }

  get buffer(): any {
    return this.term.buffer;
  }

  get options(): any {
    return this.term.options;
  }

  enqueueWrite(data: string, callback?: () => void): void {
    this.writeQueue.enqueue(data, callback);
  }

  reset(): void {
    this.writeQueue.clear();
    this.term.reset();
  }

  fit(): void {
    this.fitAddon.fit();
  }

  focus(): void {
    this.term.focus();
  }

  scrollToBottom(): void {
    this.term.scrollToBottom();
  }

  scrollToLine(line: number): void {
    this.term.scrollToLine(line);
  }

  scrollLines(lines: number): void {
    this.term.scrollLines(lines);
  }

  refreshAll(): void {
    if (this.term.rows > 0) {
      this.term.refresh(0, this.term.rows - 1);
    }
  }

  clearSelection(): void {
    this.term.clearSelection();
  }

  getSelection(): string {
    return this.term.getSelection();
  }

  select(col: number, row: number, length: number): void {
    this.term.select(col, row, length);
  }

  onData(listener: (data: string) => void): IDisposable {
    return this.term.onData(listener);
  }

  onScroll(listener: (ydisp: number) => void): IDisposable {
    return this.term.onScroll(listener);
  }

  onRender(listener: (event: { start: number; end: number }) => void): IDisposable {
    return this.term.onRender(listener);
  }

  stats(): WriteQueueStats {
    return this.writeQueue.stats();
  }

  dispose(): void {
    this.disposables.dispose();
  }
}
