package com.webterm.core.session;

import com.webterm.transport.api.MuxTransport;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * 设备级 Mux 入站有序 mailbox。
 *
 * <p>所有 Transport 回调（Open/Text/Binary/Closed/Error）进入同一 FIFO 队列，
 * 由 stateHandler 时间片 drain，避免每个 WebSocket 帧对应一个 Handler Message。
 * 不得对 Binary 单独批处理，否则会破坏与 Text/Close 的相对顺序。</p>
 *
 * <p>溢出时标记 generation 并清空该代事件，由调用方触发物理重连与 Baseline；
 * 禁止静默丢弃中间帧。</p>
 */
public final class MuxInboundMailbox {
    static final int MAX_INBOUND_EVENTS = 512;
    static final long MAX_INBOUND_BYTES = 16L * 1024L * 1024L;
    static final int MAX_DRAIN_EVENTS = 32;
    static final long MAX_DRAIN_NANOS = 3_000_000L;

    /** 与 TerminalRenderMetrics / MuxOutboundQueue 对齐的粗粒度驻留分桶。 */
    private static final long[] RESIDENCE_BUCKET_UPPER_NANOS = {
        250_000L, 500_000L, 1_000_000L, 2_000_000L,
        4_000_000L, 8_000_000L, 16_000_000L
    };

    public sealed interface InboundEvent permits
        InboundEvent.Open, InboundEvent.Text, InboundEvent.Binary,
        InboundEvent.Closed, InboundEvent.Error {
        int generation();
        MuxTransport sourceTransport();
        long enqueueNanos();
        int byteSize();

        record Open(int generation, MuxTransport sourceTransport, long enqueueNanos)
            implements InboundEvent {
            @Override public int byteSize() { return 0; }
            static Open of(int generation, MuxTransport sourceTransport) {
                return new Open(generation, sourceTransport, System.nanoTime());
            }
        }

        record Text(int generation, MuxTransport sourceTransport, String text, long enqueueNanos)
            implements InboundEvent {
            @Override public int byteSize() {
                return text == null ? 0 : text.length() * 2;
            }
            static Text of(int generation, MuxTransport sourceTransport, String text) {
                return new Text(generation, sourceTransport, text, System.nanoTime());
            }
        }

        record Binary(int generation, MuxTransport sourceTransport, ByteBuffer data, long enqueueNanos)
            implements InboundEvent {
            @Override public int byteSize() {
                return data == null ? 0 : data.remaining();
            }
            static Binary of(int generation, MuxTransport sourceTransport, ByteBuffer data) {
                return new Binary(generation, sourceTransport, data, System.nanoTime());
            }
        }

        record Closed(int generation, MuxTransport sourceTransport, int code, String reason,
                      long enqueueNanos) implements InboundEvent {
            @Override public int byteSize() { return 0; }
            static Closed of(int generation, MuxTransport sourceTransport, int code, String reason) {
                return new Closed(generation, sourceTransport, code, reason, System.nanoTime());
            }
        }

        record Error(int generation, MuxTransport sourceTransport, int code, String message,
                     long enqueueNanos) implements InboundEvent {
            @Override public int byteSize() { return 0; }
            static Error of(int generation, MuxTransport sourceTransport, int code, String message) {
                return new Error(generation, sourceTransport, code, message, System.nanoTime());
            }
        }
    }

    public static final class Offer {
        public final boolean accepted;
        public final boolean scheduleDrain;
        public final boolean overflowed;
        public final int overflowGeneration;
        public final int overflowFrames;
        public final long overflowBytes;

        Offer(boolean accepted, boolean scheduleDrain, boolean overflowed,
              int overflowGeneration, int overflowFrames, long overflowBytes) {
            this.accepted = accepted;
            this.scheduleDrain = scheduleDrain;
            this.overflowed = overflowed;
            this.overflowGeneration = overflowGeneration;
            this.overflowFrames = overflowFrames;
            this.overflowBytes = overflowBytes;
        }
    }

