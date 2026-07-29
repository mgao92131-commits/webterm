package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.BodyCache;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.HistoryCatalog;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryRenderView;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
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

  @Test
  public void distantDemandCancelsFetchingRequestAndStartsNewEpoch() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    AtomicBoolean cancelled = new AtomicBoolean();
    HistoryRangeLoader.Demand first =
        loader.acceptDemand(1, 20, 1, -1, 20, 10);
    assertTrue(loader.begin(
        new HistoryRangeLoader.Range("i1", 1, 1, 1, 40, first.demandEpoch),
        () -> cancelled.set(true)));

    HistoryRangeLoader.Demand second =
        loader.acceptDemand(1000, 1020, 1000, 1, 21, 20);

    assertTrue(second.demandEpoch > first.demandEpoch);
    assertTrue(loader.shouldCancelFor(second));
    assertTrue(loader.cancelActiveForDemand());
    assertTrue(cancelled.get());
    assertNull(loader.activeRequest());
  }

  @Test
  public void overlappingDemandKeepsFetchingRequest() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    AtomicBoolean cancelled = new AtomicBoolean();
    HistoryRangeLoader.Demand first =
        loader.acceptDemand(100, 140, 100, 1, 41, 10);
    assertTrue(loader.begin(
        new HistoryRangeLoader.Range("i1", 1, 1, 90, 180, first.demandEpoch),
        () -> cancelled.set(true)));

    HistoryRangeLoader.Demand second =
        loader.acceptDemand(130, 170, 130, 1, 41, 20);

    assertFalse(loader.shouldCancelFor(second));
    assertFalse(cancelled.get());
    assertTrue(loader.isActive(loader.activeRequest()));
  }

  @Test
  public void anchorAndDirectionHintsReuseDemandEpochForSameCoverage() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryRangeLoader.Demand first =
        loader.acceptDemand(100, 140, 110, -1, 41, 10);

    HistoryRangeLoader.Demand second =
        loader.acceptDemand(100, 140, 130, 1, 80, 20);

    assertEquals(first.demandEpoch, second.demandEpoch);
    assertEquals(130, second.anchorSeq);
    assertEquals(1, second.direction);
    assertEquals(80, second.visibleRowCount);
  }

  @Test
  public void activeRequestCoveringNewMissingRangeReusesDemandEpoch() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryExtent extent = new HistoryExtent(1, 500);
    HistoryRenderView history = emptyHistory(extent);
    HistoryRangeLoader.Demand first = loader.acceptDemand(
        100, 120, 100, -1, 21, 10,
        "i1", 1, 1, extent, history);
    assertTrue(loader.begin(
        new HistoryRangeLoader.Range("i1", 1, 1, 1, 300, first.demandEpoch),
        () -> {}));

    HistoryRangeLoader.Demand second = loader.acceptDemand(
        130, 150, 130, 1, 21, 20,
        "i1", 1, 1, extent, history);

    assertEquals(first.demandEpoch, second.demandEpoch);
    assertFalse(loader.shouldCancelFor(second));
  }

  @Test
  public void sameExpandedMissingTargetReusesEpochWithoutActiveRequest() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryExtent extent = new HistoryExtent(1, 100);
    HistoryRenderView history = emptyHistory(extent);
    HistoryRangeLoader.Demand first = loader.acceptDemand(
        1, 1, 1, 0, 1, 10,
        "i1", 1, 1, extent, history);

    HistoryRangeLoader.Demand second = loader.acceptDemand(
        2, 2, 2, 0, 1, 20,
        "i1", 1, 1, extent, history);

    assertEquals(first.demandEpoch, second.demandEpoch);
    assertEquals(1, loader.firstMissingRange(
        "i1", 1, 1, extent, history).fromSeq);
    assertEquals(32, loader.firstMissingRange(
        "i1", 1, 1, extent, history).toSeq);
  }

  @Test
  public void projectionIdentityChangeAllocatesNewDemandEpoch() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryExtent extent = new HistoryExtent(1, 500);
    HistoryRenderView history = emptyHistory(extent);
    HistoryRangeLoader.Demand first = loader.acceptDemand(
        100, 120, 100, -1, 21, 10,
        "i1", 1, 1, extent, history);

    HistoryRangeLoader.Demand second = loader.acceptDemand(
        100, 120, 100, -1, 21, 20,
        "i2", 1, 1, extent, history);

    assertTrue(second.demandEpoch > first.demandEpoch);
  }

  @Test
  public void fullyLoadedViewportKeepsDemandForEvictionPinsWithoutFetchTarget()
      throws Exception {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryExtent extent = new HistoryExtent(1, 1);
    HistoryRenderView history = loadedHistory(extent);

    HistoryRangeLoader.Demand demand = loader.acceptDemand(
        1, 1, 1, 0, 1, 10,
        "i1", 1, 1, extent, history);

    assertEquals(demand, loader.latestDemand());
    assertNull(loader.firstMissingRange("i1", 1, 1, extent, history));
  }

  @Test
  public void lateCancelledRequestCannotCompleteReplacement() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryRangeLoader.Demand first =
        loader.acceptDemand(1, 20, 1, -1, 20, 10);
    assertTrue(loader.begin(
        new HistoryRangeLoader.Range("i1", 1, 1, 1, 40, first.demandEpoch),
        () -> {}));
    HistoryRangeLoader.ActiveRequest old = loader.activeRequest();
    loader.cancelActiveForDemand();

    HistoryRangeLoader.Demand second =
        loader.acceptDemand(1000, 1020, 1000, 1, 21, 20);
    assertTrue(loader.begin(
        new HistoryRangeLoader.Range("i1", 1, 1, 990, 1050, second.demandEpoch),
        () -> {}));
    HistoryRangeLoader.ActiveRequest replacement = loader.activeRequest();

    loader.responseArrived(old, 30);
    assertFalse(loader.beginApplying(old));
    assertFalse(loader.complete(old));
    assertTrue(loader.isActive(replacement));
  }

  @Test
  public void completionIsClassifiedByDemandEpochAndOverlap() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryRangeLoader.Demand first =
        loader.acceptDemand(100, 120, 100, 1, 21, 10);
    assertTrue(loader.begin(
        new HistoryRangeLoader.Range("i1", 1, 1, 90, 150, first.demandEpoch),
        () -> {}));
    HistoryRangeLoader.ActiveRequest request = loader.activeRequest();
    assertEquals(
        HistoryRangeLoader.CompletionDisposition.CURRENT,
        loader.completionDisposition(request));

    loader.acceptDemand(130, 160, 130, 1, 31, 20);
    assertEquals(
        HistoryRangeLoader.CompletionDisposition.PARTIAL,
        loader.completionDisposition(request));

    loader.acceptDemand(500, 520, 500, 1, 21, 30);
    assertEquals(
        HistoryRangeLoader.CompletionDisposition.OBSOLETE,
        loader.completionDisposition(request));
  }

  @Test
  public void visibleRowCountControlsDirectionalPrefetch() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryExtent extent = new HistoryExtent(1, 1000);
    HistoryRenderView history = emptyHistory(extent);
    loader.acceptDemand(500, 509, 500, -1, 80, 10);

    HistoryRangeLoader.Range range =
        loader.firstMissingRange("i1", 1, 1, extent, history);

    assertEquals(350, range.fromSeq);
    assertEquals(509, range.toSeq);
  }

  @Test
  public void tailSingleLineWaitsForOneDebounceWindowOnly() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    HistoryExtent extent = new HistoryExtent(1, 100);
    loader.acceptDemand(100, 100, 100, 0, 20, 10);
    HistoryRangeLoader.Range range =
        new HistoryRangeLoader.Range("i1", 1, 1, 100, 100,
            loader.latestDemand().demandEpoch);

    long token = loader.armTailDebounce(range, extent);
    assertTrue(token > 0);
    loader.acceptDemand(100, 100, 100, 0, 20, 20);
    assertEquals(-1, loader.armTailDebounce(range, extent));
    assertTrue(loader.releaseTailDebounce(token));
    assertEquals(0, loader.armTailDebounce(range, extent));
  }

  @Test
  public void growingAuthoritativeTailRestartsQuietPeriodWithinHardDeadline() {
    HistoryRangeLoader loader = new HistoryRangeLoader();
    loader.acceptDemand(100, 100, 100, 0, 20, 10);
    long first = loader.armTailDebounce(
        new HistoryRangeLoader.Range("i1", 1, 1, 100, 100,
            loader.latestDemand().demandEpoch),
        new HistoryExtent(1, 100));
    assertTrue(first > 0);

    loader.acceptDemand(101, 101, 101, 0, 20, 20);
    long restarted = loader.armTailDebounce(
        new HistoryRangeLoader.Range("i1", 1, 1, 101, 101,
            loader.latestDemand().demandEpoch),
        new HistoryExtent(1, 101));

    assertTrue(restarted > first);
    assertTrue(loader.tailDebounceDelayMs(restarted)
        <= HistoryFetchPolicy.TAIL_QUIET_PERIOD_MS);
    assertTrue(loader.tailDebounceDelayMs(restarted) >= 0L);
  }

  private static HistoryRenderView emptyHistory(HistoryExtent extent) {
    HistoryCatalog catalog = new HistoryCatalog().edit().setExtent(extent).commit();
    BodyCache cache = new BodyCache(HistoryBudget.defaults()).edit()
        .setHistoryExtent(extent)
        .setAvailableExtent(extent)
        .commit();
    return new SemanticHistoryRenderView(catalog, cache);
  }

  private static HistoryRenderView loadedHistory(HistoryExtent extent) throws Exception {
    LineKey key = new LineKey(1, 1);
    HistoryCatalog catalog = new HistoryCatalog().edit()
        .setExtent(extent)
        .bindNew(1, key)
        .commit();
    BodyCache cache = new BodyCache(HistoryBudget.defaults()).edit()
        .setHistoryExtent(extent)
        .setAvailableExtent(extent)
        .putHistory(1, key, new LineBody(
            1, false, new CellValue[] {CellValue.EMPTY}))
        .commit();
    return new SemanticHistoryRenderView(catalog, cache);
  }
}
