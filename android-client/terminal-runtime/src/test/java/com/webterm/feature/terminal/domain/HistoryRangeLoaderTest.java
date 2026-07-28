package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.PagedTerminalHistory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class HistoryRangeLoaderTest {
  @Test
  public void missingVisibleRangeIsExpandedWithoutPageAlignment() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    loader.setDemand(new HistoryRangeLoader.Demand(937, 1012, 937, -1));
    PagedTerminalHistory history =
        new PagedTerminalHistory(HistoryBudget.defaults(), line -> 1);
    history.edit().setExtent(900, 1500).setAvailableExtent(900, 1500).commit();

    HistoryRangeLoader.Range range =
        loader.firstMissingRange(
            "i1", 3, 7, new HistoryExtent(900, 1500), history.snapshot());

    assertEquals("i1", range.instanceId);
    assertEquals(3, range.layoutEpoch);
    assertEquals(7, range.generation);
    assertEquals(900, range.fromSeq);
    assertEquals(1012, range.toSeq);
  }

  @Test
  public void oneActiveRequestAndLatestDemandOnly() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    AtomicBoolean firstCancelled = new AtomicBoolean();
    assertTrue(loader.begin(
        new HistoryRangeLoader.Range("i", 1, 1, 10, 20), () -> firstCancelled.set(true)));
    assertFalse(loader.begin(new HistoryRangeLoader.Range("i", 1, 1, 30, 40), () -> {}));
    assertFalse(firstCancelled.get());

    HistoryRangeLoader.ActiveRequest active = loader.activeRequest();
    loader.setDemand(new HistoryRangeLoader.Demand(500, 550, 500, 1));
    assertTrue(loader.complete(active));
    assertEquals(500, loader.latestDemand().visibleFromSeq);
    assertNull(loader.activeRequest());
  }

  @Test
  public void lifecycleResetCancelsAndRejectsLateCompletion() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    AtomicBoolean cancelled = new AtomicBoolean();
    assertTrue(loader.begin(
        new HistoryRangeLoader.Range("i", 1, 1, 1, 10), () -> cancelled.set(true)));
    HistoryRangeLoader.ActiveRequest active = loader.activeRequest();

    loader.resetLifecycle();

    assertTrue(cancelled.get());
    assertFalse(loader.isActive(active));
    assertFalse(loader.complete(active));
  }
}