    public static final class Snapshot {
        public final int currentEvents;
        public final long currentBytes;
        public final int maxEvents;
        public final long maxBytes;
        public final long drainRuns;
        public final long drainEventCount;
        public final int drainMaxBatch;
        public final long residenceCount;
        public final long residenceTotalNanos;
        public final long overflowCount;
        public final long overflowFrames;
        public final long overflowBytes;
        public final long staleGenerationDropped;
        public final long[] residenceBuckets;

        Snapshot(int currentEvents, long currentBytes, int maxEvents, long maxBytes,
                 long drainRuns, long drainEventCount, int drainMaxBatch,
                 long residenceCount, long residenceTotalNanos,
                 long overflowCount, long overflowFrames, long overflowBytes,
                 long staleGenerationDropped, long[] residenceBuckets) {
            this.currentEvents = currentEvents;
            this.currentBytes = currentBytes;
            this.maxEvents = maxEvents;
            this.maxBytes = maxBytes;
            this.drainRuns = drainRuns;
            this.drainEventCount = drainEventCount;
            this.drainMaxBatch = drainMaxBatch;
            this.residenceCount = residenceCount;
            this.residenceTotalNanos = residenceTotalNanos;
            this.overflowCount = overflowCount;
            this.overflowFrames = overflowFrames;
            this.overflowBytes = overflowBytes;
            this.staleGenerationDropped = staleGenerationDropped;
            this.residenceBuckets = residenceBuckets;
        }

        /** 近似 P50：按累计样本过半所在桶上界估算。 */
        public long residenceP50Nanos() {
            return residencePercentileNanos(0.50d);
        }

        public long residenceP95Nanos() {
            return residencePercentileNanos(0.95d);
        }

        private long residencePercentileNanos(double percentile) {
            if (residenceCount <= 0 || residenceBuckets == null) return 0L;
            long target = Math.max(1L, (long) Math.ceil(residenceCount * percentile));
            long cumulative = 0L;
            for (int i = 0; i < residenceBuckets.length; i++) {
                cumulative += residenceBuckets[i];
                if (cumulative >= target) {
                    return i < RESIDENCE_BUCKET_UPPER_NANOS.length
                        ? RESIDENCE_BUCKET_UPPER_NANOS[i]
                        : RESIDENCE_BUCKET_UPPER_NANOS[RESIDENCE_BUCKET_UPPER_NANOS.length - 1];
                }
            }
            return RESIDENCE_BUCKET_UPPER_NANOS[RESIDENCE_BUCKET_UPPER_NANOS.length - 1];
        }
    }

    private final ArrayDeque<InboundEvent> queue = new ArrayDeque<>();
    private boolean drainScheduled;
    private int queuedEvents;
    private long queuedBytes;
    private int highWaterEvents;
    private long highWaterBytes;
    private int overflowedGeneration = Integer.MIN_VALUE;
    private long drainRuns;
    private long drainEventCount;
    private int drainMaxBatch;
    private long residenceCount;
    private long residenceTotalNanos;
    private final long[] residenceBuckets = new long[RESIDENCE_BUCKET_UPPER_NANOS.length + 1];
    private long overflowCount;
    private long overflowFrames;
    private long overflowBytes;
    private long staleGenerationDropped;

    public synchronized Offer offer(InboundEvent event) {
        if (event == null) {
            return new Offer(false, false, false, Integer.MIN_VALUE, 0, 0L);
        }
        if (event.generation() == overflowedGeneration && event instanceof InboundEvent.Binary) {
            return new Offer(false, false, false, overflowedGeneration, 0, 0L);
        }
        int eventBytes = Math.max(0, event.byteSize());
        if (queuedEvents + 1 > MAX_INBOUND_EVENTS
                || queuedBytes + eventBytes > MAX_INBOUND_BYTES) {
            return overflow(event.generation());
        }
        queue.addLast(event);
        queuedEvents++;
        queuedBytes += eventBytes;
        if (queuedEvents > highWaterEvents) highWaterEvents = queuedEvents;
        if (queuedBytes > highWaterBytes) highWaterBytes = queuedBytes;
        boolean schedule = !drainScheduled;
        if (schedule) drainScheduled = true;
        return new Offer(true, schedule, false, Integer.MIN_VALUE, 0, 0L);
    }

