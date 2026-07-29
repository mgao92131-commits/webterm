package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

  private static HistoryRangeLoader.Range range(long from, long to) {
    return new HistoryRangeLoader.Range("i", 1, 1, from, to);
  }

  private static HistoryRangeLoader.Demand demand(long from, long to, int rows) {
    return new HistoryRangeLoader.Demand(from, to, from, 1, rows, 1, 1);
  }
}
