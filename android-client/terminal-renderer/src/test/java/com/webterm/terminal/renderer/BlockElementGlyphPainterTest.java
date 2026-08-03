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
  public void everyQuadrantCodePointMatchesItsIndependentTopology() {
    int[][] expectedMasks = {
        {0, 0, 1, 0}, // U+2596 lower left
        {0, 0, 0, 1}, // U+2597 lower right
        {1, 0, 0, 0}, // U+2598 upper left
        {1, 0, 1, 1}, // U+2599 upper left + lower left + lower right
        {1, 0, 0, 1}, // U+259A upper left + lower right
        {1, 1, 1, 0}, // U+259B upper left + upper right + lower left
        {1, 1, 0, 1}, // U+259C upper left + upper right + lower right
        {0, 1, 0, 0}, // U+259D upper right
        {0, 1, 1, 0}, // U+259E upper right + lower left
        {0, 1, 1, 1}  // U+259F upper right + lower left + lower right
    };
    for (int index = 0; index < expectedMasks.length; index++) {
      Bitmap bitmap = bitmap();
      new BlockElementGlyphPainter().draw(new Canvas(bitmap), 0x2596 + index,
          4, 4, 36, 36, FOREGROUND);
      assertQuadrant(bitmap, 0x2596 + index, 4, 4, 36, 36, expectedMasks[index]);
      bitmap.recycle();
    }
  }

  @Test
  public void shadePatternUsesTerminalXPhaseAcrossOddCellBoundary() {
    Bitmap bitmap = bitmap(64, 24);
    BlockElementGlyphPainter painter = new BlockElementGlyphPainter();
    painter.draw(new Canvas(bitmap), 0x2592, 0, 0, 31, 24, FOREGROUND, 0, 0);
    painter.draw(new Canvas(bitmap), 0x2592, 31, 0, 64, 24, FOREGROUND, 0, 0);
    for (int y = 0; y < 24; y++) {
      for (int x = 0; x < 64; x++) {
        int expected = ((x + y) & 1) == 0 ? FOREGROUND : BACKGROUND;
        assertEquals("shade phase restarted at odd cell boundary x=" + x + " y=" + y,
            expected, bitmap.getPixel(x, y));
      }
    }
    bitmap.recycle();
  }

  @Test
  public void shadePatternUsesLineLocalYPhase() {
    Bitmap bitmap = bitmap(32, 96);
    BlockElementGlyphPainter painter = new BlockElementGlyphPainter();
    painter.draw(new Canvas(bitmap), 0x2592, 0, 9, 32, 33, FOREGROUND, 0, 0);
    painter.draw(new Canvas(bitmap), 0x2592, 0, 52, 32, 76, FOREGROUND, 0, 0);
    for (int y = 0; y < 24; y++) {
      for (int x = 0; x < 32; x++) {
        assertEquals("shade phase depends on screen row", bitmap.getPixel(x, 9 + y),
            bitmap.getPixel(x, 52 + y));
      }
    }
    bitmap.recycle();
  }

  @Test
  public void shadePatternIsTintedByTheResolvedForegroundColor() {
    int[] codePoints = {0x2591, 0x2592, 0x2593};
    int[] colors = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF};
    for (int index = 0; index < codePoints.length; index++) {
      Bitmap bitmap = bitmap(8, 8);
      new BlockElementGlyphPainter().draw(new Canvas(bitmap), codePoints[index],
          0, 0, 8, 8, colors[index], 0, 0);
      assertEquals("shade must preserve resolved foreground for U+"
              + Integer.toHexString(codePoints[index]), colors[index], bitmap.getPixel(0, 0));
      bitmap.recycle();
    }

    Bitmap dimmed = bitmap(8, 8);
    new BlockElementGlyphPainter().draw(new Canvas(dimmed), 0x2592,
        0, 0, 8, 8, 0xFFAAAAAA, 0, 0);
    assertEquals("dim shade must use the dimmed foreground", 0xFFAAAAAA,
        dimmed.getPixel(0, 0));
    dimmed.recycle();

    Bitmap customIndexed = bitmap(8, 8);
    new BlockElementGlyphPainter().draw(new Canvas(customIndexed), 0x2593,
        0, 0, 8, 8, 0xFF123456, 0, 0);
    assertEquals("custom resolved indexed color must tint shade", 0xFF123456,
        customIndexed.getPixel(0, 0));
    customIndexed.recycle();
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

  private static void assertQuadrant(Bitmap bitmap, int codePoint, int left, int top,
                                     int right, int bottom, int[] expectedMask) {
    int middleX = left + (right - left) / 2;
    int middleY = top + (bottom - top) / 2;
    int[] sampleX = {left + 4, middleX + 4};
    int[] sampleY = {top + 4, middleY + 4};
    int index = 0;
    for (int row = 0; row < 2; row++) {
      for (int column = 0; column < 2; column++) {
        int expected = expectedMask[index++];
        int actual = bitmap.getPixel(sampleX[column], sampleY[row]) == FOREGROUND ? 1 : 0;
        assertEquals("quadrant mask U+" + Integer.toHexString(codePoint)
            + " index=" + (index - 1), expected, actual);
      }
    }
  }
}
