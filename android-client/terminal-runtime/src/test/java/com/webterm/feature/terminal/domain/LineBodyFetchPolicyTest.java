package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LineBodyFetchPolicyTest {
  private final LineBodyFetchPolicy policy = new LineBodyFetchPolicy();

  @Test
  public void twentyRowViewportTargetsMinimumBatch() {
    assertEquals(128, policy.desiredBatchKeys(demand(20)));
  }

  @Test
  public void fiftyRowViewportTargetsTwoHundredKeys() {
    assertEquals(200, policy.desiredBatchKeys(demand(50)));
  }

  @Test
  public void hugeViewportIsCappedAtMaxBatch() {
    assertEquals(512, policy.desiredBatchKeys(demand(10_000)));
  }

  @Test
  public void cancelDistanceIsAtLeastThirtyTwo() {
    assertEquals(32, policy.cancelDistanceLines(demand(1)));
  }

  @Test
  public void cancelDistanceGrowsWithViewport() {
    assertEquals(100, policy.cancelDistanceLines(demand(50)));
  }

  @Test
  public void shouldCancelOnlyWhenFarAndNonOverlapping() {
    VisibleBodyLoader.Demand next = demand(20);
    next = new VisibleBodyLoader.Demand(800, 819, 800, -1, 20, 1, 0L);
    assertTrue(policy.shouldCancel(100, 227, next));
    assertFalse(policy.shouldCancel(100, 227,
        new VisibleBodyLoader.Demand(200, 219, 200, -1, 20, 1, 0L)));
  }

  private static VisibleBodyLoader.Demand demand(int visibleRows) {
    return new VisibleBodyLoader.Demand(1, visibleRows, 1, 0, visibleRows, 1, 0L);
  }
}
