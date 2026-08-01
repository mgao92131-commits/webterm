package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HistoryResidencyIndexTest {
  @Test
  public void unloadedPageIndexSkipsFullResidentPages() {
    HistoryExtent extent = new HistoryExtent(1, 384);
    HistoryResidencyIndex.Editor editor = new HistoryResidencyIndex().edit();
    editor.setExtent(extent).setAvailableExtent(extent);
    for (int seq = 1; seq <= HistoryResidencyIndex.PAGE_SIZE; seq++) {
      editor.put(seq, new LineKey(seq, 1));
    }
    HistoryResidencyIndex index = editor.commit();

    assertEquals(0, index.unloadedCount(0));
    assertEquals(128, index.unloadedCount(1));
    assertEquals(129, index.nearestUnloadedSeq(1, 384, 1));
    assertEquals(384, index.nearestUnloadedSeq(1, 384, -1));
    assertEquals(1, index.firstResidentSeq());
  }

  @Test
  public void invalidatingTheOnlyResidentPageRestoresUnloadedCount() {
    HistoryExtent extent = new HistoryExtent(1, 128);
    HistoryResidencyIndex.Editor editor = new HistoryResidencyIndex().edit();
    editor.setExtent(extent);
    editor.put(1, new LineKey(1, 1));
    editor.invalidate(1);
    HistoryResidencyIndex index = editor.commit();

    assertEquals(128, index.unloadedCount(0));
    assertEquals(1, index.nearestUnloadedSeq(1, 128, 1));
    assertEquals(-1, index.firstResidentSeq());
  }
}
