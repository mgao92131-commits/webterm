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

    model.applySnapshot(snapshot);
    RenderUpdate update = model.consumeRenderUpdate();
    RemoteTerminalModel.RenderSnapshot render = update.snapshot;

    assertTrue(update.state.geometryChanged);
    assertTrue(update.dirty.fullInvalidate);
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

    model.consumeRenderUpdate();
    model.applySnapshot(sampleSnapshot(2, 4, 2, "cd"));
    assertFalse(model.consumeRenderUpdate().state.geometryChanged);

    model.applySnapshot(sampleSnapshot(3, 4, 3, "ef"));
    assertTrue(model.consumeRenderUpdate().state.geometryChanged);
  }

  @Test
  public void applyPatch_updatesRowsAndRevision() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 4, 1, "ab"));
    model.consumeRenderUpdate();

    ScreenPatch patch = samplePatch(1, 2, "cd");
    model.applyPatch(patch);
    RenderUpdate update = model.consumeRenderUpdate();

    assertEquals(2, model.screenRevision);
    assertEquals("c", update.snapshot.screen[0].at(0).text);
    assertEquals("d", update.snapshot.screen[0].at(1).text);
    assertTrue(update.dirty.changedScreenRows.get(0));
  }

  @Test
  public void consumeRenderUpdate_mergesCurrentPatches_andLeavesLaterPatchForNextFrame()
      throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 4, 1, "ab"));
    model.consumeRenderUpdate();

    model.applyPatch(samplePatch(1, 2, "c"));
    model.applyPatch(new ScreenPatch("i1", 1, 2, 3, Collections.emptyList(),
        Collections.singletonList(line(1, "d")), null, null, null,
        Collections.emptyMap(), Collections.emptyMap(), null, null, Collections.emptyList()));

    RenderUpdate current = model.consumeRenderUpdate();
    assertEquals(3, current.snapshot.screenRevision);
    assertTrue(current.dirty.changedScreenRows.get(0));
    assertTrue(current.dirty.changedScreenRows.get(1));
    assertEquals("c", current.snapshot.screen[0].at(0).text);
    assertEquals("d", current.snapshot.screen[1].at(0).text);
    assertNull(model.consumeRenderUpdate());

    model.applyPatch(samplePatch(3, 4, "e"));
    RenderUpdate next = model.consumeRenderUpdate();
    assertEquals(4, next.snapshot.screenRevision);
    assertTrue(next.dirty.changedScreenRows.get(0));
    assertFalse(next.dirty.changedScreenRows.get(1));
  }

  @Test
  public void cursorAndModesChanges_areAccumulatedForSafeRedraw() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 4, 1, "ab"));
    model.consumeRenderUpdate();

    TerminalCursor cursor = new TerminalCursor(1, 0, true, TerminalCursor.Shape.BLOCK, false);
    TerminalModes modes = new TerminalModes(true, false, false,
        TerminalModes.MouseTracking.NONE, TerminalModes.MouseEncoding.X10, false);
    model.applyPatch(new ScreenPatch("i1", 1, 1, 2, Collections.emptyList(),
        Collections.emptyList(), cursor, modes, null, Collections.emptyMap(),
        Collections.emptyMap(), null, null, Collections.emptyList()));

    RenderUpdate update = model.consumeRenderUpdate();
    assertTrue(update.dirty.cursorChanged);
    assertEquals(0, update.dirty.previousCursorRow);
    assertEquals(1, update.dirty.currentCursorRow);
    assertTrue(update.dirty.modesChanged);
  }

  @Test
  public void applyPatch_shorterReplacementClearsOldRowTail() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(sampleSnapshot(2, 12, 1, "projected"));

    model.applyPatch(samplePatch(1, 2, "ok"));

    TerminalLine row = model.renderSnapshot().screen[0];
    assertEquals("o", row.at(0).text);
    assertEquals("k", row.at(1).text);
    for (int col = 2; col < row.length(); col++) {
      assertTrue("stale tail survived at column " + col, row.at(col).isDefault());
    }
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
    model.consumeRenderUpdate();
    model.prependHistoryPage(page);
    RenderUpdate update = model.consumeRenderUpdate();

    assertTrue(update.state.historyChanged);
    assertEquals(1, update.state.historyPrependedLines);
    assertEquals("prepend is not a tail append", 0, update.state.tailAppendedLines);
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
    model.consumeRenderUpdate();
    model.applyPatch(patch);
    RenderUpdate update = model.consumeRenderUpdate();

    assertEquals(2, update.state.tailAppendedLines);
    assertEquals("tail append is not a history prepend", 0, update.state.historyPrependedLines);
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
    model.consumeRenderUpdate();
    model.applyPatch(titlePatch("", ""));
    RenderUpdate update = model.consumeRenderUpdate();

    assertEquals("", model.title());
    assertEquals("", model.workingDirectory());
    assertEquals("", model.renderSnapshot().title);
    assertTrue(update.state.titleChanged);
  }

  @Test
  public void applyPatch_absentTitleAndCwdKeepModelValues() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(titledSnapshot("vim", "/home/u"));

    // null 表示未变化，模型保持原值。
    model.consumeRenderUpdate();
    model.applyPatch(titlePatch(null, null));
    RenderUpdate update = model.consumeRenderUpdate();

    assertEquals("vim", model.title());
    assertEquals("/home/u", model.workingDirectory());
    assertNull(update);
  }

  @Test
  public void metadataOnlyPatchReusesPublishedScreenArray() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(titledSnapshot("vim", "/home/u"));
    TerminalLine[] before = model.consumeRenderUpdate().snapshot.screen;

    model.applyPatch(titlePatch("bash", null));

    assertSame("metadata-only patches must not clone the screen array", before,
        model.renderSnapshot().screen);
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
