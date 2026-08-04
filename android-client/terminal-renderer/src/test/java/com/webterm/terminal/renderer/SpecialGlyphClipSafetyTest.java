package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Clip policy is deliberately conservative for stroked/edge-extending families. */
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
}
