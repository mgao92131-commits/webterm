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

  private final RemoteTerminalView view;
  private final TerminalScreenController controller;

  RemoteTerminalScreenView(@NonNull RemoteTerminalView view,
                           @NonNull TerminalScreenController controller) {
    this.view = view;
    this.controller = controller;
    this.view.setHost(this);
  }

  @Override
  public void render(@NonNull RemoteTerminalModel model, @NonNull TerminalViewportState viewport) {
    view.setModel(model, viewport);
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
  public void onScrollPixels(int deltaPixels) {
    controller.onScrollPixels(deltaPixels);
  }

  @Override
  public void onRequestHistoryPage() {
    controller.requestOlderHistoryPage();
  }

  @Override
  public void onFollowTail() {
    controller.followTail();
  }

  @Override
  public void onFocusChanged(boolean focused) {
    controller.sendFocus(focused);
  }
}
