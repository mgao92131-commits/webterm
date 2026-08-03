package com.webterm.terminal.renderer;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;

import androidx.annotation.NonNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** 一次加载、整个 renderer 生命周期复用的字体集合。 */
final class TerminalFontSet {
  final Typeface mainText;
  final Typeface unicodeSymbols;
  final Typeface nerdSymbols;
  final Typeface emoji;
  final TerminalFontResolver resolver;

  TerminalFontSet(
      @NonNull Typeface mainText,
      @NonNull Typeface unicodeSymbols,
      @NonNull Typeface nerdSymbols,
      @NonNull Typeface emoji) {
    this(mainText, unicodeSymbols, nerdSymbols, emoji,
        TerminalFontResolver.defaultResolver());
  }

  TerminalFontSet(
      @NonNull Typeface mainText,
      @NonNull Typeface unicodeSymbols,
      @NonNull Typeface nerdSymbols,
      @NonNull Typeface emoji,
      @NonNull TerminalFontResolver resolver) {
    this.mainText = mainText;
    this.unicodeSymbols = unicodeSymbols;
    this.nerdSymbols = nerdSymbols;
    this.emoji = emoji;
    this.resolver = resolver;
  }

  static TerminalFontSet fromContext(@NonNull Context context) {
    Typeface unicodeSymbols = loadFont(context.getResources(), R.font.noto_sans_symbols_2_regular);
    Typeface nerdSymbols = loadFont(
        context.getResources(), R.font.symbols_nerd_font_mono_regular);
    // Robolectric 的部分 Android API stub 没有完整实现 Resources.getFont()。缺少资源时
    // 必须关闭角色路由，不能让主字体 Paint 冒充专用符号字体。
    if (unicodeSymbols == null || nerdSymbols == null) return mainOnly();
    return new TerminalFontSet(
        Typeface.MONOSPACE,
        unicodeSymbols,
        nerdSymbols,
        Typeface.DEFAULT);
  }

  private static Typeface loadFont(Resources resources, int resourceId) {
    try {
      Method getFont = Resources.class.getMethod("getFont", int.class);
      return (Typeface) getFont.invoke(resources, resourceId);
    } catch (NoSuchMethodException
             | IllegalAccessException
             | InvocationTargetException
             | RuntimeException ignored) {
      return null;
    }
  }

  static TerminalFontSet mainOnly() {
    return new TerminalFontSet(
        Typeface.MONOSPACE,
        Typeface.MONOSPACE,
        Typeface.MONOSPACE,
        Typeface.DEFAULT,
        TerminalFontResolver.mainOnly());
  }
}
