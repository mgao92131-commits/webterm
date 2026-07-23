package com.webterm.terminal.renderer;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class RemoteTerminalRendererTest {
  @Test public void resolvesAnsiIndexedAndColorCube() {
    assertEquals(0xFFCD0000, RemoteTerminalRenderer.resolveColor(TerminalColor.indexed(1)));
    assertEquals(0xFFFF0000, RemoteTerminalRenderer.resolveColor(TerminalColor.indexed(196)));
    assertEquals(0xFF080808, RemoteTerminalRenderer.resolveColor(TerminalColor.indexed(232)));
    // 旧 Termux 默认蓝，不是通用 ANSI 的 0x0000ee。
    assertEquals(0xFF6495ED, RemoteTerminalRenderer.resolveColor(TerminalColor.indexed(4)));
  }

  @Test public void dynamicPaletteOverridesIndexedAndSemanticColors() {
    TerminalPalette palette = new TerminalPalette(
        TerminalColor.rgb(0x112233), TerminalColor.rgb(0x223344),
        TerminalColor.rgb(0x334455), false,
        Collections.singletonMap(42, 0x010203), 7L);
    assertEquals(0xFF010203,
        RemoteTerminalRenderer.resolveColor(palette, TerminalColor.indexed(42)));
    assertEquals(0xFF112233,
        RemoteTerminalRenderer.resolveColor(palette, TerminalColor.DEFAULT_FG));
    assertEquals(0xFF223344,
        RemoteTerminalRenderer.resolveColor(palette, TerminalColor.DEFAULT_BG));
    assertEquals(0xFF334455,
        RemoteTerminalRenderer.resolveColor(palette, TerminalColor.CURSOR));
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

  @Test public void liveScreenExitBoundaryUsesExactUsableViewportPixels() {
    int threshold = RemoteTerminalRenderer.liveScreenExitOffsetPixels(721, 1.25f);
    assertEquals(720, threshold);

    TerminalViewportState viewport = new TerminalViewportState();
    viewport.scrollBy(threshold - 1, 2_000);
    assertFalse(viewport.isPureHistory(threshold));
    viewport.scrollBy(1, 2_000);
    assertTrue(viewport.isPureHistory(threshold));
  }

  @Test public void hardTopAnchorsFirstHistoryRowInsideViewport() {
    // A 680px viewport with 20px cells and a 4px inset leaves a 16px remainder
    // (676 usable = 33 rows + 16). The old bottom-anchored bound stopped the
    // first history row at y=-12, clipped by the view edge; the top bound must
    // be historyRows * lineHeight so the row lands exactly at topInset.
    assertEquals(4f, RemoteTerminalRenderer.contentTopY(680, 100, 33, 20f, 4f, 2000f), 0.001f);
    assertEquals(4f, RemoteTerminalRenderer.contentTopY(680, 100, 33, 20f, 4f, 999999f), 0.001f);
    assertEquals(2004f, RemoteTerminalRenderer.screenTopY(680, 100, 33, 20f, 4f, 999999f), 0.001f);
    // Content shorter than the viewport still cannot scroll at all.
    assertEquals(-36f, RemoteTerminalRenderer.contentTopY(680, 2, 30, 20f, 4f, 500f), 0.001f);
  }

  @Test public void selectionHighlightIsTranslucent() {
    // Selection must tint an already-rendered glyph rather than replace its
    // foreground/background colors with an opaque reverse-video cell.
    assertEquals(0x66, (RemoteTerminalRenderer.SELECTION_OVERLAY >>> 24) & 0xFF);
  }

  @Test public void requestsOlderHistoryOnlyWhenPushingPastTheHardTop() {
    assertFalse(RemoteTerminalView.shouldRequestOlderHistory(-80, 600, 600, false));
    assertFalse(RemoteTerminalView.shouldRequestOlderHistory(80, 520, 600, false));
    assertFalse(RemoteTerminalView.shouldRequestOlderHistory(80, 600, 600, true));
    assertTrue(RemoteTerminalView.shouldRequestOlderHistory(80, 600, 600, false));
  }

  @Test public void prependingHistoryKeepsExistingLinesAtSameScreenY() {
    // In the bottom-anchored geometry a prepended page grows historyRows and
    // every old row's index by the same amount. With the viewport offset
    // untouched, an existing history line must keep its exact screen Y.
    float topInset = 4f;
    float lineHeight = 20f;
    float offset = 300f; // below the 100-row hard top, so no clamping applies
    int lineIndex = 17;
    float before = RemoteTerminalRenderer.contentTopY(680, 100, 33, lineHeight, topInset, offset)
        + lineIndex * lineHeight;
    float after = RemoteTerminalRenderer.contentTopY(680, 350, 33, lineHeight, topInset, offset)
        + (lineIndex + 250) * lineHeight;
    assertEquals(before, after, 0.001f);
  }

  @Test public void clipRowRangeVisitsOnlyRowsIntersectingDirtyRect() {
    int[] range = RemoteTerminalRenderer.rowRangeIntersecting(45, 65, 4f, 20f, 40);
    // Includes one guard row on both sides for anti-aliased glyph edges, not all 40 rows.
    assertEquals(1, range[0]);
    assertEquals(5, range[1]);
  }

  @Test public void sparseHistoryPlaceholdersRenderWithoutDereferencingNullLine() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalLine historyTail = new TerminalLine(
        300, 1, 300, false, new TerminalCell[] {TerminalCell.EMPTY});
    TerminalLine screen = TerminalLine.empty(1000, 1);
    model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 300), Collections.singletonList(historyTail),
        Collections.singletonList(screen), TerminalCursor.hidden(), TerminalModes.defaults(),
        TerminalPalette.defaults(), "", ""));
    model.consumeRenderUpdate();

    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.scrollBy(6_000, 6_000); // 视口位于全是 UNLOADED 占位的历史头部。
    Canvas canvas = new Canvas(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888));
    renderer.render(canvas, model.renderSnapshot(), viewport, true);
  }

  @Test public void blinkingCursorVisibilityComesFromViewRenderState() {
    TerminalViewportState viewport = new TerminalViewportState();
    TerminalCursor blinking =
        new TerminalCursor(0, 0, true, TerminalCursor.Shape.BLOCK, true);
    assertTrue(RemoteTerminalRenderer.shouldDrawCursor(viewport, blinking, true));
    assertFalse(RemoteTerminalRenderer.shouldDrawCursor(viewport, blinking, false));

    TerminalCursor steady =
        new TerminalCursor(0, 0, true, TerminalCursor.Shape.BLOCK, false);
    assertTrue(RemoteTerminalRenderer.shouldDrawCursor(viewport, steady, false));
  }


  @Test public void inputTypeDisablesImeTextMutation() {
    int type = RemoteTerminalView.TERMINAL_INPUT_TYPE;
    assertEquals(android.text.InputType.TYPE_CLASS_TEXT,
        type & android.text.InputType.TYPE_MASK_CLASS);
    assertEquals(0, type & android.text.InputType.TYPE_MASK_VARIATION);
    assertTrue((type & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0);
    // No-capitalization is expressed by the absence of every CAP_* flag bit.
    int capFlags = android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    assertEquals(0, type & capFlags);
    assertEquals(0, type & android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
  }
}
