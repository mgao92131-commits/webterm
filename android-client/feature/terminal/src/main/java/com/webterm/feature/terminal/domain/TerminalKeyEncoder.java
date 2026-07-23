package com.webterm.feature.terminal.domain;

import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 把 Android {@link KeyEvent} 映射为 webterm.screen.v2 语义键名。
 * 只处理需要特殊 ESC 序列的键；普通字符由 IME InputConnection 以 TextInput 发送。
 */
public final class TerminalKeyEncoder {

  private TerminalKeyEncoder() {}

  /**
   * 返回语义键名；如果是普通字符或无法识别则返回 null。
   */
  @Nullable
  public static String semanticKey(int keyCode) {
    switch (keyCode) {
      case KeyEvent.KEYCODE_DPAD_UP:
        return "ArrowUp";
      case KeyEvent.KEYCODE_DPAD_DOWN:
        return "ArrowDown";
      case KeyEvent.KEYCODE_DPAD_LEFT:
        return "ArrowLeft";
      case KeyEvent.KEYCODE_DPAD_RIGHT:
        return "ArrowRight";
      case KeyEvent.KEYCODE_ENTER:
      case KeyEvent.KEYCODE_NUMPAD_ENTER:
        return "Enter";
      case KeyEvent.KEYCODE_DEL:
        return "Backspace";
      case KeyEvent.KEYCODE_FORWARD_DEL:
        return "Delete";
      case KeyEvent.KEYCODE_TAB:
        return "Tab";
      case KeyEvent.KEYCODE_ESCAPE:
        return "Escape";
      case KeyEvent.KEYCODE_MOVE_HOME:
        return "Home";
      case KeyEvent.KEYCODE_MOVE_END:
        return "End";
      case KeyEvent.KEYCODE_PAGE_UP:
        return "PageUp";
      case KeyEvent.KEYCODE_PAGE_DOWN:
        return "PageDown";
      case KeyEvent.KEYCODE_INSERT:
        return "Insert";
      case KeyEvent.KEYCODE_F1:
        return "F1";
      case KeyEvent.KEYCODE_F2:
        return "F2";
      case KeyEvent.KEYCODE_F3:
        return "F3";
      case KeyEvent.KEYCODE_F4:
        return "F4";
      case KeyEvent.KEYCODE_F5:
        return "F5";
      case KeyEvent.KEYCODE_F6:
        return "F6";
      case KeyEvent.KEYCODE_F7:
        return "F7";
      case KeyEvent.KEYCODE_F8:
        return "F8";
      case KeyEvent.KEYCODE_F9:
        return "F9";
      case KeyEvent.KEYCODE_F10:
        return "F10";
      case KeyEvent.KEYCODE_F11:
        return "F11";
      case KeyEvent.KEYCODE_F12:
        return "F12";
      case KeyEvent.KEYCODE_NUM_LOCK:
        return "NumLock";
      case KeyEvent.KEYCODE_SCROLL_LOCK:
        return "ScrollLock";
      case KeyEvent.KEYCODE_BREAK:
        return "Pause";
      case KeyEvent.KEYCODE_SPACE:
        return "Space";
      case KeyEvent.KEYCODE_BACK:
        return "Back";
      default:
        return null;
    }
  }

  /**
   * 从 KeyEvent 构造语义输入描述。
   */
  @NonNull
  public static KeyDescriptor describe(@NonNull KeyEvent event) {
    String key = semanticKey(event.getKeyCode());
    if (key == null && event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN) {
      key = "Unidentified";
    }
    int textMeta = event.getMetaState() & (KeyEvent.META_SHIFT_ON | KeyEvent.META_CAPS_LOCK_ON);
    int unicode = event.getUnicodeChar(textMeta);
    return new KeyDescriptor(
        key,
        event.isShiftPressed(),
        event.isAltPressed(),
        event.isCtrlPressed(),
        event.isMetaPressed(),
        event.getAction() == KeyEvent.ACTION_DOWN,
        event.getNumber() != 0 ? String.valueOf(event.getNumber()) : null,
        unicode != 0 ? new String(Character.toChars(unicode)) : null
    );
  }

  /**
   * 不依赖 Android KeyEvent 的纯描述构造，便于单元测试和普通 JVM 调用。
   */
  @NonNull
  public static KeyDescriptor describe(int keyCode, int action, int unicodeChar,
                                       boolean shift, boolean alt, boolean ctrl, boolean meta) {
    String key = semanticKey(keyCode);
    if (key == null && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
      key = "Unidentified";
    }
    return new KeyDescriptor(
        key,
        shift,
        alt,
        ctrl,
        meta,
        action == KeyEvent.ACTION_DOWN,
        null,
        unicodeChar != 0 ? String.valueOf((char) unicodeChar) : null
    );
  }

  public static final class KeyDescriptor {
    @Nullable public final String key;
    public final boolean shift;
    public final boolean alt;
    public final boolean ctrl;
    public final boolean meta;
    public final boolean pressed;
    @Nullable public final String numberLabel;
    @Nullable public final String unicodeChar;

    public KeyDescriptor(@Nullable String key, boolean shift, boolean alt, boolean ctrl,
                         boolean meta, boolean pressed, @Nullable String numberLabel,
                         @Nullable String unicodeChar) {
      this.key = key;
      this.shift = shift;
      this.alt = alt;
      this.ctrl = ctrl;
      this.meta = meta;
      this.pressed = pressed;
      this.numberLabel = numberLabel;
      this.unicodeChar = unicodeChar;
    }

    public boolean isFunctional() {
      return key != null;
    }
  }
}
