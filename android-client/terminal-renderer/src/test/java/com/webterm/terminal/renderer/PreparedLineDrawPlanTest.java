package com.webterm.terminal.renderer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalPalette;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PreparedLineDrawPlanTest {
  private static final int BACKGROUND = 0xFF000000;

  @Test
  public void preparedPlanPreservesSpanBoundsAndClassifiesOperations() {
    CellValue[] cells = new CellValue[] {
        new CellValue("A", (byte) 1, null, null),
        new CellValue("─", (byte) 1, null, null),
        new CellValue("B", (byte) 1, null, null),
        new CellValue("C", (byte) 1,
            new com.webterm.terminal.model.StyleValue(
                com.webterm.terminal.model.TerminalColor.rgb(0xFFFFFF),
                com.webterm.terminal.model.TerminalColor.rgb(0x223344), null, 1 << 8), null)
    };
    RenderLine line = new RenderLine(
        new LineKey(1L, 1L), new LineBody(4, false, cells));
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f);
    CompiledTerminalLine compiled = renderer.compileLine(
        line, 4, TerminalPalette.defaults(), BACKGROUND);
    TerminalCellGeometry geometry = new TerminalCellGeometry();
    geometry.update(10f, 20f, 15f);
    PreparedLineDrawPlan plan = PreparedLineDrawPlan.build(compiled, geometry, BACKGROUND);

    assertArrayEquals(new int[] {0, 10, 20, 30}, plan.spanLeftPx);
    assertArrayEquals(new int[] {10, 20, 30, 40}, plan.spanRightPx);
    assertEquals(compiled.spans().size(), plan.staticForegroundSpanIndexes.length
        + plan.slowBlinkSpanIndexes.length + plan.fastBlinkSpanIndexes.length);
    assertEquals(1, plan.staticSpecialGlyphRuns.length);
    assertEquals(1, plan.backgroundStartPx.length);
    assertEquals(30, plan.backgroundStartPx[0]);
    assertEquals(40, plan.backgroundEndPx[0]);
    assertEquals(0, plan.staticDecorationRuns.length);
  }

  @Test
  public void adjacentBackgroundSpansShareOnePhysicalRun() {
    com.webterm.terminal.model.StyleValue style = new com.webterm.terminal.model.StyleValue(
        com.webterm.terminal.model.TerminalColor.rgb(0xFFFFFF),
        com.webterm.terminal.model.TerminalColor.rgb(0x223344), null, 0);
    CellValue[] cells = new CellValue[] {
        new CellValue("A", (byte) 1, style, null),
        new CellValue("─", (byte) 1, style, null),
        new CellValue("B", (byte) 1, style, null)
    };
    RenderLine line = new RenderLine(new LineKey(2L, 1L), new LineBody(3, false, cells));
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f);
    CompiledTerminalLine compiled = renderer.compileLine(
        line, 3, TerminalPalette.defaults(), BACKGROUND);
    TerminalCellGeometry geometry = new TerminalCellGeometry();
    geometry.update(10f, 20f, 15f);
    PreparedLineDrawPlan plan = PreparedLineDrawPlan.build(compiled, geometry, BACKGROUND);

    assertEquals(1, plan.backgroundStartPx.length);
    assertEquals(0, plan.backgroundStartPx[0]);
    assertEquals(30, plan.backgroundEndPx[0]);
  }

  @Test
  public void preparedSpecialRunsUseTheFamilyClipPolicy() {
    CellValue[] cells = new CellValue[] {
        new CellValue("▀", (byte) 1, null, null),
        new CellValue("▄", (byte) 1, null, null),
        new CellValue("─", (byte) 1, null, null)
    };
    RenderLine line = new RenderLine(
        new LineKey(3L, 1L), new LineBody(3, false, cells));
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f);
    CompiledTerminalLine compiled = renderer.compileLine(
        line, 3, TerminalPalette.defaults(), BACKGROUND);
    TerminalCellGeometry geometry = new TerminalCellGeometry();
    geometry.update(10f, 20f, 15f);
    PreparedLineDrawPlan plan = PreparedLineDrawPlan.build(compiled, geometry, BACKGROUND);

    assertEquals(2, plan.staticSpecialGlyphRuns.length);
    assertEquals(TerminalSpecialGlyphPainter.ClipPolicy.RUN_CLIP_SAFE,
        plan.staticSpecialGlyphRuns[0].clipPolicy);
    assertEquals(2, plan.staticSpecialGlyphRuns[0].glyphCount());
    assertEquals(TerminalSpecialGlyphPainter.ClipPolicy.CELL_CLIP_REQUIRED,
        plan.staticSpecialGlyphRuns[1].clipPolicy);
    assertEquals(1, plan.staticSpecialGlyphRuns[1].glyphCount());
  }

  @Test
  public void sameBackgroundSeparatedByDefaultBlankMustNotMerge() {
    com.webterm.terminal.model.StyleValue style = new com.webterm.terminal.model.StyleValue(
        com.webterm.terminal.model.TerminalColor.rgb(0xFFFFFF),
        com.webterm.terminal.model.TerminalColor.rgb(0x223344), null, 0);
    RenderLine line = new RenderLine(new LineKey(4L, 1L), new LineBody(3, false,
        new CellValue[] {
            new CellValue("A", (byte) 1, style, null),
            CellValue.EMPTY,
            new CellValue("B", (byte) 1, style, null)
        }));
    PreparedLineDrawPlan plan = buildPlan(line, 3);

    assertEquals(2, plan.backgroundStartPx.length);
    assertEquals(0, plan.backgroundStartPx[0]);
    assertEquals(10, plan.backgroundEndPx[0]);
    assertEquals(20, plan.backgroundStartPx[1]);
    assertEquals(30, plan.backgroundEndPx[1]);
  }

  @Test
  public void sameUnderlineSeparatedByDefaultBlankMustNotMerge() {
    com.webterm.terminal.model.StyleValue style = new com.webterm.terminal.model.StyleValue(
        com.webterm.terminal.model.TerminalColor.rgb(0xFFFFFF), null, null, 1 << 3);
    RenderLine line = new RenderLine(new LineKey(5L, 1L), new LineBody(3, false,
        new CellValue[] {
            new CellValue("A", (byte) 1, style, null),
            CellValue.EMPTY,
            new CellValue("B", (byte) 1, style, null)
        }));
    PreparedLineDrawPlan plan = buildPlan(line, 3);

    assertEquals(2, plan.staticDecorationRuns.length);
    assertEquals(0, plan.staticDecorationRuns[0].leftPx);
    assertEquals(10, plan.staticDecorationRuns[0].rightPx);
    assertEquals(20, plan.staticDecorationRuns[1].leftPx);
    assertEquals(30, plan.staticDecorationRuns[1].rightPx);
  }

  @Test
  public void styledBlankAllowsContinuousDecoration() {
    com.webterm.terminal.model.StyleValue style = new com.webterm.terminal.model.StyleValue(
        com.webterm.terminal.model.TerminalColor.rgb(0xFFFFFF), null, null, 1 << 3);
    RenderLine line = new RenderLine(new LineKey(6L, 1L), new LineBody(3, false,
        new CellValue[] {
            new CellValue("A", (byte) 1, style, null),
            new CellValue(" ", (byte) 1, style, null),
            new CellValue("B", (byte) 1, style, null)
        }));
    PreparedLineDrawPlan plan = buildPlan(line, 3);

    assertEquals(1, plan.staticDecorationRuns.length);
    assertEquals(0, plan.staticDecorationRuns[0].leftPx);
    assertEquals(30, plan.staticDecorationRuns[0].rightPx);
  }

  @Test
  public void preparedSpecialGlyphEstimateCountsFourIntArrays() {
    RenderLine line = new RenderLine(new LineKey(7L, 1L), new LineBody(2, false,
        new CellValue[] {
            new CellValue("▀", (byte) 1, null, null),
            new CellValue("▄", (byte) 1, null, null)
        }));
    PreparedLineDrawPlan plan = buildPlan(line, 2);

    // 80-byte run header + 4 int arrays + one byte family entry per glyph.
    assertEquals(114L, plan.staticSpecialGlyphRuns[0].estimatedBytes());
  }

  private static PreparedLineDrawPlan buildPlan(RenderLine line, int columns) {
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f);
    return PreparedLineDrawPlan.build(
        renderer.compileLine(line, columns, TerminalPalette.defaults(), BACKGROUND),
        geometry(), BACKGROUND);
  }

  private static TerminalCellGeometry geometry() {
    TerminalCellGeometry geometry = new TerminalCellGeometry();
    geometry.update(10f, 20f, 15f);
    return geometry;
  }
}
