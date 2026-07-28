package com.webterm.core.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 活跃 {@link DeviceConnection} 的进程级注册表，供诊断导出采集 outbound/inbound 快照。
 * 关闭后保留最近 {@link #MAX_RECENT_CLOSED} 条最终快照；淘汰时并入
 * {@link #ARCHIVED_TOTALS}，保证 lifetime 累计计数单调不减。
 */
public final class DeviceConnectionDiagnosticsRegistry {
    static final int MAX_RECENT_CLOSED = 16;

    private static final Map<DeviceConnection, Boolean> ACTIVE =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ArrayDeque<DeviceConnection.DiagnosticsSnapshot> RECENT_CLOSED =
        new ArrayDeque<>();
    private static final ArchivedTotals ARCHIVED_TOTALS = new ArchivedTotals();

    private static final AtomicLong RECOVERY_STARTED = new AtomicLong();
    private static final AtomicLong RECOVERY_COMPLETED = new AtomicLong();
    private static final AtomicLong RECOVERY_TOTAL_DOWNTIME_MS = new AtomicLong();
    private static final AtomicLong RECOVERY_MAX_DOWNTIME_MS = new AtomicLong();

    private static final AtomicLong DIAGNOSTICS_CONTEXT_SEND_ATTEMPT = new AtomicLong();
    private static final AtomicLong DIAGNOSTICS_CONTEXT_SEND_SUCCESS = new AtomicLong();
    private static final AtomicLong DIAGNOSTICS_CONTEXT_SEND_FAILURE = new AtomicLong();

    private DeviceConnectionDiagnosticsRegistry() {}

    public static void register(DeviceConnection connection) {
        if (connection == null) return;
        ACTIVE.put(connection, Boolean.TRUE);
    }

    public static void unregister(DeviceConnection connection) {
        if (connection == null) return;
        DeviceConnection.DiagnosticsSnapshot snapshot = connection.diagnosticsSnapshot();
        ConnectionCloseReason closeReason = snapshot.lastCloseReason != null
            ? snapshot.lastCloseReason
            : ConnectionCloseReason.APP_SHUTDOWN;
        retainClosed(snapshot.withClosed(System.currentTimeMillis(), closeReason));
        ACTIVE.remove(connection);
    }

    public static void noteRecoveryStarted() {
        RECOVERY_STARTED.incrementAndGet();
    }

    public static void noteRecoveryCompleted(long downtimeMs) {
        RECOVERY_COMPLETED.incrementAndGet();
        long safe = Math.max(0L, downtimeMs);
        RECOVERY_TOTAL_DOWNTIME_MS.addAndGet(safe);
        for (;;) {
            long cur = RECOVERY_MAX_DOWNTIME_MS.get();
            if (safe <= cur) break;
            if (RECOVERY_MAX_DOWNTIME_MS.compareAndSet(cur, safe)) break;
        }
    }

    /** diagnostics.connection 关联上下文发送结果（attempt 恒增；success/failure 互斥）。 */
    public static void noteDiagnosticsContextSend(boolean sent) {
        DIAGNOSTICS_CONTEXT_SEND_ATTEMPT.incrementAndGet();
        if (sent) {
            DIAGNOSTICS_CONTEXT_SEND_SUCCESS.incrementAndGet();
        } else {
            DIAGNOSTICS_CONTEXT_SEND_FAILURE.incrementAndGet();
        }
    }

    /** 测试用：清空注册表。 */
    public static void clearForTest() {
        ACTIVE.clear();
        synchronized (RECENT_CLOSED) {
            RECENT_CLOSED.clear();
        }
        ARCHIVED_TOTALS.clear();
        RECOVERY_STARTED.set(0L);
        RECOVERY_COMPLETED.set(0L);
        RECOVERY_TOTAL_DOWNTIME_MS.set(0L);
        RECOVERY_MAX_DOWNTIME_MS.set(0L);
        DIAGNOSTICS_CONTEXT_SEND_ATTEMPT.set(0L);
        DIAGNOSTICS_CONTEXT_SEND_SUCCESS.set(0L);
        DIAGNOSTICS_CONTEXT_SEND_FAILURE.set(0L);
    }

    public static List<DeviceConnection.DiagnosticsSnapshot> snapshotAll() {
        return snapshotActive();
    }

    public static List<DeviceConnection.DiagnosticsSnapshot> snapshotActive() {
        DeviceConnection[] connections;
        synchronized (ACTIVE) {
            connections = ACTIVE.keySet().toArray(new DeviceConnection[0]);
        }
        List<DeviceConnection.DiagnosticsSnapshot> out = new ArrayList<>(connections.length);
        for (DeviceConnection connection : connections) {
            out.add(connection.diagnosticsSnapshot());
        }
        return out;
    }

    public static List<DeviceConnection.DiagnosticsSnapshot> snapshotRecentClosed() {
        synchronized (RECENT_CLOSED) {
            return new ArrayList<>(RECENT_CLOSED);
        }
    }

    /** 指标聚合：活跃 + 最近关闭（不含 archived；聚合方法会另加 ARCHIVED_TOTALS）。 */
    public static List<DeviceConnection.DiagnosticsSnapshot> snapshotForMetrics() {
        List<DeviceConnection.DiagnosticsSnapshot> out = new ArrayList<>();
        out.addAll(snapshotActive());
        out.addAll(snapshotRecentClosed());
        return out;
    }

    /** 活跃 / 最近关闭 / 已归档连接计数（lifetime = 三者之和）。 */
    public static Map<String, Long> lifetimeConnectionCounts() {
        long active;
        synchronized (ACTIVE) {
            active = ACTIVE.size();
        }
        long recentClosed;
        synchronized (RECENT_CLOSED) {
            recentClosed = RECENT_CLOSED.size();
        }
        long archived = ARCHIVED_TOTALS.archivedConnectionCount();
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("activeConnectionCount", active);
        out.put("recentClosedConnectionCount", recentClosed);
        out.put("archivedConnectionCount", archived);
        out.put("lifetimeConnectionCount", active + recentClosed + archived);
        return out;
    }

    /** outbound 累计聚合（active + recentClosed + archived）；current* 仅来自 active/recent。 */
    public static Map<String, Object> aggregateOutboundQueue() {
        long currentFrames = 0L;
        long currentBytes = 0L;
        long highWaterFrames = 0L;
        long highWaterBytes = 0L;
        long acceptedCount = 0L;
        long webSocketEnqueuedCount = 0L;
        long queueFullCount = 0L;
        long channelNotOpenCount = 0L;
        long transportRejectedCount = 0L;
        long connectionStoppedCount = 0L;
        long residenceCount = 0L;
        long residenceTotalNanos = 0L;
        long residenceMaxNanos = 0L;
        long[] residenceLatencyBuckets = new long[MuxOutboundQueue.LATENCY_BUCKET_COUNT];
        Map<String, Long> acceptedByKind = new LinkedHashMap<>();
        Map<String, Long> webSocketEnqueuedByKind = new LinkedHashMap<>();
        Map<String, Long> rejectedByKind = new LinkedHashMap<>();
        Map<String, Long> bytesByKind = new LinkedHashMap<>();

        List<DeviceConnection.DiagnosticsSnapshot> connections = snapshotForMetrics();
        for (DeviceConnection.DiagnosticsSnapshot connection : connections) {
            MuxOutboundQueue.Snapshot q = connection.outboundQueue;
            if (q == null) continue;
            currentFrames += q.currentFrames;
            currentBytes += q.currentBytes;
            if (q.highWaterFrames > highWaterFrames) highWaterFrames = q.highWaterFrames;
            if (q.highWaterBytes > highWaterBytes) highWaterBytes = q.highWaterBytes;
            acceptedCount += q.acceptedCount;
            webSocketEnqueuedCount += q.webSocketEnqueuedCount;
            queueFullCount += q.queueFullCount;
            channelNotOpenCount += q.channelNotOpenCount;
            transportRejectedCount += q.transportRejectedCount;
            connectionStoppedCount += q.connectionStoppedCount;
            residenceCount += q.residenceCount;
            residenceTotalNanos += q.residenceTotalNanos;
            if (q.residenceMaxNanos > residenceMaxNanos) residenceMaxNanos = q.residenceMaxNanos;
            addBuckets(residenceLatencyBuckets, q.residenceLatencyBuckets);
            mergeKindCounts(acceptedByKind, q.acceptedByKind);
            mergeKindCounts(webSocketEnqueuedByKind, q.webSocketEnqueuedByKind);
            mergeKindCounts(rejectedByKind, q.rejectedByKind);
            mergeKindCounts(bytesByKind, q.bytesByKind);
        }

        ArchivedTotals archived = ARCHIVED_TOTALS.copy();
        if (archived.highWaterFrames > highWaterFrames) highWaterFrames = archived.highWaterFrames;
        if (archived.highWaterBytes > highWaterBytes) highWaterBytes = archived.highWaterBytes;
        acceptedCount += archived.acceptedCount;
        webSocketEnqueuedCount += archived.webSocketEnqueuedCount;
        queueFullCount += archived.queueFullCount;
        channelNotOpenCount += archived.channelNotOpenCount;
        transportRejectedCount += archived.transportRejectedCount;
        connectionStoppedCount += archived.connectionStoppedCount;
        residenceCount += archived.residenceCount;
        residenceTotalNanos += archived.residenceTotalNanos;
        if (archived.residenceMaxNanos > residenceMaxNanos) {
            residenceMaxNanos = archived.residenceMaxNanos;
        }
        addBuckets(residenceLatencyBuckets, archived.residenceLatencyBuckets);
        mergeKindCounts(acceptedByKind, archived.acceptedByKind);
        mergeKindCounts(webSocketEnqueuedByKind, archived.webSocketEnqueuedByKind);
        mergeKindCounts(rejectedByKind, archived.rejectedByKind);
        mergeKindCounts(bytesByKind, archived.bytesByKind);

        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(lifetimeConnectionCounts());
        out.put("connectionCount", out.get("lifetimeConnectionCount"));
        out.put("currentFrames", currentFrames);
        out.put("currentBytes", currentBytes);
        out.put("highWaterFrames", highWaterFrames);
        out.put("highWaterBytes", highWaterBytes);
        out.put("acceptedCount", acceptedCount);
        out.put("webSocketEnqueuedCount", webSocketEnqueuedCount);
        out.put("queueFullCount", queueFullCount);
        out.put("channelNotOpenCount", channelNotOpenCount);
        out.put("transportRejectedCount", transportRejectedCount);
        out.put("connectionStoppedCount", connectionStoppedCount);
        out.put("residenceCount", residenceCount);
        out.put("residenceTotalNanos", residenceTotalNanos);
        out.put("residenceMaxNanos", residenceMaxNanos);
        out.put("residenceLatencyBuckets", residenceLatencyBuckets);
        Map<String, Object> byFrameKind = new LinkedHashMap<>();
        byFrameKind.put("acceptedByKind", acceptedByKind);
        byFrameKind.put("webSocketEnqueuedByKind", webSocketEnqueuedByKind);
        byFrameKind.put("rejectedByKind", rejectedByKind);
        byFrameKind.put("bytesByKind", bytesByKind);
        out.put("byFrameKind", byFrameKind);
        return out;
    }

    /** inbound 丢弃累计聚合（含 archived）。 */
    public static Map<String, Long> aggregateInboundDrops() {
        long staleTransportGenerationDropped = 0L;
        long tunnelDecodeFailed = 0L;
        long unknownChannelDropped = 0L;
        long channelNotOpenDropped = 0L;
        for (DeviceConnection.DiagnosticsSnapshot connection : snapshotForMetrics()) {
            DeviceConnection.InboundDropSnapshot drops = connection.inboundDrops;
            if (drops == null) continue;
            staleTransportGenerationDropped += drops.staleTransportGenerationDropped;
            tunnelDecodeFailed += drops.tunnelDecodeFailed;
            unknownChannelDropped += drops.unknownChannelDropped;
            channelNotOpenDropped += drops.channelNotOpenDropped;
        }
        ArchivedTotals archived = ARCHIVED_TOTALS.copy();
        staleTransportGenerationDropped += archived.staleTransportGenerationDropped;
        tunnelDecodeFailed += archived.tunnelDecodeFailed;
        unknownChannelDropped += archived.unknownChannelDropped;
        channelNotOpenDropped += archived.channelNotOpenDropped;

        Map<String, Long> out = new LinkedHashMap<>();
        out.putAll(lifetimeConnectionCounts());
        out.put("connectionCount", out.get("lifetimeConnectionCount"));
        out.put("staleTransportGenerationDropped", staleTransportGenerationDropped);
        out.put("tunnelDecodeFailed", tunnelDecodeFailed);
        out.put("unknownChannelDropped", unknownChannelDropped);
        out.put("channelNotOpenDropped", channelNotOpenDropped);
        return out;
    }

    public static Map<String, Long> aggregateConnectionRecovery() {
        int activeCount = 0;
        for (DeviceConnection.DiagnosticsSnapshot connection : snapshotActive()) {
            if (connection.recoveryId != null && !connection.recoveryId.isEmpty()) {
                activeCount++;
            }
        }
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("startedCount", RECOVERY_STARTED.get());
        out.put("completedCount", RECOVERY_COMPLETED.get());
        out.put("activeCount", (long) activeCount);
        out.put("totalDowntimeMs", RECOVERY_TOTAL_DOWNTIME_MS.get());
        out.put("maxDowntimeMs", RECOVERY_MAX_DOWNTIME_MS.get());
        return out;
    }

    /** diagnostics.connection 发送尝试/成败计数。 */
    public static Map<String, Long> aggregateDiagnosticsContextSend() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("diagnosticsContextSendAttemptCount", DIAGNOSTICS_CONTEXT_SEND_ATTEMPT.get());
        out.put("diagnosticsContextSendSuccessCount", DIAGNOSTICS_CONTEXT_SEND_SUCCESS.get());
        out.put("diagnosticsContextSendFailureCount", DIAGNOSTICS_CONTEXT_SEND_FAILURE.get());
        return out;
    }

    private static void retainClosed(DeviceConnection.DiagnosticsSnapshot snapshot) {
        if (snapshot == null) return;
        synchronized (RECENT_CLOSED) {
            while (RECENT_CLOSED.size() >= MAX_RECENT_CLOSED) {
                DeviceConnection.DiagnosticsSnapshot oldest = RECENT_CLOSED.removeFirst();
                ARCHIVED_TOTALS.merge(oldest);
            }
            RECENT_CLOSED.addLast(snapshot);
        }
    }

    private static void mergeKindCounts(Map<String, Long> target, Map<String, Long> source) {
        if (source == null) return;
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            long add = entry.getValue() != null ? entry.getValue() : 0L;
            target.put(entry.getKey(), target.getOrDefault(entry.getKey(), 0L) + add);
        }
    }

    private static void addBuckets(long[] target, long[] source) {
        if (source == null) return;
        for (int i = 0; i < target.length && i < source.length; i++) {
            target[i] += source[i];
        }
    }

    /** 被 recentClosed 淘汰的连接累计；不含 currentFrames/currentBytes 等当前深度。 */
    static final class ArchivedTotals {
        private long archivedConnectionCount;
        private long highWaterFrames;
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
        private final long[] residenceLatencyBuckets = new long[MuxOutboundQueue.LATENCY_BUCKET_COUNT];
        private final Map<String, Long> acceptedByKind = new LinkedHashMap<>();
        private final Map<String, Long> webSocketEnqueuedByKind = new LinkedHashMap<>();
        private final Map<String, Long> rejectedByKind = new LinkedHashMap<>();
        private final Map<String, Long> bytesByKind = new LinkedHashMap<>();
        private long staleTransportGenerationDropped;
        private long tunnelDecodeFailed;
        private long unknownChannelDropped;
        private long channelNotOpenDropped;

        synchronized void merge(DeviceConnection.DiagnosticsSnapshot snapshot) {
            if (snapshot == null) return;
            archivedConnectionCount++;
            MuxOutboundQueue.Snapshot q = snapshot.outboundQueue;
            if (q != null) {
                if (q.highWaterFrames > highWaterFrames) highWaterFrames = q.highWaterFrames;
                if (q.highWaterBytes > highWaterBytes) highWaterBytes = q.highWaterBytes;
                acceptedCount += q.acceptedCount;
                webSocketEnqueuedCount += q.webSocketEnqueuedCount;
                queueFullCount += q.queueFullCount;
                channelNotOpenCount += q.channelNotOpenCount;
                transportRejectedCount += q.transportRejectedCount;
                connectionStoppedCount += q.connectionStoppedCount;
                residenceCount += q.residenceCount;
                residenceTotalNanos += q.residenceTotalNanos;
                if (q.residenceMaxNanos > residenceMaxNanos) residenceMaxNanos = q.residenceMaxNanos;
                addBuckets(residenceLatencyBuckets, q.residenceLatencyBuckets);
                mergeKindCounts(acceptedByKind, q.acceptedByKind);
                mergeKindCounts(webSocketEnqueuedByKind, q.webSocketEnqueuedByKind);
                mergeKindCounts(rejectedByKind, q.rejectedByKind);
                mergeKindCounts(bytesByKind, q.bytesByKind);
            }
            DeviceConnection.InboundDropSnapshot drops = snapshot.inboundDrops;
            if (drops != null) {
                staleTransportGenerationDropped += drops.staleTransportGenerationDropped;
                tunnelDecodeFailed += drops.tunnelDecodeFailed;
                unknownChannelDropped += drops.unknownChannelDropped;
                channelNotOpenDropped += drops.channelNotOpenDropped;
            }
        }

        synchronized void clear() {
            archivedConnectionCount = 0L;
            highWaterFrames = 0L;
            highWaterBytes = 0L;
            acceptedCount = 0L;
            webSocketEnqueuedCount = 0L;
            queueFullCount = 0L;
            channelNotOpenCount = 0L;
            transportRejectedCount = 0L;
            connectionStoppedCount = 0L;
            residenceCount = 0L;
            residenceTotalNanos = 0L;
            residenceMaxNanos = 0L;
            java.util.Arrays.fill(residenceLatencyBuckets, 0L);
            acceptedByKind.clear();
            webSocketEnqueuedByKind.clear();
            rejectedByKind.clear();
            bytesByKind.clear();
            staleTransportGenerationDropped = 0L;
            tunnelDecodeFailed = 0L;
            unknownChannelDropped = 0L;
            channelNotOpenDropped = 0L;
        }

        synchronized long archivedConnectionCount() {
            return archivedConnectionCount;
        }

        synchronized ArchivedTotals copy() {
            ArchivedTotals out = new ArchivedTotals();
            out.archivedConnectionCount = archivedConnectionCount;
            out.highWaterFrames = highWaterFrames;
            out.highWaterBytes = highWaterBytes;
            out.acceptedCount = acceptedCount;
            out.webSocketEnqueuedCount = webSocketEnqueuedCount;
            out.queueFullCount = queueFullCount;
            out.channelNotOpenCount = channelNotOpenCount;
            out.transportRejectedCount = transportRejectedCount;
            out.connectionStoppedCount = connectionStoppedCount;
            out.residenceCount = residenceCount;
            out.residenceTotalNanos = residenceTotalNanos;
            out.residenceMaxNanos = residenceMaxNanos;
            System.arraycopy(residenceLatencyBuckets, 0,
                out.residenceLatencyBuckets, 0, residenceLatencyBuckets.length);
            out.acceptedByKind.putAll(acceptedByKind);
            out.webSocketEnqueuedByKind.putAll(webSocketEnqueuedByKind);
            out.rejectedByKind.putAll(rejectedByKind);
            out.bytesByKind.putAll(bytesByKind);
            out.staleTransportGenerationDropped = staleTransportGenerationDropped;
            out.tunnelDecodeFailed = tunnelDecodeFailed;
            out.unknownChannelDropped = unknownChannelDropped;
            out.channelNotOpenDropped = channelNotOpenDropped;
            return out;
        }
    }
}
