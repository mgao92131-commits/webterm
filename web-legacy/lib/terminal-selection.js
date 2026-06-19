export class TerminalSelectionController {
  constructor({ store, root, terminalElement, terminalView, clearPendingInput = () => {}, copyText = defaultCopyText }) {
    this.store = store;
    this.root = root;
    this.terminalElement = terminalElement;
    this.terminalView = terminalView;
    this.clearPendingInput = clearPendingInput;
    this.copyText = copyText;
    this.selectionMode = false;
    this.enteringSelectionMode = false;
    this.selectionAnchor = null;
    this.selectionViewportY = null;
    this.selectionScrollTop = null;
  }

  attach() {
    const copyButton = this.root.querySelector("#copySelection");
    const selectButton = this.root.querySelector("#selectMode");
    this.store.addEventListener(copyButton, "click", (event) => this.copySelection(event));
    this.store.addEventListener(selectButton, "pointerdown", () => this.prepareToggle());
    this.store.addEventListener(selectButton, "click", () => this.toggle());
    this.attachPointerSelection();
  }

  prepareToggle() {
    this.rememberViewport();
    this.enteringSelectionMode = !this.selectionMode;
    if (this.enteringSelectionMode) this.setInputSuspended(true);
  }

  toggle() {
    this.selectionMode = !this.selectionMode;
    this.enteringSelectionMode = false;
    this.selectionAnchor = null;
    this.clearPendingInput();
    if (!this.selectionMode) this.terminalView.clearSelection();
    this.updateUI();
  }

  async copySelection(event) {
    event?.preventDefault();
    this.rememberViewport();
    const text = this.terminalView.getSelection() || "";
    if (!text) {
      this.setCopyStatus("无选区");
      return;
    }
    if (text) {
      try {
        await this.copyText(text);
      } catch (err) {
        console.warn("copy failed", err);
        this.setCopyStatus("复制失败");
        return;
      }
    }
    this.selectionMode = false;
    this.selectionAnchor = null;
    this.terminalView.clearSelection();
    this.updateUI();
  }

  attachPointerSelection() {
    const blockNativeSelectionEvent = (event) => {
      if (!this.selectionMode) return;
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      this.setInputSuspended(true);
    };

    this.store.addEventListener(this.terminalElement, "mousedown", blockNativeSelectionEvent, true);
    this.store.addEventListener(this.terminalElement, "touchstart", blockNativeSelectionEvent, { capture: true, passive: false });

    this.store.addEventListener(this.terminalElement, "pointerdown", (event) => {
      if (!this.selectionMode || event.button !== 0) return;
      this.rememberViewport();
      const cell = terminalCellFromEvent({
        event,
        terminalElement: this.terminalElement,
        terminalView: this.terminalView,
      });
      if (!cell) return;
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      this.setInputSuspended(true);
      this.terminalElement.setPointerCapture?.(event.pointerId);
      this.selectionAnchor = cell;
      this.terminalView.clearSelection();
      this.terminalView.select(cell.col, cell.row, 1);
      this.restoreViewport();
    }, true);

    this.store.addEventListener(this.terminalElement, "pointermove", (event) => {
      if (!this.selectionMode || !this.selectionAnchor) return;
      const cell = terminalCellFromEvent({
        event,
        terminalElement: this.terminalElement,
        terminalView: this.terminalView,
      });
      if (!cell) return;
      event.preventDefault();
      event.stopPropagation();
      selectTerminalRange(this.terminalView, this.selectionAnchor, cell);
      this.restoreViewport();
    }, true);

    this.store.addEventListener(this.terminalElement, "pointerup", (event) => {
      if (!this.selectionMode) return;
      event.preventDefault();
      event.stopPropagation();
      this.selectionAnchor = null;
    }, true);

    this.store.addEventListener(this.terminalElement, "pointercancel", () => {
      this.selectionAnchor = null;
    }, true);
  }

  updateUI() {
    this.root.querySelector(".terminal-page")?.classList.toggle("selection-mode", this.selectionMode);
    const selectButton = this.root.querySelector("#selectMode");
    if (selectButton) selectButton.textContent = this.selectionMode ? "取消" : "选择";
    const copyButton = this.root.querySelector("#copySelection");
    if (copyButton) {
      copyButton.hidden = !this.selectionMode;
      if (this.selectionMode) copyButton.textContent = "拷贝";
    }
    this.setInputSuspended(this.selectionMode);
    this.restoreViewport();
  }

  setCopyStatus(text) {
    const copyButton = this.root.querySelector("#copySelection");
    if (!copyButton) return;
    copyButton.textContent = text;
    this.store.addTimeout(setTimeout(() => {
      if (this.selectionMode) copyButton.textContent = "拷贝";
    }, 900));
  }

  rememberViewport() {
    this.selectionViewportY = this.terminalView.buffer?.active?.viewportY ?? null;
    this.selectionScrollTop = this.terminalElement.querySelector(".xterm-viewport")?.scrollTop ?? null;
  }

  restoreViewport() {
    const viewportY = this.selectionViewportY;
    const scrollTop = this.selectionScrollTop;
    const restore = () => {
      if (Number.isFinite(viewportY)) this.terminalView.scrollToLine(viewportY);
      const viewport = this.terminalElement.querySelector(".xterm-viewport");
      if (viewport && Number.isFinite(scrollTop)) viewport.scrollTop = scrollTop;
    };
    restore();
    requestAnimationFrame(restore);
  }

  setInputSuspended(suspended) {
    const textarea = this.terminalElement.querySelector("textarea.xterm-helper-textarea");
    if (!textarea) return;
    if (suspended) {
      textarea.blur();
      textarea.readOnly = true;
      textarea.setAttribute("inputmode", "none");
      return;
    }
    textarea.readOnly = false;
    textarea.removeAttribute("inputmode");
  }
}

export function terminalCellFromEvent({ event, terminalElement, terminalView }) {
  if (!terminalView) return null;
  const screen = terminalElement.querySelector(".xterm-screen");
  const rect = (screen || terminalElement).getBoundingClientRect();
  const metrics = getCellMetrics(terminalView);
  const col = clamp(Math.floor((event.clientX - rect.left) / metrics.width), 0, terminalView.cols - 1);
  const screenRow = clamp(Math.floor((event.clientY - rect.top) / metrics.height), 0, terminalView.rows - 1);
  return { col, row: terminalView.buffer.active.viewportY + screenRow };
}

export function getCellMetrics(terminalView) {
  return {
    height: terminalView.term?._core?._renderService?.dimensions?.css?.cell?.height
      || Math.max(10, Number(terminalView.options?.fontSize || 10) * 1.4),
    width: terminalView.term?._core?._renderService?.dimensions?.css?.cell?.width
      || Math.max(5, Number(terminalView.options?.fontSize || 10) * 0.6),
  };
}

export function selectTerminalRange(terminalView, anchor, focus) {
  const cols = terminalView.cols;
  let start = anchor;
  let end = focus;
  const anchorOffset = anchor.row * cols + anchor.col;
  const focusOffset = focus.row * cols + focus.col;
  if (focusOffset < anchorOffset) {
    start = focus;
    end = anchor;
  }
  const startOffset = start.row * cols + start.col;
  const endOffset = end.row * cols + end.col;
  terminalView.select(start.col, start.row, Math.max(1, endOffset - startOffset + 1));
}

async function defaultCopyText(text) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.readOnly = true;
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  document.body.appendChild(textarea);
  textarea.select();
  document.execCommand("copy");
  textarea.remove();
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
