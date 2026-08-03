package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalPalette;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;

public final class TerminalStyleResolverTest {
  private static final int BOLD = 1 << 0;
  private static final int DIM = 1 << 1;
  private static final int UNDERLINE = 1 << 3;
  private static final int DOUBLE_UNDERLINE = 1 << 4;
  private static final int CURLY_UNDERLINE = 1 << 5;
  private static final int DOTTED_UNDERLINE = 1 << 6;
  private static final int DASHED_UNDERLINE = 1 << 7;
  private static final int BLINK_SLOW = 1 << 8;
  private static final int BLINK_FAST = 1 << 9;
  private static final int REVERSE = 1 << 10;
  private static final int HIDDEN = 1 << 11;
  private static final int STRIKE = 1 << 12;

  private TerminalStyleResolver resolver;
  private ResolvedTerminalStyle resolved;

  @Before
  public void setUp() {
    resolver = new TerminalStyleResolver();
    resolved = new ResolvedTerminalStyle();
  }

  @Test
  public void defaultStyleUsesPaletteDefaults() {
    TerminalPalette palette = palette();
    resolver.resolveInto(palette, null, false, resolved);

    assertEquals(0xFF112233, resolved.foreground);
    assertEquals(0xFF445566, resolved.background);
    assertEquals(resolved.foreground, resolved.underlineColor);
    assertFalse(resolved.bold);
    assertEquals(ResolvedTerminalStyle.UnderlineKind.NONE, resolved.underlineKind);
  }

  @Test
  public void boldRaisesOnlyLowIndexedColors() {
    TerminalPalette palette = palette();
    resolver.resolveInto(palette,
        style(TerminalColor.indexed(1), TerminalColor.DEFAULT_BG, BOLD), false, resolved);
    assertEquals(RemoteTerminalRenderer.resolveColor(palette, TerminalColor.indexed(9)),
        resolved.foreground);
    assertTrue(resolved.bold);

    resolver.resolveInto(palette,
        style(TerminalColor.rgb(0x123456), TerminalColor.DEFAULT_BG, BOLD), false, resolved);
    assertEquals(0xFF123456, resolved.foreground);
  }

  @Test
  public void dimAppliesAfterBoldBrightResolution() {
    TerminalPalette palette = palette();
    resolver.resolveInto(palette,
        style(TerminalColor.indexed(1), TerminalColor.DEFAULT_BG, BOLD | DIM), false, resolved);
    assertEquals(TerminalVisualRules.dim(
        RemoteTerminalRenderer.resolveColor(palette, TerminalColor.indexed(9))),
        resolved.foreground);
  }

  @Test
  public void blinkDoesNotBecomeBoldOrBright() {
    TerminalPalette palette = palette();
    resolver.resolveInto(palette,
        style(TerminalColor.indexed(1), TerminalColor.DEFAULT_BG, BLINK_SLOW), false, resolved);
    assertFalse(resolved.bold);
    assertTrue(resolved.blinkSlow);
    assertEquals(RemoteTerminalRenderer.resolveColor(palette, TerminalColor.indexed(1)),
        resolved.foreground);

    resolver.resolveInto(palette,
        style(TerminalColor.indexed(1), TerminalColor.DEFAULT_BG, BLINK_FAST), false, resolved);
    assertFalse(resolved.bold);
    assertTrue(resolved.blinkFast);
    assertEquals(RemoteTerminalRenderer.resolveColor(palette, TerminalColor.indexed(1)),
        resolved.foreground);
  }

  @Test
  public void reverseAndBlockCursorInvertTheFinalColorPair() {
    TerminalPalette palette = palette();
    StyleValue source = style(TerminalColor.rgb(0xAA0000), TerminalColor.rgb(0x0000AA), 0);
    resolver.resolveInto(palette, source, false, resolved);
    assertEquals(0xFFAA0000, resolved.foreground);
    assertEquals(0xFF0000AA, resolved.background);

    resolver.resolveInto(palette, source, true, resolved);
    assertEquals(0xFF0000AA, resolved.foreground);
    assertEquals(0xFFAA0000, resolved.background);

    resolver.resolveInto(palette,
        style(source.fg(), source.bg(), REVERSE), false, resolved);
    assertEquals(0xFF0000AA, resolved.foreground);
    assertEquals(0xFFAA0000, resolved.background);
  }

  @Test
  public void hiddenKeepsBackgroundAndStillExposesSemanticDecorationFlags() {
    resolver.resolveInto(palette(), style(
        TerminalColor.rgb(0xFFFFFF), TerminalColor.rgb(0x123456),
        HIDDEN | UNDERLINE | STRIKE), false, resolved);

    assertTrue(resolved.hidden);
    assertTrue(resolved.strike);
    assertEquals(0xFF123456, resolved.background);
    assertEquals(ResolvedTerminalStyle.UnderlineKind.SINGLE, resolved.underlineKind);
  }

  @Test
  public void underlineColorIsIndependentFromFinalForeground() {
    resolver.resolveInto(palette(), new StyleValue(
        TerminalColor.rgb(0xFFFFFF), TerminalColor.DEFAULT_BG,
        TerminalColor.rgb(0x00FF00), UNDERLINE | STRIKE), false, resolved);

    assertEquals(0xFFFFFFFF, resolved.foreground);
    assertEquals(0xFF00FF00, resolved.underlineColor);
    assertTrue(resolved.strike);
  }

  @Test
  public void extendedUnderlineFlagsUseStablePriority() {
    int all = UNDERLINE | DOUBLE_UNDERLINE | CURLY_UNDERLINE
        | DOTTED_UNDERLINE | DASHED_UNDERLINE;
    resolver.resolveInto(palette(), style(null, null, all), false, resolved);
    assertEquals(ResolvedTerminalStyle.UnderlineKind.DASHED, resolved.underlineKind);

    resolver.resolveInto(palette(), style(null, null,
        UNDERLINE | DOUBLE_UNDERLINE | CURLY_UNDERLINE | DOTTED_UNDERLINE), false, resolved);
    assertEquals(ResolvedTerminalStyle.UnderlineKind.DOTTED, resolved.underlineKind);

    resolver.resolveInto(palette(), style(null, null,
        UNDERLINE | DOUBLE_UNDERLINE | CURLY_UNDERLINE), false, resolved);
    assertEquals(ResolvedTerminalStyle.UnderlineKind.CURLY, resolved.underlineKind);
  }

  private static TerminalPalette palette() {
    return new TerminalPalette(
        TerminalColor.rgb(0x112233), TerminalColor.rgb(0x445566),
        TerminalColor.rgb(0x778899), false,
        Map.of(1, 0x010203, 9, 0xA0B0C0), 1L);
  }

  private static StyleValue style(TerminalColor foreground, TerminalColor background, int attrs) {
    return new StyleValue(foreground, background, null, attrs);
  }
}
