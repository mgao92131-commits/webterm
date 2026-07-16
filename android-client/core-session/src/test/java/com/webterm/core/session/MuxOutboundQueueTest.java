package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class MuxOutboundQueueTest {
    @Test
    public void enforcesFrameAndByteBudgetsWithoutRetainingRejectedFrames() {
        MuxOutboundQueue queue = new MuxOutboundQueue(2, 5L);

        MuxOutboundQueue.Offer first = queue.offer("a", new byte[3], true, result -> {});
        MuxOutboundQueue.Offer second = queue.offer("b", new byte[2], true, result -> {});
        MuxOutboundQueue.Offer full = queue.offer("c", new byte[1], true, result -> {});

        assertEquals(MuxOutboundQueue.Result.LOCAL_ACCEPTED, first.result);
        assertTrue(first.scheduleDrain);
        assertEquals(MuxOutboundQueue.Result.LOCAL_ACCEPTED, second.result);
        assertFalse(second.scheduleDrain);
        assertEquals(MuxOutboundQueue.Result.QUEUE_FULL, full.result);
        assertEquals(2, queue.pendingFrames());
        assertEquals(5L, queue.pendingBytes());
    }

    @Test
    public void pollPreservesFifoAndNextOfferSchedulesNewDrain() {
        MuxOutboundQueue queue = new MuxOutboundQueue(2, 10L);
        queue.offer("a", new byte[] {1}, true, result -> {});
        queue.offer("b", new byte[] {2}, false, result -> {});

        assertEquals("a", queue.poll().channelId);
        assertEquals("b", queue.poll().channelId);
        assertEquals(null, queue.poll());
        assertTrue(queue.offer("c", new byte[] {3}, true, result -> {}).scheduleDrain);
    }

    @Test
    public void stopReturnsPendingFramesAndRejectsFutureOffers() {
        MuxOutboundQueue queue = new MuxOutboundQueue(2, 10L);
        queue.offer("a", new byte[] {1}, true, result -> {});

        List<MuxOutboundQueue.Frame> pending = queue.stopAndDrain();

        assertEquals(1, pending.size());
        assertEquals(0, queue.pendingFrames());
        assertEquals(MuxOutboundQueue.Result.CONNECTION_STOPPED,
            queue.offer("b", new byte[] {2}, true, result -> {}).result);
    }
}
