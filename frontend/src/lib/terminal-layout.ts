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
      getLine?(index: number): { translateToString(trimRight?: boolean): string } | undefined;
    };
  };
  scrollToBottom(): void;
  scrollLines?(lines: number): void;
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
  private lastKeyboardOffset = 0;
  private lastKeyboardActive = false;
  private initialHeight = 0;
  private touchScrollLastY: number | null = null;
  private touchScrollRemainder = 0;
  private keyboardAvoidanceTimer: any = null;
  private resizeMessageCount = 0;
  private lastResizeMessage: ResizeMessage | null = null;
  private resizeDebounceMs = 100;

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
    this.debouncedSendResizeMessage = debounce((size) => this.emitResizeMessage(size), 150);
    this.resizeDebounceMs = (typeof window !== 'undefined' && window.localStorage?.getItem('webtermDisableWebGL') === '1') ? 0 : 100;
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

    this.attachTouchScroll();
    this.attachKeyboardFocusAvoidance();

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
    }, this.resizeDebounceMs);
  }

  updateViewportMetrics(options: { height?: boolean } = {}): void {
    const updateHeight = options.height !== false;
    const viewport = this.windowObject.visualViewport;
    const height = viewport?.height || this.windowObject.innerHeight;
    const offsetTop = viewport?.offsetTop || 0;
    const currentWidth = this.windowObject.innerWidth || 0;
    const widthDelta = Math.abs(currentWidth - this.lastWidth);
    const heightCompression = this.heightCompressionFor(height, widthDelta);
    const keyboardOffset = keyboardOffsetFor({
      innerHeight: this.windowObject.innerHeight,
      viewportHeight: height,
      viewportOffsetTop: offsetTop,
    });

    const isTouchDevice = this.isTouchDevice();
    const scale = viewport?.scale || 1;
    const isKeyboardActive = this.isKeyboardViewport({
      heightCompression,
      keyboardOffset,
      scale,
    });
    if (DEBUG_KEYBOARD_METRICS) {
      (this.windowObject as any).__webtermKeyboardDebug = {
        ...(this.windowObject as any).__webtermKeyboardDebug,
        isTouchDevice,
        scale,
        widthDelta,
        innerHeight: this.windowObject.innerHeight,
        height,
        offsetTop,
        keyboardOffset,
        heightCompression,
        isKeyboardActive,
        lastKeyboardOffset: this.lastKeyboardOffset,
        initialHeight: this.initialHeight
      };
    }

    if (updateHeight) {
      this.documentElement.style.setProperty("--viewport-height", `${this.initialHeight}px`);
    }
    this.documentElement.style.setProperty("--keyboard-offset", `${keyboardOffset}px`);
    this.documentElement.style.setProperty("--keyboard-scroll-space", "0px");
    if (isKeyboardActive) {
      this.scheduleKeyboardAvoidance();
    } else {
      this.resetKeyboardTransforms();
    }

    // 键盘收起时恢复滚动位置。部分 Android 浏览器会让 innerHeight
    // 和 visualViewport.height 一起变化，keyboardOffset 可能始终为 0。
    if ((!isKeyboardActive && this.lastKeyboardActive) || (heightCompression === 0 && keyboardOffset === 0 && this.lastKeyboardOffset > 0)) {
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
    const widthDelta = Math.abs(currentWidth - this.lastWidth);
    const heightCompression = this.heightCompressionFor(vvHeight, widthDelta);
    const isKeyboardActive = this.isKeyboardViewport({
      heightCompression,
      keyboardOffset,
      scale,
    });
    const isKeyboardEvent = isKeyboardActive || (this.lastKeyboardActive && widthDelta <= 8);

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
        widthDelta,
        heightCompression,
        lastWidth: this.lastWidth,
        currentWidth,
        initialHeight: this.initialHeight
      };
    }

    this.lastWidth = currentWidth;

    if (isKeyboardEvent) {
      options.beforeFit?.();
      this.scheduleKeyboardAvoidance();
      return;
    }

    if (widthDelta > 8 || vvHeight > this.initialHeight) {
      this.initialHeight = vvHeight;
      this.documentElement.style.setProperty("--viewport-height", `${this.initialHeight}px`);
      this.resetKeyboardTransforms();
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
          this.emitResizeMessage(size);
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

  debugKeyboardAvoidance(): void {
    this.scheduleKeyboardAvoidance();
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
      resizeMessageCount: this.resizeMessageCount,
      lastResizeMessage: this.lastResizeMessage,
    };
  }

  emitResizeMessage(size: ResizeMessage): void {
    this.resizeMessageCount += 1;
    this.lastResizeMessage = size;
    this.sendResizeMessage(size);
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
    if (this.keyboardAvoidanceTimer) {
      clearTimeout(this.keyboardAvoidanceTimer);
      this.keyboardAvoidanceTimer = null;
    }
    this.documentElement.style.setProperty("--keyboard-scroll-space", "0px");
    this.resetKeyboardTransforms();
    this.terminalView = null;
  }

  private attachTouchScroll(): void {
    if (!this.container) return;

    this.store.addEventListener(this.container, "touchstart", ((event: TouchEvent) => {
      if (this.isSelectionMode()) return;
      if (event.touches.length !== 1) {
        this.touchScrollLastY = null;
        this.touchScrollRemainder = 0;
        return;
      }
      this.touchScrollLastY = event.touches[0].clientY;
      this.touchScrollRemainder = 0;
      // 用户开始触摸滚动时，立即取消底部粘滞和程序化滚动标记，
      // 避免触摸滚动后被底部粘滞逻辑立即拉回最底部。
      this.bottomPin.programmaticScroll = false;
      this.cancelBottomPin();
    }) as EventListener, { passive: true } as any);

    this.store.addEventListener(this.container, "touchmove", ((event: TouchEvent) => {
      if (this.isSelectionMode() || !this.terminalView) return;
      if (event.touches.length !== 1 || this.touchScrollLastY === null) return;

      const currentY = event.touches[0].clientY;
      const deltaY = this.touchScrollLastY - currentY;
      this.touchScrollLastY = currentY;

      const rowHeight = this.terminalRowHeight();
      if (!rowHeight) return;

      this.touchScrollRemainder += deltaY / rowHeight;
      const lines = Math.trunc(this.touchScrollRemainder);
      if (lines === 0) return;

      this.touchScrollRemainder -= lines;
      if (typeof this.terminalView.scrollLines === "function") {
        this.terminalView.scrollLines(lines);
      } else {
        const viewport = this.domViewport();
        if (viewport) viewport.scrollTop += lines * rowHeight;
      }
      event.preventDefault();
    }) as EventListener, { capture: true, passive: false } as any);

    const endTouchScroll = () => {
      this.touchScrollLastY = null;
      this.touchScrollRemainder = 0;
    };
    this.store.addEventListener(this.container, "touchend", endTouchScroll, { passive: true } as any);
    this.store.addEventListener(this.container, "touchcancel", endTouchScroll, { passive: true } as any);
  }

  private attachKeyboardFocusAvoidance(): void {
    this.store.addEventListener(this.container, "focusin", (event: Event) => {
      if (!this.isTerminalInput(event.target)) return;
      this.keepTerminalPageAnchored();
      this.queueKeyboardAvoidanceChecks();
    });
    this.store.addEventListener(this.container, "focusout", (event: Event) => {
      if (!this.isTerminalInput(event.target)) return;
      this.store.setTimeout(() => {
        const active = this.documentElement.ownerDocument?.activeElement;
        if (!this.isTerminalInput(active)) {
          this.resetPageScroll();
        }
      }, 120);
    });
  }

  private terminalRowHeight(): number {
    const rows = this.terminalView?.rows || 0;
    const screen = this.container?.querySelector?.(".xterm-screen") as HTMLElement | null;
    if (!screen || rows <= 0 || screen.clientHeight <= 0) return 0;
    return screen.clientHeight / rows;
  }

  private isSelectionMode(): boolean {
    return Boolean(this.container?.closest?.(".terminal-page.selection-mode"));
  }

  private isTerminalInput(target: EventTarget | Element | null | undefined): boolean {
    return Boolean((target as Element | null)?.matches?.(".xterm-helper-textarea, textarea"));
  }

  private queueKeyboardAvoidanceChecks(): void {
    this.store.setTimeout(() => this.scheduleKeyboardAvoidance(), 80);
    this.store.setTimeout(() => this.scheduleKeyboardAvoidance(), 220);
    this.store.setTimeout(() => this.scheduleKeyboardAvoidance(), 420);
  }

  private keepTerminalPageAnchored(): void {
    const page = this.container?.closest?.('.terminal-page') as HTMLElement | null;
    const reset = () => {
      if (page && page.scrollTop > 0) {
        page.scrollTo({ top: 0, behavior: 'instant' });
      }
      if (this.windowObject.scrollY > 0) {
        this.windowObject.scrollTo?.(0, 0);
      }
    };
    reset();
    this.safeRaf(reset);
  }

  private scheduleKeyboardAvoidance(): void {
    this.updateKeyboardShift();
    if (this.keyboardAvoidanceTimer) {
      clearTimeout(this.keyboardAvoidanceTimer);
    }
    this.keyboardAvoidanceTimer = setTimeout(() => {
      this.keyboardAvoidanceTimer = null;
      this.updateKeyboardShift();
    }, 180);
  }

  /**
   * 键盘弹出时不改变终端尺寸：quickbar 固定贴键盘，终端内容只做最小避让。
   */
  private updateKeyboardShift(): void {
    const page = this.container?.closest?.('.terminal-page') as HTMLElement | null;
    if (!page || !this.terminalView) return;

    const buffer = this.terminalView.buffer?.active;
    const rows = this.terminalView.rows || 0;
    if (!buffer || !rows) return;

    // 1. 获取光标在当前可视区内的相对 Y 坐标 (0-based)
    const cursorY = buffer.cursorY;
    if (typeof cursorY !== 'number') return;

    // 2. 从视口最底部开始向上扫描，获取可视区内最后一行非空正常文字的相对 Y 坐标
    let lastNonEmptyRow: number | null = null;
    for (let i = rows - 1; i >= 0; i--) {
      const line = buffer.getLine?.(buffer.viewportY + i);
      if (line && line.translateToString(true).trim().length > 0) {
        lastNonEmptyRow = i;
        break;
      }
    }

    // 3. 优先保护最后一行实际文本；没有文本时才退回保护光标。
    let viewportRelativeRow = lastNonEmptyRow ?? cursorY;
    viewportRelativeRow = Math.max(0, Math.min(rows - 1, viewportRelativeRow));

    // 获取 xterm-screen 渲染高度
    const screen = this.container?.querySelector?.('.xterm-screen') as HTMLElement | null;
    if (!screen || screen.clientHeight <= 0) return;
    const rowHeight = screen.clientHeight / rows;

    // 计算这一行在没有内容位移时的底部坐标，避免连续事件中累加偏移。
    const containerRect = this.container.getBoundingClientRect();
    const currentContentShift = this.currentTerminalContentShift();
    const protectedRowBottom = containerRect.top + currentContentShift + (viewportRelativeRow + 1) * rowHeight;

    const viewport = this.windowObject.visualViewport;
    const visibleHeight = viewport?.height || this.windowObject.innerHeight;
    const visibleBottom = (viewport?.offsetTop || 0) + visibleHeight;
    const keyboardOffset = keyboardOffsetFor({
      innerHeight: this.windowObject.innerHeight,
      viewportHeight: visibleHeight,
      viewportOffsetTop: viewport?.offsetTop || 0,
    });
    const heightCompression = this.heightCompressionFor(visibleHeight, 0);
    const scale = viewport?.scale || 1;
    if (!this.isKeyboardViewport({ heightCompression, keyboardOffset, scale })) {
      this.resetKeyboardTransforms();
      return;
    }

    // Quickbar 快捷键栏高度
    const quickbar = page.querySelector('.quickbar') as HTMLElement | null;
    const keyboardShiftLimit = Math.max(heightCompression, keyboardOffset);
    const toolbarBottom = this.keyboardToolbarBottomFor(quickbar, visibleBottom, keyboardShiftLimit);
    this.setKeyboardToolbarBottom(toolbarBottom);
    const quickbarHeight = quickbar?.getBoundingClientRect().height || 54;

    // 目标位置：目标行底部应该刚好停留在键盘上方的 quickbar 顶部。
    const targetY = visibleBottom - quickbarHeight;
    const rowShift = protectedRowBottom - targetY;
    const shift = Math.max(0, Math.min(rowShift, keyboardShiftLimit));
    this.setTerminalContentShift(shift);
  }

  /**
   * 键盘收起时，复位页面滚动条位置为 0
   */
  private resetPageScroll(): void {
    const page = this.container?.closest?.('.terminal-page') as HTMLElement | null;
    if (page && page.scrollTop > 0) {
      page.scrollTo({ top: 0, behavior: 'instant' });
    }
    this.resetKeyboardTransforms();
  }

  private heightCompressionFor(viewportHeight: number, widthDelta: number): number {
    if (widthDelta > 8 || viewportHeight <= 0 || this.initialHeight <= 0) return 0;
    const compression = this.initialHeight - viewportHeight;
    return compression > 80 ? Math.round(compression) : 0;
  }

  private isKeyboardViewport(options: { heightCompression: number; keyboardOffset: number; scale: number }): boolean {
    return Math.abs(options.scale - 1) < 0.05
      && (options.heightCompression > 0 || options.keyboardOffset > 80);
  }

  private resetKeyboardTransforms(): void {
    this.setKeyboardToolbarBottom(0);
    this.setTerminalContentShift(0);
  }

  private setKeyboardToolbarBottom(value: number): void {
    const bottom = Math.max(0, Math.round(value));
    this.documentElement.style.setProperty("--keyboard-toolbar-bottom", `${bottom}px`);
    this.documentElement.style.setProperty("--keyboard-toolbar-offset", `${bottom}px`);
  }

  private keyboardToolbarBottomFor(quickbar: HTMLElement | null, visibleBottom: number, fallback: number): number {
    if (!quickbar) return fallback;
    const currentBottom = this.currentKeyboardToolbarBottom();
    const unshiftedBottom = quickbar.getBoundingClientRect().bottom + currentBottom;
    return Math.max(0, unshiftedBottom - visibleBottom);
  }

  private setTerminalContentShift(value: number): void {
    const shift = Math.max(0, Math.round(value));
    this.documentElement.style.setProperty("--terminal-content-shift", `${shift}px`);
    this.documentElement.style.setProperty("--terminal-keyboard-shift", `${shift}px`);
  }

  private currentTerminalContentShift(): number {
    const raw = this.documentElement.style.getPropertyValue("--terminal-content-shift")
      || this.documentElement.style.getPropertyValue("--terminal-keyboard-shift");
    const value = parseFloat(raw);
    return Number.isFinite(value) ? value : 0;
  }

  private currentKeyboardToolbarBottom(): number {
    const raw = this.documentElement.style.getPropertyValue("--keyboard-toolbar-bottom")
      || this.documentElement.style.getPropertyValue("--keyboard-toolbar-offset");
    const value = parseFloat(raw);
    return Number.isFinite(value) ? value : 0;
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
