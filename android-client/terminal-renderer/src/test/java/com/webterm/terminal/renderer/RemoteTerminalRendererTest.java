package com.webterm.terminal.renderer;

import com.webterm.terminal.model.TerminalColor;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RemoteTerminalRendererTest {
  @Test public void resolvesAnsiIndexedAndColorCube() {
    assertEquals(0xFFCD0000, RemoteTerminalRenderer.resolveColor(TerminalColor.indexed(1)));
    assertEquals(0xFFFF0000, RemoteTerminalRenderer.resolveColor(TerminalColor.indexed(196)));
    assertEquals(0xFF080808, RemoteTerminalRenderer.resolveColor(TerminalColor.indexed(232)));
    // 旧 Termux 默认蓝，不是通用 ANSI 的 0x0000ee。
    assertEquals(0xFF6495ED, RemoteTerminalRenderer.resolveColor(TerminalColor.indexed(4)));
  }

  @Test public void sharesLegacyDimAndGlyphAspectRules() {
    assertEquals(0xFF884400, TerminalVisualRules.dim(0xFFCC6600));
    assertTrue(TerminalVisualRules.shouldPreserveGlyphAspect(0xE0B0, 1, false));
    assertFalse(TerminalVisualRules.shouldPreserveGlyphAspect('A', 1, true));
  }

  @Test public void sharedGeometryKeepsScreenAtViewportBottomWithHistory() {
    // 100 history rows must live above the viewport; the active 30-row screen
    // remains visible at the bottom when following output.
    assertEquals(0f, RemoteTerminalRenderer.screenTopY(600, 100, 30, 20f, 0f), 0.001f);
    assertEquals(-2000f, RemoteTerminalRenderer.contentTopY(600, 100, 30, 20f, 0f), 0.001f);
  }

  @Test public void termuxFontInsetAnchorsFirstScreenRowAndPtyRows() {
    // Old TerminalView reserves lineSpacing + ascent above row zero. A 600px
    // viewport with 17px cells and a 4px inset therefore starts at y=4, not
    // at the canvas edge or an arbitrary bottom-aligned offset.
    assertEquals(4f, RemoteTerminalRenderer.screenTopY(600, 0, 35, 17f, 4f, 0f), 0.001f);
    assertEquals(4f, RemoteTerminalRenderer.contentTopY(600, 0, 35, 17f, 4f, 0f), 0.001f);
  }

  @Test public void selectionHighlightIsTranslucent() {
    // Selection must tint an already-rendered glyph rather than replace its
    // foreground/background colors with an opaque reverse-video cell.
    assertEquals(0x66, (RemoteTerminalRenderer.SELECTION_OVERLAY >>> 24) & 0xFF);
  }
}
