package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.StyleValue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class TerminalLineCompilerTest {
  private static final TerminalPalette PALETTE = TerminalPalette.defaults();
  private static final int BACKGROUND = 0xFF000000;

  @Test
  public void preservesGraphemeAndPhysicalColumnMapping() {
    RenderLine line = line(
        new CellValue("A", (byte) 1, null, null),
        new CellValue("e\u0301", (byte) 1, null, null),
        new CellValue("界", (byte) 2, null, null),
        CellValue.SPACER,
        new CellValue("B", (byte) 1, null, null));

    CompiledTerminalLine compiled = new TerminalLineCompiler().compile(line, 5, PALETTE, BACKGROUND);
    assertEquals(1, compiled.spans().size());
    CompiledTerminalLine.TextSpan text =
        (CompiledTerminalLine.TextSpan) compiled.spans().get(0);
    assertEquals("Ae\u0301界B", text.text());
    assertEquals(5, text.columnCount());
    assertEquals(4, text.clusterCount());
    assertEquals(0, text.clusterUtf16Start(0));
    assertEquals(1, text.clusterUtf16Start(1));
    assertEquals(3, text.clusterUtf16Start(2));
    assertEquals(4, text.clusterUtf16Start(3));
    assertEquals(0, text.clusterColumn(0));
    assertEquals(1, text.clusterColumn(1));
    assertEquals(2, text.clusterColumn(2));
    assertEquals(4, text.clusterColumn(3));
    assertEquals(2, text.clusterWidth(2));
  }

  @Test
  public void styleBoundaryCreatesSeparateTextSpans() {
    StyleValue bold = new StyleValue(null, null, null, 1);
    RenderLine line = line(
        new CellValue("a", (byte) 1, null, null),
        new CellValue("b", (byte) 1, null, null),
        new CellValue("C", (byte) 1, bold, null),
        new CellValue("D", (byte) 1, bold, null));

    List<CompiledTerminalLine.Span> spans =
        new TerminalLineCompiler().compile(line, 4, PALETTE, BACKGROUND).spans();
    assertEquals(2, spans.size());
    assertTrue(spans.get(0) instanceof CompiledTerminalLine.TextSpan);
    assertTrue(spans.get(1) instanceof CompiledTerminalLine.TextSpan);
    assertEquals("ab", ((CompiledTerminalLine.TextSpan) spans.get(0)).text());
    assertEquals("CD", ((CompiledTerminalLine.TextSpan) spans.get(1)).text());
  }

  @Test
  public void specialGlyphCutsTextRunWithoutReparsingGrapheme() {
    RenderLine line = line(
        new CellValue("a", (byte) 1, null, null),
        new CellValue("─", (byte) 1, null, null),
        new CellValue("b", (byte) 1, null, null));

    List<CompiledTerminalLine.Span> spans =
        new TerminalLineCompiler().compile(line, 3, PALETTE, BACKGROUND).spans();
    assertEquals(3, spans.size());
    assertTrue(spans.get(1) instanceof CompiledTerminalLine.SpecialGlyphSpan);
    assertEquals(0x2500,
        ((CompiledTerminalLine.SpecialGlyphSpan) spans.get(1)).codePoint());
  }

  @Test
  public void defaultBlankLineProducesNoSpans() {
    RenderLine line = line(CellValue.EMPTY, CellValue.EMPTY, CellValue.EMPTY, CellValue.EMPTY);
    assertEquals(0, new TerminalLineCompiler().compile(line, 4, PALETTE, BACKGROUND)
        .spans().size());
  }

  @Test
  public void internalDefaultBlankIsMaterializedWithoutLeadingOrTrailingBlanks() {
    CompiledTerminalLine.TextSpan span = (CompiledTerminalLine.TextSpan)
        new TerminalLineCompiler().compile(line(
            new CellValue("A", (byte) 1, null, null),
            CellValue.EMPTY,
            new CellValue("B", (byte) 1, null, null),
            CellValue.EMPTY), 4, PALETTE, BACKGROUND).spans().get(0);

    assertEquals("A B", span.text());
    assertEquals(3, span.clusterCount());
    assertEquals(3, span.columnCount());
    assertEquals(1, span.clusterColumn(1));
    assertEquals(2, span.clusterUtf16Start(2));
  }

  @Test
  public void leadingDefaultBlanksAreNotMaterialized() {
    CompiledTerminalLine.TextSpan span = (CompiledTerminalLine.TextSpan)
        new TerminalLineCompiler().compile(line(
            CellValue.EMPTY,
            CellValue.EMPTY,
            new CellValue("A", (byte) 1, null, null)), 3, PALETTE, BACKGROUND)
            .spans().get(0);

    assertEquals("A", span.text());
    assertEquals(2, span.startColumn());
    assertEquals(1, span.columnCount());
    assertEquals(0, span.clusterUtf16Start(0));
  }

  @Test
  public void styledBoundaryDiscardsPendingDefaultBlank() {
    StyleValue bold = new StyleValue(null, null, null, 1);
    List<CompiledTerminalLine.Span> spans = new TerminalLineCompiler().compile(line(
        new CellValue("A", (byte) 1, null, null),
        CellValue.EMPTY,
        new CellValue("B", (byte) 1, bold, null)), 3, PALETTE, BACKGROUND).spans();

    assertEquals(2, spans.size());
    assertEquals("A", ((CompiledTerminalLine.TextSpan) spans.get(0)).text());
    assertEquals("B", ((CompiledTerminalLine.TextSpan) spans.get(1)).text());
    assertEquals(2, spans.get(1).startColumn());
  }

  @Test
  public void defaultBlankAfterWideSpacerKeepsPhysicalColumn() {
    List<CompiledTerminalLine.Span> spans = new TerminalLineCompiler().compile(line(
        new CellValue("界", (byte) 2, null, null),
        CellValue.SPACER,
        CellValue.EMPTY,
        new CellValue("A", (byte) 1, null, null)), 4, PALETTE, BACKGROUND).spans();

    assertEquals(1, spans.size());
    CompiledTerminalLine.TextSpan span = (CompiledTerminalLine.TextSpan) spans.get(0);
    assertEquals("界 A", span.text());
    assertEquals(0, span.startColumn());
    assertEquals(3, span.clusterColumn(2));
  }

  @Test
  public void defaultBlankBeforeSpecialGlyphDoesNotCreateTextSpan() {
    List<CompiledTerminalLine.Span> spans = new TerminalLineCompiler().compile(line(
        CellValue.EMPTY,
        new CellValue("─", (byte) 1, null, null)), 2, PALETTE, BACKGROUND).spans();

    assertEquals(1, spans.size());
    assertTrue(spans.get(0) instanceof CompiledTerminalLine.SpecialGlyphSpan);
    assertEquals(1, spans.get(0).startColumn());
  }

  @Test
  public void styledBlankAndHiddenTextRetainBlankSpan() {
    StyleValue background = new StyleValue(
        null, TerminalColor.rgb(0x204060), null, 0);
    StyleValue hidden = new StyleValue(
        TerminalColor.rgb(0xFFFFFF), TerminalColor.rgb(0x102030), null, 1 << 11);
    RenderLine line = line(
        new CellValue(" ", (byte) 1, background, null),
        new CellValue("X", (byte) 1, hidden, null));

    List<CompiledTerminalLine.Span> spans =
        new TerminalLineCompiler().compile(line, 2, PALETTE, BACKGROUND).spans();
    assertEquals(2, spans.size());
    assertTrue(spans.get(0) instanceof CompiledTerminalLine.BlankStyleSpan);
    assertTrue(spans.get(1) instanceof CompiledTerminalLine.BlankStyleSpan);
  }

  @Test
  public void truncatedWideCellUsesRemainingPhysicalColumns() {
    RenderLine line = line(new CellValue("界", (byte) 2, null, null));
    CompiledTerminalLine.TextSpan span = (CompiledTerminalLine.TextSpan)
        new TerminalLineCompiler().compile(line, 1, PALETTE, BACKGROUND).spans().get(0);
    assertEquals(1, span.columnCount());
    assertEquals(1, span.clusterWidth(0));
  }

  @Test
  public void wideGraphemeFitDoesNotDependOnRightNeighbor() {
    CompiledTerminalLine.TextSpan padded = (CompiledTerminalLine.TextSpan)
        new TerminalLineCompiler().compile(line(
            new CellValue("界", (byte) 2, null, null),
            CellValue.SPACER,
            CellValue.EMPTY), 3, PALETTE, BACKGROUND).spans().get(0);
    CompiledTerminalLine.TextSpan adjacentText = (CompiledTerminalLine.TextSpan)
        new TerminalLineCompiler().compile(line(
            new CellValue("界", (byte) 2, null, null),
            CellValue.SPACER,
            new CellValue("X", (byte) 1, null, null)), 3, PALETTE, BACKGROUND)
        .spans().get(0);

    assertEquals(TerminalGlyphFitter.ClusterFitMode.GRID_START, padded.clusterFitMode(0));
    assertEquals(padded.clusterFitMode(0), adjacentText.clusterFitMode(0));
  }

  @Test
  public void malformedSpacerDoesNotCreateTextOrCrash() {
    RenderLine line = line(CellValue.SPACER, new CellValue("x", (byte) 1, null, null));
    List<CompiledTerminalLine.Span> spans =
        new TerminalLineCompiler().compile(line, 2, PALETTE, BACKGROUND).spans();
    assertEquals(1, spans.size());
    assertEquals(1, spans.get(0).startColumn());
  }

  @Test
  public void emptyWidthOneBlinkCellDoesNotStartAnimation() {
    StyleValue slowBlink = new StyleValue(
        TerminalColor.rgb(0xFFFFFF), TerminalColor.DEFAULT_BG, null, 1 << 8);
    RenderLine line = line(new CellValue("", (byte) 1, slowBlink, null));
    TerminalLineCompiler compiler = new TerminalLineCompiler();

    assertEquals(0, compiler.visibleBlinkKinds(line, 1, PALETTE, BACKGROUND));
    assertTrue(compiler.compile(line, 1, PALETTE, BACKGROUND).spans().isEmpty());
  }

  private static RenderLine line(CellValue... cells) {
    return new RenderLine(
        new LineKey(1, 1), new LineBody(cells.length, false, Arrays.copyOf(cells, cells.length)));
  }
}
