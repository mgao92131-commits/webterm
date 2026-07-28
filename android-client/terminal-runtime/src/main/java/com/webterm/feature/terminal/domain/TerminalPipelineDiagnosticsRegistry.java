package com.webterm.feature.terminal.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 活跃 {@link TerminalSessionRuntime} 的进程级注册表，供诊断导出采集
 * per-session pipeline / history loader 快照与进程聚合。
 */
public final class TerminalPipelineDiagnosticsRegistry {
    private static final Map<TerminalSessionRuntime, Boolean> ACTIVE =
        Collections.synchronizedMap(new IdentityHashMap<>());

    private TerminalPipelineDiagnosticsRegistry() {}

    public static void register(TerminalSessionRuntime runtime) {
        if (runtime == null) return;
        ACTIVE.put(runtime, Boolean.TRUE);
    }

    public static void unregister(TerminalSessionRuntime runtime) {
        if (runtime == null) return;
        ACTIVE.remove(runtime);
    }

    /** 测试用：清空注册表。 */
    public static void clearForTest() {
        ACTIVE.clear();
    }

    public static List<SessionDiagnosticsSnapshot> snapshotAll() {
        TerminalSessionRuntime[] runtimes;
        synchronized (ACTIVE) {
            runtimes = ACTIVE.keySet().toArray(new TerminalSessionRuntime[0]);
        }
        List<SessionDiagnosticsSnapshot> out = new ArrayList<>(runtimes.length);
        for (TerminalSessionRuntime runtime : runtimes) {
            out.add(runtime.diagnosticsSnapshot());
        }
        return out;
    }

    /** 跨会话 pipeline 计数聚合（不含 per-session 水位实时态）。 */
    public static Map<String, Long> aggregateScreenPipeline() {
        long receivedFrameCount = 0L;
        long receivedBytes = 0L;
        long staleConnectionEpochDropped = 0L;
        long staleMailboxGenerationDropped = 0L;
        long wrongSourceConnectionDropped = 0L;
        long invalidFrameSizeRejected = 0L;
        long projectionOverflowDiscarded = 0L;
        long urgentOverflowDiscarded = 0L;
        long reliableOverflowDiscarded = 0L;
        long backgroundDropped = 0L;
        long unknownEnvelopeCount = 0L;
        int sessionCount = 0;
        for (SessionDiagnosticsSnapshot session : snapshotAll()) {
            sessionCount++;
            Map<String, Object> pipeline = session.pipeline;
            receivedFrameCount += longOf(pipeline, "receivedFrameCount");
            receivedBytes += longOf(pipeline, "receivedBytes");
            staleConnectionEpochDropped += longOf(pipeline, "staleConnectionEpochDropped");
            staleMailboxGenerationDropped += longOf(pipeline, "staleMailboxGenerationDropped");
            wrongSourceConnectionDropped += longOf(pipeline, "wrongSourceConnectionDropped");
            invalidFrameSizeRejected += longOf(pipeline, "invalidFrameSizeRejected");
            projectionOverflowDiscarded += longOf(pipeline, "projectionOverflowDiscarded");
            urgentOverflowDiscarded += longOf(pipeline, "urgentOverflowDiscarded");
            reliableOverflowDiscarded += longOf(pipeline, "reliableOverflowDiscarded");
            backgroundDropped += longOf(pipeline, "backgroundDropped");
            unknownEnvelopeCount += longOf(pipeline, "unknownEnvelopeCount");
        }
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("sessionCount", (long) sessionCount);
        out.put("receivedFrameCount", receivedFrameCount);
        out.put("receivedBytes", receivedBytes);
        out.put("staleConnectionEpochDropped", staleConnectionEpochDropped);
        out.put("staleMailboxGenerationDropped", staleMailboxGenerationDropped);
        out.put("wrongSourceConnectionDropped", wrongSourceConnectionDropped);
        out.put("invalidFrameSizeRejected", invalidFrameSizeRejected);
        out.put("projectionOverflowDiscarded", projectionOverflowDiscarded);
        out.put("urgentOverflowDiscarded", urgentOverflowDiscarded);
        out.put("reliableOverflowDiscarded", reliableOverflowDiscarded);
        out.put("backgroundDropped", backgroundDropped);
        out.put("unknownEnvelopeCount", unknownEnvelopeCount);
        return out;
    }

    /** 跨会话 history loader 计数聚合。 */
    public static Map<String, Long> aggregateHistoryLoader() {
        long demandDeduplicatedCount = 0L;
        long activeRequestCount = 0L;
        long closedCount = 0L;
        int sessionCount = 0;
        for (SessionDiagnosticsSnapshot session : snapshotAll()) {
            sessionCount++;
            Map<String, Object> loader = session.historyLoader;
            demandDeduplicatedCount += longOf(loader, "demandDeduplicatedCount");
            if (Boolean.TRUE.equals(loader.get("hasActiveRequest"))) {
                activeRequestCount++;
            }
            if (Boolean.TRUE.equals(loader.get("closed"))) {
                closedCount++;
            }
        }
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("sessionCount", (long) sessionCount);
        out.put("demandDeduplicatedCount", demandDeduplicatedCount);
        out.put("activeRequestCount", activeRequestCount);
        out.put("closedCount", closedCount);
        return out;
    }

    public static final class SessionDiagnosticsSnapshot {
        public final String sessionId;
        public final String state;
        public final Map<String, Object> pipeline;
        public final Map<String, Object> historyLoader;

        SessionDiagnosticsSnapshot(String sessionId, String state,
                                   Map<String, Object> pipeline,
                                   Map<String, Object> historyLoader) {
            this.sessionId = sessionId != null ? sessionId : "";
            this.state = state != null ? state : "";
            this.pipeline = pipeline != null ? pipeline : Map.of();
            this.historyLoader = historyLoader != null ? historyLoader : Map.of();
        }
    }

    private static long longOf(Map<String, Object> map, String key) {
        if (map == null) return 0L;
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }
}
