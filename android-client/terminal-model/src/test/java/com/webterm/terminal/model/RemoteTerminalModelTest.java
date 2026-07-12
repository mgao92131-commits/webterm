package com.webterm.terminal.model;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RemoteTerminalModelTest {

  @Test
  public void applySnapshot_replacesScreenAndHistory() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ScreenSnapshot snapshot = sampleSnapshot(2, 4, 1, "ab");

    ModelChange change = model.applySnapshot(snapshot);

    assertTrue(change.fullInvalidate);
    assertEquals(1, model.screenRevision);
    assertEquals("i1", model.instanceId);
    assertEquals(2, model.rows);
    assertEquals(4, model.columns);
    assertEquals(2, model.screen().length);
    assertEquals("a", model.screen()[0].at(0).text);
    assertEquals("b", model.screen()[0].at(1).text);
    assertEquals(1, model.historyCache().size());
    assertEquals(100L, (long) model.historyCache().firstKey());
    assertEquals(4, model.historyCache().firstEntry().getValue().length());
  }

  @Test
  public void applyPatch_updatesRowsAndRevision() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 4, 1, "ab"));

    ScreenPatch patch = samplePatch(1, 2, "cd");
    ModelChange change = model.applyPatch(patch);

    assertFalse(change.fullInvalidate);
    assertEquals(2, model.screenRevision);
    assertEquals("c", model.screen()[0].at(0).text);
    assertEquals("d", model.screen()[0].at(1).text);
    assertTrue(change.changedScreenRows.contains(0));
  }

  @Test(expected = RemoteTerminalModel.RevisionGapException.class)
  public void applyPatch_wrongBaseRevision_throws() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 4, 5, "ab"));
    ScreenPatch patch = samplePatch(1, 2, "cd");
    model.applyPatch(patch);
  }

  @Test
  public void prependHistoryPage_addsLines() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 4, 1, "ab"));

    List<TerminalLine> lines = new ArrayList<>();
    lines.add(line(99, "x"));
    HistoryPage page = new HistoryPage("r1", 1, 1, 98, true, lines);
    ModelChange change = model.prependHistoryPage(page);

    assertTrue(change.historyChanged);
    assertEquals(2, model.historyCache().size());
    assertEquals(98L, model.firstAvailableHistoryId());
  }

  @Test
  public void trimHistory_removesOldLines() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 4, 1, "ab"));
    model.trimHistory(1, 101);

    assertTrue(model.historyCache().isEmpty());
    assertEquals(101L, model.firstAvailableHistoryId());
  }

  private static ScreenSnapshot sampleSnapshot(int rows, int cols, long revision, String text) {
    List<TerminalLine> screen = new ArrayList<>();
    for (int r = 0; r < rows; r++) {
      screen.add(line(r, text));
    }
    List<TerminalLine> history = new ArrayList<>();
    history.add(line(100, "h"));
    return new ScreenSnapshot(
        "s1", "i1", 1, revision, rows, cols, ScreenSnapshot.BufferKind.MAIN,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        new HistoryWindow(100, 100, 100, false, history),
        screen, Collections.emptyMap(), Collections.emptyMap(), "", ""
    );
  }

  private static ScreenPatch samplePatch(long baseRevision, long revision, String text) {
    List<TerminalLine> rows = new ArrayList<>();
    rows.add(line(0, text));
    return new ScreenPatch(
        "i1", 1, baseRevision, revision, Collections.emptyList(), rows,
        null, null, null, Collections.emptyMap(), Collections.emptyMap(),
        null, null, Collections.emptyList()
    );
  }

  private static TerminalLine line(long id, String text) {
    TerminalCell[] cells = new TerminalCell[text.length()];
    for (int i = 0; i < text.length(); i++) {
      cells[i] = new TerminalCell(String.valueOf(text.charAt(i)), (byte) 1, 0, 0);
    }
    return new TerminalLine(id, false, cells);
  }
}
