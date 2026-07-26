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
    assertTrue(model.staleProjection());
    assertEquals(173, model.firstCachedHistorySeq());

    assertFalse(model.applyHistoryRange(new HistoryRangeResult(
        "r2", "i1", 1, HistoryRangeResult.Status.RETRYABLE,
        new HistoryExtent(2, 300), Collections.emptyList(), 200), 1, 1, 128));
    assertEquals(173, model.firstCachedHistorySeq());
  }

  @Test
  public void okAndTrimmedMarkUnavailableAndLoadReturnedLines() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applyBaseline(V2ModelTestData.baseline(1, 1));
    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "r1", "i1", 1, HistoryRangeResult.Status.TRIMMED,
        new HistoryExtent(50, 300),
        Collections.singletonList(V2ModelTestData.line(50, 1, 50, "x")), 0),
        50, 1, 128));

    PagedTerminalHistorySnapshot history =
        (PagedTerminalHistorySnapshot) model.renderSnapshot().history;
    assertEquals(SlotState.UNAVAILABLE, history.slotStateAt(0));
    assertNull(history.firstRequestablePage(1, 49));
    assertEquals(50, history.lineBySeq(50).historySeq);
  }

  @Test
  public void lineOutsideRequestedRangeRejectsWholeTransactionAndKeepsMigration() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(V2ModelTestData.baseline(1, 1)));
    model.consumeRenderUpdate();
    assertTrue(model.applyScreenPatch(new ScreenPatchV2(
        "i1", 1, 1, 1, 2, new long[] {2000},
        Collections.singletonList(V2ModelTestData.line(2000, 1, 0, "b")),
        null, null, null, null, null, null)));
    model.consumeRenderUpdate();
    assertEquals(1, model.pendingHistoryMigrationCountForTest());
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
    assertEquals(1, model.pendingHistoryMigrationCountForTest());
    assertNull(model.loadedHistorySeqForLineIdForTest(1000));
    assertFalse(model.renderPublicationPendingForTest());
  }

  @Test
  public void lineOutsideAvailableExtentRejectsWholeTransaction() {
    RemoteTerminalModel model = baselineModel();
    assertRejected(model, range(HistoryRangeResult.Status.OK, new HistoryExtent(110, 300),
        V2ModelTestData.line(10_105, 1, 105, "x")), 100, 127);
    assertNull(model.loadedHistorySeqForLineIdForTest(10_105));
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
  public void partialRangeCommitsAndLeavesOtherSlotsRequestableByExtent() {
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
    assertEquals(SlotState.UNAVAILABLE, history.slotStateAt(99));
    assertEquals(SlotState.LOADED, history.slotStateAt(109));
    assertEquals(SlotState.LOADED, history.slotStateAt(119));
    assertEquals(SlotState.UNLOADED, history.slotStateAt(120));
  }

  @Test
  public void trimmedRangePreservesResidentLineOutsideRemoteExtent() {
    RemoteTerminalModel model = baselineModel();
    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "r", "i1", 1, HistoryRangeResult.Status.TRIMMED,
        new HistoryExtent(200, 300),
        Collections.singletonList(V2ModelTestData.line(200, 1, 200, "h")), 0),
        200, 100, 256));

    PagedTerminalHistorySnapshot history =
        (PagedTerminalHistorySnapshot) model.renderSnapshot().history;
    assertEquals(173, history.lineBySeq(173).historySeq);
    assertEquals(SlotState.LOADED, history.slotStateAt(172));
    assertEquals(SlotState.UNAVAILABLE, history.slotStateAt(99));
    assertNull(history.firstRequestablePage(100, 199));
  }

  private static RemoteTerminalModel baselineModel() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(V2ModelTestData.baseline(1, 1)));
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
