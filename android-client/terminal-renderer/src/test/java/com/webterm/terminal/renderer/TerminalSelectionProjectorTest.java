package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalSelection;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public final class TerminalSelectionProjectorTest {
  @Test
  public void selectionOnWideSpacerExpandsToBothPhysicalColumns() {
    RenderLine line = lineWithWideCell();
    TerminalSelection selection = new TerminalSelection(
        new TerminalSelection.Anchor(0, 0, 2),
        new TerminalSelection.Anchor(0, 0, 3));
    TerminalSelectionProjector.Range range = project(selection, line, 0, 0);

    assertEquals(1, range.startColumn);
    assertEquals(3, range.endColumnExclusive);
  }

  @Test
  public void multiRowSelectionProjectsOnlyBoundaryColumns() {
    RenderLine line = simpleLine();
    TerminalSelection selection = new TerminalSelection(
        new TerminalSelection.Anchor(0, 1, 2),
        new TerminalSelection.Anchor(0, 3, 4));

    TerminalSelectionProjector projector = new TerminalSelectionProjector();
    TerminalSelectionProjector.Range first = new TerminalSelectionProjector.Range();
    TerminalSelectionProjector.Range middle = new TerminalSelectionProjector.Range();
    TerminalSelectionProjector.Range last = new TerminalSelectionProjector.Range();
    projector.project(selection, line, 0, 1, 8, first);
    projector.project(selection, line, 0, 2, 8, middle);
    projector.project(selection, line, 0, 3, 8, last);

    assertEquals(2, first.startColumn);
    assertEquals(8, first.endColumnExclusive);
    assertEquals(0, middle.startColumn);
    assertEquals(8, middle.endColumnExclusive);
    assertEquals(0, last.startColumn);
    assertEquals(4, last.endColumnExclusive);
  }

  @Test
  public void historyRowsRemainBeforeScreenRows() {
    RenderLine line = simpleLine();
    TerminalSelection selection = new TerminalSelection(
        new TerminalSelection.Anchor(4, -1, 1),
        new TerminalSelection.Anchor(0, 2, 3));
    TerminalSelectionProjector projector = new TerminalSelectionProjector();
    TerminalSelectionProjector.Range history = new TerminalSelectionProjector.Range();
    TerminalSelectionProjector.Range screen = new TerminalSelectionProjector.Range();
    projector.project(selection, line, 4, -1, 8, history);
    projector.project(selection, line, 0, 2, 8, screen);

    assertEquals(1, history.startColumn);
    assertEquals(8, history.endColumnExclusive);
    assertEquals(0, screen.startColumn);
    assertEquals(3, screen.endColumnExclusive);
  }

  @Test
  public void emptySelectionProducesNoRange() {
    TerminalSelection.Anchor anchor = new TerminalSelection.Anchor(0, 0, 2);
    TerminalSelectionProjector.Range range = project(
        new TerminalSelection(anchor, anchor), simpleLine(), 0, 0);
    assertTrue(range.isEmpty());
  }

  @Test
  public void randomWideCellSelectionsMatchLegacyCellOverlapMask() {
    Random random = new Random(0x5E1EC710L);
    TerminalSelectionProjector projector = new TerminalSelectionProjector();

    for (int iteration = 0; iteration < 2_000; iteration++) {
      int columns = 1 + random.nextInt(24);
      int historyRows = random.nextInt(5);
      int screenRows = 1 + random.nextInt(5);
      List<RandomRow> rows = new ArrayList<>(historyRows + screenRows);
      for (int row = 0; row < historyRows; row++) {
        rows.add(new RandomRow(10_000L + row, -1, randomLine(columns, random)));
      }
      for (int row = 0; row < screenRows; row++) {
        rows.add(new RandomRow(0, row, randomLine(columns, random)));
      }

      RandomRow startRow = rows.get(random.nextInt(rows.size()));
      RandomRow endRow = rows.get(random.nextInt(rows.size()));
      TerminalSelection selection = new TerminalSelection(
          anchor(startRow, random.nextInt(columns + 1)),
          anchor(endRow, random.nextInt(columns + 1))).normalized();

      for (RandomRow row : rows) {
        TerminalSelectionProjector.Range actual = new TerminalSelectionProjector.Range();
        projector.project(selection, row.line, row.historySeq, row.screenRow,
            columns, actual);
        boolean[] expectedMask = legacySelectedMask(
            selection, row.line, row.historySeq, row.screenRow, columns);
        for (int column = 0; column < columns; column++) {
          boolean actualSelected = column >= actual.startColumn
              && column < actual.endColumnExclusive;
          assertEquals(
              "iteration=" + iteration
                  + " row=" + row.screenRow
                  + " historySeq=" + row.historySeq
                  + " column=" + column
                  + " selectionStart=" + selection.start.col
                  + " selectionEnd=" + selection.end.col,
              expectedMask[column], actualSelected);
        }
      }
    }
  }

  private static TerminalSelectionProjector.Range project(
      TerminalSelection selection, RenderLine line, long historySeq, int screenRow) {
    TerminalSelectionProjector.Range range = new TerminalSelectionProjector.Range();
    new TerminalSelectionProjector().project(selection.normalized(), line,
        historySeq, screenRow, 8, range);
    return range;
  }

  private static RenderLine lineWithWideCell() {
    return new RenderLine(new LineKey(1, 1), new LineBody(8, false, new CellValue[] {
        CellValue.EMPTY,
        new CellValue("界", (byte) 2, null, null),
        CellValue.SPACER,
        CellValue.EMPTY,
        CellValue.EMPTY,
        CellValue.EMPTY,
        CellValue.EMPTY,
        CellValue.EMPTY
    }));
  }

  private static RenderLine simpleLine() {
    CellValue[] cells = new CellValue[8];
    java.util.Arrays.fill(cells, CellValue.EMPTY);
    return new RenderLine(new LineKey(1, 1), new LineBody(8, false, cells));
  }

  private static RenderLine randomLine(int columns, Random random) {
    CellValue[] cells = new CellValue[columns];
    for (int column = 0; column < columns;) {
      if (column + 1 < columns && random.nextBoolean()) {
        cells[column] = new CellValue("界", (byte) 2, null, null);
        cells[column + 1] = CellValue.SPACER;
        column += 2;
      } else {
        cells[column] = CellValue.EMPTY;
        column++;
      }
    }
    return new RenderLine(new LineKey(1, 1), new LineBody(columns, false, cells));
  }

  private static TerminalSelection.Anchor anchor(RandomRow row, int column) {
    return new TerminalSelection.Anchor(row.historySeq, row.screenRow, column);
  }

  private static boolean[] legacySelectedMask(
      TerminalSelection selection, RenderLine line, long historySeq, int screenRow,
      int columns) {
    boolean[] selected = new boolean[columns];
    if (selection.isEmpty()) return selected;
    for (int column = 0; column < line.length() && column < columns; column++) {
      CellValue cell = line.at(column);
      int width = Math.max(1, cell.width());
      if (compareSelectionPosition(historySeq, screenRow, column, selection.end) < 0
          && compareSelectionPosition(
              historySeq, screenRow, column + width, selection.start) > 0) {
        int end = Math.min(columns, column + width);
        for (int physicalColumn = column; physicalColumn < end; physicalColumn++) {
          selected[physicalColumn] = true;
        }
      }
    }
    return selected;
  }

  private static int compareSelectionPosition(long historySeq, int screenRow, int column,
                                              TerminalSelection.Anchor other) {
    if (historySeq != 0 && other.historySeq != 0) {
      int cmp = Long.compare(historySeq, other.historySeq);
      return cmp != 0 ? cmp : Integer.compare(column, other.col);
    }
    if (historySeq != 0) return -1;
    if (other.historySeq != 0) return 1;
    int cmp = Integer.compare(screenRow, other.screenRow);
    return cmp != 0 ? cmp : Integer.compare(column, other.col);
  }

  private static final class RandomRow {
    final long historySeq;
    final int screenRow;
    final RenderLine line;

    RandomRow(long historySeq, int screenRow, RenderLine line) {
      this.historySeq = historySeq;
      this.screenRow = screenRow;
      this.line = line;
    }
  }
}
