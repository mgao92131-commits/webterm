package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 页面级终端输入协调器。统一保存一次性 Ctrl 状态，并把文字、粘贴和按键
 * 转换为 TerminalScreenController 能理解的语义输入。
 *
 * <p>本类不读取剪贴板、不处理 IME composing，也不拥有连接或可靠输入队列。</p>
 */
final class TerminalInputCoordinator {

  interface Sink {
    void sendText(@NonNull String text);
    void sendPaste(@NonNull String text);
    void sendKey(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                 boolean meta, boolean pressed);
  }

  interface ModifierStateListener {
    void onCtrlStateChanged(boolean armed);
  }

  private final Sink sink;
  @Nullable private final ModifierStateListener modifierStateListener;
  private boolean ctrlArmed;

  TerminalInputCoordinator(@NonNull Sink sink,
                           @Nullable ModifierStateListener modifierStateListener) {
    this.sink = sink;
    this.modifierStateListener = modifierStateListener;
  }

  void toggleCtrl() {
    setCtrlArmed(!ctrlArmed);
  }

  void submitText(@NonNull String text) {
    submitText(text, "terminal_text");
  }

  void submitText(@NonNull String text, @NonNull String source) {
    if (text.isEmpty()) return;
    boolean useCtrl = consumeCtrl();
    boolean ctrlKey = useCtrl && text.codePointCount(0, text.length()) == 1;
    if (ctrlKey) {
      sink.sendKey(text, false, false, true, false, true);
      return;
    }
    sink.sendText(text);
  }

  void submitPaste(@NonNull String text) {
    submitPaste(text, "terminal_paste");
  }

  void submitPaste(@NonNull String text, @NonNull String source) {
    if (text.isEmpty()) return;
    sink.sendPaste(text);
  }

  void submitFunctionalKey(@NonNull String key, boolean shift, boolean alt, boolean meta) {
    boolean useCtrl = consumeCtrl();
    sink.sendKey(key, shift, alt, useCtrl, meta, true);
  }

  /**
   * 物理键盘事件保留自身 modifier。真实 Ctrl/Alt/Meta 优先于快捷栏 Ctrl，
   * 并清除后者，避免两个 modifier 来源跨事件叠加。
   */
  void submitHardwareKey(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                         boolean meta, boolean pressed) {
    boolean effectiveCtrl = ctrl;
    boolean ctrlBefore = ctrlArmed;
    boolean ctrlConsumed = false;
    if (pressed) {
      if (ctrl || alt || meta) {
        clearModifiers();
        ctrlConsumed = ctrlBefore;
      } else {
        effectiveCtrl = consumeCtrl();
        ctrlConsumed = effectiveCtrl;
      }
    }
    sink.sendKey(key, shift, alt, effectiveCtrl, meta, pressed);
  }

  void clearModifiers() {
    setCtrlArmed(false);
  }

  boolean isCtrlArmed() {
    return ctrlArmed;
  }

  private boolean consumeCtrl() {
    if (!ctrlArmed) return false;
    setCtrlArmed(false);
    return true;
  }

  private void setCtrlArmed(boolean armed) {
    if (ctrlArmed == armed) return;
    ctrlArmed = armed;
    if (modifierStateListener != null) {
      modifierStateListener.onCtrlStateChanged(armed);
    }
  }

}
