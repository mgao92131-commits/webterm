package com.webterm.core.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备级 Mux 帧有界队列。
 *
 * <p>本类只拥有内存预算、drain 调度标记和停止语义；logical channel 是否已打开、
 * WebSocket 是否接受帧仍由 {@link DeviceConnection} 在其唯一 event loop 上判断。</p>
 */
public final class MuxOutboundQueue {
    public enum Result {
        LOCAL_ACCEPTED,
        WEBSOCKET_ENQUEUED,
        QUEUE_FULL,
        CHANNEL_NOT_OPEN,
        TRANSPORT_REJECTED,
        CONNECTION_STOPPED
    }

    public interface Completion {
        void onResult(Result result);
    }

    static final class Frame {
        final String channelId;
        final byte[] payload;
        final boolean binary;
        final Completion completion;

        Frame(String channelId, byte[] payload, boolean binary, Completion completion) {
            this.channelId = channelId;
            this.payload = payload;
            this.binary = binary;
            this.completion = completion;
        }
    }

    static final class Offer {
        final Result result;
        final boolean scheduleDrain;

        Offer(Result result, boolean scheduleDrain) {
            this.result = result;
            this.scheduleDrain = scheduleDrain;
        }
    }

    private final int maxFrames;
    private final long maxBytes;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private long bytes;
    private boolean drainScheduled;
    private boolean accepting = true;

    public MuxOutboundQueue(int maxFrames, long maxBytes) {
        if (maxFrames <= 0 || maxBytes <= 0L) {
            throw new IllegalArgumentException("queue budgets must be positive");
        }
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
    }

    synchronized Offer offer(String channelId, byte[] payload, boolean binary,
                             Completion completion) {
        if (!accepting) return new Offer(Result.CONNECTION_STOPPED, false);
        if (frames.size() >= maxFrames || bytes + payload.length > maxBytes) {
            return new Offer(Result.QUEUE_FULL, false);
        }
        frames.addLast(new Frame(channelId, payload, binary, completion));
        bytes += payload.length;
        boolean schedule = !drainScheduled;
        drainScheduled = true;
        return new Offer(Result.LOCAL_ACCEPTED, schedule);
    }

    synchronized Frame poll() {
        Frame frame = frames.pollFirst();
        if (frame == null) {
            drainScheduled = false;
            return null;
        }
        bytes -= frame.payload.length;
        return frame;
    }

    synchronized List<Frame> stopAndDrain() {
        accepting = false;
        List<Frame> pending = new ArrayList<>(frames);
        frames.clear();
        bytes = 0L;
        drainScheduled = false;
        return pending;
    }

    synchronized int pendingFrames() {
        return frames.size();
    }

    synchronized long pendingBytes() {
        return bytes;
    }
}
