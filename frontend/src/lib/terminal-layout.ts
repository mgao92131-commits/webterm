import { DisposableStore, IDisposable } from './disposable';

export interface TerminalViewInterface {
  fit(): void;
  rows?: number;
  cols?: number;
  buffer?: {
    active: {
      viewportY: number;
      baseY: number;
    };
  };
  scrollToBottom(): void;
  refreshAll?(): void;
}

export interface ResizeMessage {
  cols: number;
  rows: number;
  visible: boolean;
}

export interface TerminalLayoutOptions {
  store: DisposableStore;
  terminalView: TerminalViewInterface;
  container: HTMLElement;
  documentElement: HTMLElement;
  windowObject?: any;
  sendResizeMessage: (size: ResizeMessage) => void;
  isVisible?: () => boolean;
}

interface BottomPinState {
  active: boolean;
  until: number;
  rafId: any | null;
  deadlineTimeout: IDisposable | null;
  checkTimeout: IDisposable | null;
  stableFrames: number;
  programmaticScroll: boolean;
  clearProgrammaticRaf: any | null;
  clearProgrammaticTimeout: IDisposable | null;
}

export class TerminalLayoutController implements IDisposable {
  private store: DisposableStore;
  private terminalView: TerminalViewInterface | null;
  private container: HTMLElement;
  private documentElement: HTMLElement;
  private windowObject: any;
  private sendResizeMessage: (size: ResizeMessage) => void;
  private debouncedSendResizeMessage: (size: ResizeMessage) => void;
  private isVisible: () => boolean;
  private resizingTerminal = false;
  private disposed = false;
  private resizeRafId: any | null = null;
  
  private bottomPin: BottomPinState = {
    active: false,
    until: 0,
    rafId: null,
    deadlineTimeout: null,
    checkTimeout: null,
    stableFrames: 0,
    programmaticScroll: false,
    clearProgrammaticRaf: null,
    clearProgrammaticTimeout: null,
  };

  constructor(options: TerminalLayoutOptions) {
    this.store = options.store;
    this.terminalView = options.terminalView;
    this.container = options.container;
    this.documentElement = options.documentElement;
    this.windowObject = options.windowObject || window;
    this.sendResizeMessage = options.sendResizeMessage;
    this.debouncedSendResizeMessage = debounce(options.sendResizeMessage, 150);
    this.isVisible = options.isVisible || (() => !document.hidden);
  }

  attach(): void {
    this.updateViewportMetrics();

    const viewport = this.windowObject.visualViewport;
    if (viewport) {
      this.store.addEventListener(viewport, "resize", debounce(() => this.handleViewportResize(), 16));
      this.store.addEventListener(viewport, "scroll", debounce(() => this.updateViewportMetrics({ height: false }), 16));
    }
    this.store.addEventListener(this.windowObject, "resize", debounce(() => this.handleViewportResize(), 16));

    if (this.windowObject.ResizeObserver && this.container) {
      const observer = new this.windowObject.ResizeObserver(debounce(() => {
        this.sendResize({
          reason: "container",
          beforeFit: () => this.updateViewportMetrics(),
        });
      }, 16));
      observer.observe(this.container);
      this.store.add({ dispose: () => observer.disconnect() });
    }

    const fontsReady = this.documentElement?.ownerDocument?.fonts?.ready;
    if (fontsReady) {
      fontsReady.then(() => {
        this.sendResize({ reason: "fonts" });
        this.store.setTimeout(() => this.sendResize({ reason: "fonts" }), 50);
      });
    } else {
      this.store.setTimeout(() => this.sendResize({ reason: "initial" }), 100);
    }
  }

  handleViewportResize(): void {
    this.sendResize({
      reason: "viewport",
      beforeFit: () => this.updateViewportMetrics(),
    });
  }

  updateViewportMetrics(options: { height?: boolean } = {}): void {
    const updateHeight = options.height !== false;
    const viewport = this.windowObject.visualViewport;
    const height = viewport?.height || this.windowObject.innerHeight;
    const offsetTop = viewport?.offsetTop || 0;
    const keyboardOffset = keyboardOffsetFor({
      innerHeight: this.windowObject.innerHeight,
      viewportHeight: height,
      viewportOffsetTop: offsetTop,
    });
    if (updateHeight) this.documentElement.style.setProperty("--viewport-height", `${height}px`);
    this.documentElement.style.setProperty("--keyboard-offset", `${keyboardOffset}px`);
  }

