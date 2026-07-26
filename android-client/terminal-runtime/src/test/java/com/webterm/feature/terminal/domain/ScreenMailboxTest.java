package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

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
    assertTrue(!first.fence.rebuildChannel);
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

    boolean sawAck = false;
    boolean sawLease = false;
    boolean sawExit = false;
    for (int i = 0; i < ScreenMailbox.SCHEDULE_LENGTH && !(sawAck && sawLease && sawExit); i++) {
      ScreenMailbox.MessageKind kind = mailbox.poll().message.kind;
      sawAck |= kind == ScreenMailbox.MessageKind.INPUT_ACK;
      sawLease |= kind == ScreenMailbox.MessageKind.LAYOUT_LEASE;
      sawExit |= kind == ScreenMailbox.MessageKind.EXIT;
    }
    assertTrue(sawAck);
    assertTrue(sawLease);
    assertTrue(sawExit);
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

    int expectedProjection = 0;
    boolean sawBackground = false;
    for (int drained = 0; drained < 21; drained++) {
      ScreenMailbox.Message message = mailbox.poll().message;
      if (message.kind == ScreenMailbox.MessageKind.PONG) {
        sawBackground = true;
      } else {
        assertEquals(ScreenMailbox.MessageKind.TERMINAL_COMMIT, message.kind);
        assertEquals((byte) expectedProjection++, message.payload[0]);
      }
    }
    assertTrue(sawBackground);
    assertEquals(20, expectedProjection);
  }

  @Test
  public void weightedRoundRobinKeepsAllContinuouslyNonEmptyLanesMoving() {
    ScreenMailbox mailbox = new ScreenMailbox(
        128, 4096L, 128, 4096L, 128, 4096L, 128, 4096L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    offer(mailbox, source, ScreenMailbox.MessageKind.INPUT_ACK, 1);
    offer(mailbox, source, ScreenMailbox.MessageKind.TERMINAL_COMMIT, 0);
    offer(mailbox, source, ScreenMailbox.MessageKind.PONG, 1);

    int expectedProjection = 0;
    for (int window = 0; window < 4; window++) {
      int urgent = 0;
      int projection = 0;
      int background = 0;
      for (int i = 0; i < ScreenMailbox.SCHEDULE_LENGTH; i++) {
        ScreenMailbox.Message message = mailbox.poll().message;
        if (message.kind == ScreenMailbox.MessageKind.INPUT_ACK) {
          urgent++;
          offer(mailbox, source, message.kind, 1);
        } else if (message.kind == ScreenMailbox.MessageKind.TERMINAL_COMMIT) {
          projection++;
          assertEquals((byte) expectedProjection, message.payload[0]);
          expectedProjection++;
          offer(mailbox, source, message.kind, expectedProjection);
        } else if (message.kind == ScreenMailbox.MessageKind.PONG) {
          background++;
          offer(mailbox, source, message.kind, 1);
        }
      }
      assertTrue(urgent > 0);
      assertTrue(projection > 0);
      assertTrue(background > 0);
    }
    assertEquals(3, mailbox.pendingMessages());
    assertEquals(3, mailbox.pendingBytes());
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
    assertTrue(drain.fence.rebuildChannel);
    assertEquals(0, mailbox.pendingMessages());
    assertEquals(0, mailbox.pendingBytes());
    assertNull(mailbox.poll());
  }

  @Test
  public void reliableClipboardHasIndependentBudgetAndNeverUsesBackgroundEviction() {
    ScreenMailbox mailbox = new ScreenMailbox(
        4, 16L, 4, 16L, 2, 4L, 2, 2L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    long droppedBell = 0;
    for (int i = 0; i < 20; i++) {
      droppedBell += mailbox.offer(1L, source, new byte[] {(byte) i}, true,
          ScreenMailbox.MessageKind.EFFECT).droppedBackgroundMessages;
    }
    assertEquals(18, droppedBell);

    ScreenMailbox.Offer read = mailbox.offer(1L, source, new byte[] {40, 41}, true,
        ScreenMailbox.MessageKind.CLIPBOARD_EFFECT);
    ScreenMailbox.Offer write = mailbox.offer(1L, source, new byte[] {42, 43}, true,
        ScreenMailbox.MessageKind.CLIPBOARD_EFFECT);
    assertEquals(0, read.droppedBackgroundMessages);
    assertEquals(0, write.droppedBackgroundMessages);
    assertEquals(4, mailbox.pendingMessages());
    assertEquals(6, mailbox.pendingBytes());

    boolean sawRead = false;
    boolean sawWrite = false;
    ScreenMailbox.Drain drain;
    while ((drain = mailbox.poll()) != null) {
      if (drain.message.kind != ScreenMailbox.MessageKind.CLIPBOARD_EFFECT) continue;
      sawRead |= drain.message.payload[0] == 40;
      sawWrite |= drain.message.payload[0] == 42;
    }
    assertTrue(sawRead);
    assertTrue(sawWrite);
    assertEquals(0, mailbox.pendingBytes());
  }

  @Test
  public void reliableClipboardOverflowProducesExplicitRecoveryFence() {
    ScreenMailbox mailbox = new ScreenMailbox(
        4, 16L, 4, 16L, 1, 2L, 2, 2L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    mailbox.offer(1L, source, new byte[] {1, 2}, true,
        ScreenMailbox.MessageKind.CLIPBOARD_EFFECT);
    mailbox.offer(1L, source, new byte[] {3}, true,
        ScreenMailbox.MessageKind.CLIPBOARD_EFFECT);

    ScreenMailbox.Drain drain = mailbox.poll();
    assertNotNull(drain.fence);
    assertEquals("screen mailbox reliable control exceeded byte budget", drain.fence.reason);
    assertTrue(drain.fence.rebuildChannel);
    assertEquals(0, mailbox.pendingMessages());
    assertEquals(0, mailbox.pendingBytes());
  }

  @Test
  public void resetAndProjectionOverflowRestartWeightedScheduleAndCounters() {
    ScreenMailbox mailbox = new ScreenMailbox(
        1, 2L, 4, 16L, 4, 16L, 4, 16L);
    TerminalSessionRuntime.ScreenConnection source =
        mock(TerminalSessionRuntime.ScreenConnection.class);
    offer(mailbox, source, ScreenMailbox.MessageKind.INPUT_ACK, 1);
    offer(mailbox, source, ScreenMailbox.MessageKind.TERMINAL_COMMIT, 1);
    assertEquals(ScreenMailbox.MessageKind.INPUT_ACK, mailbox.poll().message.kind);
    mailbox.reset();

    offer(mailbox, source, ScreenMailbox.MessageKind.INPUT_ACK, 1);
    offer(mailbox, source, ScreenMailbox.MessageKind.TERMINAL_COMMIT, 2);
    assertEquals(ScreenMailbox.MessageKind.INPUT_ACK, mailbox.poll().message.kind);
    assertEquals(ScreenMailbox.MessageKind.TERMINAL_COMMIT, mailbox.poll().message.kind);
    assertEquals(0, mailbox.pendingBytes());

    offer(mailbox, source, ScreenMailbox.MessageKind.TERMINAL_COMMIT, 3);
    offer(mailbox, source, ScreenMailbox.MessageKind.TERMINAL_COMMIT, 4);
    assertNotNull(mailbox.poll().fence);
    offer(mailbox, source, ScreenMailbox.MessageKind.INPUT_ACK, 1);
    offer(mailbox, source, ScreenMailbox.MessageKind.TERMINAL_COMMIT, 5);
    assertEquals(ScreenMailbox.MessageKind.INPUT_ACK, mailbox.poll().message.kind);
    assertEquals(ScreenMailbox.MessageKind.TERMINAL_COMMIT, mailbox.poll().message.kind);
    assertEquals(0, mailbox.pendingMessages());
    assertEquals(0, mailbox.pendingBytes());
  }

  @Test
  public void classifierSeparatesDroppableAndReliableEffects() {
    TerminalScreenV2Proto.ScreenEnvelope bell =
        TerminalScreenV2Proto.ScreenEnvelope.newBuilder().setProtocolVersion(2)
            .setEffect(TerminalScreenV2Proto.TerminalEffect.newBuilder()
                .setInstanceId("i")
                .setBell(TerminalScreenV2Proto.Bell.getDefaultInstance()))
            .build();
    TerminalScreenV2Proto.ScreenEnvelope clipboard =
        TerminalScreenV2Proto.ScreenEnvelope.newBuilder().setProtocolVersion(2)
            .setEffect(TerminalScreenV2Proto.TerminalEffect.newBuilder()
                .setInstanceId("i")
                .setClipboardRead(TerminalScreenV2Proto.ClipboardReadRequest.newBuilder()
                    .setRequestId("r").setClipboard("c")))
            .build();
    TerminalScreenV2Proto.ScreenEnvelope clipboardWrite =
        TerminalScreenV2Proto.ScreenEnvelope.newBuilder().setProtocolVersion(2)
            .setEffect(TerminalScreenV2Proto.TerminalEffect.newBuilder()
                .setInstanceId("i")
                .setClipboardWrite(TerminalScreenV2Proto.ClipboardWriteRequest.newBuilder()
                    .setRequestId("w").setClipboard("c")))
            .build();

    assertEquals(ScreenMailbox.MessageKind.EFFECT,
        TerminalSessionRuntime.classifyScreenMessage(bell.toByteArray()));
    assertEquals(ScreenMailbox.MessageKind.CLIPBOARD_EFFECT,
        TerminalSessionRuntime.classifyScreenMessage(clipboard.toByteArray()));
    assertEquals(ScreenMailbox.MessageKind.CLIPBOARD_EFFECT,
        TerminalSessionRuntime.classifyScreenMessage(clipboardWrite.toByteArray()));
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

  private static void offer(ScreenMailbox mailbox,
                            TerminalSessionRuntime.ScreenConnection source,
                            ScreenMailbox.MessageKind kind, int value) {
    mailbox.offer(1L, source, new byte[] {(byte) value}, true, kind);
  }

}
