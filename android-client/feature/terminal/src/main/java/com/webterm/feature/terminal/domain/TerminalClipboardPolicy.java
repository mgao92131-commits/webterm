package com.webterm.feature.terminal.domain;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * OSC 52 剪贴板安全策略。
 *
 * <ul>
 *   <li>读取 Android 剪贴板默认禁止。</li>
 *   <li>写入剪贴板需要用户在设置中显式授权。</li>
 *   <li>后台 observer 不执行写入。</li>
 * </ul>
 */
public final class TerminalClipboardPolicy {

  private static final String PREFS_NAME = "terminal_clipboard_policy";
  private static final String KEY_WRITE_ALLOWED = "osc52_write_allowed";

  private final SharedPreferences prefs;

  public TerminalClipboardPolicy(@NonNull Context context) {
    this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  /** OSC 52 读取是否被允许。第一版默认禁止。 */
  public boolean isReadAllowed() {
    return false;
  }

  /** OSC 52 写入是否被允许。 */
  public boolean isWriteAllowed() {
    return prefs.getBoolean(KEY_WRITE_ALLOWED, false);
  }

  public void setWriteAllowed(boolean allowed) {
    prefs.edit().putBoolean(KEY_WRITE_ALLOWED, allowed).apply();
  }
}
