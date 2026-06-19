import { DisposableStore, IDisposable } from './disposable';

export interface TerminalSelectionViewInterface {
  clearSelection(): void;
  getSelection(): string;
  select(col: number, row: number, length: number): void;
  scrollToLine(line: number): void;
  options: {
    fontSize: number;
  };
  cols: number;
  rows: number;
  buffer?: {
    active: {
      viewportY: number;
      baseY: number;
    };
  };
  term?: any;
}

export interface SelectionCell {
  col: number;
  row: number;
}

export interface SelectionControllerOptions {
  store: DisposableStore;
  root: HTMLElement;
  terminalElement: HTMLElement;
  terminalView: TerminalSelectionViewInterface;
  clearPendingInput?: () => void;
  copyText?: (text: string) => Promise<void>;
  onSelectionModeChange?: (active: boolean) => void;
}

export class TerminalSelectionController implements IDisposable {
  private store: DisposableStore;
  private root: HTMLElement;
  private terminalElement: HTMLElement;
  private terminalView: TerminalSelectionViewInterface;
  private clearPendingInput: () => void;
  private copyText: (text: string) => Promise<void>;
  private onSelectionModeChange?: (active: boolean) => void;
  
  public selectionMode = false;
  private enteringSelectionMode = false;
  private selectionAnchor: SelectionCell | null = null;
  private selectionViewportY: number | null = null;
  private selectionScrollTop: number | null = null;

  constructor(options: SelectionControllerOptions) {
    this.store = options.store;
    this.root = options.root;
    this.terminalElement = options.terminalElement;
    this.terminalView = options.terminalView;
    this.clearPendingInput = options.clearPendingInput || (() => {});
    this.copyText = options.copyText || defaultCopyText;
    this.onSelectionModeChange = options.onSelectionModeChange;
  }

  attach(): void {
    const copyButton = this.root.querySelector("#copySelection");
    const selectButton = this.root.querySelector("#selectMode");
    
    if (copyButton) {
      this.store.addEventListener(copyButton, "click", (event) => this.copySelection(event));
    }
    if (selectButton) {
      this.store.addEventListener(selectButton, "pointerdown", () => this.prepareToggle());
      this.store.addEventListener(selectButton, "click", () => this.toggle());
    }
    this.attachPointerSelection();
  }

  prepareToggle(): void {
    this.rememberViewport();
    this.enteringSelectionMode = !this.selectionMode;
    if (this.enteringSelectionMode) this.setInputSuspended(true);
  }

  toggle(): void {
    this.selectionMode = !this.selectionMode;
    this.enteringSelectionMode = false;
    this.selectionAnchor = null;
    this.clearPendingInput();
    if (!this.selectionMode) this.terminalView.clearSelection();
    this.updateUI();
  }

  async copySelection(event?: Event): Promise<void> {
    event?.preventDefault();
    this.rememberViewport();
    const text = this.terminalView.getSelection() || "";
    if (!text) {
      this.setCopyStatus("无选区");
      return;
    }
    try {
      await this.copyText(text);
    } catch (err) {
      console.warn("copy failed", err);
      this.setCopyStatus("复制失败");
      return;
    }
    this.selectionMode = false;
    this.selectionAnchor = null;
    this.terminalView.clearSelection();
    this.updateUI();
  }

