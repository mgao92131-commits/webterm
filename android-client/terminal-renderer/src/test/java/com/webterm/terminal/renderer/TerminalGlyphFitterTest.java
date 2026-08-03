package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TerminalGlyphFitterTest {
  @Test
  public void presentationModeUsesWholeGraphemeAndServerWidth() {
    assertEquals(TerminalGlyphFitter.ClusterFitMode.GRID_START,
        TerminalGlyphFitter.fitMode(TerminalFontRole.MAIN_TEXT));
    assertEquals(TerminalGlyphFitter.ClusterFitMode.GRID_START,
        TerminalGlyphFitter.fitMode(TerminalFontRole.MAIN_TEXT));
    assertEquals(TerminalGlyphFitter.ClusterFitMode.CENTERED,
        TerminalGlyphFitter.fitMode(TerminalFontRole.EMOJI));
    assertEquals(TerminalGlyphFitter.ClusterFitMode.CENTERED,
        TerminalGlyphFitter.fitMode(TerminalFontRole.EMOJI));
  }

  @Test
  public void fittingNeverUpscalesAndOnlyShrinksUniformly() {
    TerminalGlyphFitter fitter = new TerminalGlyphFitter();
    TerminalGlyphFitter.FitResult result = new TerminalGlyphFitter.FitResult();

    fitter.fit(result, 5f, 10f, 20f, 30f,
        TerminalGlyphFitter.ClusterFitMode.CENTERED);
    assertEquals(1f, result.scale, 0.0001f);
    assertEquals(22.5f, result.drawX, 0.0001f);

    fitter.fit(result, 20f, 10f, 20f, 30f,
        TerminalGlyphFitter.ClusterFitMode.GRID_START);
    assertEquals(0.5f, result.scale, 0.0001f);
    assertEquals(20f, result.drawX, 0.0001f);
    assertTrue(result.scale <= 1f);
  }
}
