package com.webterm.feature.terminal.domain;

import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.renderer.RemoteTerminalView;

/**
 * 把 {@link RemoteTerminalView} 适配为 {@link TerminalScreenController.View}
 * 与 {@link RemoteTerminalView.Host}。
 */
final class RemoteTerminalScreenView implements TerminalScreenController.View,
    RemoteTerminalView.Host {

  interface ConnectionStateListener {
    void onConnectionStateChanged(@NonNull TerminalSessionRuntime.State state);
  }

  private final RemoteTerminalView view;
  private final TerminalScreenController controller;
  @Nullable private final ConnectionStateListener connectionStateListener;
  @Nullable private Runnable afterRender;

  RemoteTerminalScreenView(@NonNull RemoteTerminalView view,
                           @NonNull TerminalScreenController controller) {
    this(view, controller, null);
  }

  RemoteTerminalScreenView(@NonNull RemoteTerminalView view,
                           @NonNull TerminalScreenController controller,
                           @Nullable ConnectionStateListener connectionStateListener) {
    this.view = view;
    this.controller = controller;
    this.connectionStateListener = connectionStateListener;
    this.view.setHost(this);
  }

  /** 每次渲染后回调，用于键盘弹出期间随内容变化重算避让平移。 */
  void setAfterRender(@Nullable Runnable afterRender) {
    this.afterRender = afterRender;
  }

  @Override
  public void render(@NonNull RemoteTerminalModel model, @NonNull TerminalViewportState viewport) {
    view.setModel(model, viewport);
    if (afterRender != null) afterRender.run();
  }

  @Override
  public void onCursorChanged() {
    view.invalidate();
  }

  @Override
  public void onTitleChanged(@Nullable String title) {
    // 标题变更由外部 Activity/Fragment 监听并处理。
  }

  @Override
  public void requestInvalidate() {
    view.invalidate();
  }

  @Override
  public void onHistoryAppended(int lineCount) {
    view.preserveViewportForAppendedLines(lineCount);
  }

  @Override
  public void onConnectionStateChanged(@NonNull TerminalSessionRuntime.State state) {
    if (connectionStateListener != null) connectionStateListener.onConnectionStateChanged(state);
  }

  @Override
  public void onTextInput(@NonNull String text) {
    controller.sendText(text);
  }

  @Override
  public void onPasteInput(@NonNull String text) {
    controller.sendPaste(text);
  }

  @Override
  public void onKeyEvent(@NonNull KeyEvent event) {
    TerminalKeyEncoder.KeyDescriptor desc = TerminalKeyEncoder.describe(event);
    if (desc.isFunctional()) {
      controller.sendKey(desc.key, desc.shift, desc.alt, desc.ctrl, desc.meta, desc.pressed);
      return;
    }
    if (desc.unicodeChar != null && event.getAction() == KeyEvent.ACTION_DOWN) {
      // 普通字符作为文本输入；KEYCODE_NUMPAD 等带 modifiers 时也可发送 key。
      if (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) {
        controller.sendKey(desc.unicodeChar, desc.shift, desc.alt, desc.ctrl, desc.meta, true);
      } else {
        controller.sendText(desc.unicodeChar);
      }
    }
  }

  @Override
  public void onRequestResize(int cols, int rows) {
    controller.requestResize(cols, rows);
  }

  @Override
  public void onRequestShowKeyboard() {
    InputMethodManager imm = (InputMethodManager) view.getContext()
        .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
    if (imm != null) {
      imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }
  }

  @Override
  public void onScrollPixels(int deltaPixels, int maxScrollOffsetPixels) {
    controller.onScrollPixels(deltaPixels, maxScrollOffsetPixels);
  }

  @Override
  public void onRequestHistoryPage() {
    controller.requestOlderHistoryPage();
  }

  @Override
  public void onFocusChanged(boolean focused) {
    controller.sendFocus(focused);
  }

  @Override
  public void onMouse(int row, int col, @NonNull String button, int wheelDelta,
                      boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed) {
    controller.sendMouse(row, col, button, wheelDelta, shift, alt, ctrl, meta, pressed);
  }

  @Override
  public void onAlternateScreenScroll(int rowsDown) {
    if (rowsDown == 0) return;
    String key = rowsDown < 0 ? "ArrowUp" : "ArrowDown";
    for (int i = 0; i < Math.abs(rowsDown); i++) {
      controller.sendKey(key, false, false, false, false, true);
    }
  }
}
