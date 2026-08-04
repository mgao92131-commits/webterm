package com.webterm.terminal.renderer;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 进程内复用终端字体资源，避免每个终端 View 重复从 Resources 加载字体。
 *
 * <p>Registry 不保存 Context；Context 只在第一次加载时转换为 application context 并传给
 * loader。这样页面或 Activity 销毁后不会因为字体缓存反向持有它。</p>
 */
final class TerminalFontRegistry {
  interface Loader {
    @Nullable
    TerminalFontSet load(@NonNull Context applicationContext);
  }

  private static final Object LOCK = new Object();
  private static final Loader DEFAULT_LOADER = TerminalFontSet::fromContext;

  @Nullable
  private static volatile TerminalFontSet instance;
  private static volatile Loader loader = DEFAULT_LOADER;

  private TerminalFontRegistry() {}

  @NonNull
  static TerminalFontSet get(@NonNull Context context) {
    TerminalFontSet cached = instance;
    if (cached != null) return cached;

    synchronized (LOCK) {
      cached = instance;
      if (cached != null) return cached;

      Context applicationContext = context.getApplicationContext();
      if (applicationContext == null) applicationContext = context;

      try {
        cached = loader.load(applicationContext);
      } catch (RuntimeException ignored) {
        cached = null;
      }
      if (cached == null) cached = TerminalFontSet.mainOnly();
      instance = cached;
      return cached;
    }
  }

  static void setLoaderForTest(@NonNull Loader testLoader) {
    synchronized (LOCK) {
      loader = testLoader;
      instance = null;
    }
  }

  static void resetForTest() {
    synchronized (LOCK) {
      loader = DEFAULT_LOADER;
      instance = null;
    }
  }
}
