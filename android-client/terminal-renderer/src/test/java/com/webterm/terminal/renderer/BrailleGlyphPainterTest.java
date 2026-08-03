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
public final class BrailleGlyphPainterTest {
  private static final int BACKGROUND = 0xFF000000;
  private static final int FOREGROUND = 0xFFFFFFFF;

  @Test
  public void completeRangeIsSupported() {
    for (int codePoint = 0x2800; codePoint <= 0x28FF; codePoint++) {
      assertTrue(BrailleGlyphPainter.supports(codePoint));
    }
  }

  @Test
  public void blankBrailleIsHandledWithoutFontInk() {
    Bitmap bitmap = bitmap();
    new BrailleGlyphPainter().draw(new Canvas(bitmap), 0x2800, 4, 4, 36, 36, FOREGROUND);
    assertEquals(0, countInk(bitmap, 0, bitmap.getWidth(), 0, bitmap.getHeight()));
    bitmap.recycle();
  }

  @Test
  public void eachUnicodeDotBitMapsToTheCorrectTwoByFourRegion() {
    int[] codePoints = {0x2801, 0x2802, 0x2804, 0x2808,
        0x2810, 0x2820, 0x2840, 0x2880};
    int[][] regions = {
        {0, 0}, {0, 1}, {0, 2}, {1, 0},
        {1, 1}, {1, 2}, {0, 3}, {1, 3}
    };
    for (int index = 0; index < codePoints.length; index++) {
      Bitmap bitmap = bitmap();
      new BrailleGlyphPainter().draw(new Canvas(bitmap), codePoints[index], 4, 4, 36, 36,
          FOREGROUND);
      int expectedColumn = regions[index][0];
      int expectedRow = regions[index][1];
      assertTrue("missing dot U+" + Integer.toHexString(codePoints[index]),
          countInk(bitmap, 4 + expectedColumn * 16, 4 + (expectedColumn + 1) * 16,
              4 + expectedRow * 8, 4 + (expectedRow + 1) * 8) > 0);
      assertEquals("unexpected extra dot U+" + Integer.toHexString(codePoints[index]), 0,
          countInkOutsideRegion(bitmap, expectedColumn, expectedRow));
      bitmap.recycle();
    }
  }

  @Test
  public void fullBrailleHasEightDotsInsideTheCell() {
    Bitmap bitmap = bitmap();
    new BrailleGlyphPainter().draw(new Canvas(bitmap), 0x28FF, 4, 4, 36, 36, FOREGROUND);
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 2; column++) {
        assertTrue("missing full Braille dot at " + column + "," + row,
            countInk(bitmap, 4 + column * 16, 4 + (column + 1) * 16,
                4 + row * 8, 4 + (row + 1) * 8) > 0);
      }
    }
    assertEquals(0, countInk(bitmap, 0, 4, 0, 40) + countInk(bitmap, 36, 40, 0, 40));
    bitmap.recycle();
  }

  private static Bitmap bitmap() {
    Bitmap bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(BACKGROUND);
    return bitmap;
  }

  private static int countInkOutsideRegion(Bitmap bitmap, int expectedColumn, int expectedRow) {
    int count = 0;
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 2; column++) {
        if (column == expectedColumn && row == expectedRow) continue;
        count += countInk(bitmap, 4 + column * 16, 4 + (column + 1) * 16,
            4 + row * 8, 4 + (row + 1) * 8);
      }
    }
    return count;
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
