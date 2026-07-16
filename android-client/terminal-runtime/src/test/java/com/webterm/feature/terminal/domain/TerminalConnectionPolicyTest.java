package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TerminalConnectionPolicyTest {
  private final TerminalConnectionPolicy policy = new TerminalConnectionPolicy();

  @Test
  public void onlyLogicalChannelUnavailabilityRebuildsScreenChannel() {
    assertEquals(TerminalConnectionPolicy.Decision.REBUILD_SCREEN_CHANNEL,
        policy.onInputDelivery(new ReliableInputTracker.Event(
            ReliableInputTracker.EventType.CHANNEL_UNAVAILABLE, "closed")));
    assertEquals(TerminalConnectionPolicy.Decision.CONTINUE_WAITING,
        policy.onInputDelivery(new ReliableInputTracker.Event(
            ReliableInputTracker.EventType.INPUT_QUEUE_FULL, "full")));
    assertEquals(TerminalConnectionPolicy.Decision.CONTINUE_WAITING,
        policy.onInputDelivery(new ReliableInputTracker.Event(
            ReliableInputTracker.EventType.TRANSPORT_REJECTED, "physical")));
  }
}
