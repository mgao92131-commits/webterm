package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalSelection;

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
}
