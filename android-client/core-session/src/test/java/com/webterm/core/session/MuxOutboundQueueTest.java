package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    public void tracksHighWaterAndQueueFullCounter() {
        MuxOutboundQueue queue = new MuxOutboundQueue(2, 10L);
        queue.offer("a", new byte[] {1, 2, 3}, true, result -> {});
        queue.offer("b", new byte[] {4}, true, result -> {});
        queue.offer("c", new byte[] {5}, true, result -> {});

        MuxOutboundQueue.Snapshot snapshot = queue.snapshot();
        assertEquals(2, snapshot.highWaterFrames);
        assertEquals(4L, snapshot.highWaterBytes);
        assertEquals(2, snapshot.currentFrames);
        assertEquals(2L, snapshot.acceptedCount);
        assertEquals(1L, snapshot.queueFullCount);
    }

    @Test
    public void recordsResidenceOnWebSocketEnqueuedCompletion() {
        MuxOutboundQueue queue = new MuxOutboundQueue(4, 64L);
        AtomicReference<MuxOutboundQueue.Result> resultRef = new AtomicReference<>();
        queue.offer("screen", new byte[] {1}, true, resultRef::set);
        MuxOutboundQueue.Frame frame = queue.poll();
        frame.completion.onResult(MuxOutboundQueue.Result.WEBSOCKET_ENQUEUED);

        MuxOutboundQueue.Snapshot snapshot = queue.snapshot();
        assertEquals(MuxOutboundQueue.Result.WEBSOCKET_ENQUEUED, resultRef.get());
        assertEquals(1L, snapshot.webSocketEnqueuedCount);
        assertEquals(1L, snapshot.residenceCount);
        assertTrue(snapshot.residenceTotalNanos >= 0L);
    }

    @Test
    public void infersFrameKindFromChannelId() {
        MuxOutboundQueue queue = new MuxOutboundQueue(8, 128L);
        queue.offer("webterm.screen.v2", new byte[] {1}, true, result -> {});
        queue.offer("term:manager", new byte[] {1}, true, result -> {});
        queue.offer("capture-preview", new byte[] {1}, true, result -> {});
        queue.offer("term:input", new byte[] {1}, true, result -> {});

        assertEquals(MuxOutboundQueue.FrameKind.SCREEN, queue.poll().kind);
        assertEquals(MuxOutboundQueue.FrameKind.MANAGER, queue.poll().kind);
        assertEquals(MuxOutboundQueue.FrameKind.OTHER, queue.poll().kind);
        assertEquals(MuxOutboundQueue.FrameKind.INPUT, queue.poll().kind);
    }

    @Test
    public void tracksByKindStatsOnOfferAndCompletion() {
        MuxOutboundQueue queue = new MuxOutboundQueue(8, 128L);
        queue.offer("a", new byte[] {1, 2}, true, MuxOutboundQueue.FrameKind.INPUT, result -> {});
        queue.offer("b", new byte[] {3}, true, MuxOutboundQueue.FrameKind.CONTROL, result -> {});
        MuxOutboundQueue.Frame input = queue.poll();
        input.completion.onResult(MuxOutboundQueue.Result.WEBSOCKET_ENQUEUED);

        MuxOutboundQueue.Snapshot snapshot = queue.snapshot();
        assertEquals(Long.valueOf(1L), snapshot.acceptedByKind.get("INPUT"));
        assertEquals(Long.valueOf(1L), snapshot.acceptedByKind.get("CONTROL"));
        assertEquals(Long.valueOf(1L), snapshot.webSocketEnqueuedByKind.get("INPUT"));
        assertEquals(Long.valueOf(2L), snapshot.bytesByKind.get("INPUT"));
        assertEquals(Long.valueOf(1L), snapshot.bytesByKind.get("CONTROL"));
    }

    @Test
    public void stopIncrementsConnectionStoppedCount() {
        MuxOutboundQueue queue = new MuxOutboundQueue(2, 10L);
        queue.offer("a", new byte[] {1}, true, result -> {});
        queue.stopAndDrain();
        queue.offer("b", new byte[] {2}, true, result -> {});

        MuxOutboundQueue.Snapshot snapshot = queue.snapshot();
        assertEquals(1L, snapshot.connectionStoppedCount);
    }
}
