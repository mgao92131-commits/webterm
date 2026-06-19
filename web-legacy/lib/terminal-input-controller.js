import { keyEventData, modifiedInput, quickbarInput } from "./terminal-input.js";

export class TerminalInputController {
  constructor({ store, root, terminalElement, sendInput, focusTerminal }) {
    this.store = store;
    this.root = root;
    this.terminalElement = terminalElement;
    this.sendInput = sendInput;
    this.focusTerminal = focusTerminal;
    this.pendingModifier = null;
    this.lastQuickbarTouchAt = 0;
  }

  attach() {
    this.attachModifierCapture();
    this.root.querySelectorAll("[data-key]").forEach((button) => this.attachQuickbarButton(button));
  }

  attachModifierCapture() {
    const onKeyDown = (event) => {
      if (!this.pendingModifier || event.metaKey || event.isComposing) return;
      const data = keyEventData(event);
      if (!data) return;
      if (!this.sendModifiedInput(data)) return;
      event.preventDefault();
      event.stopPropagation();
    };

    const onBeforeInput = (event) => {
      if (!this.pendingModifier || event.isComposing) return;
      if (event.inputType && !event.inputType.startsWith("insert")) return;
      const data = event.data || "";
      if (!data) return;
      if (!this.sendModifiedInput(data)) return;
      event.preventDefault();
      event.stopPropagation();
    };

    const bindTarget = (target) => {
      if (!target || target.dataset.modifierCapture === "1") return;
      target.dataset.modifierCapture = "1";
      this.store.addEventListener(target, "keydown", onKeyDown, true);
      this.store.addEventListener(target, "beforeinput", onBeforeInput, true);
    };

    bindTarget(this.terminalElement);
    bindTarget(this.textarea());
    this.store.addTimeout(setTimeout(() => bindTarget(this.textarea()), 0));
  }

  attachQuickbarButton(button) {
    this.store.addEventListener(button, "touchend", (event) => {
      event.preventDefault();
      this.lastQuickbarTouchAt = Date.now();
      this.tapQuickbarButton(button);
    }, { passive: false });
    this.store.addEventListener(button, "click", (event) => {
      event.preventDefault();
      if (Date.now() - this.lastQuickbarTouchAt < 700) return;
      this.tapQuickbarButton(button);
    });
  }

  tapQuickbarButton(button) {
    this.sendKey(button.dataset.key);
    button.blur();
    this.focusTerminal();
  }

  sendKey(key) {
    if (key === "Ctrl") {
      this.togglePendingModifier(key.toLowerCase());
      return;
    }
    const modified = quickbarInput(this.pendingModifier, key);
    this.clearPendingModifier();
    if (modified) this.sendInput(modified, "quickbar");
    this.focusTerminal();
  }

  sendModifiedInput(data) {
    if (!this.pendingModifier) return false;
    const modified = modifiedInput(this.pendingModifier, data);
    this.clearPendingModifier();
    if (!modified) return false;
    this.sendInput(modified, "modifier");
    this.focusTerminal();
    return true;
  }

  togglePendingModifier(modifier) {
    this.pendingModifier = this.pendingModifier === modifier ? null : modifier;
    this.updateModifierButtons();
    this.focusTerminal();
  }

  clearPendingModifier() {
    if (!this.pendingModifier) return;
    this.pendingModifier = null;
    this.updateModifierButtons();
  }

  updateModifierButtons() {
    this.root.querySelectorAll("[data-key='Ctrl']").forEach((button) => {
      button.classList.toggle("active", button.dataset.key.toLowerCase() === this.pendingModifier);
    });
  }

  textarea() {
    return this.terminalElement.querySelector("textarea.xterm-helper-textarea");
  }
}
