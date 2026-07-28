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
 * 关闭后保留最近 {@link #MAX_RECENT_CLOSED} 条最终快照，避免页面退出后丢失高水位。
 */
public final class DeviceConnectionDiagnosticsRegistry {
    static final int MAX_RECENT_CLOSED = 16;

    private static final Map<DeviceConnection, Boolean> ACTIVE =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ArrayDeque<DeviceConnection.DiagnosticsSnapshot> RECENT_CLOSED =
        new ArrayDeque<>();

    private static final AtomicLong RECOVERY_STARTED = new AtomicLong();
    private static final AtomicLong RECOVERY_COMPLETED = new AtomicLong();
    private static final AtomicLong RECOVERY_TOTAL_DOWNTIME_MS = new AtomicLong();
    private static final AtomicLong RECOVERY_MAX_DOWNTIME_MS = new AtomicLong();

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

    /** 测试用：清空注册表。 */
    public static void clearForTest() {
        ACTIVE.clear();
        synchronized (RECENT_CLOSED) {
            RECENT_CLOSED.clear();
        }
        RECOVERY_STARTED.set(0L);
        RECOVERY_COMPLETED.set(0L);
        RECOVERY_TOTAL_DOWNTIME_MS.set(0L);
        RECOVERY_MAX_DOWNTIME_MS.set(0L);
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

    /** 指标聚合：活跃 + 最近关闭（保留高水位等累计语义）。 */
    public static List<DeviceConnection.DiagnosticsSnapshot> snapshotForMetrics() {
        List<DeviceConnection.DiagnosticsSnapshot> out = new ArrayList<>();
        out.addAll(snapshotActive());
        out.addAll(snapshotRecentClosed());
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

    private static void retainClosed(DeviceConnection.DiagnosticsSnapshot snapshot) {
        if (snapshot == null) return;
        synchronized (RECENT_CLOSED) {
            RECENT_CLOSED.addLast(snapshot);
            while (RECENT_CLOSED.size() > MAX_RECENT_CLOSED) {
                RECENT_CLOSED.removeFirst();
            }
        }
    }
}
