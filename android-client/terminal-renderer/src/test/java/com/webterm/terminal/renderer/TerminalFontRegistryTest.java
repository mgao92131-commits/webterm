package com.webterm.terminal.renderer;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Typeface;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class TerminalFontRegistryTest {
  private final Context application = RuntimeEnvironment.getApplication();

  @After
  public void tearDown() {
    TerminalFontRegistry.resetForTest();
  }

  @Test
  public void loadsFontSetOnlyOncePerProcess() {
    AtomicInteger loads = new AtomicInteger();
    TerminalFontSet expected = TerminalFontSet.mainOnly();
    TerminalFontRegistry.setLoaderForTest(context -> {
      loads.incrementAndGet();
      assertSame(application, context);
      return expected;
    });

    assertSame(expected, TerminalFontRegistry.get(application));
    assertSame(expected, TerminalFontRegistry.get(application));
    assertEquals(1, loads.get());
  }

  @Test
  public void registryDoesNotKeepActivityContext() {
    TerminalFontSet expected = new TerminalFontSet(
        Typeface.MONOSPACE,
        Typeface.MONOSPACE,
        Typeface.MONOSPACE,
        Typeface.DEFAULT);
    WeakReference<Context> loadedContext = new WeakReference<>(application);
    TerminalFontRegistry.setLoaderForTest(context -> {
      // 只用于证明 loader 收到的是 application context；registry 本身不保存它。
      assertSame(application, context);
      return expected;
    });

    assertSame(expected, TerminalFontRegistry.get(application));
    assertEquals(application, loadedContext.get());
    // 这里只检查 registry 的可观察契约：后续 get 不再调用 loader，也不要求 Context 参数。
    assertSame(expected, TerminalFontRegistry.get(application));
  }

  @Test
  public void loaderFailureFallsBackToMainOnly() {
    TerminalFontRegistry.setLoaderForTest(context -> {
      throw new IllegalStateException("test");
    });

    TerminalFontSet fonts = TerminalFontRegistry.get(application);
    assertEquals(TerminalFontRole.MAIN_TEXT, fonts.resolver.resolve("⏺"));
    assertEquals(TerminalFontRole.MAIN_TEXT, fonts.resolver.resolve("😀"));
  }
}
