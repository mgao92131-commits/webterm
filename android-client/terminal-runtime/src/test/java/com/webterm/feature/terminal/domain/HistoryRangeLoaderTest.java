package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.BodyCache;
import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.HistoryCatalog;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryRenderView;
import com.webterm.terminal.model.SemanticHistoryRenderView;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class HistoryRangeLoaderTest {
  @Test
  public void missingVisibleRangeIsExpandedWithoutPageAlignment() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    loader.setDemand(new HistoryRangeLoader.Demand(937, 1012, 937, -1));
    HistoryRenderView history = emptyHistory(new HistoryExtent(900, 1500));

    HistoryRangeLoader.Range range =
        loader.firstMissingRange(
            "i1", 3, 7, new HistoryExtent(900, 1500), history);

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
    HistoryRenderView history = emptyHistory(new HistoryExtent(1, 5000));

    HistoryRangeLoader.Range range = loader.firstMissingRange(
        "i1", 1, 1, new HistoryExtent(1, 5000), history);
    assertEquals(1, range.fromSeq);
    assertEquals(5000, range.toSeq);
  }

  @Test
  public void observedTrimWatermarkSuppressesRequestsWithoutChangingWsExtent() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    loader.setDemand(new HistoryRangeLoader.Demand(1, 20, 1, -1));
    HistoryExtent wsExtent = new HistoryExtent(1, 100);
    HistoryRenderView history = emptyHistory(wsExtent);

    assertEquals(1, loader.firstMissingRange(
        "i1", 1, 1, wsExtent, history).fromSeq);
    loader.observeServerExtent("i1", 1, 1, new HistoryExtent(50, 100));
    assertNull(loader.firstMissingRange("i1", 1, 1, wsExtent, history));
    assertEquals(new HistoryExtent(1, 100), wsExtent);

    loader.resetLifecycle();
    assertEquals(1, loader.firstMissingRange(
        "i1", 1, 1, wsExtent, history).fromSeq);
  }

  @Test
  public void observedTrimWatermarkResetsWhenProjectionChanges() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    loader.setDemand(new HistoryRangeLoader.Demand(1, 20, 1, -1));
    HistoryExtent extent = new HistoryExtent(1, 100);
    HistoryRenderView history = emptyHistory(extent);
    loader.observeServerExtent("i1", 1, 1, new HistoryExtent(50, 100));

    assertNull(loader.firstMissingRange("i1", 1, 1, extent, history));
    assertEquals(1, loader.firstMissingRange(
        "i2", 1, 1, extent, history).fromSeq);
  }

  @Test
  public void demandMovementDoesNotClearUnavailableIntervals() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryExtent extent = new HistoryExtent(1, 200);
    HistoryRenderView history = emptyHistory(extent);
    loader.setDemand(new HistoryRangeLoader.Demand(1, 100, 1, 1));
    HistoryRangeLoader.Range failed =
        new HistoryRangeLoader.Range("i1", 1, 1, 20, 30);
    loader.markRangeUnavailable(failed, 20, 30, "BODY_CONFLICT");

    loader.setDemand(new HistoryRangeLoader.Demand(15, 40, 15, 1));
    HistoryRangeLoader.Range next =
        loader.firstMissingRange("i1", 1, 1, extent, history);

    assertEquals(15, next.fromSeq);
    assertEquals(19, next.toSeq);
  }

  @Test
  public void prefetchNeverCrossesUnavailableInterval() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryExtent extent = new HistoryExtent(1, 200);
    HistoryRenderView history = emptyHistory(extent);
    HistoryRangeLoader.Range failed =
        new HistoryRangeLoader.Range("i1", 1, 1, 40, 50);
    loader.markRangeUnavailable(failed, 40, 50, "PROTOCOL");
    loader.setDemand(new HistoryRangeLoader.Demand(51, 70, 51, -1));

    HistoryRangeLoader.Range next =
        loader.firstMissingRange("i1", 1, 1, extent, history);

    assertEquals(51, next.fromSeq);
    assertEquals(70, next.toSeq);
  }

  @Test
  public void authoritativeRebindReleasesOnlyItsFailedPosition() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryExtent extent = new HistoryExtent(1, 200);
    HistoryRenderView history = emptyHistory(extent);
    HistoryRangeLoader.Range failed =
        new HistoryRangeLoader.Range("i1", 1, 1, 40, 42);
    loader.markRangeUnavailable(failed, 40, 42, "BODY_CONFLICT");

    loader.onAuthoritativeBinding("i1", 1, 1, 41);
    loader.setDemand(new HistoryRangeLoader.Demand(40, 42, 41, 0));
    HistoryRangeLoader.Range next =
        loader.firstMissingRange("i1", 1, 1, extent, history);

    assertEquals(41, next.fromSeq);
    assertEquals(41, next.toSeq);
  }

  private static HistoryRenderView emptyHistory(HistoryExtent extent) {
    HistoryCatalog catalog = new HistoryCatalog().edit().setExtent(extent).commit();
    BodyCache cache = new BodyCache(HistoryBudget.defaults()).edit()
        .setHistoryExtent(extent)
        .setAvailableExtent(extent)
        .commit();
    return new SemanticHistoryRenderView(catalog, cache);
  }
}
