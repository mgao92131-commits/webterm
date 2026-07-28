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

  @Test
  public void contiguousFiveThousandLineGapIsRequestedWhole() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    loader.setDemand(new HistoryRangeLoader.Demand(1, 5000, 1, 1));
    PagedTerminalHistory history =
        new PagedTerminalHistory(HistoryBudget.defaults(), line -> 1);
    history.edit().setExtent(1, 5000).setAvailableExtent(1, 5000).commit();

    HistoryRangeLoader.Range range = loader.firstMissingRange(
        "i1", 1, 1, new HistoryExtent(1, 5000), history.snapshot());
    assertEquals(1, range.fromSeq);
    assertEquals(5000, range.toSeq);
  }

  @Test
  public void observedTrimWatermarkSuppressesRequestsWithoutChangingWsExtent() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    loader.setDemand(new HistoryRangeLoader.Demand(1, 20, 1, -1));
    PagedTerminalHistory history =
        new PagedTerminalHistory(HistoryBudget.defaults(), line -> 1);
    HistoryExtent wsExtent = new HistoryExtent(1, 100);
    history.edit().setExtent(1, 100).setAvailableExtent(1, 100).commit();

    assertEquals(1, loader.firstMissingRange(
        "i1", 1, 1, wsExtent, history.snapshot()).fromSeq);
    loader.observeServerExtent("i1", 1, 1, new HistoryExtent(50, 100));
    assertNull(loader.firstMissingRange("i1", 1, 1, wsExtent, history.snapshot()));
    assertEquals(new HistoryExtent(1, 100), wsExtent);

    loader.resetLifecycle();
    assertEquals(1, loader.firstMissingRange(
        "i1", 1, 1, wsExtent, history.snapshot()).fromSeq);
  }

  @Test
  public void observedTrimWatermarkResetsWhenProjectionChanges() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    loader.setDemand(new HistoryRangeLoader.Demand(1, 20, 1, -1));
    PagedTerminalHistory history =
        new PagedTerminalHistory(HistoryBudget.defaults(), line -> 1);
    HistoryExtent extent = new HistoryExtent(1, 100);
    history.edit().setExtent(1, 100).setAvailableExtent(1, 100).commit();
    loader.observeServerExtent("i1", 1, 1, new HistoryExtent(50, 100));

    assertNull(loader.firstMissingRange("i1", 1, 1, extent, history.snapshot()));
    assertEquals(1, loader.firstMissingRange(
        "i2", 1, 1, extent, history.snapshot()).fromSeq);
  }
}
