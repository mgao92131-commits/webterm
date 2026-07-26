package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Test;

public final class ScreenMailboxTest {
  @Test
  public void overflowDiscardsCommitChainAndEmitsFenceBeforeNewMessages() {
    ScreenMailbox mailbox = new ScreenMailbox(2, 10L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    mailbox.offer(1L, source, new byte[] {1, 2}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT);
    mailbox.offer(1L, source, new byte[] {3, 4}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT);
    long oldGeneration = mailbox.generation();

    mailbox.offer(1L, source, new byte[] {5}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT);

    ScreenMailbox.Drain first = mailbox.poll();
    assertNotNull(first.fence);
    assertNull(first.message);
    assertEquals("screen mailbox exceeded frame budget", first.fence.reason);
    assertEquals(oldGeneration + 1L, mailbox.generation());
    assertNull(mailbox.poll());
  }

  @Test
  public void overflowDiscardsAllPendingProjectionMessages() {
    ScreenMailbox mailbox = new ScreenMailbox(2, 10L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    mailbox.offer(1L, source, new byte[] {1}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT);
    mailbox.offer(1L, source, new byte[] {2}, true, ScreenMailbox.MessageKind.BASELINE);

    mailbox.offer(1L, source, new byte[] {3}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT);

    assertNotNull(mailbox.poll().fence);
    assertNull(mailbox.poll());
  }

  @Test
  public void resetAndAbandonDrainAllowFutureOffersToReschedule() {
    ScreenMailbox mailbox = new ScreenMailbox(2, 10L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);

    assertTrue(mailbox.offer(1L, source, new byte[] {1}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT).scheduleDrain);
    mailbox.reset();
    assertTrue(mailbox.offer(2L, source, new byte[] {2}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT).scheduleDrain);
    mailbox.abandonDrain();
    assertTrue(mailbox.offer(2L, source, new byte[] {3}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT).scheduleDrain);
  }

  @Test
  public void historyAndControlMessagesRemainFifo() {
    ScreenMailbox mailbox = new ScreenMailbox(2, 10L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    mailbox.offer(1L, source, new byte[] {1}, true, ScreenMailbox.MessageKind.HISTORY_RANGE);
    mailbox.offer(1L, source, new byte[] {2}, true, ScreenMailbox.MessageKind.PONG);
    mailbox.offer(1L, source, new byte[] {3}, true, ScreenMailbox.MessageKind.INPUT_ACK);

    assertEquals(ScreenMailbox.MessageKind.INPUT_ACK, mailbox.poll().message.kind);
    assertEquals(ScreenMailbox.MessageKind.HISTORY_RANGE, mailbox.poll().message.kind);
    assertEquals(ScreenMailbox.MessageKind.PONG, mailbox.poll().message.kind);
    assertNull(mailbox.poll());
  }

  @Test
  public void sustainedProjectionCannotStarveUrgentControl() {
    ScreenMailbox mailbox = new ScreenMailbox(64, 1024L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    for (int i = 0; i < 32; i++) {
      mailbox.offer(1L, source, new byte[] {(byte) i}, true,
          ScreenMailbox.MessageKind.TERMINAL_COMMIT);
    }
    mailbox.offer(1L, source, new byte[] {99}, true, ScreenMailbox.MessageKind.INPUT_ACK);
    mailbox.offer(1L, source, new byte[] {98}, true, ScreenMailbox.MessageKind.LAYOUT_LEASE);
    mailbox.offer(1L, source, new byte[] {97}, true, ScreenMailbox.MessageKind.EXIT);

    assertEquals(ScreenMailbox.MessageKind.INPUT_ACK, mailbox.poll().message.kind);
    assertEquals(ScreenMailbox.MessageKind.LAYOUT_LEASE, mailbox.poll().message.kind);
    assertEquals(ScreenMailbox.MessageKind.EXIT, mailbox.poll().message.kind);
  }

  @Test
  public void backgroundGetsFiniteFairTurnWithoutReorderingProjection() {
    ScreenMailbox mailbox = new ScreenMailbox(64, 1024L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    for (int i = 0; i < 20; i++) {
      mailbox.offer(1L, source, new byte[] {(byte) i}, true,
          ScreenMailbox.MessageKind.TERMINAL_COMMIT);
    }
    mailbox.offer(1L, source, new byte[] {99}, true, ScreenMailbox.MessageKind.PONG);

    for (int i = 0; i < ScreenMailbox.MAX_CONSECUTIVE_PROJECTION; i++) {
      ScreenMailbox.Message message = mailbox.poll().message;
      assertEquals(ScreenMailbox.MessageKind.TERMINAL_COMMIT, message.kind);
      assertEquals((byte) i, message.payload[0]);
    }
    assertEquals(ScreenMailbox.MessageKind.PONG, mailbox.poll().message.kind);
    for (int i = ScreenMailbox.MAX_CONSECUTIVE_PROJECTION; i < 20; i++) {
      ScreenMailbox.Message message = mailbox.poll().message;
      assertEquals(ScreenMailbox.MessageKind.TERMINAL_COMMIT, message.kind);
      assertEquals((byte) i, message.payload[0]);
    }
  }

  @Test
  public void backgroundBudgetDropsOldestAndKeepsCountersExact() {
    ScreenMailbox mailbox = new ScreenMailbox(
        4, 16L, 4, 16L, 3, 3L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    long dropped = 0;
    for (int i = 0; i < 100; i++) {
      ScreenMailbox.Offer offer = mailbox.offer(
          1L, source, new byte[] {(byte) i}, true, ScreenMailbox.MessageKind.PONG);
      dropped += offer.droppedBackgroundMessages;
      assertTrue(mailbox.pendingMessages() <= 3);
      assertTrue(mailbox.pendingBytes() <= 3);
    }
    assertEquals(97, dropped);
    assertEquals(3, mailbox.pendingMessages());
    assertEquals(3, mailbox.pendingBytes());
    assertEquals((byte) 97, mailbox.poll().message.payload[0]);
    assertEquals((byte) 98, mailbox.poll().message.payload[0]);
    assertEquals((byte) 99, mailbox.poll().message.payload[0]);
    assertEquals(0, mailbox.pendingMessages());
    assertEquals(0, mailbox.pendingBytes());
  }

  @Test
  public void urgentOverflowEmitsBoundedRecoveryFenceInsteadOfSilentDrop() {
    ScreenMailbox mailbox = new ScreenMailbox(
        4, 16L, 2, 2L, 2, 2L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    mailbox.offer(1L, source, new byte[] {1}, true, ScreenMailbox.MessageKind.INPUT_ACK);
    mailbox.offer(1L, source, new byte[] {2}, true, ScreenMailbox.MessageKind.LAYOUT_LEASE);
    mailbox.offer(1L, source, new byte[] {3}, true, ScreenMailbox.MessageKind.EXIT);

    ScreenMailbox.Drain drain = mailbox.poll();
    assertNotNull(drain.fence);
    assertEquals("screen mailbox urgent control exceeded byte budget", drain.fence.reason);
    assertEquals(0, mailbox.pendingMessages());
    assertEquals(0, mailbox.pendingBytes());
    assertNull(mailbox.poll());
  }

  @Test
  public void overflowFenceDropsOldGenerationButKeepsNewProjectionFifo() {
    ScreenMailbox mailbox = new ScreenMailbox(2, 10L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    mailbox.offer(1L, source, new byte[] {1}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT);
    mailbox.offer(1L, source, new byte[] {2}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT);
    mailbox.offer(1L, source, new byte[] {3}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT);
    mailbox.offer(1L, source, new byte[] {4}, true,
        ScreenMailbox.MessageKind.BASELINE);
    mailbox.offer(1L, source, new byte[] {5}, true,
        ScreenMailbox.MessageKind.TERMINAL_COMMIT);

    assertNotNull(mailbox.poll().fence);
    assertEquals(4, mailbox.poll().message.payload[0]);
    assertEquals(5, mailbox.poll().message.payload[0]);
    assertNull(mailbox.poll());
  }

  @Test
  public void repeatedOverflowCoalescesIntoOneBoundedFence() {
    ScreenMailbox mailbox = new ScreenMailbox(1, 1L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    for (int overflow = 0; overflow < 3; overflow++) {
      mailbox.offer(1L, source, new byte[] {1}, true,
          ScreenMailbox.MessageKind.TERMINAL_COMMIT);
      mailbox.offer(1L, source, new byte[] {2}, true,
          ScreenMailbox.MessageKind.TERMINAL_COMMIT);
    }

    ScreenMailbox.Drain drain = mailbox.poll();
    assertNotNull(drain.fence);
    assertEquals(3, drain.fence.overflowCount);
    assertEquals(0, mailbox.pendingMessages());
    assertEquals(0, mailbox.pendingBytes());
    assertNull(mailbox.poll());
  }

  @Test
  public void inputAckOrderingIsPreserved() {
    ScreenMailbox mailbox = new ScreenMailbox(1, 1L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    mailbox.offer(1L, source, new byte[] {1}, true, ScreenMailbox.MessageKind.INPUT_ACK);
    mailbox.offer(1L, source, new byte[] {2}, true, ScreenMailbox.MessageKind.INPUT_ACK);
    mailbox.offer(1L, source, new byte[] {3}, true, ScreenMailbox.MessageKind.EXIT);

    assertEquals(1, mailbox.poll().message.payload[0]);
    assertEquals(2, mailbox.poll().message.payload[0]);
    assertEquals(3, mailbox.poll().message.payload[0]);
  }

}
