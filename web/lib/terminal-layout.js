export class TerminalLayoutController {
  constructor({
    store,
    terminalView,
    container,
    documentElement,
    windowObject = window,
    sendResizeMessage,
    isVisible = () => !document.hidden,
  }) {
    this.store = store;
    this.terminalView = terminalView;
    this.container = container;
    this.documentElement = documentElement;
    this.windowObject = windowObject;
    this.sendResizeMessage = sendResizeMessage;
    this.debouncedSendResizeMessage = debounce(sendResizeMessage, 150);
    this.isVisible = isVisible;
    this.resizingTerminal = false;
    this.disposed = false;
    this.resizeRafId = null;
    this.bottomPin = {
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
  }

  attach() {
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

  handleViewportResize() {
    this.sendResize({
      reason: "viewport",
      beforeFit: () => this.updateViewportMetrics(),
    });
  }

  updateViewportMetrics(options = {}) {
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

  sendResize(options = {}) {
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

      if (this.terminalView.cols > 0 && this.terminalView.rows > 0) {
        const size = {
          cols: this.terminalView.cols,
          rows: this.terminalView.rows,
          visible: this.isVisible(),
        };
        if (options.reason === "container") {
          this.debouncedSendResizeMessage(size);
        } else {
          this.sendResizeMessage(size);
        }
      }
    });
  }

  beginBottomPin({ grew = false } = {}) {
    const now = this.now();
    this.bottomPin.active = true;
    this.bottomPin.until = now + (grew ? 500 : 250);
    this.bottomPin.stableFrames = 0;
    this.clearBottomPinTimers();
    this.bottomPin.deadlineTimeout = this.store.setTimeout?.(() => {
      this.bottomPin.deadlineTimeout = null;
      this.cancelBottomPin();
    }, grew ? 520 : 270) || null;
  }

  pokeBottomPin({ force = false } = {}) {
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

  handleTerminalRender() {
    this.pokeBottomPin();
  }

  handleTerminalScroll() {
    if (this.resizingTerminal || this.bottomPin.programmaticScroll) {
      this.pokeBottomPin();
      return;
    }
    if (this.bottomPin.active && !this.isNearBottom() && !this.isDomViewportAtBottom()) {
      this.cancelBottomPin();
    }
  }

  settleAfterWrite({ fit = false } = {}) {
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
    this.store.setTimeout?.(settle, 80);
    this.store.setTimeout?.(() => {
      settle();
      this.cancelBottomPin();
    }, 180);
  }

  cancelPendingRestore() {
    this.cancelBottomPin();
  }

  cancelBottomPin() {
    this.bottomPin.active = false;
    this.bottomPin.until = 0;
    this.bottomPin.stableFrames = 0;
    this.clearBottomPinTimers();
  }

  clearBottomPinTimers() {
    if (this.bottomPin.deadlineTimeout) {
      this.bottomPin.deadlineTimeout.dispose?.();
      this.bottomPin.deadlineTimeout = null;
    }
    if (this.bottomPin.checkTimeout) {
      this.bottomPin.checkTimeout.dispose?.();
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

  isNearBottom(tolerance = 2) {
    const buffer = this.terminalView?.buffer?.active;
    if (!buffer) return false;
    return buffer.baseY - buffer.viewportY <= tolerance;
  }

  isAtBottom() {
    const buffer = this.terminalView?.buffer?.active;
    if (!buffer) return false;
    return buffer.viewportY >= buffer.baseY;
  }

  isDomViewportAtBottom(tolerance = 2) {
    const viewport = this.domViewport();
    if (!viewport) return false;
    return viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight <= tolerance;
  }

  hasDomViewport() {
    return Boolean(this.domViewport());
  }

  domViewport() {
    return this.container?.querySelector?.(".xterm-viewport") || null;
  }

  viewportHeight() {
    return this.windowObject.visualViewport?.height || this.windowObject.innerHeight || 0;
  }

  programmaticScrollToBottom() {
    if (!this.terminalView) return;
    this.bottomPin.programmaticScroll = true;
    this.terminalView.scrollToBottom();
    this.scrollDomViewportToBottom();
    this.terminalView.refreshAll?.();
    this.scheduleClearProgrammaticScroll();
  }

  scrollDomViewportToBottom() {
    const viewport = this.container?.querySelector?.(".xterm-viewport");
    if (!viewport) return;
    viewport.scrollTop = viewport.scrollHeight;
  }

  scheduleClearProgrammaticScroll() {
    if (this.bottomPin.clearProgrammaticRaf !== null) {
      this.safeCancelRaf(this.bottomPin.clearProgrammaticRaf);
    }
    if (this.bottomPin.clearProgrammaticTimeout) {
      this.bottomPin.clearProgrammaticTimeout.dispose?.();
      this.bottomPin.clearProgrammaticTimeout = null;
    }

    this.bottomPin.clearProgrammaticRaf = this.safeRaf(() => {
      this.bottomPin.clearProgrammaticRaf = null;
      this.bottomPin.clearProgrammaticTimeout = this.store.setTimeout?.(() => {
        this.bottomPin.clearProgrammaticTimeout = null;
        this.bottomPin.programmaticScroll = false;
      }, 0) || null;
    });
  }

  queueBottomPinCheck(delay) {
    if (this.bottomPin.checkTimeout) return;
    this.bottomPin.checkTimeout = this.store.setTimeout?.(() => {
      this.bottomPin.checkTimeout = null;
      this.pokeBottomPin();
    }, delay) || null;
  }

  now() {
    return this.windowObject.performance?.now?.() ?? Date.now();
  }

  safeRaf(callback) {
    const raf = this.windowObject.requestAnimationFrame?.bind(this.windowObject);
    if (raf) {
      return raf(callback) ?? null;
    }
    return this.store.setTimeout(callback, 0) ?? null;
  }

  safeCancelRaf(id) {
    if (!id) return;
    if (typeof id.dispose === "function") {
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

  dispose() {
    this.disposed = true;
    this.cancelBottomPin();
    if (this.bottomPin.clearProgrammaticRaf !== null) {
      this.safeCancelRaf(this.bottomPin.clearProgrammaticRaf);
      this.bottomPin.clearProgrammaticRaf = null;
    }
    if (this.bottomPin.clearProgrammaticTimeout) {
      this.bottomPin.clearProgrammaticTimeout.dispose?.();
      this.bottomPin.clearProgrammaticTimeout = null;
    }
    if (this.resizeRafId !== null) {
      this.safeCancelRaf(this.resizeRafId);
      this.resizeRafId = null;
    }
    this.terminalView = null;
  }
}

export function keyboardOffsetFor({ innerHeight, viewportHeight, viewportOffsetTop = 0 }) {
  return Math.max(0, innerHeight - viewportHeight - viewportOffsetTop);
}

function debounce(fn, wait) {
  let timer;
  return () => {
    clearTimeout(timer);
    timer = setTimeout(fn, wait);
  };
}
