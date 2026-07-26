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

    assertEquals(ScreenMailbox.MessageKind.HISTORY_RANGE, mailbox.poll().message.kind);
    assertEquals(ScreenMailbox.MessageKind.PONG, mailbox.poll().message.kind);
    assertEquals(ScreenMailbox.MessageKind.INPUT_ACK, mailbox.poll().message.kind);
    assertNull(mailbox.poll());
  }

  @Test
  public void controlFrameBurstDoesNotDiscardRetainedBaseline() {
    ScreenMailbox mailbox = new ScreenMailbox(1, 4L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    mailbox.offer(1L, source, new byte[] {1}, true, ScreenMailbox.MessageKind.BASELINE);
    for (int i = 0; i < 128; i++) {
      mailbox.offer(1L, source, new byte[] {(byte) i}, true,
          ScreenMailbox.MessageKind.PONG);
    }

    assertEquals(ScreenMailbox.MessageKind.BASELINE, mailbox.poll().message.kind);
    for (int i = 0; i < 128; i++) {
      ScreenMailbox.Message message = mailbox.poll().message;
      assertEquals(ScreenMailbox.MessageKind.PONG, message.kind);
      assertEquals((byte) i, message.payload[0]);
    }
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
