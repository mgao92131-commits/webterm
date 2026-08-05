package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Clip policy is deliberately conservative for stroked/edge-extending families. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SpecialGlyphClipSafetyTest {
  @Test
  public void filledFamiliesCanUseOneRunClip() {
    assertEquals(TerminalSpecialGlyphPainter.ClipPolicy.RUN_CLIP_SAFE,
        TerminalSpecialGlyphPainter.clipPolicy(
            TerminalSpecialGlyphPainter.Family.BLOCK_ELEMENTS));
    assertEquals(TerminalSpecialGlyphPainter.ClipPolicy.RUN_CLIP_SAFE,
        TerminalSpecialGlyphPainter.clipPolicy(
            TerminalSpecialGlyphPainter.Family.BRAILLE));
  }

  @Test
  public void strokedAndEdgeFamiliesKeepCellClip() {
    assertEquals(TerminalSpecialGlyphPainter.ClipPolicy.CELL_CLIP_REQUIRED,
        TerminalSpecialGlyphPainter.clipPolicy(
            TerminalSpecialGlyphPainter.Family.BOX_DRAWING));
    assertEquals(TerminalSpecialGlyphPainter.ClipPolicy.CELL_CLIP_REQUIRED,
        TerminalSpecialGlyphPainter.clipPolicy(
            TerminalSpecialGlyphPainter.Family.POWERLINE));
  }

  @Test
  public void everyBlockElementStaysInsideItsCellWithoutCellClip() {
    assertFamilyStaysInsideCell(
        TerminalSpecialGlyphPainter.Family.BLOCK_ELEMENTS, 0x2580, 0x259F);
  }

  @Test
  public void everyBraillePatternStaysInsideItsCellWithoutCellClip() {
    assertFamilyStaysInsideCell(
        TerminalSpecialGlyphPainter.Family.BRAILLE, 0x2800, 0x28FF);
  }

  private static void assertFamilyStaysInsideCell(
      TerminalSpecialGlyphPainter.Family family, int firstCodePoint, int lastCodePoint) {
    final int bitmapWidth = 56;
    final int bitmapHeight = 72;
    final int left = 18;
    final int top = 16;
    final int right = 38;
    final int bottom = 56;
    for (int codePoint = firstCodePoint; codePoint <= lastCodePoint; codePoint++) {
      Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(0);
      try {
        TerminalSpecialGlyphPainter painter = new TerminalSpecialGlyphPainter();
        boolean drawn = painter.drawCodePointWithFamily(
            new Canvas(bitmap), codePoint, family, left, top, right, bottom,
            0xFFFFFFFF, 0, 0, 0, right - left, false);
        if (!drawn) {
          throw new AssertionError("glyph was not drawn: U+" + Integer.toHexString(codePoint));
        }
        for (int y = 0; y < bitmapHeight; y++) {
          for (int x = 0; x < bitmapWidth; x++) {
            if (x >= left && x < right && y >= top && y < bottom) continue;
            if ((bitmap.getPixel(x, y) >>> 24) != 0) {
              throw new AssertionError("glyph escaped cell at U+"
                  + Integer.toHexString(codePoint) + " pixel=" + x + "," + y);
            }
          }
        }
      } finally {
        bitmap.recycle();
      }
    }
  }
}
