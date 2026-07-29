package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;

import com.webterm.terminal.model.BodyCache;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.HistoryCatalog;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryRenderView;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.SemanticHistoryRenderView;
import com.webterm.terminal.model.TerminalSelection;
import java.util.Arrays;
import org.junit.Test;

public final class TerminalSelectionTextExtractorTest {
  @Test
  public void extractsScreenRowsAndSkipsWideSpacer() {
    RenderLine[] screen = {
        line(1, false,
            new CellValue("界", (byte) 2, null, null),
            CellValue.SPACER,
            new CellValue("x", (byte) 1, null, null)),
        textLine(2, false, "next")
    };
    TerminalSelection selection = new TerminalSelection(
        screenAnchor(0, 0), screenAnchor(1, 4)).normalized();

    assertEquals(
        "界x\nnext",
        TerminalSelectionTextExtractor.extract(
            selection, emptyHistory(), screen));
  }

  @Test
  public void wrappedRowsAreJoinedAndHardRowsKeepNewline() {
    RenderLine[] screen = {
        textLine(1, true, "first "),
        textLine(2, false, "continued "),
        textLine(3, false, "hard ")
    };
    TerminalSelection selection = new TerminalSelection(
        screenAnchor(0, 0), screenAnchor(2, 5)).normalized();

    assertEquals(
        "first continued\nhard",
        TerminalSelectionTextExtractor.extract(
            selection, emptyHistory(), screen));
  }

  @Test
  public void missingHistoryBodyIsSkippedWithoutInventingText() throws Exception {
    HistoryExtent extent = new HistoryExtent(10, 11);
    HistoryCatalog catalog = new HistoryCatalog().edit()
        .setExtent(extent)
        .bindNew(10, new LineKey(10, 1))
        .bindNew(11, new LineKey(11, 1))
        .commit();
    BodyCache cache = new BodyCache(HistoryBudget.defaults()).edit()
        .setHistoryExtent(extent)
        .setAvailableExtent(extent)
        .putHistory(11, new LineKey(11, 1), textBody(false, "loaded"))
        .commit();
    HistoryRenderView history = new SemanticHistoryRenderView(catalog, cache);
    TerminalSelection selection = new TerminalSelection(
        historyAnchor(10, 0), historyAnchor(11, 6)).normalized();

    assertEquals(
        "loaded",
        TerminalSelectionTextExtractor.extract(
            selection, history, new RenderLine[0]));
  }

  private static HistoryRenderView emptyHistory() {
    return new SemanticHistoryRenderView(
        new HistoryCatalog(), new BodyCache(HistoryBudget.defaults()));
  }

  private static TerminalSelection.Anchor screenAnchor(int row, int column) {
    return new TerminalSelection.Anchor(0, row, column);
  }

  private static TerminalSelection.Anchor historyAnchor(long seq, int column) {
    return new TerminalSelection.Anchor(seq, -1, column);
  }

  private static RenderLine textLine(long id, boolean wrapped, String text) {
    CellValue[] cells = new CellValue[Math.max(1, text.length())];
    Arrays.fill(cells, CellValue.EMPTY);
    for (int index = 0; index < text.length(); index++) {
      cells[index] = new CellValue(
          String.valueOf(text.charAt(index)), (byte) 1, null, null);
    }
    return new RenderLine(new LineKey(id, 1), new LineBody(cells.length, wrapped, cells));
  }

  private static LineBody textBody(boolean wrapped, String text) {
    return textLine(1, wrapped, text).body();
  }

  private static RenderLine line(long id, boolean wrapped, CellValue... cells) {
    return new RenderLine(new LineKey(id, 1), new LineBody(cells.length, wrapped, cells));
  }
}