  sendResize(options: { pinBottom?: boolean; beforeFit?: () => void; reason?: string } = {}): void {
    if (this.disposed || !this.terminalView) return;
    const beforeViewportHeight = this.viewportHeight();
    const beforeRows = this.terminalView.rows || 0;
    const domViewport = this.domViewport();
    const shouldPinBottom = options.pinBottom ?? (this.isNearBottom() || Boolean(domViewport && this.isDomViewportAtBottom()));

    if (this.resizeRafId !== null) {
      this.safeCancelRaf(this.resizeRafId);
    }

    this.resizeRafId = this.safeRaf(() => {
      this.resizeRafId = null;
      if (this.disposed || !this.terminalView) return;

      options.beforeFit?.();
      this.resizingTerminal = true;
      try {
        this.terminalView.fit();
        if (shouldPinBottom) {
          const grew = this.viewportHeight() > beforeViewportHeight || (this.terminalView.rows || 0) > beforeRows;
          this.beginBottomPin({ grew });
          this.pokeBottomPin({ force: true });
        } else {
          this.cancelBottomPin();
          this.terminalView.refreshAll?.();
        }
      } finally {
        this.resizingTerminal = false;
      }

      if (this.terminalView.cols !== undefined && this.terminalView.rows !== undefined && this.terminalView.cols > 0 && this.terminalView.rows > 0) {
        const size = {
          cols: this.terminalView.cols,
          rows: this.terminalView.rows,
          visible: this.isVisible(),
        };
        if (options.reason === "container" || options.reason === "viewport") {
          this.debouncedSendResizeMessage(size);
        } else {
          this.sendResizeMessage(size);
        }
      }
    });
  }

  beginBottomPin({ grew = false } = {}): void {
    const now = this.now();
    this.bottomPin.active = true;
    this.bottomPin.until = now + (grew ? 500 : 250);
    this.bottomPin.stableFrames = 0;
    this.clearBottomPinTimers();
    this.bottomPin.deadlineTimeout = this.store.setTimeout(() => {
      this.bottomPin.deadlineTimeout = null;
      this.cancelBottomPin();
    }, grew ? 520 : 270);
  }

  pokeBottomPin({ force = false } = {}): void {
    if (this.disposed || !this.bottomPin.active || !this.terminalView) return;
    if (this.now() > this.bottomPin.until) {
      this.cancelBottomPin();
      return;
    }
    if (this.bottomPin.rafId !== null) return;

    this.bottomPin.rafId = this.safeRaf(() => {
      this.bottomPin.rafId = null;
      if (this.disposed || !this.bottomPin.active || !this.terminalView) return;
      if (this.now() > this.bottomPin.until) {
        this.cancelBottomPin();
        return;
      }

      const domViewportSettled = !this.hasDomViewport() || this.isDomViewportAtBottom();
      if (force || !this.isAtBottom() || !domViewportSettled) {
        this.programmaticScrollToBottom();
        this.bottomPin.stableFrames = 0;
        this.queueBottomPinCheck(48);
        return;
      }

      this.bottomPin.stableFrames += 1;
      if (this.bottomPin.stableFrames >= 2) {
        this.cancelBottomPin();
      } else {
        this.queueBottomPinCheck(48);
      }
    });
  }

  handleTerminalRender(): void {
    this.pokeBottomPin();
  }

  handleTerminalScroll(): void {
    if (this.resizingTerminal || this.bottomPin.programmaticScroll) {
      this.pokeBottomPin();
      return;
    }
    if (this.bottomPin.active && !this.isNearBottom() && !this.isDomViewportAtBottom()) {
      this.cancelBottomPin();
    }
  }

  settleAfterWrite({ fit = false } = {}): void {
    if (this.disposed || !this.terminalView) return;
    this.beginBottomPin({ grew: true });

    const settle = () => {
      if (this.disposed || !this.terminalView) return;
      if (fit) {
        this.terminalView.fit();
      }
      this.programmaticScrollToBottom();
      this.terminalView.refreshAll?.();
      this.pokeBottomPin({ force: true });
    };

    settle();
    this.safeRaf(() => {
      settle();
      this.safeRaf(settle);
    });
    this.store.setTimeout(settle, 80);
    this.store.setTimeout(() => {
      settle();
      this.cancelBottomPin();
    }, 180);
  }

  cancelPendingRestore(): void {
    this.cancelBottomPin();
  }

  cancelBottomPin(): void {
    this.bottomPin.active = false;
    this.bottomPin.until = 0;
    this.bottomPin.stableFrames = 0;
    this.clearBottomPinTimers();
  }

  clearBottomPinTimers(): void {
    if (this.bottomPin.deadlineTimeout) {
      this.bottomPin.deadlineTimeout.dispose();
      this.bottomPin.deadlineTimeout = null;
    }
    if (this.bottomPin.checkTimeout) {
      this.bottomPin.checkTimeout.dispose();
      this.bottomPin.checkTimeout = null;
    }
    if (this.bottomPin.rafId !== null) {
      this.safeCancelRaf(this.bottomPin.rafId);
      this.bottomPin.rafId = null;
    }
  }

