package com.webterm.core.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 设备级 Mux 帧有界队列。
 *
 * <p>本类只拥有内存预算、drain 调度标记和停止语义；logical channel 是否已打开、
 * WebSocket 是否接受帧仍由 {@link DeviceConnection} 在其唯一 event loop 上判断。</p>
 *
 * <p>成功路径只累计计数器（高水位、驻留时间、accepted/enqueued），不写 Diagnostics 事件；
 * 拒绝类结果由调用方（DeviceConnection）按需记 WARN。</p>
 */
public final class MuxOutboundQueue {
    /** 与 TerminalRenderMetrics 对齐的 8 桶上限（纳秒）：250µs…16ms，最后一桶为 ≥16ms。 */
    public static final int LATENCY_BUCKET_COUNT = 8;
    private static final long[] LATENCY_BUCKET_UPPER_BOUNDS_NANOS = {
        250_000L, 500_000L, 1_000_000L, 2_000_000L,
        4_000_000L, 8_000_000L, 16_000_000L
    };

    public enum Result {
        LOCAL_ACCEPTED,
        WEBSOCKET_ENQUEUED,
        QUEUE_FULL,
        CHANNEL_NOT_OPEN,
        TRANSPORT_REJECTED,
        CONNECTION_STOPPED
    }

    public enum FrameKind {
        SCREEN, INPUT, CONTROL, MANAGER, FILE, OTHER
    }

    public interface Completion {
        void onResult(Result result);
    }

    public static final class Snapshot {
        public final int currentFrames;
        public final long currentBytes;
        public final int highWaterFrames;
        public final long highWaterBytes;
        public final long acceptedCount;
        public final long webSocketEnqueuedCount;
        public final long queueFullCount;
        public final long channelNotOpenCount;
        public final long transportRejectedCount;
        public final long connectionStoppedCount;
        public final long residenceCount;
        public final long residenceTotalNanos;
        public final long residenceMaxNanos;
        public final long[] residenceLatencyBuckets;
        public final Map<String, Long> acceptedByKind;
        public final Map<String, Long> webSocketEnqueuedByKind;
        public final Map<String, Long> rejectedByKind;
        public final Map<String, Long> bytesByKind;

        Snapshot(int currentFrames, long currentBytes, int highWaterFrames, long highWaterBytes,
                 long acceptedCount, long webSocketEnqueuedCount, long queueFullCount,
                 long channelNotOpenCount, long transportRejectedCount, long connectionStoppedCount,
                 long residenceCount, long residenceTotalNanos, long residenceMaxNanos,
                 long[] residenceLatencyBuckets,
                 Map<String, Long> acceptedByKind,
                 Map<String, Long> webSocketEnqueuedByKind,
                 Map<String, Long> rejectedByKind,
                 Map<String, Long> bytesByKind) {
            this.currentFrames = currentFrames;
            this.currentBytes = currentBytes;
            this.highWaterFrames = highWaterFrames;
            this.highWaterBytes = highWaterBytes;
            this.acceptedCount = acceptedCount;
            this.webSocketEnqueuedCount = webSocketEnqueuedCount;
            this.queueFullCount = queueFullCount;
            this.channelNotOpenCount = channelNotOpenCount;
            this.transportRejectedCount = transportRejectedCount;
            this.connectionStoppedCount = connectionStoppedCount;
            this.residenceCount = residenceCount;
            this.residenceTotalNanos = residenceTotalNanos;
            this.residenceMaxNanos = residenceMaxNanos;
            this.residenceLatencyBuckets = residenceLatencyBuckets;
            this.acceptedByKind = acceptedByKind;
            this.webSocketEnqueuedByKind = webSocketEnqueuedByKind;
            this.rejectedByKind = rejectedByKind;
            this.bytesByKind = bytesByKind;
        }
    }

    static final class Frame {
        final String channelId;
        final byte[] payload;
        final boolean binary;
        final Completion completion;
        final long enqueuedAtNanos;
        final int payloadBytes;
        final FrameKind kind;

