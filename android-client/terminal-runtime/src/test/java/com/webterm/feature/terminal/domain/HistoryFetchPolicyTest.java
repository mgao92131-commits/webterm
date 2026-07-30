package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HistoryFetchPolicyTest {
  private final HistoryFetchPolicy policy = new HistoryFetchPolicy();

  @Test
  public void overlappingViewportKeepsActiveRequest() {
    assertFalse(policy.shouldCancel(
        range(100, 180),
        demand(150, 220, 40)));
  }

  @Test
  public void nearbyViewportKeepsActiveRequest() {
    assertFalse(policy.shouldCancel(
        range(100, 180),
        demand(200, 240, 40)));
  }

  @Test
  public void distantViewportCancelsActiveRequest() {
    assertTrue(policy.shouldCancel(
        range(100, 180),
        demand(1_000, 1_040, 40)));
  }

  @Test
  public void visibleRowsControlDynamicBatch() {
    assertTrue(policy.desiredBatchLines(demand(1, 10, 10))
        < policy.desiredBatchLines(demand(1, 80, 80)));
  }

  @Test
  public void batchIsClampedToRevisedBounds() {
    assertEquals(128L, policy.desiredBatchLines(demand(1, 1, 1)));
    assertEquals(224L, policy.desiredBatchLines(demand(1, 56, 56)));
    assertEquals(512L, policy.desiredBatchLines(demand(1, 1_000, 1_000)));
  }

  @Test
  public void cancellationDistanceDoesNotGrowWithFourViewportBatch() {
    HistoryRangeLoader.Demand atBoundary = demand(293, 348, 56);
    HistoryRangeLoader.Demand beyondBoundary = demand(294, 349, 56);
    assertEquals(112L, policy.cancelDistanceLines(atBoundary));
    assertFalse(policy.shouldCancel(range(1, 180), atBoundary));
    assertTrue(policy.shouldCancel(range(1, 180), beyondBoundary));
  }

  @Test
  public void activeScrollBypassesTailDebounce() {
    HistoryRangeLoader.Range tail =
        new HistoryRangeLoader.Range("i", 1, 1, 100, 100, 1, 1);
    HistoryRangeLoader.Demand scrolling =
        new HistoryRangeLoader.Demand(100, 100, 100, -1, 20, 1, 1);

    assertFalse(policy.shouldDebounceTail(tail, scrolling, 100));
  }

  private static HistoryRangeLoader.Range range(long from, long to) {
    return new HistoryRangeLoader.Range("i", 1, 1, from, to);
  }

  private static HistoryRangeLoader.Demand demand(long from, long to, int rows) {
    return new HistoryRangeLoader.Demand(from, to, from, 1, rows, 1, 1);
  }
}
