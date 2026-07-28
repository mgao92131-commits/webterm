package com.webterm.feature.terminal.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 活跃 {@link TerminalSessionRuntime} 的进程级注册表，供诊断导出采集
 * per-session pipeline / history loader 快照与进程聚合。
 * 关闭后保留最近 {@link #MAX_RECENT_CLOSED} 条最终快照。
 */
public final class TerminalPipelineDiagnosticsRegistry {
    static final int MAX_RECENT_CLOSED = 32;

    private static final Map<TerminalSessionRuntime, Boolean> ACTIVE =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ArrayDeque<SessionDiagnosticsSnapshot> RECENT_CLOSED =
        new ArrayDeque<>();

    private TerminalPipelineDiagnosticsRegistry() {}

    public static void register(TerminalSessionRuntime runtime) {
        if (runtime == null) return;
        ACTIVE.put(runtime, Boolean.TRUE);
    }

    public static void unregister(TerminalSessionRuntime runtime) {
        if (runtime == null) return;
        SessionDiagnosticsSnapshot snapshot = runtime.diagnosticsSnapshotForClose();
        retainClosed(snapshot);
        ACTIVE.remove(runtime);
    }

    /** 测试用：清空注册表。 */
    public static void clearForTest() {
        ACTIVE.clear();
        synchronized (RECENT_CLOSED) {
            RECENT_CLOSED.clear();
        }
    }

    public static List<SessionDiagnosticsSnapshot> snapshotAll() {
        return snapshotActive();
    }

    public static List<SessionDiagnosticsSnapshot> snapshotActive() {
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

    public static List<SessionDiagnosticsSnapshot> snapshotRecentClosed() {
        synchronized (RECENT_CLOSED) {
            return new ArrayList<>(RECENT_CLOSED);
        }
    }

    /** 跨会话 pipeline 计数聚合（含最近关闭会话，保留累计水位）。 */
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
        long renderSuccessCount = 0L;
        long renderFailureCount = 0L;
        long stateOnlyHandledCount = 0L;
        int sessionCount = 0;
        List<SessionDiagnosticsSnapshot> sessions = new ArrayList<>();
        sessions.addAll(snapshotActive());
        sessions.addAll(snapshotRecentClosed());
        for (SessionDiagnosticsSnapshot session : sessions) {
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
            renderSuccessCount += longOf(pipeline, "renderSuccessCount");
            renderFailureCount += longOf(pipeline, "renderFailureCount");
            stateOnlyHandledCount += longOf(pipeline, "stateOnlyHandledCount");
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
        out.put("renderSuccessCount", renderSuccessCount);
        out.put("renderFailureCount", renderFailureCount);
        out.put("stateOnlyHandledCount", stateOnlyHandledCount);
        return out;
    }

    /** 跨会话 history loader 计数聚合。 */
    public static Map<String, Long> aggregateHistoryLoader() {
        long demandDeduplicatedCount = 0L;
        long pumpWhileFetchingCount = 0L;
        long demandChangedWhileFetchingCount = 0L;
        long activeRequestCount = 0L;
        long closedCount = 0L;
        int sessionCount = 0;
        List<SessionDiagnosticsSnapshot> sessions = new ArrayList<>();
        sessions.addAll(snapshotActive());
        sessions.addAll(snapshotRecentClosed());
        for (SessionDiagnosticsSnapshot session : sessions) {
            sessionCount++;
            Map<String, Object> loader = session.historyLoader;
            demandDeduplicatedCount += longOf(loader, "demandDeduplicatedCount");
            pumpWhileFetchingCount += longOf(loader, "pumpWhileFetchingCount");
            demandChangedWhileFetchingCount += longOf(loader, "demandChangedWhileFetchingCount");
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
        out.put("pumpWhileFetchingCount", pumpWhileFetchingCount);
        out.put("demandChangedWhileFetchingCount", demandChangedWhileFetchingCount);
        out.put("activeRequestCount", activeRequestCount);
        out.put("closedCount", closedCount);
        return out;
    }

    /** 跨会话输入投递计数聚合。 */
    public static Map<String, Long> aggregateInputDelivery() {
        long inputAttemptCount = 0L;
        long inputRejectedNotLiveCount = 0L;
        long inputRejectedNoLeaseCount = 0L;
        long inputLocalQueueAcceptedCount = 0L;
        long inputWebSocketEnqueuedCount = 0L;
        long inputQueueFullCount = 0L;
        long inputTransportRejectedCount = 0L;
        List<SessionDiagnosticsSnapshot> sessions = new ArrayList<>();
        sessions.addAll(snapshotActive());
        sessions.addAll(snapshotRecentClosed());
        for (SessionDiagnosticsSnapshot session : sessions) {
            Map<String, Long> input = session.inputDelivery;
            if (input == null) continue;
            inputAttemptCount += longOf(input, "inputAttemptCount");
            inputRejectedNotLiveCount += longOf(input, "inputRejectedNotLiveCount");
            inputRejectedNoLeaseCount += longOf(input, "inputRejectedNoLeaseCount");
            inputLocalQueueAcceptedCount += longOf(input, "inputLocalQueueAcceptedCount");
            inputWebSocketEnqueuedCount += longOf(input, "inputWebSocketEnqueuedCount");
            inputQueueFullCount += longOf(input, "inputQueueFullCount");
            inputTransportRejectedCount += longOf(input, "inputTransportRejectedCount");
        }
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("inputAttemptCount", inputAttemptCount);
        out.put("inputRejectedNotLiveCount", inputRejectedNotLiveCount);
        out.put("inputRejectedNoLeaseCount", inputRejectedNoLeaseCount);
        out.put("inputLocalQueueAcceptedCount", inputLocalQueueAcceptedCount);
        out.put("inputWebSocketEnqueuedCount", inputWebSocketEnqueuedCount);
        out.put("inputQueueFullCount", inputQueueFullCount);
        out.put("inputTransportRejectedCount", inputTransportRejectedCount);
        return out;
    }

    public static final class SessionDiagnosticsSnapshot {
        public final String sessionId;
        public final String state;
        public final Map<String, Object> pipeline;
        public final Map<String, Object> historyLoader;
        public final Map<String, Long> inputDelivery;
        public final long closedAtEpochMs;
        public final String finalState;
        public final long connectionEpoch;
        public final long syncGeneration;
        public final String projectionContinuity;
        public final boolean renderConsumerAttached;
        public final int listenerCount;
        public final int mailboxMessages;
        public final long mailboxBytes;

        SessionDiagnosticsSnapshot(String sessionId, String state,
                                   Map<String, Object> pipeline,
                                   Map<String, Object> historyLoader) {
            this(sessionId, state, pipeline, historyLoader, Map.of(),
                0L, "", 0L, 0L, "", false, 0, 0, 0L);
        }

        SessionDiagnosticsSnapshot(String sessionId, String state,
                                   Map<String, Object> pipeline,
                                   Map<String, Object> historyLoader,
                                   Map<String, Long> inputDelivery,
                                   long closedAtEpochMs, String finalState,
                                   long connectionEpoch, long syncGeneration,
                                   String projectionContinuity,
                                   boolean renderConsumerAttached,
                                   int listenerCount,
                                   int mailboxMessages, long mailboxBytes) {
            this.sessionId = sessionId != null ? sessionId : "";
            this.state = state != null ? state : "";
            this.pipeline = pipeline != null ? pipeline : Map.of();
            this.historyLoader = historyLoader != null ? historyLoader : Map.of();
            this.inputDelivery = inputDelivery != null ? inputDelivery : Map.of();
            this.closedAtEpochMs = closedAtEpochMs;
            this.finalState = finalState != null ? finalState : "";
            this.connectionEpoch = connectionEpoch;
            this.syncGeneration = syncGeneration;
            this.projectionContinuity = projectionContinuity != null ? projectionContinuity : "";
            this.renderConsumerAttached = renderConsumerAttached;
            this.listenerCount = listenerCount;
            this.mailboxMessages = mailboxMessages;
            this.mailboxBytes = mailboxBytes;
        }
    }

    private static void retainClosed(SessionDiagnosticsSnapshot snapshot) {
        if (snapshot == null) return;
        synchronized (RECENT_CLOSED) {
            RECENT_CLOSED.addLast(snapshot);
            while (RECENT_CLOSED.size() > MAX_RECENT_CLOSED) {
                RECENT_CLOSED.removeFirst();
            }
        }
    }

    private static long longOf(Map<?, ?> map, String key) {
        if (map == null) return 0L;
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }
}