  stats() {
    return {
      bottomPinActive: this.bottomPin.active,
      bottomPinStableFrames: this.bottomPin.stableFrames,
      programmaticScroll: this.bottomPin.programmaticScroll,
      resizingTerminal: this.resizingTerminal,
    };
  }

  isNearBottom(tolerance = 2): boolean {
    const buffer = this.terminalView?.buffer?.active;
    if (!buffer) return false;
    return buffer.baseY - buffer.viewportY <= tolerance;
  }

  isAtBottom(): boolean {
    const buffer = this.terminalView?.buffer?.active;
    if (!buffer) return false;
    return buffer.viewportY >= buffer.baseY;
  }

  isDomViewportAtBottom(tolerance = 2): boolean {
    const viewport = this.domViewport();
    if (!viewport) return false;
    return viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight <= tolerance;
  }

  hasDomViewport(): boolean {
    return Boolean(this.domViewport());
  }

  domViewport(): HTMLElement | null {
    return this.container?.querySelector?.(".xterm-viewport") || null;
  }

  viewportHeight(): number {
    return this.windowObject.visualViewport?.height || this.windowObject.innerHeight || 0;
  }

  programmaticScrollToBottom(): void {
    if (!this.terminalView) return;
    this.bottomPin.programmaticScroll = true;
    this.terminalView.scrollToBottom();
    this.scrollDomViewportToBottom();
    this.terminalView.refreshAll?.();
    this.scheduleClearProgrammaticScroll();
  }

  scrollDomViewportToBottom(): void {
    const viewport = this.container?.querySelector?.(".xterm-viewport");
    if (!viewport) return;
    viewport.scrollTop = viewport.scrollHeight;
  }

  scheduleClearProgrammaticScroll(): void {
    if (this.bottomPin.clearProgrammaticRaf !== null) {
      this.safeCancelRaf(this.bottomPin.clearProgrammaticRaf);
    }
    if (this.bottomPin.clearProgrammaticTimeout) {
      this.bottomPin.clearProgrammaticTimeout.dispose();
      this.bottomPin.clearProgrammaticTimeout = null;
    }

    this.bottomPin.clearProgrammaticRaf = this.safeRaf(() => {
      this.bottomPin.clearProgrammaticRaf = null;
      this.bottomPin.clearProgrammaticTimeout = this.store.setTimeout(() => {
        this.bottomPin.clearProgrammaticTimeout = null;
        this.bottomPin.programmaticScroll = false;
      }, 0);
    });
  }

  queueBottomPinCheck(delay: number): void {
    if (this.bottomPin.checkTimeout) return;
    this.bottomPin.checkTimeout = this.store.setTimeout(() => {
      this.bottomPin.checkTimeout = null;
      this.pokeBottomPin();
    }, delay);
  }

  now(): number {
    return this.windowObject.performance?.now?.() ?? Date.now();
  }

  safeRaf(callback: FrameRequestCallback): any {
    const raf = this.windowObject.requestAnimationFrame?.bind(this.windowObject);
    if (raf) {
      return raf(callback) ?? null;
    }
    return this.store.setTimeout(callback as () => void, 0) ?? null;
  }

  safeCancelRaf(id: any): void {
    if (!id) return;
    if (id && typeof id.dispose === "function") {
      id.dispose();
      return;
    }
    const cancel = this.windowObject.cancelAnimationFrame?.bind(this.windowObject);
    if (cancel && typeof id === "number") {
      cancel(id);
    } else {
      clearTimeout(id);
    }
  }

  dispose(): void {
    this.disposed = true;
    this.cancelBottomPin();
    if (this.bottomPin.clearProgrammaticRaf !== null) {
      this.safeCancelRaf(this.bottomPin.clearProgrammaticRaf);
      this.bottomPin.clearProgrammaticRaf = null;
    }
    if (this.bottomPin.clearProgrammaticTimeout) {
      this.bottomPin.clearProgrammaticTimeout.dispose();
      this.bottomPin.clearProgrammaticTimeout = null;
    }
    if (this.resizeRafId !== null) {
      this.safeCancelRaf(this.resizeRafId);
      this.resizeRafId = null;
    }
    this.terminalView = null;
  }
}

export function keyboardOffsetFor(options: { innerHeight: number; viewportHeight: number; viewportOffsetTop?: number }): number {
  const viewportOffsetTop = options.viewportOffsetTop || 0;
  return Math.max(0, options.innerHeight - options.viewportHeight - viewportOffsetTop);
}

function debounce<T extends (...args: any[]) => void>(fn: T, wait: number): (...args: Parameters<T>) => void {
  let timer: any;
  return (...args: Parameters<T>) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), wait);
  };
}
