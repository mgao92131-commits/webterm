package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.graphics.Path;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class TerminalDecorationPatternCacheTest {
  @Test
  public void repeatedPatternUsesTheSamePath() {
    TerminalDecorationPatternCache cache = new TerminalDecorationPatternCache();

    assertSame(cache.dotted(120, 4), cache.dotted(120, 4));
    assertSame(cache.dashed(120, 11), cache.dashed(120, 11));
    Path firstCurly = cache.curly(120, 4);
    assertEquals(124, cache.lastBuildSegmentCount());
    assertSame(firstCurly, cache.curly(120, 4));
    assertEquals(3L, cache.buildCountForTest());
    assertEquals(3L, cache.hitCountForTest());
  }

  @Test
  public void cacheIsBounded() {
    TerminalDecorationPatternCache cache = new TerminalDecorationPatternCache();
    for (int width = 1; width <= 200; width++) {
      cache.dotted(width, width);
      cache.dashed(width, width);
      cache.curly(width, width);
    }
    assertEquals(TerminalDecorationPatternCache.MAX_ENTRIES, cache.sizeForTest());
  }
}
