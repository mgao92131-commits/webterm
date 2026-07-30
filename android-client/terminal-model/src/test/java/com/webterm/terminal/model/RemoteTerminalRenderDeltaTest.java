package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class RemoteTerminalRenderDeltaTest {
  private static final int ROWS = 4;

  @Test
  public void rowWritePublishesOnlyTheWrittenRow() throws Exception {
    RemoteTerminalModel model = initializedModel();

    model.applyTerminalCommit(commit(
        1, 2,
        new ScreenMutation(
            null,
            Collections.singletonList(new ScreenRowWrite(2, line(102, 2, "changed"))))));

    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull(update);
    assertFalse(update.dirty.fullInvalidate);
    assertEquals(rows(2), update.dirty.changedScreenRows);
    assertEquals(new BitSet(), update.dirty.exposedScreenRows);
    assertEquals(0, update.dirty.screenScrollRows);
  }

  @Test
  public void upwardScrollPreservesScrollAndExposedRows() throws Exception {
    RemoteTerminalModel model = initializedModel();

    model.applyTerminalCommit(commit(
        1, 2,
        new ScreenMutation(
            new ScreenScroll(0, ROWS, 1),
            Collections.singletonList(new ScreenRowWrite(
                ROWS - 1, line(200, 1, "new-tail"))))));

    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull(update);
    assertFalse(update.dirty.fullInvalidate);
    assertEquals(1, update.dirty.screenScrollRows);
    assertEquals(rows(ROWS - 1), update.dirty.changedScreenRows);
    assertEquals(rows(ROWS - 1), update.dirty.exposedScreenRows);
  }

  @Test
  public void downwardScrollPreservesScrollAndExposedRows() throws Exception {
    RemoteTerminalModel model = initializedModel();

    model.applyTerminalCommit(commit(
        1, 2,
        new ScreenMutation(
            new ScreenScroll(0, ROWS, -2),
            List.of(
                new ScreenRowWrite(0, line(300, 1, "new-head-0")),
                new ScreenRowWrite(1, line(301, 1, "new-head-1"))))));

    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull(update);
    assertFalse(update.dirty.fullInvalidate);
    assertEquals(-2, update.dirty.screenScrollRows);
    assertEquals(rows(0, 1), update.dirty.changedScreenRows);
    assertEquals(rows(0, 1), update.dirty.exposedScreenRows);
  }

  @Test
  public void scrollMovesExistingRenderLineReferencesAndRebuildsOnlyExposedRow() throws Exception {
    RemoteTerminalModel model = initializedModel();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();

    model.applyTerminalCommit(commit(
        1, 2,
        new ScreenMutation(
            new ScreenScroll(0, ROWS, 1),
            Collections.singletonList(new ScreenRowWrite(
                ROWS - 1, line(200, 1, "new-tail"))))));

    RemoteTerminalModel.RenderSnapshot after = model.renderSnapshot();
    for (int row = 0; row < ROWS - 1; row++) {
      assertSame(before.screenView.lineAt(row + 1), after.screenView.lineAt(row));
    }
    assertNotSame(before.screenView.lineAt(ROWS - 1), after.screenView.lineAt(ROWS - 1));
    assertEquals(new LineKey(200, 1), after.screenView.lineAt(ROWS - 1).key());
  }

  @Test
  public void cursorOnlyCommitReusesScreenAndContentAxis() throws Exception {
    RemoteTerminalModel model = initializedModel();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();

    model.applyTerminalCommit(new TerminalCommit(
        "i1", 1, 1, 2, 1, 1, TerminalBufferKind.MAIN,
        null, null, new TerminalCursor(1, 0, true, TerminalCursor.Shape.BLOCK, false),
        null, null));

    RemoteTerminalModel.RenderSnapshot after = model.renderSnapshot();
    assertSame(before.screenView, after.screenView);
    assertSame(before.history, after.history);
    assertSame(before.contentAxis, after.contentAxis);
  }

  @Test
  public void screenCommitReusesHistoryAxisItemsAndUnchangedLines() throws Exception {
    RemoteTerminalModel model = initializedModelWithMissingHistory();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();
    UnifiedContentAxis.Item historyItem = before.contentAxis.itemAtRow(0);
    RenderLine unchanged = before.screenView.lineAt(0);

    model.applyTerminalCommit(commit(
        1, 2,
        new ScreenMutation(
            null,
            Collections.singletonList(new ScreenRowWrite(2, line(102, 2, "changed"))))));

    RemoteTerminalModel.RenderSnapshot after = model.renderSnapshot();
    assertNotSame(before.screenView, after.screenView);
    assertNotSame(before.contentAxis, after.contentAxis);
    assertSame(historyItem, after.contentAxis.itemAtRow(0));
    assertSame(unchanged, after.screenView.lineAt(0));
    assertSame(before.history, after.history);
  }

  private static RemoteTerminalModel initializedModel() {
    return initializedModel(Collections.emptyList(), HistoryExtent.INITIAL_EMPTY);
  }

  private static RemoteTerminalModel initializedModelWithMissingHistory() {
    return initializedModel(
        Collections.singletonList(new HistoryPush(1, new LineKey(900, 1))),
        new HistoryExtent(1, 1));
  }

  private static RemoteTerminalModel initializedModel(
      List<HistoryPush> history, HistoryExtent extent) {
    RemoteTerminalModel model = new RemoteTerminalModel();
    List<ScreenLineContent> screen = new ArrayList<>();
    for (int row = 0; row < ROWS; row++) {
      screen.add(line(100 + row, 1, "row-" + row));
    }
    model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, ROWS, 1,
        TerminalBufferKind.MAIN,
        extent,
        history,
        screen,
        TerminalCursor.hidden(),
        TerminalModes.defaults(),
        TerminalPalette.defaults()));
    model.consumeRenderUpdate();
    return model;
  }

  private static TerminalCommit commit(
      long baseRevision, long revision, ScreenMutation mutation) {
    return new TerminalCommit(
        "i1", 1, baseRevision, revision, 1, 1,
        TerminalBufferKind.MAIN, mutation, null, null, null, null);
  }

  private static ScreenLineContent line(long id, long version, String text) {
    return new ScreenLineContent(
        new LineKey(id, version),
        new LineBody(1, false, new CellValue[] {
            new CellValue(text, (byte) 1, null, null)
        }));
  }

  private static BitSet rows(int... rows) {
    BitSet result = new BitSet();
    for (int row : rows) result.set(row);
    return result;
  }
}
