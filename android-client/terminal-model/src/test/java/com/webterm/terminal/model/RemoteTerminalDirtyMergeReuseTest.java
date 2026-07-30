package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class RemoteTerminalDirtyMergeReuseTest {
  private static final int ROWS = 4;

  @Test
  public void pendingPublicationMergesScrollAndWriteWithoutFullInvalidate() throws Exception {
    RemoteTerminalModel model = initializedModel();

    model.applyTerminalCommit(commit(
        1, 2,
        new ScreenMutation(
            new ScreenScroll(0, ROWS, 1),
            Collections.singletonList(new ScreenRowWrite(
                ROWS - 1, line(200, 1, "new-tail"))))));
    assertTrue(model.renderPublicationPendingForTest());

    model.applyTerminalCommit(commit(
        2, 3,
        new ScreenMutation(
            null,
            Collections.singletonList(new ScreenRowWrite(1, line(101, 2, "mid"))))));

    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull(update);
    assertFalse(update.dirty.fullInvalidate);
    assertFalse(update.dirty.screenRegionInvalidate);
    assertEquals(1, update.dirty.screenScrollRows);
    assertTrue(update.dirty.changedScreenRows.get(1));
    assertTrue(update.dirty.changedScreenRows.get(ROWS - 1)
        || update.dirty.exposedScreenRows.get(ROWS - 1));
  }

  @Test
  public void screenRegionFallbackRebuildsOnlyScreenView() throws Exception {
    RemoteTerminalModel model = initializedModelWithMissingHistory();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();

    // 两次大滚动量在 publication 合并后超过屏幕高度 → screenRegionInvalidate。
    model.applyTerminalCommit(commit(
        1, 2,
        new ScreenMutation(
            new ScreenScroll(0, ROWS, 3),
            List.of(
                new ScreenRowWrite(1, line(201, 1, "e1")),
                new ScreenRowWrite(2, line(202, 1, "e2")),
                new ScreenRowWrite(3, line(203, 1, "e3"))))));
    model.applyTerminalCommit(commit(
        2, 3,
        new ScreenMutation(
            new ScreenScroll(0, ROWS, 3),
            List.of(
                new ScreenRowWrite(1, line(211, 1, "f1")),
                new ScreenRowWrite(2, line(212, 1, "f2")),
                new ScreenRowWrite(3, line(213, 1, "f3"))))));

    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull(update);
    assertFalse(update.dirty.fullInvalidate);
    assertTrue(update.dirty.screenRegionInvalidate);

    RemoteTerminalModel.RenderSnapshot after = model.renderSnapshot();
    assertNotSame(before.screenView, after.screenView);
    assertSame(before.history, after.history);
  }

  @Test
  public void screenRegionFallbackReusesHistoryView() throws Exception {
    RemoteTerminalModel model = initializedModelWithMissingHistory();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();

    forceScreenRegionViaCoalescedScrolls(model);

    RemoteTerminalModel.RenderSnapshot after = model.renderSnapshot();
    assertSame(before.history, after.history);
  }

  @Test
  public void screenRegionFallbackReusesHistoryPart() throws Exception {
    RemoteTerminalModel model = initializedModelWithMissingHistory();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();
    UnifiedContentAxis.Item historyItem = before.contentAxis.itemAtRow(0);

    forceScreenRegionViaCoalescedScrolls(model);

    RemoteTerminalModel.RenderSnapshot after = model.renderSnapshot();
    assertSame(historyItem, after.contentAxis.itemAtRow(0));
  }

  @Test
  public void pendingPublicationPreservesZeroNetScrollDamage() throws Exception {
    RemoteTerminalModel model = initializedModel();

    // 向上滚动 1 行后，再向下滚动 1 行 → 净滚动为 0，但暴露行仍需保留。
    model.applyTerminalCommit(commit(
        1, 2,
        new ScreenMutation(
            new ScreenScroll(0, ROWS, 1),
            Collections.singletonList(new ScreenRowWrite(
                ROWS - 1, line(200, 1, "up-tail"))))));
    model.applyTerminalCommit(commit(
        2, 3,
        new ScreenMutation(
            new ScreenScroll(0, ROWS, -1),
            Collections.singletonList(new ScreenRowWrite(
                0, line(201, 1, "down-head"))))));

    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull(update);
    assertFalse(update.dirty.fullInvalidate);
    assertFalse(update.dirty.screenRegionInvalidate);
    assertEquals(0, update.dirty.screenScrollRows);
    assertFalse(update.dirty.exposedScreenRows.isEmpty());
    assertTrue(update.dirty.exposedScreenRows.get(0)
        || update.dirty.changedScreenRows.get(0));
  }

  private static void forceScreenRegionViaCoalescedScrolls(RemoteTerminalModel model)
      throws Exception {
    model.applyTerminalCommit(commit(
        1, 2,
        new ScreenMutation(
            new ScreenScroll(0, ROWS, 3),
            List.of(
                new ScreenRowWrite(1, line(201, 1, "e1")),
                new ScreenRowWrite(2, line(202, 1, "e2")),
                new ScreenRowWrite(3, line(203, 1, "e3"))))));
    model.applyTerminalCommit(commit(
        2, 3,
        new ScreenMutation(
            new ScreenScroll(0, ROWS, 3),
            List.of(
                new ScreenRowWrite(1, line(211, 1, "f1")),
                new ScreenRowWrite(2, line(212, 1, "f2")),
                new ScreenRowWrite(3, line(213, 1, "f3"))))));
    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull(update);
    assertTrue(update.dirty.screenRegionInvalidate);
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
}
