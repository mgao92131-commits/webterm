package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
public final class PowerlineGlyphPainterTest {
  private static final int BACKGROUND = 0xFF000000;
  private static final int FOREGROUND = 0xFFFFFFFF;

  @Test
  public void onlyTheVerifiedAllowlistIsSupported() {
    for (int codePoint : new int[] {0xE0B0, 0xE0B2, 0xE0B4, 0xE0B6}) {
      assertTrue(PowerlineGlyphPainter.supports(codePoint));
    }
    for (int codePoint : new int[] {0xE0A0, 0xE0B1, 0xE0B3, 0xE0B5, 0xE0B7, 0xE0D7}) {
      assertFalse(PowerlineGlyphPainter.supports(codePoint));
    }
  }

  @Test
  public void allowlistedShapesProduceClippedInk() {
    for (int codePoint : new int[] {0xE0B0, 0xE0B2, 0xE0B4, 0xE0B6}) {
      Bitmap bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(BACKGROUND);
      Canvas canvas = new Canvas(bitmap);
      int saveCount = canvas.save();
      canvas.clipRect(8, 4, 40, 28);
      new PowerlineGlyphPainter().draw(canvas, codePoint, 8, 4, 40, 28, FOREGROUND);
      canvas.restoreToCount(saveCount);

      assertTrue("Powerline must produce ink U+" + Integer.toHexString(codePoint),
          countInk(bitmap, 8, 40, 4, 28) > 0);
      assertEquals("Powerline leaked left U+" + Integer.toHexString(codePoint), 0,
          countInk(bitmap, 0, 8, 0, 32));
      assertEquals("Powerline leaked right U+" + Integer.toHexString(codePoint), 0,
          countInk(bitmap, 40, 48, 0, 32));
      bitmap.recycle();
    }
  }

  @Test
  public void unsupportedPuaReturnsFontFallbackFromUnifiedPainter() {
    TerminalSpecialGlyphPainter painter = new TerminalSpecialGlyphPainter();
    Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(BACKGROUND);
    assertFalse(painter.supports("\uE0B1"));
    assertFalse(painter.drawIfSupported(new Canvas(bitmap), "\uE0B1",
        4, 2, 28, 22, FOREGROUND));
    assertEquals(0, countInk(bitmap, 0, bitmap.getWidth(), 0, bitmap.getHeight()));
    bitmap.recycle();
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
