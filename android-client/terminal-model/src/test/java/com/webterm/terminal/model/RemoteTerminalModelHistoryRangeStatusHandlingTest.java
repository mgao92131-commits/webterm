package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class RemoteTerminalModelHistoryRangeStatusHandlingTest {
  @Test
  public void staleAndRetryableDoNotMutateCache() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applyBaseline(V2ModelTestData.baseline(1, 1));
    assertFalse(model.applyHistoryRange(new HistoryRangeResult(
        "r1", "i1", 1, HistoryRangeResult.Status.STALE_PROJECTION,
        new HistoryExtent(2, 300), Collections.emptyList(), 0), 1, 1, 128));
    assertFalse(model.staleProjection());
    assertEquals(-1, model.firstCachedHistorySeq());

    assertFalse(model.applyHistoryRange(new HistoryRangeResult(
        "r2", "i1", 1, HistoryRangeResult.Status.RETRYABLE,
        new HistoryExtent(2, 300), Collections.emptyList(), 200), 1, 1, 128));
    assertEquals(-1, model.firstCachedHistorySeq());
  }

  @Test
  public void okLoadsReturnedLinesWithoutChangingWsExtent() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applyBaseline(V2ModelTestData.baseline(1, 1));
    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "r1", "i1", 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(50, 300),
        Collections.singletonList(V2ModelTestData.line(50, 1, 50, "x")), 0),
        50, 1, 128));

    PagedTerminalHistorySnapshot history =
        (PagedTerminalHistorySnapshot) model.renderSnapshot().history;
    assertEquals(new HistoryExtent(1, 300), model.remoteAvailableExtent());
    assertEquals(50, history.lineBySeq(50).id);
  }

  @Test
  public void lineOutsideRequestedRangeRejectsWholeTransaction() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(V2ModelTestData.baseline(1, 1)));
    model.consumeRenderUpdate();
    long loadedBefore = model.loadedHistoryLineCountForTest();
    int identitiesBefore = model.loadedLineIdentityCountForTest();
    int pagesBefore = model.residentHistoryPageCountForTest();

    assertRejected(model, range(HistoryRangeResult.Status.OK, new HistoryExtent(1, 300),
        V2ModelTestData.line(1000, 1, 100, "a"),
        V2ModelTestData.line(10_101, 1, 101, "x"),
        V2ModelTestData.line(10_128, 1, 128, "x")), 100, 127);

    assertEquals(loadedBefore, model.loadedHistoryLineCountForTest());
    assertEquals(identitiesBefore, model.loadedLineIdentityCountForTest());
    assertEquals(pagesBefore, model.residentHistoryPageCountForTest());
    assertNull(model.loadedHistorySeqForLineIdForTest(1000));
    assertFalse(model.renderPublicationPendingForTest());
  }

  @Test
  public void linesOutsideWsAvailableExtentAreSkippedNotRejected() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, DictionaryEntries.EMPTY, 1, 1,
        TerminalBufferKind.MAIN, new HistoryExtent(57, 300),
        Collections.singletonList(V2ModelTestData.line(1000, 1, 0, "a")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    model.consumeRenderUpdate();

    // 仅含已裁剪前缀：过滤后无写入。
    List<TerminalLine> trimmedOnly = new ArrayList<>();
    for (long seq = 1; seq <= 56; seq++) {
      trimmedOnly.add(V2ModelTestData.line(40_000 + seq, 1, seq, "x"));
    }
    assertFalse(model.applyHistoryRange(new HistoryRangeResult(
        "r0", "i1", 1, HistoryRangeResult.Status.OK,
        model.remoteAvailableExtent(), trimmedOnly, 0), 1, 1, 128));

    // 完整段含已裁剪前缀 + 有效后缀：只写入 57—128。
    List<TerminalLine> mixed = new ArrayList<>();
    for (long seq = 1; seq <= 128; seq++) {
      mixed.add(V2ModelTestData.line(30_000 + seq, 1, seq, "x"));
    }
    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "r", "i1", 1, HistoryRangeResult.Status.OK,
        model.remoteAvailableExtent(), mixed, 0), 57, 1, 128));
    PagedTerminalHistorySnapshot history =
        (PagedTerminalHistorySnapshot) model.renderSnapshot().history;
    assertNull(history.lineBySeq(1));
    assertEquals(SlotState.LOADED, history.slotStateAt(0)); // seq 57
    // LineStore 归零 historySeq；位置由槽位绑定，用 LineID 断言写入成功。
    assertEquals(30_000 + 57, history.lineBySeq(57).id);
  }

  @Test
  public void outOfOrderAndDuplicateSeqRangesAreRejected() {
    RemoteTerminalModel model = baselineModel();
    assertRejected(model, range(HistoryRangeResult.Status.OK, new HistoryExtent(1, 300),
        V2ModelTestData.line(10_100, 1, 100, "x"),
        V2ModelTestData.line(10_102, 1, 102, "x"),
        V2ModelTestData.line(10_101, 1, 101, "x")), 100, 127);
    assertRejected(model, range(HistoryRangeResult.Status.OK, new HistoryExtent(1, 300),
        V2ModelTestData.line(11_100, 1, 100, "x"),
        V2ModelTestData.line(11_101, 1, 101, "x"),
        V2ModelTestData.line(11_102, 1, 101, "x")), 100, 127);
  }

  @Test
  public void duplicateLineIdRangeIsRejected() {
    RemoteTerminalModel model = baselineModel();
    assertRejected(model, range(HistoryRangeResult.Status.OK, new HistoryExtent(1, 300),
        V2ModelTestData.line(12_000, 1, 100, "x"),
        V2ModelTestData.line(12_000, 1, 101, "x")), 100, 127);
  }

  @Test
  public void partialRangeCommitsWithoutAdvancingTrimBoundary() {
    RemoteTerminalModel model = baselineModel();
    List<TerminalLine> lines = new ArrayList<>();
    for (long seq = 110; seq <= 120; seq++) {
      lines.add(V2ModelTestData.line(20_000 + seq, 1, seq, "x"));
    }
    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "r", "i1", 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(110, 300), lines, 0), 110, 100, 127));

    PagedTerminalHistorySnapshot history =
        (PagedTerminalHistorySnapshot) model.renderSnapshot().history;
    assertEquals(new HistoryExtent(1, 300), model.remoteAvailableExtent());
    assertEquals(SlotState.UNLOADED, history.slotStateAt(0));
    assertEquals(SlotState.LOADED, history.slotStateAt(109));
    assertEquals(SlotState.LOADED, history.slotStateAt(119));
    assertEquals(SlotState.UNLOADED, history.slotStateAt(120));
  }

  @Test
  public void alreadyLoadedLinesAreIgnoredOnReapply() {
    RemoteTerminalModel model = baselineModel();
    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "r", "i1", 1, HistoryRangeResult.Status.OK,
        model.remoteAvailableExtent(),
        Collections.singletonList(V2ModelTestData.line(200, 1, 200, "h")), 0),
        200, 100, 256));

    PagedTerminalHistorySnapshot history =
        (PagedTerminalHistorySnapshot) model.renderSnapshot().history;
    assertEquals(200, history.lineBySeq(200).id);
  }

  @Test
  public void lateOldRangeCannotRollBackNewerWsExtent() throws Exception {
    RemoteTerminalModel model = modelWithExtent(new HistoryExtent(1, 100));
    List<HistoryPush> pushes = new ArrayList<>();
    for (long seq = 101; seq <= 120; seq++) {
      pushes.add(new HistoryPush(seq, 50_000 + seq, 1));
    }
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "i1", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null, null,
        new HistoryMutation(new HistoryExtent(1, 120), pushes), null, null, null)));

    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "old", "i1", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(1, 100),
        Collections.singletonList(V2ModelTestData.line(50, 1, 50, "old")), 0),
        50, 1, 100));

    assertEquals(new HistoryExtent(1, 120), model.historyIndex().extent());
    assertEquals(new HistoryLineRef(50_120, 1), model.historyIndex().ref(120));
  }

  @Test
  public void rangeMaySeeFutureTailButCannotAdvanceWsExtent() {
    RemoteTerminalModel model = modelWithExtent(new HistoryExtent(1, 100));
    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "future", "i1", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(1, 120),
        Arrays.asList(
            V2ModelTestData.line(10_099, 1, 99, "loaded"),
            V2ModelTestData.line(10_101, 1, 101, "future")), 0),
        99, 99, 101));

    assertEquals(new HistoryExtent(1, 100), model.historyIndex().extent());
    assertEquals(10_099, ((PagedTerminalHistorySnapshot)
        model.renderSnapshot().history).lineBySeq(99).id);
    assertNull(model.historyIndex().ref(101));
  }

  private static RemoteTerminalModel baselineModel() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(V2ModelTestData.baseline(1, 1)));
    model.consumeRenderUpdate();
    return model;
  }

  private static RemoteTerminalModel modelWithExtent(HistoryExtent extent) {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, DictionaryEntries.EMPTY, 1, 1,
        TerminalBufferKind.MAIN, extent,
        Collections.singletonList(V2ModelTestData.line(1000, 1, 0, "a")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    model.consumeRenderUpdate();
    return model;
  }

  private static HistoryRangeResult range(
      HistoryRangeResult.Status status, HistoryExtent extent, TerminalLine... lines) {
    return new HistoryRangeResult(
        "r", "i1", 1, status, extent, Arrays.asList(lines), 0);
  }

  private static void assertRejected(RemoteTerminalModel model, HistoryRangeResult range,
                                     long requestedFrom, long requestedTo) {
    try {
      model.applyHistoryRange(range, requestedFrom, requestedFrom, requestedTo);
      org.junit.Assert.fail("invalid HistoryRange must be rejected");
    } catch (IllegalArgumentException expected) {
      // Expected: validation completes before Editor creation/commit.
    }
  }
}