        Frame(String channelId, byte[] payload, boolean binary, Completion completion,
              long enqueuedAtNanos, int payloadBytes, FrameKind kind) {
            this.channelId = channelId;
            this.payload = payload;
            this.binary = binary;
            this.completion = completion;
            this.enqueuedAtNanos = enqueuedAtNanos;
            this.payloadBytes = payloadBytes;
            this.kind = kind;
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

    private int highWaterFrames;
    private long highWaterBytes;
    private long acceptedCount;
    private long webSocketEnqueuedCount;
    private long queueFullCount;
    private long channelNotOpenCount;
    private long transportRejectedCount;
    private long connectionStoppedCount;
    private long residenceCount;
    private long residenceTotalNanos;
    private long residenceMaxNanos;
    private final long[] residenceLatencyBuckets = new long[LATENCY_BUCKET_COUNT];
    private final EnumMap<FrameKind, Long> acceptedByKind = newEmptyKindMap();
    private final EnumMap<FrameKind, Long> webSocketEnqueuedByKind = newEmptyKindMap();
    private final EnumMap<FrameKind, Long> rejectedByKind = newEmptyKindMap();
    private final EnumMap<FrameKind, Long> bytesByKind = newEmptyKindMap();

    public MuxOutboundQueue(int maxFrames, long maxBytes) {
        if (maxFrames <= 0 || maxBytes <= 0L) {
            throw new IllegalArgumentException("queue budgets must be positive");
        }
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
    }

    synchronized Offer offer(String channelId, byte[] payload, boolean binary,
                             Completion completion) {
        return offer(channelId, payload, binary, inferFrameKind(channelId), completion);
    }

    synchronized Offer offer(String channelId, byte[] payload, boolean binary, FrameKind kind,
                             Completion completion) {
        FrameKind resolved = kind != null ? kind : FrameKind.OTHER;
        if (!accepting) {
            connectionStoppedCount++;
            incrementKind(rejectedByKind, resolved);
            return new Offer(Result.CONNECTION_STOPPED, false);
        }
        if (frames.size() >= maxFrames || bytes + payload.length > maxBytes) {
            queueFullCount++;
            incrementKind(rejectedByKind, resolved);
            return new Offer(Result.QUEUE_FULL, false);
        }
        long enqueuedAt = System.nanoTime();
        Completion wrapped = wrapCompletion(enqueuedAt, resolved, completion);
        frames.addLast(new Frame(channelId, payload, binary, wrapped, enqueuedAt,
            payload.length, resolved));
        bytes += payload.length;
        acceptedCount++;
        incrementKind(acceptedByKind, resolved);
        addKind(bytesByKind, resolved, payload.length);
        if (frames.size() > highWaterFrames) highWaterFrames = frames.size();
        if (bytes > highWaterBytes) highWaterBytes = bytes;
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
        bytes -= frame.payloadBytes;
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

    /** 返回队列深度、高水位与分类累计的不可变快照。 */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
            frames.size(), bytes, highWaterFrames, highWaterBytes,
            acceptedCount, webSocketEnqueuedCount, queueFullCount,
            channelNotOpenCount, transportRejectedCount, connectionStoppedCount,
            residenceCount, residenceTotalNanos, residenceMaxNanos,
            Arrays.copyOf(residenceLatencyBuckets, residenceLatencyBuckets.length),
            copyKindMap(acceptedByKind),
            copyKindMap(webSocketEnqueuedByKind),
            copyKindMap(rejectedByKind),
            copyKindMap(bytesByKind));
    }

    static FrameKind inferFrameKind(String channelId) {
        if (channelId == null || channelId.isEmpty()) return FrameKind.OTHER;
        String id = channelId.toLowerCase(Locale.ROOT);
        if (id.contains("capture")) return FrameKind.OTHER;
        if (id.contains("webterm.screen") || id.contains("screen")) return FrameKind.SCREEN;
        if (id.contains("manager")) return FrameKind.MANAGER;
        if (id.contains("input")) return FrameKind.INPUT;
        if (id.contains("control")) return FrameKind.CONTROL;
        if (id.contains("file")) return FrameKind.FILE;
        return FrameKind.OTHER;
    }

    private Completion wrapCompletion(long enqueuedAtNanos, FrameKind kind, Completion completion) {
        return result -> {
            noteResult(result, kind, enqueuedAtNanos);
            if (completion != null) completion.onResult(result);
        };
    }

    private synchronized void noteResult(Result result, FrameKind kind, long enqueuedAtNanos) {
        switch (result) {
            case WEBSOCKET_ENQUEUED:
                webSocketEnqueuedCount++;
                incrementKind(webSocketEnqueuedByKind, kind);
                recordResidence(System.nanoTime() - enqueuedAtNanos);
                break;
            case CHANNEL_NOT_OPEN:
                channelNotOpenCount++;
                incrementKind(rejectedByKind, kind);
                break;
            case TRANSPORT_REJECTED:
                transportRejectedCount++;
                incrementKind(rejectedByKind, kind);
                break;
            case CONNECTION_STOPPED:
                connectionStoppedCount++;
                incrementKind(rejectedByKind, kind);
                break;
            case QUEUE_FULL:
            case LOCAL_ACCEPTED:
                break;
        }
    }

    private void recordResidence(long nanos) {
        long safe = Math.max(0L, nanos);
        residenceCount++;
        residenceTotalNanos += safe;
        if (safe > residenceMaxNanos) residenceMaxNanos = safe;
        int bucket = LATENCY_BUCKET_UPPER_BOUNDS_NANOS.length;
        for (int i = 0; i < LATENCY_BUCKET_UPPER_BOUNDS_NANOS.length; i++) {
            if (safe < LATENCY_BUCKET_UPPER_BOUNDS_NANOS[i]) {
                bucket = i;
                break;
            }
        }
        residenceLatencyBuckets[bucket]++;
    }

    private static EnumMap<FrameKind, Long> newEmptyKindMap() {
        EnumMap<FrameKind, Long> map = new EnumMap<>(FrameKind.class);
        for (FrameKind kind : FrameKind.values()) {
            map.put(kind, 0L);
        }
        return map;
    }

    private static void incrementKind(EnumMap<FrameKind, Long> map, FrameKind kind) {
        addKind(map, kind, 1L);
    }

    private static void addKind(EnumMap<FrameKind, Long> map, FrameKind kind, long delta) {
        FrameKind resolved = kind != null ? kind : FrameKind.OTHER;
        map.put(resolved, map.getOrDefault(resolved, 0L) + delta);
    }

    private static Map<String, Long> copyKindMap(EnumMap<FrameKind, Long> source) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (FrameKind kind : FrameKind.values()) {
            out.put(kind.name(), source.getOrDefault(kind, 0L));
        }
        return out;
    }
}
