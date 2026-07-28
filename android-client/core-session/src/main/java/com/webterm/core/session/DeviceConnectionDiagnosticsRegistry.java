package com.webterm.core.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 活跃 {@link DeviceConnection} 的进程级注册表，供诊断导出采集 outbound/inbound 快照。
 */
public final class DeviceConnectionDiagnosticsRegistry {
    private static final Map<DeviceConnection, Boolean> ACTIVE =
        Collections.synchronizedMap(new IdentityHashMap<>());

    private DeviceConnectionDiagnosticsRegistry() {}

    public static void register(DeviceConnection connection) {
        if (connection == null) return;
        ACTIVE.put(connection, Boolean.TRUE);
    }

    public static void unregister(DeviceConnection connection) {
        if (connection == null) return;
        ACTIVE.remove(connection);
    }

    /** 测试用：清空注册表。 */
    public static void clearForTest() {
        ACTIVE.clear();
    }

    public static List<DeviceConnection.DiagnosticsSnapshot> snapshotAll() {
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
}
