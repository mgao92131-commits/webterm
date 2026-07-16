package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