  private attachPointerSelection(): void {
    const blockNativeSelectionEvent = (event: Event) => {
      if (!this.selectionMode) return;
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      this.setInputSuspended(true);
    };

    this.store.addEventListener(this.terminalElement, "mousedown", blockNativeSelectionEvent, true);
    this.store.addEventListener(this.terminalElement, "touchstart", blockNativeSelectionEvent, { capture: true, passive: false } as any);

    this.store.addEventListener(this.terminalElement, "pointerdown", (event: any) => {
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
      (this.terminalElement as any).setPointerCapture?.(event.pointerId);
      this.selectionAnchor = cell;
      this.terminalView.clearSelection();
      this.terminalView.select(cell.col, cell.row, 1);
      this.restoreViewport();
    }, true);

    this.store.addEventListener(this.terminalElement, "pointermove", (event: any) => {
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

    this.store.addEventListener(this.terminalElement, "pointerup", (event: Event) => {
      if (!this.selectionMode) return;
      event.preventDefault();
      event.stopPropagation();
      this.selectionAnchor = null;
    }, true);

    this.store.addEventListener(this.terminalElement, "pointercancel", () => {
      this.selectionAnchor = null;
    }, true);
  }

  private updateUI(): void {
    this.root.querySelector(".terminal-page")?.classList.toggle("selection-mode", this.selectionMode);
    const selectButton = this.root.querySelector("#selectMode");
    if (selectButton) selectButton.textContent = this.selectionMode ? "取消" : "选择";
    const copyButton = this.root.querySelector("#copySelection") as HTMLElement;
    if (copyButton) {
      copyButton.hidden = !this.selectionMode;
      if (this.selectionMode) copyButton.textContent = "拷贝";
    }
    this.setInputSuspended(this.selectionMode);
    this.restoreViewport();
    this.onSelectionModeChange?.(this.selectionMode);
  }

  private setCopyStatus(text: string): void {
    const copyButton = this.root.querySelector("#copySelection") as HTMLElement;
    if (!copyButton) return;
    copyButton.textContent = text;
    this.store.addTimeout(setTimeout(() => {
      if (this.selectionMode) copyButton.textContent = "拷贝";
    }, 900));
  }

  rememberViewport(): void {
    this.selectionViewportY = this.terminalView.buffer?.active?.viewportY ?? null;
    this.selectionScrollTop = this.terminalElement.querySelector(".xterm-viewport")?.scrollTop ?? null;
  }

  restoreViewport(): void {
    const viewportY = this.selectionViewportY;
    const scrollTop = this.selectionScrollTop;
    const restore = () => {
      if (viewportY !== null && Number.isFinite(viewportY)) this.terminalView.scrollToLine(viewportY);
      const viewport = this.terminalElement.querySelector(".xterm-viewport");
      if (viewport && scrollTop !== null && Number.isFinite(scrollTop)) viewport.scrollTop = scrollTop;
    };
    restore();
    requestAnimationFrame(restore);
  }

  setInputSuspended(suspended: boolean): void {
    const textarea = this.terminalElement.querySelector("textarea.xterm-helper-textarea") as HTMLTextAreaElement;
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

  dispose(): void {
    this.selectionAnchor = null;
    this.selectionViewportY = null;
    this.selectionScrollTop = null;
  }
}

export function terminalCellFromEvent(options: { event: MouseEvent; terminalElement: HTMLElement; terminalView: TerminalSelectionViewInterface }): SelectionCell | null {
  const { event, terminalElement, terminalView } = options;
  if (!terminalView || !terminalView.buffer) return null;
  const screen = terminalElement.querySelector(".xterm-screen");
  const rect = (screen || terminalElement).getBoundingClientRect();
  const metrics = getCellMetrics(terminalView);
  const col = clamp(Math.floor((event.clientX - rect.left) / metrics.width), 0, terminalView.cols - 1);
  const screenRow = clamp(Math.floor((event.clientY - rect.top) / metrics.height), 0, terminalView.rows - 1);
  return { col, row: terminalView.buffer.active.viewportY + screenRow };
}

export function getCellMetrics(terminalView: TerminalSelectionViewInterface): { height: number; width: number } {
  return {
    height: terminalView.term?._core?._renderService?.dimensions?.css?.cell?.height
      || Math.max(10, Number(terminalView.options?.fontSize || 10) * 1.4),
    width: terminalView.term?._core?._renderService?.dimensions?.css?.cell?.width
      || Math.max(5, Number(terminalView.options?.fontSize || 10) * 0.6),
  };
}

export function selectTerminalRange(terminalView: TerminalSelectionViewInterface, anchor: SelectionCell, focus: SelectionCell): void {
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

async function defaultCopyText(text: string): Promise<void> {
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

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
