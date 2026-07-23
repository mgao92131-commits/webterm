package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class HistoryRequestCoordinatorTest {
  @Test
  public void reconnectClearRejectsLateHistoryResponse() {
    HistoryRequestCoordinator coordinator = new HistoryRequestCoordinator();
    String first = coordinator.nextRequestId();
    coordinator.markPending(first);
    assertTrue(coordinator.accept(first));
    coordinator.clear();
    assertFalse(coordinator.accept(first));

    String second = coordinator.nextRequestId();
    coordinator.markPending(second);
    assertFalse(coordinator.accept(first));
    assertTrue(coordinator.accept(second));
  }

  @Test
  public void rangeDedupeTimeoutAndCompatibleBaselineRetention() {
    HistoryRequestCoordinator coordinator = new HistoryRequestCoordinator();
    coordinator.markPending("r1", 129, 256, 180, "i1", 7, 2);
    assertTrue(coordinator.isRangePending(129, 256));

    coordinator.retainCompatible("i1", 7);
    assertTrue(coordinator.accept("r1"));
    HistoryRequestCoordinator.Pending expired = coordinator.expire("r1");
    assertEquals(2, expired.retryAttempt);
    assertFalse(coordinator.isRangePending(129, 256));
    assertNull(coordinator.complete("r1"));

    coordinator.markPending("r2", 1, 128, 1, "i1", 7, 0);
    coordinator.retainCompatible("i1", 8);
    assertFalse(coordinator.accept("r2"));
  }
}
