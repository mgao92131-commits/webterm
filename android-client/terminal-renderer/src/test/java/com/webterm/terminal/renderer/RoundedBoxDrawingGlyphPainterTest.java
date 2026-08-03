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

/** Regression coverage for the rounded U+256D..U+2570 box corners. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class RoundedBoxDrawingGlyphPainterTest {
  private static final int BACKGROUND = 0xFF000000;
  private static final int FOREGROUND = 0xFFFFFFFF;

  @Test
  public void roundedCornersUseEdgeTangentCurvesInsteadOfBowingDeepIntoTheCell() {
    // Regions are mirrored around the 50px cell centre. A correct quarter ellipse passes
    // through the near-centre region. The old swapped-axis controls instead passed through
    // the deep-interior region and formed a kink at both neighbouring straight strokes.
    assertRoundedCorner(0x256D, 57, 57, 68, 68, 73, 73, 84, 84); // ╭
    assertRoundedCorner(0x256E, 32, 57, 43, 68, 16, 73, 27, 84); // ╮
    assertRoundedCorner(0x256F, 32, 32, 43, 43, 16, 16, 27, 27); // ╯
    assertRoundedCorner(0x2570, 57, 32, 68, 43, 73, 16, 84, 27); // ╰
  }

  private static void assertRoundedCorner(
      int codePoint,
      int expectedLeft,
      int expectedTop,
      int expectedRight,
      int expectedBottom,
      int wrongLeft,
      int wrongTop,
      int wrongRight,
      int wrongBottom) {
    Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(BACKGROUND);
    Canvas canvas = new Canvas(bitmap);
    int saveCount = canvas.save();
    canvas.clipRect(10, 10, 90, 90);
    new BoxDrawingGlyphPainter().draw(
        canvas, codePoint, 10, 10, 90, 90, FOREGROUND);
    canvas.restoreToCount(saveCount);

    String label = "U+" + Integer.toHexString(codePoint);
    assertTrue(label + " does not follow the expected rounded path",
        countInk(bitmap, expectedLeft, expectedRight, expectedTop, expectedBottom) > 0);
    assertEquals(label + " bows too far into the cell", 0,
        countInk(bitmap, wrongLeft, wrongRight, wrongTop, wrongBottom));
    bitmap.recycle();
  }

  private static int countInk(
      Bitmap bitmap, int left, int right, int top, int bottom) {
    int count = 0;
    for (int y = top; y < bottom; y++) {
      for (int x = left; x < right; x++) {
        if (bitmap.getPixel(x, y) != BACKGROUND) count++;
      }
    }
    return count;
  }
}
