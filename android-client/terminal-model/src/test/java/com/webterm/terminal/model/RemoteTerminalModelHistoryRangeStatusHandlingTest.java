package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
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
}
