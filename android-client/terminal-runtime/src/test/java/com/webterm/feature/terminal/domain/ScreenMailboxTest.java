package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Test;

public final class ScreenMailboxTest {
  @Test
  public void overflowDiscardsPatchChainAndEmitsFenceBeforeNewMessages() {
    ScreenMailbox mailbox = new ScreenMailbox(2, 10L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    mailbox.offer(1L, source, new byte[] {1, 2}, true, ScreenMailbox.MessageKind.PATCH);
    mailbox.offer(1L, source, new byte[] {3, 4}, true, ScreenMailbox.MessageKind.PATCH);
    long oldGeneration = mailbox.generation();

    mailbox.offer(1L, source, new byte[] {5}, true, ScreenMailbox.MessageKind.PATCH);

    ScreenMailbox.Drain first = mailbox.poll();
    assertNotNull(first.fence);
    assertNull(first.message);
    assertEquals("screen mailbox exceeded frame budget", first.fence.reason);
    assertEquals(oldGeneration + 1L, mailbox.generation());
    assertNull(mailbox.poll());
  }

  @Test
  public void overflowRetainsNewestSnapshotForRecovery() {
    ScreenMailbox mailbox = new ScreenMailbox(2, 10L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    mailbox.offer(1L, source, new byte[] {1}, true, ScreenMailbox.MessageKind.PATCH);
    mailbox.offer(1L, source, new byte[] {2}, true, ScreenMailbox.MessageKind.SNAPSHOT);

    mailbox.offer(1L, source, new byte[] {3}, true, ScreenMailbox.MessageKind.PATCH);

    assertNotNull(mailbox.poll().fence);
    ScreenMailbox.Drain retained = mailbox.poll();
    assertNotNull(retained.message);
    assertEquals(ScreenMailbox.MessageKind.SNAPSHOT, retained.message.kind);
    assertEquals(2, retained.message.payload[0]);
  }

  @Test
  public void resetAndAbandonDrainAllowFutureOffersToReschedule() {
    ScreenMailbox mailbox = new ScreenMailbox(2, 10L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);

    assertTrue(mailbox.offer(1L, source, new byte[] {1}, true,
        ScreenMailbox.MessageKind.PATCH).scheduleDrain);
    mailbox.reset();
    assertTrue(mailbox.offer(2L, source, new byte[] {2}, true,
        ScreenMailbox.MessageKind.PATCH).scheduleDrain);
    mailbox.abandonDrain();
    assertTrue(mailbox.offer(2L, source, new byte[] {3}, true,
        ScreenMailbox.MessageKind.PATCH).scheduleDrain);
  }
}
