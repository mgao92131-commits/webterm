package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.webterm.transport.api.MuxTransport;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class MuxInboundMailboxTest {
  @Test
  public void binaryTextBinaryOrderIsPreserved() {
    MuxInboundMailbox mailbox = new MuxInboundMailbox();
    MuxTransport transport = new StubTransport();
    mailbox.offer(MuxInboundMailbox.InboundEvent.Binary.of(
        1, transport, ByteBuffer.wrap(new byte[] {1})));
    mailbox.offer(MuxInboundMailbox.InboundEvent.Text.of(1, transport, "mid"));
    mailbox.offer(MuxInboundMailbox.InboundEvent.Binary.of(
        1, transport, ByteBuffer.wrap(new byte[] {2})));

    assertTrue(mailbox.poll() instanceof MuxInboundMailbox.InboundEvent.Binary);
    assertTrue(mailbox.poll() instanceof MuxInboundMailbox.InboundEvent.Text);
    assertTrue(mailbox.poll() instanceof MuxInboundMailbox.InboundEvent.Binary);
    assertNull(mailbox.poll());
  }

  @Test
  public void firstOfferSchedulesDrainSubsequentDoNot() {
    MuxInboundMailbox mailbox = new MuxInboundMailbox();
    MuxTransport transport = new StubTransport();
    MuxInboundMailbox.Offer first = mailbox.offer(MuxInboundMailbox.InboundEvent.Binary.of(
        1, transport, ByteBuffer.wrap(new byte[] {1})));
    MuxInboundMailbox.Offer second = mailbox.offer(MuxInboundMailbox.InboundEvent.Binary.of(
        1, transport, ByteBuffer.wrap(new byte[] {2})));
    assertTrue(first.scheduleDrain);
    assertFalse(second.scheduleDrain);
  }

  @Test
  public void finishDrainReschedulesWhenQueueRemains() {
    MuxInboundMailbox mailbox = new MuxInboundMailbox();
    MuxTransport transport = new StubTransport();
    mailbox.offer(MuxInboundMailbox.InboundEvent.Text.of(1, transport, "a"));
    mailbox.offer(MuxInboundMailbox.InboundEvent.Text.of(1, transport, "b"));
    assertNotNull(mailbox.poll());
    assertTrue(mailbox.finishDrainOrReschedule());
    assertTrue(mailbox.isDrainScheduled());
  }

  @Test
  public void finishDrainMarksIdleWhenEmpty() {
    MuxInboundMailbox mailbox = new MuxInboundMailbox();
    MuxTransport transport = new StubTransport();
    mailbox.offer(MuxInboundMailbox.InboundEvent.Text.of(1, transport, "a"));
    assertNotNull(mailbox.poll());
    assertFalse(mailbox.finishDrainOrReschedule());
    assertFalse(mailbox.isDrainScheduled());
  }

  @Test
  public void overflowClearsGenerationAndRejectsFurtherBinary() {
    MuxInboundMailbox mailbox = new MuxInboundMailbox();
    MuxTransport transport = new StubTransport();
    for (int i = 0; i < MuxInboundMailbox.MAX_INBOUND_EVENTS; i++) {
      MuxInboundMailbox.Offer offer = mailbox.offer(MuxInboundMailbox.InboundEvent.Binary.of(
          7, transport, ByteBuffer.wrap(new byte[] {(byte) i})));
      assertTrue(offer.accepted);
    }
    MuxInboundMailbox.Offer overflow = mailbox.offer(MuxInboundMailbox.InboundEvent.Binary.of(
        7, transport, ByteBuffer.wrap(new byte[] {99})));
    assertFalse(overflow.accepted);
    assertTrue(overflow.overflowed);
    assertEquals(7, overflow.overflowGeneration);
    assertEquals(0, mailbox.snapshot().currentEvents);

    MuxInboundMailbox.Offer rejected = mailbox.offer(MuxInboundMailbox.InboundEvent.Binary.of(
        7, transport, ByteBuffer.wrap(new byte[] {100})));
    assertFalse(rejected.accepted);
    assertFalse(rejected.overflowed);
  }

  @Test
  public void clearDropsQueuedBuffers() {
    MuxInboundMailbox mailbox = new MuxInboundMailbox();
    MuxTransport transport = new StubTransport();
    mailbox.offer(MuxInboundMailbox.InboundEvent.Binary.of(
        1, transport, ByteBuffer.wrap(new byte[] {1, 2, 3})));
    mailbox.clear();
    assertFalse(mailbox.hasMore());
    assertEquals(0, mailbox.snapshot().currentEvents);
    assertEquals(0L, mailbox.snapshot().currentBytes);
  }

  @Test
  public void offerDuringDrainDoesNotLoseWakeup() {
    MuxInboundMailbox mailbox = new MuxInboundMailbox();
    MuxTransport transport = new StubTransport();
    mailbox.offer(MuxInboundMailbox.InboundEvent.Text.of(1, transport, "a"));
    // Simulate drain in progress: poll one, then offer more before finish.
    assertNotNull(mailbox.poll());
    MuxInboundMailbox.Offer mid = mailbox.offer(MuxInboundMailbox.InboundEvent.Text.of(
        1, transport, "b"));
    // drain still marked scheduled from first offer; mid should not need schedule
    // but finish must reschedule because queue non-empty.
    assertFalse(mid.scheduleDrain);
    assertTrue(mailbox.finishDrainOrReschedule());
  }

  private static final class StubTransport implements MuxTransport {
    @Override public void start(Listener listener) {}
    @Override public void close() {}
    @Override public boolean isConnected() { return false; }
    @Override public boolean sendText(String text) { return false; }
    @Override public boolean sendBinary(byte[] payload) { return false; }
  }
}
