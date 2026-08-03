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

/** 特殊终端字符的服务端 grapheme 分类和 fallback 契约。 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class TerminalSpecialGlyphPainterTest {
  @Test
  public void classifiesCompleteSpecialRangesAndPowerlineAllowlist() {
    for (int codePoint = 0x2500; codePoint <= 0x257F; codePoint++) {
      assertEquals(TerminalSpecialGlyphPainter.Family.BOX_DRAWING,
          TerminalSpecialGlyphPainter.familyForCodePoint(codePoint));
    }
    for (int codePoint = 0x2580; codePoint <= 0x259F; codePoint++) {
      assertEquals(TerminalSpecialGlyphPainter.Family.BLOCK_ELEMENTS,
          TerminalSpecialGlyphPainter.familyForCodePoint(codePoint));
    }
    for (int codePoint = 0x2800; codePoint <= 0x28FF; codePoint++) {
      assertEquals(TerminalSpecialGlyphPainter.Family.BRAILLE,
          TerminalSpecialGlyphPainter.familyForCodePoint(codePoint));
    }
    for (int codePoint : new int[] {0xE0B0, 0xE0B2, 0xE0B4, 0xE0B6}) {
      assertEquals(TerminalSpecialGlyphPainter.Family.POWERLINE,
          TerminalSpecialGlyphPainter.familyForCodePoint(codePoint));
    }
    assertEquals(TerminalSpecialGlyphPainter.Family.NONE,
        TerminalSpecialGlyphPainter.familyForCodePoint(0xE0B1));
    assertEquals(TerminalSpecialGlyphPainter.Family.NONE,
        TerminalSpecialGlyphPainter.familyForCodePoint(0xE0A0));
  }

  @Test
  public void rejectsMultiCodepointAndEmptyGraphemes() {
    assertEquals(-1, TerminalSpecialGlyphPainter.singleCodePoint(null));
    assertEquals(-1, TerminalSpecialGlyphPainter.singleCodePoint(""));
    assertEquals(-1, TerminalSpecialGlyphPainter.singleCodePoint("\u2764\uFE0F"));
    assertEquals(-1, TerminalSpecialGlyphPainter.singleCodePoint("👨‍👩‍👧‍👦"));
    assertEquals(TerminalSpecialGlyphPainter.Family.NONE,
        TerminalSpecialGlyphPainter.familyFor("❤️"));
    assertEquals(TerminalSpecialGlyphPainter.Family.NONE,
        TerminalSpecialGlyphPainter.familyFor("😀"));
  }

  @Test
  public void unsupportedPrivateUseCharactersRemainFontFallback() {
    assertEquals(TerminalSpecialGlyphPainter.Family.NONE,
        TerminalSpecialGlyphPainter.familyFor("\uE0A0"));
    assertEquals(TerminalSpecialGlyphPainter.Family.NONE,
        TerminalSpecialGlyphPainter.familyFor("\uE0B1"));
    assertEquals(TerminalSpecialGlyphPainter.Family.NONE,
        TerminalSpecialGlyphPainter.familyFor("\uE0D7"));
  }

  @Test
  public void supportedGraphemesUseTheUnifiedPainterAndComplexGraphemesFallback() {
    TerminalSpecialGlyphPainter painter = new TerminalSpecialGlyphPainter();
    for (String grapheme : new String[] {"─", "█", "⠁", "\uE0B0", "\u2800"}) {
      org.junit.Assert.assertTrue(grapheme, painter.supports(grapheme));
    }
    org.junit.Assert.assertFalse(painter.supports("❤️"));
    org.junit.Assert.assertFalse(painter.supports("👨‍👩‍👧‍👦"));
  }

  @Test
  public void unifiedDispatchClipsSpecialGlyphToTheTargetCell() {
    Bitmap bitmap = Bitmap.createBitmap(48, 24, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xFF000000);
    TerminalSpecialGlyphPainter painter = new TerminalSpecialGlyphPainter();
    assertTrue(painter.drawIfSupported(new Canvas(bitmap), "╳",
        8, 2, 40, 22, 0xFFFFFFFF));
    assertEquals(0, countInk(bitmap, 0, 8, 0, 24));
    assertEquals(0, countInk(bitmap, 40, 48, 0, 24));
    assertTrue(countInk(bitmap, 8, 40, 2, 22) > 0);
    bitmap.recycle();
  }

  private static int countInk(Bitmap bitmap, int left, int right, int top, int bottom) {
    int count = 0;
    for (int y = top; y < bottom; y++) {
      for (int x = left; x < right; x++) {
        if (bitmap.getPixel(x, y) != 0xFF000000) count++;
      }
    }
    return count;
  }
}
