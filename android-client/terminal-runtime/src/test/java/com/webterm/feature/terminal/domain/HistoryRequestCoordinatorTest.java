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
    coordinator.markPending("r1", 129, 256, 180, "i1", 7, 3, 2);
    assertTrue(coordinator.isRangePending(129, 256));

    coordinator.retainCompatible("i1", 7, 3);
    assertTrue(coordinator.accept("r1"));
    HistoryRequestCoordinator.Pending expired = coordinator.expire("r1");
    assertEquals(2, expired.retryAttempt);
    assertFalse(coordinator.isRangePending(129, 256));
    assertNull(coordinator.complete("r1"));

    coordinator.markPending("r2", 1, 128, 1, "i1", 7, 3, 0);
    coordinator.retainCompatible("i1", 8, 3);
    assertFalse(coordinator.accept("r2"));

    coordinator.markPending("r3", 1, 128, 1, "i1", 7, 3, 0);
    coordinator.retainCompatible("i1", 7, 4);
    assertFalse(coordinator.accept("r3"));
  }

  @Test
  public void reservePublishesOneImmutableWireIdentityAndCancelRollsItBack() {
    HistoryRequestCoordinator coordinator = new HistoryRequestCoordinator();
    HistoryRequestCoordinator.Pending reserved = coordinator.reserve(
        "r1", 129, 256, 180, "instance", 9, 4, 3);

    assertTrue(coordinator.accept("r1"));
    assertEquals("instance", reserved.instanceId);
    assertEquals(9, reserved.layoutEpoch);
    assertEquals(4, reserved.historyGeneration);
    assertEquals(129, reserved.fromSeq);
    assertEquals(256, reserved.toSeq);
    assertEquals(3, reserved.retryAttempt);
    assertEquals(reserved, coordinator.cancel("r1"));
    assertFalse(coordinator.accept("r1"));
  }
}
