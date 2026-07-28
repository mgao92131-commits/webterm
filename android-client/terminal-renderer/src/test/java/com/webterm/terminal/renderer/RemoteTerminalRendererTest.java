package com.webterm.terminal.renderer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

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
import com.webterm.terminal.model.TerminalStyle;
import com.webterm.terminal.model.TerminalViewportState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

  @Test public void topInsetQuantizesFractionalAscentToWholePixels() {
    // lineHeight 已是 ceil 后的整数；baselineOffset 来自 -Paint.ascent()，常为小数。
    // 若不量化 topInset，整列行顶会落在亚像素 Y，硬件加速行级 RenderNode 会透出暗缝。
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 42f, 33.75f);
    assertEquals(8.25f, 42f - 33.75f, 0f); // 量化前的原始留白
    assertEquals(8f, renderer.getTopInset(), 0f);

    renderer.setFontMetrics(10f, 42f, 33.4f);
    assertEquals(8.6f, 42f - 33.4f, 0.0001f);
    assertEquals(9f, renderer.getTopInset(), 0f);
  }

  @Test public void pixelAlignedGridKeepsIntegerRowBoundaries() {
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    float lineHeight = 42f;
    renderer.setFontMetrics(10f, lineHeight, 33.75f); // raw inset 8.25 → aligned 8
    float topInset = renderer.getTopInset();
    float screenTop = RemoteTerminalRenderer.screenTopY(800, 0, 20, lineHeight, topInset, 0f);
    assertEquals(8f, screenTop, 0f);
    for (int row = 0; row < 20; row++) {
      float rowTop = screenTop + row * lineHeight;
      float rowBottom = rowTop + lineHeight;
      assertEquals("row " + row + " top must be whole pixels",
          (float) Math.round(rowTop), rowTop, 0f);
      assertEquals("row " + row + " bottom must be whole pixels",
          (float) Math.round(rowBottom), rowBottom, 0f);
    }
    // 带历史滚动时，整数 scrollOffset 仍保持整像素网格。
    float scrolledTop = RemoteTerminalRenderer.contentTopY(
        800, 50, 20, lineHeight, topInset, 300f);
    for (int row = 0; row < 70; row++) {
      float y = scrolledTop + row * lineHeight;
      assertEquals("scrolled row " + row + " must stay pixel-aligned",
          (float) Math.round(y), y, 0f);
    }
  }

  @Test public void coloredBackgroundRectsAbutOnPixelAlignedGrid() {
    // 连续彩色背景是行间暗缝最易暴露的场景。Robolectric 不一定光栅化 drawRect，
    // 因此记录矩形几何：相邻行背景必须在整像素边界无缝衔接。
    RemoteTerminalModel model = new RemoteTerminalModel();
    int cols = 4;
    int rows = 6;
    TerminalColor green = TerminalColor.rgb(0x00AA00);
    TerminalCell greenCell = new TerminalCell("G", (byte) 1,
        new TerminalStyle(1, TerminalColor.DEFAULT_FG, green, null, 0), null);
    List<TerminalLine> screen = new ArrayList<>();
    for (int i = 0; i < rows; i++) {
      TerminalCell[] cells = new TerminalCell[cols];
      Arrays.fill(cells, greenCell);
      screen.add(new TerminalLine(1000L + i, 1, 0, false, cells));
    }
    model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, com.webterm.terminal.model.DictionaryEntries.EMPTY,
        rows, cols, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults()));
    model.consumeRenderUpdate();

    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    float lineHeight = 42f;
    float cellWidth = 10f;
    renderer.setFontMetrics(cellWidth, lineHeight, 33.75f); // topInset → 8
    int width = (int) (cols * cellWidth);
    int height = (int) (rows * lineHeight + renderer.getTopInset() + 2);
    BgRectRecordingCanvas canvas = new BgRectRecordingCanvas(width, height);
    renderer.render(canvas, model.renderSnapshot(), new TerminalViewportState(), true);

    int expectedGreen = RemoteTerminalRenderer.resolveColor(TerminalPalette.defaults(), green);
    float topInset = renderer.getTopInset();
    assertEquals(8f, topInset, 0f);
    assertTrue("expected background rects for green rows", canvas.bgTops.size() >= rows);
    for (int row = 0; row < rows; row++) {
      float expectedTop = topInset + row * lineHeight;
      float expectedBottom = expectedTop + lineHeight;
      assertTrue("missing bg rect for row " + row + " tops=" + canvas.bgTops,
          canvas.bgTops.contains(expectedTop));
      assertTrue("missing bg bottom for row " + row,
          canvas.bgBottoms.contains(expectedBottom));
      assertEquals((float) Math.round(expectedTop), expectedTop, 0f);
      assertEquals((float) Math.round(expectedBottom), expectedBottom, 0f);
    }
    assertTrue(canvas.bgColors.contains(expectedGreen));
  }

  /** 记录背景矩形几何，用于验证行间无亚像素缝隙。 */
  private static final class BgRectRecordingCanvas extends Canvas {
    final List<Float> bgTops = new ArrayList<>();
    final List<Float> bgBottoms = new ArrayList<>();
    final List<Integer> bgColors = new ArrayList<>();

    BgRectRecordingCanvas(int width, int height) {
      super(Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888));
    }

    @Override
    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
      bgTops.add(top);
      bgBottoms.add(bottom);
      bgColors.add(paint.getColor());
    }

    @Override
    public void drawColor(int color) {
    }
  }

  @Test public void liveScreenExitBoundaryUsesExactUsableViewportPixels() {
    int threshold = RemoteTerminalRenderer.liveScreenExitOffsetPixels(721, 1.25f);
    assertEquals(720, threshold);

    assertFalse(threshold - 1 >= threshold);
    assertTrue(threshold >= threshold);
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
        "s1", "i1", 1, 1, 1, 1, com.webterm.terminal.model.DictionaryEntries.EMPTY, 1, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 300),
        Collections.singletonList(screen), TerminalCursor.hidden(), TerminalModes.defaults(),
        TerminalPalette.defaults()));
    model.consumeRenderUpdate();

    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.scrollBy(6_000, 6_000, model.renderSnapshot(), 20f);
    // 视口位于全是 UNLOADED 占位的历史头部。
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

  @Test public void historyDoesNotBleedIntoTopInsetWhenFollowingTail() {
    // 5 行红色背景历史 + 1 行默认背景 screen。followTail 时 screenTopY == topInset，
    // 历史绘制裁剪到 [topInset, screenTopY) 后应为空，topInset 留白必须保持默认背景。
    RemoteTerminalModel model = new RemoteTerminalModel();
    int cols = 1;
    TerminalColor red = TerminalColor.rgb(0xFF0000);
    TerminalCell redCell = new TerminalCell(" ", (byte) 1,
        new TerminalStyle(1, TerminalColor.DEFAULT_FG, red, null, 0), null);
    List<TerminalLine> history = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      TerminalCell[] cells = new TerminalCell[] { redCell };
      history.add(new TerminalLine(1000L + i, 1, i + 1L, false, cells));
    }
    model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, com.webterm.terminal.model.DictionaryEntries.EMPTY, 1, cols, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 5),
        Collections.singletonList(TerminalLine.empty(2000, cols)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults()));
    model.consumeRenderUpdate();

    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f); // topInset = 20 - 15 = 5
    TerminalViewportState viewport = new TerminalViewportState();

    Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
    int defaultBg = RemoteTerminalRenderer.resolveColor(TerminalPalette.defaults(),
        TerminalColor.DEFAULT_BG);
    bitmap.eraseColor(defaultBg);
    Canvas canvas = new Canvas(bitmap);
    renderer.render(canvas, model.renderSnapshot(), viewport, true);

    int topInset = 5;
    for (int y = 0; y < topInset; y++) {
      for (int x = 0; x < cols * 10; x += 2) {
        assertEquals("history must not bleed into topInset at y=" + y,
            defaultBg, bitmap.getPixel(x, y));
      }
    }
    // 作为对照，第一行 screen 下方区域应为默认背景（screen 行没有自定义背景）。
    int screenRowTop = topInset;
    assertEquals(defaultBg, bitmap.getPixel(5, screenRowTop + 2));
  }
}
