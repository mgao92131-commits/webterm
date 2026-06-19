import { DisposableStore, IDisposable } from "./disposable";
import { keyEventData, modifiedInput, quickbarInput } from "./terminal-input";

export interface InputControllerOptions {
  store: DisposableStore;
  root: HTMLElement;
  terminalElement: HTMLElement;
  sendInput: (data: string, source: string) => void;
  focusTerminal: () => void;
  onModifierChange?: (modifier: string | null) => void;
}

export class TerminalInputController implements IDisposable {
  private store: DisposableStore;
  private root: HTMLElement;
  private terminalElement: HTMLElement;
  private sendInput: (data: string, source: string) => void;
  private focusTerminal: () => void;
  private onModifierChange?: (modifier: string | null) => void;
  
  public pendingModifier: string | null = null;
  private lastQuickbarTouchAt = 0;

  constructor(options: InputControllerOptions) {
    this.store = options.store;
    this.root = options.root;
    this.terminalElement = options.terminalElement;
    this.sendInput = options.sendInput;
    this.focusTerminal = options.focusTerminal;
    this.onModifierChange = options.onModifierChange;
  }

  attach(): void {
    this.attachModifierCapture();
    this.root.querySelectorAll("[data-key]").forEach((button) => {
      this.attachQuickbarButton(button as HTMLElement);
    });
  }

  attachModifierCapture(): void {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!this.pendingModifier || event.metaKey || event.isComposing) return;
      const data = keyEventData(event);
      if (!data) return;
      if (!this.sendModifiedInput(data)) return;
      event.preventDefault();
      event.stopPropagation();
    };

    const onBeforeInput = (event: any) => {
      if (!this.pendingModifier || event.isComposing) return;
      if (event.inputType && !event.inputType.startsWith("insert")) return;
      const data = event.data || "";
      if (!data) return;
      if (!this.sendModifiedInput(data)) return;
      event.preventDefault();
      event.stopPropagation();
    };

    const bindTarget = (target: HTMLElement | null) => {
      if (!target || target.dataset.modifierCapture === "1") return;
      target.dataset.modifierCapture = "1";
      this.store.addEventListener(target, "keydown", onKeyDown as EventListener, true);
      this.store.addEventListener(target, "beforeinput", onBeforeInput, true);
    };

    bindTarget(this.terminalElement);
    bindTarget(this.textarea());
    this.store.addTimeout(setTimeout(() => bindTarget(this.textarea()), 0));
  }

  attachQuickbarButton(button: HTMLElement): void {
    this.store.addEventListener(button, "touchend", (event: Event) => {
      event.preventDefault();
      this.lastQuickbarTouchAt = Date.now();
      this.tapQuickbarButton(button);
    }, { passive: false } as any);
    
    this.store.addEventListener(button, "click", (event: Event) => {
      event.preventDefault();
      if (Date.now() - this.lastQuickbarTouchAt < 700) return;
      this.tapQuickbarButton(button);
    });
  }

  tapQuickbarButton(button: HTMLElement): void {
    const key = button.dataset.key;
    if (key) {
      this.sendKey(key);
    }
    button.blur();
    this.focusTerminal();
  }

  sendKey(key: string): void {
    if (key === "Ctrl") {
      this.togglePendingModifier(key.toLowerCase());
      return;
    }
    const modified = quickbarInput(this.pendingModifier, key);
    this.clearPendingModifier();
    if (modified) this.sendInput(modified, "quickbar");
    this.focusTerminal();
  }

  sendModifiedInput(data: string): boolean {
    if (!this.pendingModifier) return false;
    const modified = modifiedInput(this.pendingModifier, data);
    this.clearPendingModifier();
    if (!modified) return false;
    this.sendInput(modified, "modifier");
    this.focusTerminal();
    return true;
  }

  togglePendingModifier(modifier: string): void {
    this.pendingModifier = this.pendingModifier === modifier ? null : modifier;
    this.updateModifierButtons();
    this.focusTerminal();
  }

  clearPendingModifier(): void {
    if (!this.pendingModifier) return;
    this.pendingModifier = null;
    this.updateModifierButtons();
  }

  updateModifierButtons(): void {
    this.root.querySelectorAll("[data-key='Ctrl']").forEach((button) => {
      const btn = button as HTMLElement;
      btn.classList.toggle("active", btn.dataset.key?.toLowerCase() === this.pendingModifier);
    });
    this.onModifierChange?.(this.pendingModifier);
  }

  textarea(): HTMLTextAreaElement | null {
    return this.terminalElement.querySelector("textarea.xterm-helper-textarea");
  }

  dispose(): void {
    this.pendingModifier = null;
  }
}
