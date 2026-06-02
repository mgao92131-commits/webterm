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
    this.keyboardOpen = this.isKeyboardOpen();
    this.resizingTerminal = false;
    this.lastScrollAnchor = null;
    this.disposed = false;
    this.resizeRafId = null;
    this.restoreTimeout = null;
    this.restoreRaf = null;
    this.pendingRestoreCancelled = false;
  }

  attach() {
    this.updateViewportMetrics();

    const viewport = this.windowObject.visualViewport;
    if (viewport) {
      this.store.addEventListener(viewport, "resize", debounce(() => this.handleViewportResize(), 16));
      this.store.addEventListener(viewport, "scroll", debounce(() => this.updateViewportMetrics({ height: false }), 16));
    }

    if (this.windowObject.ResizeObserver && this.container) {
      const observer = new this.windowObject.ResizeObserver(debounce(() => this.sendResize({ reason: "container" }), 16));
      observer.observe(this.container);
      this.store.add({ dispose: () => observer.disconnect() });
    } else {
      this.store.addEventListener(this.windowObject, "resize", debounce(() => this.handleViewportResize(), 16));
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
    const anchor = this.captureScrollAnchor() || this.lastScrollAnchor;
    const wasKeyboardOpen = this.keyboardOpen;
    const nextKeyboardOpen = this.isKeyboardOpen();
    this.sendResize({
      reason: "viewport",
      anchor,
      wasKeyboardOpen,
      nextKeyboardOpen,
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

  keyboardOffset() {
    const viewport = this.windowObject.visualViewport;
    if (!viewport) return 0;
    return keyboardOffsetFor({
      innerHeight: this.windowObject.innerHeight,
      viewportHeight: viewport.height,
      viewportOffsetTop: viewport.offsetTop || 0,
    });
  }

  isKeyboardOpen() {
    return this.keyboardOffset() > 80;
  }

  sendResize(options = {}) {
    if (this.disposed || !this.terminalView) return;
    this.cancelPendingRestore();
    const anchor = options.anchor || this.captureScrollAnchor();
    const wasKeyboardOpen = options.wasKeyboardOpen ?? this.keyboardOpen;
    const nextKeyboardOpen = options.nextKeyboardOpen ?? this.isKeyboardOpen();
    this.keyboardOpen = nextKeyboardOpen;

    if (this.resizeRafId !== null) {
      this.safeCancelRaf(this.resizeRafId);
    }

    this.resizeRafId = this.safeRaf(() => {
      this.resizeRafId = null;
      if (this.disposed || !this.terminalView) return;

      options.beforeFit?.();
      this.pendingRestoreCancelled = false;
      this.resizingTerminal = true;
      try {
        this.terminalView.fit();
        if (!wasKeyboardOpen && nextKeyboardOpen) {
          this.terminalView.scrollToBottom();
          this.restoreRaf = this.windowObject.requestAnimationFrame?.(() => {
            this.restoreRaf = null;
            if (this.pendingRestoreCancelled) return;
            this.terminalView?.scrollToBottom();
          }) || null;
          this.restoreTimeout = this.store.setTimeout(() => {
            this.restoreTimeout = null;
            if (this.pendingRestoreCancelled) return;
            this.terminalView?.scrollToBottom();
          }, 80);
        } else if (!nextKeyboardOpen) {
          this.restoreScrollAnchor(anchor);
          this.restoreRaf = this.windowObject.requestAnimationFrame?.(() => {
            this.restoreRaf = null;
            if (this.pendingRestoreCancelled) return;
            this.restoreScrollAnchor(anchor);
          }) || null;
          this.restoreTimeout = this.store.setTimeout(() => {
            this.restoreTimeout = null;
            if (this.pendingRestoreCancelled) return;
            this.restoreScrollAnchor(anchor);
          }, 80);
        }
        this.lastScrollAnchor = this.captureScrollAnchor();
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

  cancelPendingRestore() {
    this.pendingRestoreCancelled = true;
    if (this.restoreTimeout) {
      this.restoreTimeout.dispose();
      this.restoreTimeout = null;
    }
    if (this.restoreRaf && this.windowObject.cancelAnimationFrame) {
      this.windowObject.cancelAnimationFrame(this.restoreRaf);
      this.restoreRaf = null;
    }
  }

  captureScrollAnchor() {
    return captureScrollAnchor(this.terminalView);
  }

  restoreScrollAnchor(anchor) {
    restoreScrollAnchor(this.terminalView, anchor);
  }

  stats() {
    return {
      keyboardOpen: this.keyboardOpen,
      resizingTerminal: this.resizingTerminal,
      lastScrollAnchor: this.lastScrollAnchor,
    };
  }

  raf(callback) {
    const raf = this.windowObject.requestAnimationFrame?.bind(this.windowObject);
    if (raf) {
      raf(callback);
      return;
    }
    this.store.setTimeout(callback, 0);
  }

  safeRaf(callback) {
    const raf = this.windowObject.requestAnimationFrame?.bind(this.windowObject);
    if (raf) {
      return raf(callback);
    }
    return this.store.setTimeout(callback, 0);
  }

  safeCancelRaf(id) {
    const cancel = this.windowObject.cancelAnimationFrame?.bind(this.windowObject);
    if (cancel && typeof id === "number") {
      cancel(id);
    } else {
      clearTimeout(id);
    }
  }

  dispose() {
    this.disposed = true;
    this.cancelPendingRestore();
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

export function captureScrollAnchor(terminalView) {
  const buffer = terminalView?.buffer?.active;
  if (!buffer) return null;
  return {
    viewportY: buffer.viewportY,
    centerY: buffer.viewportY + Math.floor((terminalView.rows || 1) / 2),
    baseY: buffer.baseY,
    rows: terminalView.rows || null,
    atBottom: buffer.viewportY >= buffer.baseY,
  };
}

export function restoreScrollAnchor(terminalView, anchor) {
  if (!anchor || !terminalView) return;
  if (anchor.atBottom) {
    terminalView.scrollToBottom();
    return;
  }
  const buffer = terminalView.buffer?.active;
  if (!buffer) return;
  const nextRows = terminalView.rows || anchor.rows || 1;
  let line = anchor.viewportY;
  if (!Number.isFinite(line) && Number.isFinite(anchor.centerY)) {
    line = anchor.centerY - Math.floor(nextRows / 2);
  }
  terminalView.scrollToLine(clamp(line, 0, buffer.baseY));
}

function debounce(fn, wait) {
  let timer;
  return () => {
    clearTimeout(timer);
    timer = setTimeout(fn, wait);
  };
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
