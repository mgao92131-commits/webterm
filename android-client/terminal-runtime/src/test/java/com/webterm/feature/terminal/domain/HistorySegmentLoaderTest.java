package com.webterm.feature.terminal.domain;

import org.junit.Test;

import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.HistoryCatalog;
import com.webterm.terminal.model.PagedTerminalHistory;
import com.webterm.terminal.model.SegmentKey;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HistorySegmentLoaderTest {
  @Test
  public void scrollDoesNotCancelActiveRequest() {
    HistorySegmentLoader loader = new HistorySegmentLoader();
    loader.setDemand(new HistorySegmentLoader.Demand(1, 50, 1, -1, 20));
    AtomicBoolean cancelled = new AtomicBoolean();
    assertTrue(loader.begin(new SegmentKey(1, 0), () -> cancelled.set(true)));
    loader.setDemand(new HistorySegmentLoader.Demand(5000, 5100, 5000, 1, 20));
    assertNotNull(loader.activeRequest());
    assertFalse(cancelled.get());
  }

  @Test
  public void prefersVisibleUnloadedSegment() {
    HistorySegmentLoader loader = new HistorySegmentLoader();
    PagedTerminalHistory history = new PagedTerminalHistory(
        HistoryBudget.defaults(), line -> 64);
    history.edit().setExtent(1, 256).setAvailableExtent(1, 256).commit();
    loader.setDemand(new HistorySegmentLoader.Demand(130, 180, 130, -1, 20));
    HistoryCatalog catalog = new HistoryCatalog(1, 1, 256, 256);
    SegmentKey key = loader.highestPriorityMissing(catalog, history.snapshot());
    assertNotNull(key);
    assertEquals(1, key.number);
  }

  @Test
  public void completeThenPicksLatestDemand() {
    HistorySegmentLoader loader = new HistorySegmentLoader();
    assertTrue(loader.begin(new SegmentKey(1, 0), () -> {}));
    HistorySegmentLoader.ActiveRequest active = loader.activeRequest();
    loader.setDemand(new HistorySegmentLoader.Demand(200, 250, 200, 1, 10));
    assertTrue(loader.complete(active));
    assertNull(loader.activeRequest());
    PagedTerminalHistory history = new PagedTerminalHistory(
        HistoryBudget.defaults(), line -> 64);
    history.edit().setExtent(1, 256).setAvailableExtent(1, 256).commit();
    SegmentKey next = loader.highestPriorityMissing(
        new HistoryCatalog(1, 1, 256, 256), history.snapshot());
    assertNotNull(next);
    assertEquals(1, next.number);
  }
}
