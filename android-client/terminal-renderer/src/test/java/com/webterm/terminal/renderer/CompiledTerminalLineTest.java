package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;

import org.junit.Test;

/** 编译结果的防御复制和生产所有权边界测试。 */
public final class CompiledTerminalLineTest {
  private static final CompiledTerminalLine.CompiledStyle STYLE =
      new CompiledTerminalLine.CompiledStyle(
          0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, false, false, false, false,
          false, false, false, ResolvedTerminalStyle.UnderlineKind.NONE);

  @Test
  public void publicTextSpanConstructorDefensivelyCopiesArrays() {
    int[] offsets = {0};
    int[] columns = {2};
    byte[] widths = {1};
    byte[] fitModes = {0};
    CompiledTerminalLine.TextSpan span = new CompiledTerminalLine.TextSpan(
        2, 1, STYLE, TerminalFontRole.MAIN_TEXT, "A",
        offsets, columns, widths, fitModes);

    offsets[0] = 7;
    columns[0] = 9;
    widths[0] = 2;
    fitModes[0] = 1;
    assertEquals(0, span.clusterUtf16Start(0));
    assertEquals(2, span.clusterColumn(0));
    assertEquals(1, span.clusterWidth(0));
    assertEquals(TerminalGlyphFitter.ClusterFitMode.GRID_START, span.clusterFitMode(0));
  }

  @Test
  public void ownershipTextSpanUsesTransferredArraysInternally() {
    int[] offsets = {0};
    int[] columns = {2};
    byte[] widths = {1};
    byte[] fitModes = {0};
    CompiledTerminalLine.TextSpan span = CompiledTerminalLine.TextSpan.takeOwnership(
        2, 1, STYLE, TerminalFontRole.MAIN_TEXT, "A",
        offsets, columns, widths, fitModes);

    offsets[0] = 1;
    columns[0] = 3;
    assertEquals(1, span.clusterUtf16Start(0));
    assertEquals(3, span.clusterColumn(0));
  }

  @Test
  public void publicCompiledLineConstructorDefensivelyCopiesList() {
    ArrayList<CompiledTerminalLine.Span> source = new ArrayList<>();
    source.add(textSpan());
    CompiledTerminalLine line = new CompiledTerminalLine(source);
    source.clear();

    assertEquals(1, line.spans().size());
    assertThrows(UnsupportedOperationException.class, () -> line.spans().clear());
  }

  @Test
  public void ownershipCompiledLineUsesAnUnmodifiableView() {
    ArrayList<CompiledTerminalLine.Span> source = new ArrayList<>();
    source.add(textSpan());
    CompiledTerminalLine line = CompiledTerminalLine.takeOwnership(source);

    assertThrows(UnsupportedOperationException.class, () -> line.spans().add(textSpan()));
  }

  @Test
  public void emptyReturnsSameInstance() {
    assertSame(CompiledTerminalLine.empty(), CompiledTerminalLine.empty());
  }

  @Test
  public void compileResultsRemainIndependentAcrossCalls() {
    TerminalLineCompiler compiler = new TerminalLineCompiler();
    CompiledTerminalLine first = compiler.compile(
        line(1, "A"), 1, com.webterm.terminal.model.TerminalPalette.defaults(), 0xFF000000);
    compiler.compile(
        line(2, "B"), 1, com.webterm.terminal.model.TerminalPalette.defaults(), 0xFF000000);

    assertEquals("A", ((CompiledTerminalLine.TextSpan) first.spans().get(0)).text());
  }

  private static com.webterm.terminal.model.RenderLine line(long id, String text) {
    return new com.webterm.terminal.model.RenderLine(
        new com.webterm.terminal.model.LineKey(id, 1),
        new com.webterm.terminal.model.LineBody(1, false,
            new com.webterm.terminal.model.CellValue[] {
                new com.webterm.terminal.model.CellValue(text, (byte) 1, null, null)
            }));
  }

  private static CompiledTerminalLine.TextSpan textSpan() {
    return new CompiledTerminalLine.TextSpan(
        0, 1, STYLE, TerminalFontRole.MAIN_TEXT, "A",
        new int[] {0}, new int[] {0}, new byte[] {1}, new byte[] {0});
  }
}
