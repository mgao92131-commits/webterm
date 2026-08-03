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
public final class BoxDrawingGlyphPainterTest {
  private static final int BACKGROUND = 0xFF000000;
  private static final int FOREGROUND = 0xFFFFFFFF;

  @Test
  public void completeRangeHasADataDrivenDescriptor() {
    for (int codePoint = 0x2500; codePoint <= 0x257F; codePoint++) {
      assertTrue("missing descriptor U+" + Integer.toHexString(codePoint),
          BoxDrawingGlyphPainter.hasDescriptor(codePoint));
    }
  }

  @Test
  public void horizontalAndVerticalLinesReachBothCellEdges() {
    Bitmap bitmap = bitmap(60, 40);
    BoxDrawingGlyphPainter painter = new BoxDrawingGlyphPainter();
    drawClipped(bitmap, painter, 0x2500, 7, 3, 47, 27);
    int horizontalY = 15;
    assertTrue(hasInkInColumn(bitmap, 7, 3, 27));
    assertTrue(hasInkInColumn(bitmap, 46, 3, 27));
    assertTrue(countInk(bitmap, 7, 47, horizontalY - 3, horizontalY + 4) > 0);
    bitmap.recycle();

    bitmap = bitmap(60, 40);
    drawClipped(bitmap, painter, 0x2502, 7, 3, 47, 27);
    assertTrue(hasInkInRow(bitmap, 7, 47, 3));
    assertTrue(hasInkInRow(bitmap, 7, 47, 26));
    bitmap.recycle();
  }

  @Test
  public void doubleLineKeepsTwoBandsAndMixedJunctionKeepsInkAtCenter() {
    Bitmap bitmap = bitmap(60, 40);
    BoxDrawingGlyphPainter painter = new BoxDrawingGlyphPainter();
    drawClipped(bitmap, painter, 0x2550, 7, 3, 47, 27);
    int inkRows = 0;
    for (int y = 3; y < 27; y++) {
      if (bitmap.getPixel(25, y) != BACKGROUND) inkRows++;
    }
    assertTrue("double horizontal must have two visible bands", inkRows >= 2);

    bitmap.eraseColor(BACKGROUND);
    drawClipped(bitmap, painter, 0x256C, 7, 3, 47, 27);
    assertTrue("double junction must connect at its center",
        bitmap.getPixel(27, 15) != BACKGROUND);
    bitmap.recycle();
  }

  @Test
  public void fractionalAccumulatedEdgesRemainContinuousAcrossThreeCells() {
    Bitmap bitmap = bitmap(40, 24);
    BoxDrawingGlyphPainter painter = new BoxDrawingGlyphPainter();
    float cellWidth = 7.3f;
    for (int column = 0; column < 3; column++) {
      int left = Math.round(column * cellWidth);
      int right = Math.round((column + 1) * cellWidth);
      drawClipped(bitmap, painter, 0x2500, left, 0, right, 20);
    }
    for (int x = 0; x < Math.round(3 * cellWidth); x++) {
      assertTrue("horizontal box line has a seam at x=" + x,
          bitmap.getPixel(x, 10) != BACKGROUND);
    }
    bitmap.recycle();
  }

  @Test
  public void roundedAndDiagonalGlyphsStayInsideTheTargetCell() {
    for (int codePoint : new int[] {0x256D, 0x256E, 0x256F, 0x2570,
        0x2571, 0x2572, 0x2573}) {
      Bitmap bitmap = bitmap(40, 30);
      drawClipped(bitmap, new BoxDrawingGlyphPainter(), codePoint, 5, 4, 25, 24);
      assertTrue("glyph must have ink U+" + Integer.toHexString(codePoint),
          countInk(bitmap, 5, 25, 4, 24) > 0);
      assertEquals("glyph leaked left U+" + Integer.toHexString(codePoint), 0,
          countInk(bitmap, 0, 5, 0, 30));
      assertEquals("glyph leaked right U+" + Integer.toHexString(codePoint), 0,
          countInk(bitmap, 25, 40, 0, 30));
      bitmap.recycle();
    }
  }

  private static void drawClipped(Bitmap bitmap, BoxDrawingGlyphPainter painter,
                                  int codePoint, int left, int top, int right, int bottom) {
    Canvas canvas = new Canvas(bitmap);
    int saveCount = canvas.save();
    canvas.clipRect(left, top, right, bottom);
    painter.draw(canvas, codePoint, left, top, right, bottom, FOREGROUND);
    canvas.restoreToCount(saveCount);
  }

  private static Bitmap bitmap(int width, int height) {
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(BACKGROUND);
    return bitmap;
  }

  private static boolean hasInkInColumn(Bitmap bitmap, int x, int top, int bottom) {
    return countInk(bitmap, x, x + 1, top, bottom) > 0;
  }

  private static boolean hasInkInRow(Bitmap bitmap, int left, int right, int y) {
    return countInk(bitmap, left, right, y, y + 1) > 0;
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
