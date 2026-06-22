import { DisposableStore, IDisposable } from './disposable';

const DEBUG_KEYBOARD_METRICS = Boolean((import.meta as any).env?.DEV);

export interface TerminalViewInterface {
  fit(): void;
  rows?: number;
  cols?: number;
  buffer?: {
    active: {
      viewportY: number;
      baseY: number;
      cursorY: number;
      cursorX: number;
    };
  };
  scrollToBottom(): void;
  refreshAll?(): void;
  setFontSize?(size: number): void;
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
  private sharedResizeTimer: IDisposable | null = null;
  private lastWidth = 0;
  private keyboardScrollTimer: any = null;
  private lastKeyboardOffset = 0;
  private lastKeyboardActive = false;
  private initialHeight = 0;

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
    this.initialHeight = this.windowObject.innerHeight || 0;
  }

  setFontSize(size: number): void {
    if (this.terminalView && typeof this.terminalView.setFontSize === 'function') {
      this.terminalView.setFontSize(size);
      this.sendResize({ reason: "fontsize-change" });
    }
  }

  attach(): void {
    this.updateViewportMetrics();
    this.lastWidth = this.windowObject.innerWidth;

    const viewport = this.windowObject.visualViewport;
    if (viewport) {
      this.store.addEventListener(viewport, "resize", () => {
        this.handleResizeDebounced("viewport", () => this.updateViewportMetrics());
      });
      this.store.addEventListener(viewport, "scroll", debounce(() => this.updateViewportMetrics({ height: false }), 16));
    }
    this.store.addEventListener(this.windowObject, "resize", () => {
      this.handleResizeDebounced("viewport", () => this.updateViewportMetrics());
    });

    if (this.windowObject.ResizeObserver && this.container) {
      const observer = new this.windowObject.ResizeObserver(() => {
        this.handleResizeDebounced("container", () => this.updateViewportMetrics());
      });
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

  handleResizeDebounced(reason: string, beforeFit?: () => void): void {
    if (this.sharedResizeTimer) {
      this.sharedResizeTimer.dispose();
      this.sharedResizeTimer = null;
    }
    this.sharedResizeTimer = this.store.setTimeout(() => {
      this.sharedResizeTimer = null;
      this.sendResize({
        reason,
        beforeFit,
      });
    }, 100);
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

    const isTouchDevice = this.isTouchDevice();
    const scale = viewport?.scale || 1;
    const isKeyboardActive = isTouchDevice
                          && Math.abs(scale - 1) < 0.05
                          && (this.initialHeight - height) > 150;

    if (DEBUG_KEYBOARD_METRICS) {
      (this.windowObject as any).__webtermKeyboardDebug = {
        ...(this.windowObject as any).__webtermKeyboardDebug,
        isTouchDevice,
        scale,
        innerHeight: this.windowObject.innerHeight,
        height,
        offsetTop,
        keyboardOffset,
        isKeyboardActive,
        lastKeyboardOffset: this.lastKeyboardOffset,
        initialHeight: this.initialHeight
      };
    }

    if (updateHeight) {
      this.documentElement.style.setProperty("--viewport-height", `${height}px`);
    }
    this.documentElement.style.setProperty("--keyboard-offset", `${keyboardOffset}px`);

    // 键盘收起时恢复滚动位置。部分 Android 浏览器会让 innerHeight
    // 和 visualViewport.height 一起变化，keyboardOffset 可能始终为 0。
    if ((!isKeyboardActive && this.lastKeyboardActive) || (keyboardOffset === 0 && this.lastKeyboardOffset > 0)) {
      this.resetPageScroll();
    }
    this.lastKeyboardOffset = keyboardOffset;
    this.lastKeyboardActive = isKeyboardActive;
  }

  sendResize(options: { pinBottom?: boolean; beforeFit?: () => void; reason?: string } = {}): void {
    if (this.disposed || !this.terminalView) return;
    const beforeViewportHeight = this.viewportHeight();
    const beforeRows = this.terminalView.rows || 0;
    const domViewport = this.domViewport();
    const shouldPinBottom = options.pinBottom ?? (this.isNearBottom() || Boolean(domViewport && this.isDomViewportAtBottom()));

    // 统一检测软键盘弹起事件（物理高度缩短 > 150px 且宽度未改变，同时满足安全网条件）
    const currentWidth = this.windowObject.innerWidth;
    const viewport = this.windowObject.visualViewport;
    const vvHeight = viewport?.height || this.windowObject.innerHeight;
    const vvOffsetTop = viewport?.offsetTop || 0;
    const keyboardOffset = keyboardOffsetFor({
      innerHeight: this.windowObject.innerHeight,
      viewportHeight: vvHeight,
      viewportOffsetTop: vvOffsetTop,
    });

    const isTouchDevice = this.isTouchDevice();
    const scale = viewport?.scale || 1;
    const isKeyboardActive = isTouchDevice
                          && Math.abs(scale - 1) < 0.05
                          && (this.initialHeight - vvHeight) > 150;
    const isKeyboardEvent = isKeyboardActive && currentWidth === this.lastWidth;

    if (DEBUG_KEYBOARD_METRICS) {
      (this.windowObject as any).__webtermKeyboardDebug = {
        ...(this.windowObject as any).__webtermKeyboardDebug,
        isTouchDevice,
        scale,
        vvHeight,
        vvOffsetTop,
        keyboardOffset,
        isKeyboardActive,
        isKeyboardEvent,
        lastWidth: this.lastWidth,
        currentWidth,
        initialHeight: this.initialHeight
      };
    }

    this.lastWidth = currentWidth;

    if (isKeyboardEvent) {
      // 纯键盘触发：保持终端不重新 fit、不向后端发送 resize 消息
      options.beforeFit?.();
      // 统一用 visualViewport 高度和光标/最后非空行计算避让位置。
      // Android 上 keyboardOffset 经常为 0，不能把它作为是否滚动的门槛。
      this.scrollPageToCursor();
      return;
    }

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
    if (this.sharedResizeTimer) {
      this.sharedResizeTimer.dispose();
      this.sharedResizeTimer = null;
    }
    if (this.keyboardScrollTimer) {
      clearTimeout(this.keyboardScrollTimer);
      this.keyboardScrollTimer = null;
    }
    this.terminalView = null;
  }

  /**
   * 精准将页面滚动到使光标及最后一行文字正好出现在键盘 + quickbar 的上方。
   */
  private scrollPageToCursor(): void {
    const page = this.container?.closest?.('.terminal-page') as HTMLElement | null;
    if (!page || !this.terminalView) return;

    const buffer = this.terminalView.buffer?.active;
    const rows = this.terminalView.rows || 0;
    if (!buffer || !rows) return;

    // 1. 获取光标在当前可视区内的相对 Y 坐标 (0-based)
    const cursorY = buffer.cursorY;
    if (typeof cursorY !== 'number') return;

    // 2. 从视口最底部开始向上扫描，获取可视区内最后一行非空正常文字的相对 Y 坐标
    let lastNonEmptyRow = 0;
    for (let i = rows - 1; i >= 0; i--) {
      const line = buffer.getLine(buffer.viewportY + i);
      if (line && line.translateToString(true).trim().length > 0) {
        lastNonEmptyRow = i;
        break;
      }
    }

    // 3. 双重保险：取两者的较大行号，确保光标闪烁处和最后非空内容均不会被软键盘遮挡
    let viewportRelativeRow = Math.max(cursorY, lastNonEmptyRow);
    viewportRelativeRow = Math.max(0, Math.min(rows - 1, viewportRelativeRow));

    // 获取 xterm-screen 渲染高度
    const screen = this.container?.querySelector?.('.xterm-screen') as HTMLElement | null;
    if (!screen || screen.clientHeight <= 0) return;
    const rowHeight = screen.clientHeight / rows;

    // 计算这一行底部在 layout viewport 中的 Y 坐标
    const containerRect = this.container.getBoundingClientRect();
    const cursorBottomInViewport = containerRect.top + (viewportRelativeRow + 1) * rowHeight;

    // 实际可见可视区域高度
    const visibleHeight = this.windowObject.visualViewport?.height || this.windowObject.innerHeight;

    // Quickbar 快捷键栏高度
    const quickbar = page.querySelector('.quickbar') as HTMLElement | null;
    const quickbarHeight = quickbar?.getBoundingClientRect().height || 54;

    // 目标位置：目标行底部应该停留在 quickbar 上方 4px 处
    const targetY = visibleHeight - quickbarHeight - 4;
    const overflow = cursorBottomInViewport - targetY;

    if (this.keyboardScrollTimer) {
      clearTimeout(this.keyboardScrollTimer);
    }

    this.keyboardScrollTimer = setTimeout(() => {
      this.keyboardScrollTimer = null;
      const maxScroll = Math.max(0, page.scrollHeight - page.clientHeight);
      const newScrollTop = Math.max(0, Math.min(maxScroll, page.scrollTop + overflow));

      page.scrollTo({
        top: newScrollTop,
        behavior: 'instant', // instant 可最大化避免与 iOS 键盘自带滚动弹簧动画冲突
      });
    }, 16);
  }

  /**
   * 键盘收起时，复位页面滚动条位置为 0
   */
  private resetPageScroll(): void {
    const page = this.container?.closest?.('.terminal-page') as HTMLElement | null;
    if (page && page.scrollTop > 0) {
      page.scrollTo({ top: 0, behavior: 'instant' });
    }
  }

  private isTouchDevice(): boolean {
    return 'ontouchstart' in this.windowObject
        || (this.windowObject.navigator.maxTouchPoints > 0);
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
