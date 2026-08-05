package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.BitSet;
import java.util.List;

public final class RenderDirtyStateMergeTest {

  private static RenderDirtyState state() {
    return new RenderDirtyState();
  }

  private static BitSet rows(int... indices) {
    BitSet b = new BitSet();
    for (int i : indices) b.set(i);
    return b;
  }

  @Test
  public void consecutiveUpScrollsMerge() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertEquals(2, dirty.screenScrollRows);
    assertEquals(rows(3, 4), dirty.exposedScreenRows);
    assertEquals(rows(3, 4), dirty.changedScreenRows);
    assertFalse(dirty.fullInvalidate);
    assertFalse(dirty.screenRegionInvalidate);
  }

  @Test
  public void writeThenScrollDoesNotFullInvalidate() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(2), 0, null, 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertFalse(dirty.fullInvalidate);
    assertFalse(dirty.screenRegionInvalidate);
    assertEquals(1, dirty.screenScrollRows);
    assertTrue(dirty.changedScreenRows.get(1)); // 旧行 2 随内容上移到 1
    assertTrue(dirty.changedScreenRows.get(4));
    assertEquals(rows(4), dirty.exposedScreenRows);
  }

  @Test
  public void scrollThenWriteDoesNotFullInvalidate() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, rows(2), 0, null, 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertFalse(dirty.fullInvalidate);
    assertFalse(dirty.screenRegionInvalidate);
    assertEquals(1, dirty.screenScrollRows);
    assertEquals(rows(2, 4), dirty.changedScreenRows);
    assertEquals(rows(4), dirty.exposedScreenRows);
  }

  @Test
  public void twoSameDirectionScrollsCompose() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, rows(4), 2, rows(3, 4), 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertEquals(3, dirty.screenScrollRows);
    assertFalse(dirty.fullInvalidate);
    assertFalse(dirty.screenRegionInvalidate);
  }

  @Test
  public void oppositeScrollsComposeToNetScroll() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 2, rows(3, 4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, rows(0), -1, rows(0), 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertFalse(dirty.fullInvalidate);
    assertFalse(dirty.screenRegionInvalidate);
    assertEquals(1, dirty.screenScrollRows);
  }

  @Test
  public void oppositeScrollsWithZeroNetKeepDirtyRows() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 2, rows(3, 4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, rows(0, 1), -2, rows(0, 1), 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertFalse(dirty.fullInvalidate);
    assertFalse(dirty.screenRegionInvalidate);
    assertEquals(0, dirty.screenScrollRows);
    assertFalse(dirty.changedScreenRows.isEmpty() && dirty.exposedScreenRows.isEmpty());
  }

  @Test
  public void mergeFromPreservesExposedRowsWhenNetScrollIsZero() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 2, rows(3, 4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, rows(0, 1), -2, rows(0, 1), 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertEquals(0, dirty.screenScrollRows);
    assertFalse(dirty.exposedScreenRows.isEmpty());
    BitSet exposedBeforeMergeFrom = (BitSet) dirty.exposedScreenRows.clone();

    RenderDirtyState target = state();
    target.mergeFrom(dirty, 5);

    assertFalse(target.fullInvalidate);
    assertFalse(target.screenRegionInvalidate);
    assertEquals(0, target.screenScrollRows);
    assertEquals(exposedBeforeMergeFrom, target.exposedScreenRows);
    assertFalse(target.exposedScreenRows.isEmpty());
  }

  @Test
  public void scrollBeyondScreenFallsBackToScreenRegion() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 3, rows(2, 3, 4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, rows(4), 3, rows(2, 3, 4), 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertFalse(dirty.fullInvalidate);
    assertTrue(dirty.screenRegionInvalidate);
    assertEquals(0, dirty.screenScrollRows);
    assertTrue(dirty.changedScreenRows.isEmpty());
    assertTrue(dirty.exposedScreenRows.isEmpty());
  }

  @Test
  public void firstPreviousCursorAndLastCurrentCursorArePreserved() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, true, 2, 3, false, false, false, false, false);
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, true, 0, 4, false, false, false, false, false);

    assertEquals(2, dirty.screenScrollRows);
    // 光标不随内容滚动平移：保留首 Commit 旧光标与末 Commit 新光标。
    assertEquals(2, dirty.previousCursorRow);
    assertEquals(4, dirty.currentCursorRow);
  }

  @Test
  public void scrollAfterBufferSwitchForcesFullInvalidate() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, null, 0, null, 5,
        false, false, false, -1, -1, false, false, false, false, true);

    assertTrue(dirty.fullInvalidate);
    assertFalse(dirty.screenRegionInvalidate);
    assertEquals(0, dirty.screenScrollRows);
  }

  @Test
  public void geometryChangeAfterScrollForcesFullInvalidate() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, null, 0, null, 5,
        false, true, false, -1, -1, false, false, false, false, false);

    assertTrue(dirty.fullInvalidate);
    assertFalse(dirty.screenRegionInvalidate);
    assertEquals(0, dirty.screenScrollRows);
  }

  @Test
  public void invalidRowCountFallsBackToScreenRegion() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(0), 1, rows(0), 0,
        false, false, false, -1, -1, false, false, false, false, false);

    assertFalse(dirty.fullInvalidate);
    assertTrue(dirty.screenRegionInvalidate);
  }

  @Test
  public void disjointHistoryRangesStaySeparate() {
    RenderDirtyState dirty = state();
    dirty.mergeHistoryRange(1000, 1100, false);
    dirty.mergeHistoryRange(100_000, 100_000, false);

    assertEquals(List.of(
        new HistorySeqRange(1000, 1100),
        new HistorySeqRange(100_000, 100_000)), dirty.historyRanges());
    assertFalse(dirty.historyRangesOverflow);
  }

  @Test
  public void tooManyHistoryRangesRequestFullAxisRebuild() {
    RenderDirtyState dirty = state();
    for (int i = 0; i < RenderDirtyState.MAX_HISTORY_DIRTY_RANGES + 1; i++) {
      dirty.mergeHistoryRange(10_000L + i * 100L, 10_000L + i * 100L, false);
    }

    assertTrue(dirty.historyRangesOverflow);
    assertTrue(dirty.changedHistoryFromSeq <= dirty.changedHistoryToSeq);
  }
}
