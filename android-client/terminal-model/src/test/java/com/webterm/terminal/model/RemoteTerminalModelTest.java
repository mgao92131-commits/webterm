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
    RemoteTerminalModel.RenderSnapshot render = model.renderSnapshot();

    assertTrue(change.fullInvalidate);
    assertTrue(change.geometryChanged);
    assertEquals(1, model.screenRevision);
    assertEquals("i1", model.instanceId);
    assertEquals(2, model.rows);
    assertEquals(4, model.columns);
    assertEquals(2, render.screen.length);
    assertEquals("a", render.screen[0].at(0).text);
    assertEquals("b", render.screen[0].at(1).text);
    assertEquals(1, render.history.size());
    assertEquals(100L, render.history.firstLineId());
    assertEquals(4, render.history.lineAt(0).length());
  }

  @Test
  public void applySnapshot_marksOnlyGeometryOrInstanceChanges() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 4, 1, "ab"));

    ModelChange sameGeometry = model.applySnapshot(sampleSnapshot(2, 4, 2, "cd"));
    assertFalse(sameGeometry.geometryChanged);

    ModelChange resized = model.applySnapshot(sampleSnapshot(3, 4, 3, "ef"));
    assertTrue(resized.geometryChanged);
  }

  @Test
  public void applyPatch_updatesRowsAndRevision() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 4, 1, "ab"));

    ScreenPatch patch = samplePatch(1, 2, "cd");
    ModelChange change = model.applyPatch(patch);

    assertFalse(change.fullInvalidate);
    assertEquals(2, model.screenRevision);
    assertEquals("c", model.renderSnapshot().screen[0].at(0).text);
    assertEquals("d", model.renderSnapshot().screen[0].at(1).text);
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
    assertEquals(1, change.historyPrependedLines);
    assertEquals("prepend is not a tail append", 0, change.tailAppendedLines);
    assertEquals(2, model.renderSnapshot().history.size());
    assertEquals(98L, model.firstAvailableHistoryId());
  }

  @Test
  public void applyPatch_historyAppendReportsOnlyTailAppends() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 4, 1, "ab"));

    List<TerminalLine> appended = new ArrayList<>();
    appended.add(line(101, "t1"));
    appended.add(line(102, "t2"));
    ScreenPatch patch = new ScreenPatch(
        "i1", 1, 1, 2, appended, Collections.emptyList(),
        null, null, null, Collections.emptyMap(), Collections.emptyMap(),
        null, null, Collections.emptyList());
    ModelChange change = model.applyPatch(patch);

    assertEquals(2, change.tailAppendedLines);
    assertEquals("tail append is not a history prepend", 0, change.historyPrependedLines);
  }

  @Test
  public void historyCache_trimsByByteBudgetBeforeLineLimit() {
    RemoteTerminalModel model = new RemoteTerminalModel(100, 100, 250, 300);
    model.applySnapshot(sampleSnapshot(2, 4, 1, "ab"));

    List<TerminalLine> lines = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      lines.add(line(90 - i, "0123456789"));
    }
    model.prependHistoryPage(new HistoryPage("r1", 1, 1, 1, false, lines));

    int historySize = model.renderSnapshot().history.size();
    assertTrue("byte budget should trim despite generous line limit", historySize < 6);
    assertTrue(model.historyBytes() <= 300 || historySize == 1);
  }

  @Test
  public void trimHistory_removesOldLines() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 4, 1, "ab"));
    model.trimHistory(1, 101);

    assertTrue(model.renderSnapshot().history.isEmpty());
    assertEquals(101L, model.firstAvailableHistoryId());
  }

  @Test
  public void applyPatch_presentEmptyTitleAndCwdClearModel() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(titledSnapshot("vim", "/home/u"));

    // present 空串表示 title/cwd 已被清空，模型必须清空而不是保持原值。
    ModelChange change = model.applyPatch(titlePatch("", ""));

    assertEquals("", model.title());
    assertEquals("", model.workingDirectory());
    assertEquals("", model.renderSnapshot().title);
    assertTrue(change.titleChanged);
  }

  @Test
  public void applyPatch_absentTitleAndCwdKeepModelValues() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(titledSnapshot("vim", "/home/u"));

    // null 表示未变化，模型保持原值。
    ModelChange change = model.applyPatch(titlePatch(null, null));

    assertEquals("vim", model.title());
    assertEquals("/home/u", model.workingDirectory());
    assertFalse(change.titleChanged);
  }

  @Test
  public void applyPatch_presentTitleAndCwdUpdateModel() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(titledSnapshot("vim", "/home/u"));

    model.applyPatch(titlePatch("bash", "/tmp"));

    assertEquals("bash", model.title());
    assertEquals("/tmp", model.workingDirectory());
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

  private static ScreenSnapshot titledSnapshot(String title, String cwd) {
    List<TerminalLine> screen = new ArrayList<>();
    screen.add(line(0, "ab"));
    return new ScreenSnapshot(
        "s1", "i1", 1, 1, 1, 2, ScreenSnapshot.BufferKind.MAIN,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        new HistoryWindow(1, 0, 0, false, Collections.emptyList()),
        screen, Collections.emptyMap(), Collections.emptyMap(), title, cwd
    );
  }

  private static ScreenPatch titlePatch(String title, String cwd) {
    return new ScreenPatch(
        "i1", 1, 1, 2, Collections.emptyList(), Collections.emptyList(),
        null, null, null, Collections.emptyMap(), Collections.emptyMap(),
        title, cwd, Collections.emptyList()
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
