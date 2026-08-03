package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class BlockElementGlyphPainterTest {
  private static final int BACKGROUND = 0xFF000000;
  private static final int FOREGROUND = 0xFFFFFFFF;

  @Test
  public void completeRangeIsSupported() {
    for (int codePoint = 0x2580; codePoint <= 0x259F; codePoint++) {
      assertTrue(BlockElementGlyphPainter.supports(codePoint));
    }
  }

  @Test
  public void fullAndHalfBlocksUseExactCellBounds() {
    Bitmap bitmap = bitmap();
    BlockElementGlyphPainter painter = new BlockElementGlyphPainter();
    painter.draw(new Canvas(bitmap), 0x2588, 4, 4, 36, 36, FOREGROUND);
    assertEquals(FOREGROUND, bitmap.getPixel(4, 4));
    assertEquals(FOREGROUND, bitmap.getPixel(35, 35));

    bitmap.eraseColor(BACKGROUND);
    painter.draw(new Canvas(bitmap), 0x2580, 4, 4, 36, 36, FOREGROUND);
    assertEquals(FOREGROUND, bitmap.getPixel(20, 8));
    assertEquals(BACKGROUND, bitmap.getPixel(20, 28));

    bitmap.eraseColor(BACKGROUND);
    painter.draw(new Canvas(bitmap), 0x2584, 4, 4, 36, 36, FOREGROUND);
    assertEquals(BACKGROUND, bitmap.getPixel(20, 8));
    assertEquals(FOREGROUND, bitmap.getPixel(20, 28));
    bitmap.recycle();
  }

  @Test
  public void eighthBlocksUseCumulativeRoundedEdges() {
    Bitmap bitmap = bitmap();
    BlockElementGlyphPainter painter = new BlockElementGlyphPainter();
    for (int codePoint = 0x2581; codePoint <= 0x2588; codePoint++) {
      bitmap.eraseColor(BACKGROUND);
      painter.draw(new Canvas(bitmap), codePoint, 4, 4, 36, 36, FOREGROUND);
      int expectedTop = 4 + Math.round(32 * (8 - (codePoint - 0x2580)) / 8f);
      assertEquals("lower block U+" + Integer.toHexString(codePoint), FOREGROUND,
          bitmap.getPixel(20, expectedTop));
      if (expectedTop > 4) {
        assertEquals(BACKGROUND, bitmap.getPixel(20, expectedTop - 1));
      }
    }
    bitmap.recycle();
  }

  @Test
  public void quadrantsAndShadesAreStableAndCellBounded() {
    Bitmap bitmap = bitmap();
    BlockElementGlyphPainter painter = new BlockElementGlyphPainter();
    painter.draw(new Canvas(bitmap), 0x2596, 4, 4, 36, 36, FOREGROUND);
    assertEquals(BACKGROUND, bitmap.getPixel(10, 10));
    assertEquals(FOREGROUND, bitmap.getPixel(10, 28));
    assertEquals(BACKGROUND, bitmap.getPixel(28, 10));
    assertEquals(BACKGROUND, bitmap.getPixel(28, 28));

    for (int codePoint = 0x2591; codePoint <= 0x2593; codePoint++) {
      bitmap.eraseColor(BACKGROUND);
      painter.draw(new Canvas(bitmap), codePoint, 4, 4, 36, 36, FOREGROUND);
      int ink = countInk(bitmap, 4, 36, 4, 36);
      assertTrue("shade must leave a measurable mask", ink > 0 && ink < 32 * 32);
      assertEquals(0, countInk(bitmap, 0, 4, 0, 40) + countInk(bitmap, 36, 40, 0, 40));
    }
    bitmap.recycle();
  }

  @Test
  public void shadePatternUsesAbsolutePixelPhaseAcrossCells() {
    Bitmap bitmap = bitmap(64, 24);
    BlockElementGlyphPainter painter = new BlockElementGlyphPainter();
    painter.draw(new Canvas(bitmap), 0x2592, 0, 0, 32, 24, FOREGROUND);
    painter.draw(new Canvas(bitmap), 0x2592, 32, 0, 64, 24, FOREGROUND);
    for (int y = 0; y < 24; y++) {
      assertEquals("shade phase restarted at cell boundary", bitmap.getPixel(31, y),
          bitmap.getPixel(33, y));
    }
    bitmap.recycle();
  }

  private static Bitmap bitmap() {
    return bitmap(40, 40);
  }

  private static Bitmap bitmap(int width, int height) {
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(BACKGROUND);
    return bitmap;
  }

  private static int countInk(Bitmap bitmap, int left, int right, int top, int bottom) {
    int count = 0;
    for (int y = top; y < bottom; y++) {
      for (int x = left; x < right; x++) {
        if (bitmap.getPixel(x, y) != BACKGROUND) count++;
      }
    }
    return count;
  }
}
