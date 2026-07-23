package com.webterm.terminal.model;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public final class PagedTerminalHistoryTest {
  private static PagedTerminalHistory history(HistoryBudget budget) {
    return new PagedTerminalHistory(budget, line -> 10);
  }

  private static TerminalLine line(long seq) {
    return new TerminalLine(
        seq, 1, seq, false, new TerminalCell[] {TerminalCell.EMPTY});
  }

  @Test
  public void sparseAbsolutePositionsStayStable() {
    PagedTerminalHistory history = history(new HistoryBudget(1000, 1000, 0, 0));
    history.edit()
        .setExtent(1, 547)
        .put(17, line(17))
        .put(443, line(443))
        .commit();

    PagedTerminalHistorySnapshot snapshot = history.snapshot();
    assertEquals(547, snapshot.logicalSize());
    assertSame(lineClass(), snapshot.lineAt(16).getClass());
    assertNull(snapshot.lineAt(17));
    assertEquals(SlotState.UNLOADED, snapshot.slotStateAt(17));
    assertEquals(443, snapshot.lineAt(442).historySeq);
  }

  @Test
  public void unavailableDoesNotMoveOtherRowsAndLoadedContentWins() {
    PagedTerminalHistory history = history(new HistoryBudget(1000, 1000, 0, 0));
    history.edit()
        .setExtent(10, 20)
        .markUnavailable(10, 15)
        .put(12, line(12))
        .commit();

    PagedTerminalHistorySnapshot snapshot = history.snapshot();
    assertEquals(SlotState.UNAVAILABLE, snapshot.slotStateAt(0));
    assertEquals(SlotState.LOADED, snapshot.slotStateAt(2));
    assertEquals(12, snapshot.lineAt(2).historySeq);
    assertEquals(11, snapshot.logicalSize());
  }

  @Test
  public void trimChangesExtentButEvictionDoesNot() {
    PagedTerminalHistory history = history(new HistoryBudget(1, 2, 0, 0));
    history.edit()
        .setExtent(1, 4)
        .putAll(Arrays.asList(line(1), line(2), line(3), line(4)))
        .evictIfNeeded(4)
        .commit();

    PagedTerminalHistorySnapshot evicted = history.snapshot();
    assertEquals(new HistoryExtent(1, 4), evicted.extent());
    assertTrue(evicted.loadedLineCount() <= 1);

    history.edit().setExtent(3, 4).commit();
    assertEquals(new HistoryExtent(3, 4), history.snapshot().extent());
    assertEquals(2, history.snapshot().logicalSize());
  }

  @Test(expected = IllegalStateException.class)
  public void sameSeqCannotBeRewritten() {
    PagedTerminalHistory history = history(new HistoryBudget(100, 100, 0, 0));
    history.edit().setExtent(1, 1).put(1, line(1)).commit();
    TerminalLine changed = new TerminalLine(
        99, 2, 1, false, new TerminalCell[] {TerminalCell.EMPTY});
    history.edit().put(1, changed);
  }

  @Test
  public void firstLoadedSeqIgnoresLogicalPlaceholders() {
    PagedTerminalHistory history = history(new HistoryBudget(1000, 1000, 0, 0));
    history.edit()
        .setExtent(1, 547)
        .put(420, line(420))
        .put(500, line(500))
        .commit();

    assertEquals(420, history.snapshot().firstLoadedSeq());
  }

  @Test
  public void visibleUnloadedSlotSelectsOneClippedPageAndUnavailableDoesNotRetry() {
    PagedTerminalHistory history = history(new HistoryBudget(1000, 1000, 0, 0));
    history.edit()
        .setExtent(10, 400)
        .markUnavailable(10, 128)
        .put(300, line(300))
        .commit();

    assertNull(history.snapshot().firstRequestablePage(10, 100));
    assertArrayEquals(new long[] {129, 256},
        history.snapshot().firstRequestablePage(140, 180));
    assertArrayEquals(new long[] {257, 384},
        history.snapshot().firstRequestablePage(300, 320));
  }

  private static Class<?> lineClass() {
    return TerminalLine.class;
  }
}
