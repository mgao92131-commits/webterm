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
  public void remoteAvailableExtentDoesNotMoveLoadedRows() {
    PagedTerminalHistory history = history(new HistoryBudget(1000, 1000, 0, 0));
    history.edit()
        .setExtent(10, 20)
        .setAvailableExtent(16, 20)
        .put(17, line(17))
        .commit();

    PagedTerminalHistorySnapshot snapshot = history.snapshot();
    assertEquals(SlotState.UNAVAILABLE, snapshot.slotStateAt(0));
    assertEquals(SlotState.LOADED, snapshot.slotStateAt(7));
    assertEquals(17, snapshot.lineAt(7).historySeq);
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

  @Test
  public void trimReleasesLoadedLineIdentityWithoutGrowingAProjectionWideIndex() {
    PagedTerminalHistory history = history(new HistoryBudget(100, 100, 0, 0));
    TerminalLine firstOwner = new TerminalLine(
        500, 1, 1, false, new TerminalCell[] {TerminalCell.EMPTY});
    history.edit().setExtent(1, 2).put(1, firstOwner).commit();
    assertEquals(Long.valueOf(1), history.historySeqByLineId(500));
    assertEquals(1, history.loadedLineIdentityCountForTest());

    history.edit().setExtent(2, 2).commit();
    assertNull(history.historySeqByLineId(500));
    assertEquals(0, history.loadedLineIdentityCountForTest());

    TerminalLine secondOwner = new TerminalLine(
        500, 2, 3, false, new TerminalCell[] {TerminalCell.EMPTY});
    history.edit().setExtent(2, 3).put(3, secondOwner).commit();
    assertEquals(Long.valueOf(3), history.historySeqByLineId(500));
    assertEquals(history.snapshot().loadedLineCount(),
        history.loadedLineIdentityCountForTest());
  }

  @Test
  public void highHistorySeqExtentAndPageBoundariesDoNotOverflow() {
    long first = Long.MAX_VALUE - 128;
    long last = Long.MAX_VALUE - 1;
    HistoryExtent extent = new HistoryExtent(first, last);
    assertEquals(128, extent.logicalSize());

    PagedTerminalHistory history = history(new HistoryBudget(100, 100, 0, 0));
    history.edit().setExtent(first, last).put(last, line(last)).commit();
    PagedTerminalHistorySnapshot snapshot = history.snapshot();
    assertEquals(last, snapshot.firstLoadedSeq());
    long[] page = snapshot.firstRequestablePage(first, last);
    assertNotNull(page);
    assertTrue(page[0] >= 1);
    assertTrue(page[1] >= page[0]);
    assertTrue(page[1] <= last);
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
        .setAvailableExtent(129, 400)
        .put(300, line(300))
        .commit();

    assertNull(history.snapshot().firstRequestablePage(10, 100));
    assertArrayEquals(new long[] {129, 256},
        history.snapshot().firstRequestablePage(140, 180));
    assertArrayEquals(new long[] {257, 384},
        history.snapshot().firstRequestablePage(300, 320));
  }

  @Test
  public void editorIdentityOverlayDoesNotCopyLoadedIndexAtAnyScale() {
    for (int loaded : new int[] {1_000, 5_000, 10_000}) {
      PagedTerminalHistory history = history(
          new HistoryBudget(20_000, 20_000, 0, 0));
      PagedTerminalHistory.Editor initial = history.edit().setExtent(1, loaded + 1_000L);
      for (int seq = 1; seq <= loaded; seq++) initial.put(seq, line(seq));
      initial.commit();

      for (int offset = 1; offset <= 1_000; offset++) {
        long seq = loaded + offset;
        PagedTerminalHistory.Editor editor = history.edit();
        assertEquals(0, editor.identityEntriesCopiedForTest());
        editor.put(seq, line(seq)).commit();
      }
      assertEquals(loaded + 1_000, history.loadedLineIdentityCountForTest());
      assertEquals(Long.valueOf(loaded + 1_000L),
          history.historySeqByLineId(loaded + 1_000L));
    }
  }

  @Test
  public void unavailableOutsideRemoteExtentDoesNotCreateResidentPages() {
    PagedTerminalHistory history = history(new HistoryBudget(1000, 1000, 0, 0));
    history.edit()
        .setExtent(1, 1_000_000)
        .setAvailableExtent(999_001, 1_000_000)
        .commit();

    PagedTerminalHistorySnapshot snapshot = history.snapshot();
    for (long seq = 1; seq < 900_000; seq += PagedTerminalHistory.PAGE_SIZE) {
      long index = seq - snapshot.firstSeq();
      assertEquals(SlotState.UNAVAILABLE, snapshot.slotStateAt(index));
      assertNull(snapshot.firstRequestablePage(seq,
          Math.min(seq + PagedTerminalHistory.PAGE_SIZE - 1, 900_000)));
    }
    assertEquals(0, history.residentPageCountForTest());
    assertEquals(0, history.unavailableRangeCountForTest());
    assertEquals(0, snapshot.loadedLineCount());
    assertEquals(0, snapshot.estimatedByteCount());
  }

  @Test
  public void trimmedAvailableExtentHidesPreviouslyResidentContent() {
    PagedTerminalHistory history = history(new HistoryBudget(1000, 1000, 0, 0));
    history.edit().setExtent(1, 200).put(10, line(10)).put(150, line(150)).commit();
    history.edit().setAvailableExtent(100, 200).commit();

    PagedTerminalHistorySnapshot snapshot = history.snapshot();
    assertNull(snapshot.lineBySeq(10));
    assertEquals(SlotState.UNAVAILABLE, snapshot.slotStateAt(9));
    assertEquals(150, snapshot.lineBySeq(150).historySeq);
    assertEquals(150, snapshot.firstLoadedSeq());
  }

  private static Class<?> lineClass() {
    return TerminalLine.class;
  }
}