    private Offer overflow(int generation) {
        int discarded = 0;
        long discardedBytes = 0L;
        ArrayDeque<InboundEvent> kept = new ArrayDeque<>();
        while (!queue.isEmpty()) {
            InboundEvent pending = queue.removeFirst();
            if (pending.generation() == generation) {
                discarded++;
                discardedBytes += Math.max(0, pending.byteSize());
            } else {
                kept.addLast(pending);
            }
        }
        queue.clear();
        queue.addAll(kept);
        queuedEvents = queue.size();
        queuedBytes = 0L;
        for (InboundEvent pending : queue) {
            queuedBytes += Math.max(0, pending.byteSize());
        }
        overflowedGeneration = generation;
        overflowCount++;
        overflowFrames += discarded;
        overflowBytes += discardedBytes;
        boolean schedule = !drainScheduled && !queue.isEmpty();
        if (schedule) drainScheduled = true;
        // 溢出本身也需要在 stateHandler 上处理恢复；即使队列已空也唤醒一次。
        if (!drainScheduled) {
            drainScheduled = true;
            schedule = true;
        }
        return new Offer(false, schedule, true, generation, discarded, discardedBytes);
    }

    public synchronized InboundEvent poll() {
        InboundEvent event = queue.pollFirst();
        if (event == null) return null;
        queuedEvents = Math.max(0, queuedEvents - 1);
        queuedBytes = Math.max(0L, queuedBytes - Math.max(0, event.byteSize()));
        long residence = Math.max(0L, System.nanoTime() - event.enqueueNanos());
        residenceCount++;
        residenceTotalNanos += residence;
        int bucket = residenceBuckets.length - 1;
        for (int i = 0; i < RESIDENCE_BUCKET_UPPER_NANOS.length; i++) {
            if (residence <= RESIDENCE_BUCKET_UPPER_NANOS[i]) {
                bucket = i;
                break;
            }
        }
        residenceBuckets[bucket]++;
        return event;
    }

    public synchronized boolean hasMore() {
        return !queue.isEmpty();
    }

    public synchronized void noteDrainBatch(int processed) {
        drainRuns++;
        drainEventCount += Math.max(0, processed);
        if (processed > drainMaxBatch) drainMaxBatch = processed;
    }

    public synchronized boolean finishDrainOrReschedule() {
        if (queue.isEmpty()) {
            drainScheduled = false;
            return false;
        }
        drainScheduled = true;
        return true;
    }

    public synchronized void noteStaleGenerationDropped() {
        staleGenerationDropped++;
    }

    public synchronized void clearOverflowGeneration(int generation) {
        if (overflowedGeneration == generation) {
            overflowedGeneration = Integer.MIN_VALUE;
        }
    }

    public synchronized int overflowedGeneration() {
        return overflowedGeneration;
    }

    public static final class ClearResult {
        public final int events;
        public final long bytes;

        ClearResult(int events, long bytes) {
            this.events = events;
            this.bytes = bytes;
        }
    }

    /**
     * 中止 drain 并清空队列引用；不清除 overflow 标记。
     * Handler 拒绝 post 时用于释放 ByteBuffer，避免永久卡在 drainScheduled=true。
     */
    public synchronized ClearResult abortDrainAndClear() {
        ClearResult result = new ClearResult(queuedEvents, queuedBytes);
        queue.clear();
        queuedEvents = 0;
        queuedBytes = 0L;
        drainScheduled = false;
        return result;
    }

    public synchronized void clear() {
        abortDrainAndClear();
        overflowedGeneration = Integer.MIN_VALUE;
    }

    public synchronized boolean isDrainScheduled() {
        return drainScheduled;
    }

    public synchronized Snapshot snapshot() {
        long[] buckets = new long[residenceBuckets.length];
        System.arraycopy(residenceBuckets, 0, buckets, 0, residenceBuckets.length);
        return new Snapshot(
            queuedEvents, queuedBytes, highWaterEvents, highWaterBytes,
            drainRuns, drainEventCount, drainMaxBatch,
            residenceCount, residenceTotalNanos,
            overflowCount, overflowFrames, overflowBytes,
            staleGenerationDropped, buckets);
    }
}
