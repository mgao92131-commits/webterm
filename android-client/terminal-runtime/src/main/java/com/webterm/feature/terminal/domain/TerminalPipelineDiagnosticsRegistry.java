package com.webterm.feature.terminal.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 活跃 {@link TerminalSessionRuntime} 的进程级注册表，供诊断导出采集
 * per-session pipeline / history loader 快照与进程聚合。
 * 关闭后保留最近 {@link #MAX_RECENT_CLOSED} 条最终快照；淘汰时并入
 * {@link #ARCHIVED_TOTALS}，保证 lifetime 累计计数单调不减。
 * <p>
 * ACTIVE / RECENT_CLOSED / ARCHIVED_TOTALS 由同一把 {@link #LOCK} 保护，
 * 避免 unregister 与 aggregate 交错导致会话被双重计数。
 */
public final class TerminalPipelineDiagnosticsRegistry {
    static final int MAX_RECENT_CLOSED = 32;

    private static final Object LOCK = new Object();
    private static final Map<TerminalSessionRuntime, Boolean> ACTIVE = new IdentityHashMap<>();
    private static final ArrayDeque<SessionDiagnosticsSnapshot> RECENT_CLOSED = new ArrayDeque<>();
    private static final ArchivedTotals ARCHIVED_TOTALS = new ArchivedTotals();

    private TerminalPipelineDiagnosticsRegistry() {}

    public static void register(TerminalSessionRuntime runtime) {
        if (runtime == null) return;
        synchronized (LOCK) {
            ACTIVE.put(runtime, Boolean.TRUE);
        }
    }

    /**
     * 注销 runtime 并保留调用方提供的最终不可变快照。
     * 不得再主动读取 runtime（清理后状态由 {@code finalSnapshot} 承载）。
     * 顺序：先从 ACTIVE 移除，再写入 recentClosed（必要时归档最旧项）。
     */
    public static void unregister(TerminalSessionRuntime runtime,
                                  SessionDiagnosticsSnapshot finalSnapshot) {
        if (runtime == null) return;
        synchronized (LOCK) {
            ACTIVE.remove(runtime);
            retainClosedLocked(finalSnapshot);
        }
    }

    /** 测试用：清空注册表。 */
    public static void clearForTest() {
        synchronized (LOCK) {
            ACTIVE.clear();
            RECENT_CLOSED.clear();
            ARCHIVED_TOTALS.clear();
        }
    }

    public static List<SessionDiagnosticsSnapshot> snapshotAll() {
        return snapshotActive();
    }

    public static List<SessionDiagnosticsSnapshot> snapshotActive() {
        TerminalSessionRuntime[] runtimes;
        synchronized (LOCK) {
            runtimes = ACTIVE.keySet().toArray(new TerminalSessionRuntime[0]);
        }
        List<SessionDiagnosticsSnapshot> out = new ArrayList<>(runtimes.length);
        for (TerminalSessionRuntime runtime : runtimes) {
            out.add(runtime.diagnosticsSnapshot());
        }
        return out;
    }

    public static List<SessionDiagnosticsSnapshot> snapshotRecentClosed() {
        synchronized (LOCK) {
            return new ArrayList<>(RECENT_CLOSED);
        }
    }

    /** 活跃 / 最近关闭 / 已归档会话计数（lifetime = 三者之和）。 */
    public static Map<String, Long> lifetimeSessionCounts() {
        synchronized (LOCK) {
            long active = ACTIVE.size();
            long recentClosed = RECENT_CLOSED.size();
            long archived = ARCHIVED_TOTALS.archivedSessionCount;
            Map<String, Long> out = new LinkedHashMap<>();
            out.put("activeSessionCount", active);
            out.put("recentClosedSessionCount", recentClosed);
            out.put("archivedSessionCount", archived);
            out.put("lifetimeSessionCount", active + recentClosed + archived);
            return out;
        }
    }

    /** 跨会话 pipeline 计数聚合（active + recentClosed + archived，lifetime 单调）。 */
    public static Map<String, Long> aggregateScreenPipeline() {
        TerminalSessionRuntime[] runtimes;
        List<SessionDiagnosticsSnapshot> recentClosed;
        ArchivedTotals archived;
        synchronized (LOCK) {
            runtimes = ACTIVE.keySet().toArray(new TerminalSessionRuntime[0]);
            recentClosed = new ArrayList<>(RECENT_CLOSED);
            archived = ARCHIVED_TOTALS.copy();
        }
        List<SessionDiagnosticsSnapshot> sessions = new ArrayList<>(runtimes.length + recentClosed.size());
        for (TerminalSessionRuntime runtime : runtimes) {
            sessions.add(runtime.diagnosticsSnapshot());
        }
        sessions.addAll(recentClosed);

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
        for (SessionDiagnosticsSnapshot session : sessions) {
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
        receivedFrameCount += archived.receivedFrameCount;
        receivedBytes += archived.receivedBytes;
        staleConnectionEpochDropped += archived.staleConnectionEpochDropped;
        staleMailboxGenerationDropped += archived.staleMailboxGenerationDropped;
        wrongSourceConnectionDropped += archived.wrongSourceConnectionDropped;
        invalidFrameSizeRejected += archived.invalidFrameSizeRejected;
        projectionOverflowDiscarded += archived.projectionOverflowDiscarded;
        urgentOverflowDiscarded += archived.urgentOverflowDiscarded;
        reliableOverflowDiscarded += archived.reliableOverflowDiscarded;
        backgroundDropped += archived.backgroundDropped;
        unknownEnvelopeCount += archived.unknownEnvelopeCount;
        renderSuccessCount += archived.renderSuccessCount;
        renderFailureCount += archived.renderFailureCount;
        stateOnlyHandledCount += archived.stateOnlyHandledCount;

        Map<String, Long> out = new LinkedHashMap<>();
        out.putAll(lifetimeSessionCounts());
        out.put("sessionCount", out.get("lifetimeSessionCount"));
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

    /** 跨会话 history loader 计数聚合（含 archived）。 */
    public static Map<String, Long> aggregateHistoryLoader() {
        TerminalSessionRuntime[] runtimes;
        List<SessionDiagnosticsSnapshot> recentClosed;
        ArchivedTotals archived;
        synchronized (LOCK) {
            runtimes = ACTIVE.keySet().toArray(new TerminalSessionRuntime[0]);
            recentClosed = new ArrayList<>(RECENT_CLOSED);
            archived = ARCHIVED_TOTALS.copy();
        }
        List<SessionDiagnosticsSnapshot> sessions = new ArrayList<>(runtimes.length + recentClosed.size());
        for (TerminalSessionRuntime runtime : runtimes) {
            sessions.add(runtime.diagnosticsSnapshot());
        }
        sessions.addAll(recentClosed);

        long demandDeduplicatedCount = 0L;
        long pumpWhileFetchingCount = 0L;
        long demandChangedWhileFetchingCount = 0L;
        long activeRequestCount = 0L;
        long closedCount = 0L;
        for (SessionDiagnosticsSnapshot session : sessions) {
            Map<String, Object> loader = session.historyLoader;
            demandDeduplicatedCount += longOf(loader, "demandDeduplicatedCount");
            pumpWhileFetchingCount += longOf(loader, "pumpWhileFetchingCount");
            demandChangedWhileFetchingCount += longOf(loader, "demandChangedWhileFetchingCount");
            // hasActiveRequest / closed 是当前深度，只统计仍可见的会话，不归档。
            if (Boolean.TRUE.equals(loader.get("hasActiveRequest"))) {
                activeRequestCount++;
            }
            if (Boolean.TRUE.equals(loader.get("closed"))) {
                closedCount++;
            }
        }
        demandDeduplicatedCount += archived.demandDeduplicatedCount;
        pumpWhileFetchingCount += archived.pumpWhileFetchingCount;
        demandChangedWhileFetchingCount += archived.demandChangedWhileFetchingCount;

        Map<String, Long> out = new LinkedHashMap<>();
        out.putAll(lifetimeSessionCounts());
        out.put("sessionCount", out.get("lifetimeSessionCount"));
        out.put("demandDeduplicatedCount", demandDeduplicatedCount);
        out.put("pumpWhileFetchingCount", pumpWhileFetchingCount);
        out.put("demandChangedWhileFetchingCount", demandChangedWhileFetchingCount);
        out.put("activeRequestCount", activeRequestCount);
        out.put("closedCount", closedCount);
        return out;
    }

    /** 跨会话输入投递计数聚合（含 archived）。 */
    public static Map<String, Long> aggregateInputDelivery() {
        TerminalSessionRuntime[] runtimes;
        List<SessionDiagnosticsSnapshot> recentClosed;
        ArchivedTotals archived;
        synchronized (LOCK) {
            runtimes = ACTIVE.keySet().toArray(new TerminalSessionRuntime[0]);
            recentClosed = new ArrayList<>(RECENT_CLOSED);
            archived = ARCHIVED_TOTALS.copy();
        }
        List<SessionDiagnosticsSnapshot> sessions = new ArrayList<>(runtimes.length + recentClosed.size());
        for (TerminalSessionRuntime runtime : runtimes) {
            sessions.add(runtime.diagnosticsSnapshot());
        }
        sessions.addAll(recentClosed);

        long inputAttemptCount = 0L;
        long inputRejectedNotLiveCount = 0L;
        long inputRejectedNoLeaseCount = 0L;
        long inputLocalQueueAcceptedCount = 0L;
        long inputWebSocketEnqueuedCount = 0L;
        long inputQueueFullCount = 0L;
        long inputTransportRejectedCount = 0L;
        long inputChannelNotOpenCount = 0L;
        long inputConnectionStoppedCount = 0L;
        long inputUnknownResultCount = 0L;
        long inputPendingFinalResultCount = 0L;
        long inputAbandonedAtCloseCount = 0L;
        long inputUnitsAttempted = 0L;
        long inputUnitsLocallyAccepted = 0L;
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
            inputChannelNotOpenCount += longOf(input, "inputChannelNotOpenCount");
            inputConnectionStoppedCount += longOf(input, "inputConnectionStoppedCount");
            inputUnknownResultCount += longOf(input, "inputUnknownResultCount");
            // pending 是当前深度，只统计仍可见会话。
            inputPendingFinalResultCount += longOf(input, "inputPendingFinalResultCount");
            inputAbandonedAtCloseCount += longOf(input, "inputAbandonedAtCloseCount");
            inputUnitsAttempted += longOf(input, "inputUnitsAttempted");
            inputUnitsLocallyAccepted += longOf(input, "inputUnitsLocallyAccepted");
        }
        inputAttemptCount += archived.inputAttemptCount;
        inputRejectedNotLiveCount += archived.inputRejectedNotLiveCount;
        inputRejectedNoLeaseCount += archived.inputRejectedNoLeaseCount;
        inputLocalQueueAcceptedCount += archived.inputLocalQueueAcceptedCount;
        inputWebSocketEnqueuedCount += archived.inputWebSocketEnqueuedCount;
        inputQueueFullCount += archived.inputQueueFullCount;
        inputTransportRejectedCount += archived.inputTransportRejectedCount;
        inputChannelNotOpenCount += archived.inputChannelNotOpenCount;
        inputConnectionStoppedCount += archived.inputConnectionStoppedCount;
        inputUnknownResultCount += archived.inputUnknownResultCount;
        inputAbandonedAtCloseCount += archived.inputAbandonedAtCloseCount;
        inputUnitsAttempted += archived.inputUnitsAttempted;
        inputUnitsLocallyAccepted += archived.inputUnitsLocallyAccepted;

        Map<String, Long> out = new LinkedHashMap<>();
        out.put("inputAttemptCount", inputAttemptCount);
        out.put("inputRejectedNotLiveCount", inputRejectedNotLiveCount);
        out.put("inputRejectedNoLeaseCount", inputRejectedNoLeaseCount);
        out.put("inputLocalQueueAcceptedCount", inputLocalQueueAcceptedCount);
        out.put("inputWebSocketEnqueuedCount", inputWebSocketEnqueuedCount);
        out.put("inputQueueFullCount", inputQueueFullCount);
        out.put("inputTransportRejectedCount", inputTransportRejectedCount);
        out.put("inputChannelNotOpenCount", inputChannelNotOpenCount);
        out.put("inputConnectionStoppedCount", inputConnectionStoppedCount);
        out.put("inputUnknownResultCount", inputUnknownResultCount);
        out.put("inputPendingFinalResultCount", inputPendingFinalResultCount);
        out.put("inputAbandonedAtCloseCount", inputAbandonedAtCloseCount);
        out.put("inputUnitsAttempted", inputUnitsAttempted);
        out.put("inputUnitsLocallyAccepted", inputUnitsLocallyAccepted);
        return out;
    }

    public static final class SessionDiagnosticsSnapshot {
        public final String sessionId;
        public final String state;
        public final Map<String, Object> pipeline;
        public final Map<String, Object> historyLoader;
        public final Map<String, Long> inputDelivery;
        public final long closeRequestedAtEpochMs;
        public final long closedAtEpochMs;
        public final String finalState;
        public final long connectionEpoch;
        public final long syncGeneration;
        public final String projectionContinuity;
        public final boolean renderConsumerAttached;
        public final int listenerCount;
        /** 当前深度（活跃）或最终深度（关闭后，通常为 0）。 */
        public final int mailboxMessages;
        public final long mailboxBytes;
        public final int mailboxMessagesAtCloseRequest;
        public final long mailboxBytesAtCloseRequest;
        public final int finalMailboxMessages;
        public final long finalMailboxBytes;

        SessionDiagnosticsSnapshot(String sessionId, String state,
                                   Map<String, Object> pipeline,
                                   Map<String, Object> historyLoader) {
            this(sessionId, state, pipeline, historyLoader, Map.of(),
                0L, 0L, "", 0L, 0L, "", false, 0, 0, 0L, 0, 0L, 0, 0L);
        }

        SessionDiagnosticsSnapshot(String sessionId, String state,
                                   Map<String, Object> pipeline,
                                   Map<String, Object> historyLoader,
                                   Map<String, Long> inputDelivery,
                                   long closeRequestedAtEpochMs,
                                   long closedAtEpochMs, String finalState,
                                   long connectionEpoch, long syncGeneration,
                                   String projectionContinuity,
                                   boolean renderConsumerAttached,
                                   int listenerCount,
                                   int mailboxMessages, long mailboxBytes,
                                   int mailboxMessagesAtCloseRequest,
                                   long mailboxBytesAtCloseRequest,
                                   int finalMailboxMessages, long finalMailboxBytes) {
            this.sessionId = sessionId != null ? sessionId : "";
            this.state = state != null ? state : "";
            this.pipeline = pipeline != null ? pipeline : Map.of();
            this.historyLoader = historyLoader != null ? historyLoader : Map.of();
            this.inputDelivery = inputDelivery != null ? inputDelivery : Map.of();
            this.closeRequestedAtEpochMs = closeRequestedAtEpochMs;
            this.closedAtEpochMs = closedAtEpochMs;
            this.finalState = finalState != null ? finalState : "";
            this.connectionEpoch = connectionEpoch;
            this.syncGeneration = syncGeneration;
            this.projectionContinuity = projectionContinuity != null ? projectionContinuity : "";
            this.renderConsumerAttached = renderConsumerAttached;
            this.listenerCount = listenerCount;
            this.mailboxMessages = mailboxMessages;
            this.mailboxBytes = mailboxBytes;
            this.mailboxMessagesAtCloseRequest = mailboxMessagesAtCloseRequest;
            this.mailboxBytesAtCloseRequest = mailboxBytesAtCloseRequest;
            this.finalMailboxMessages = finalMailboxMessages;
            this.finalMailboxBytes = finalMailboxBytes;
        }
    }

    /** 被 recentClosed 淘汰的会话累计计数；不含当前深度类字段。 */
    static final class ArchivedTotals {
        long archivedSessionCount;
        long receivedFrameCount;
        long receivedBytes;
        long staleConnectionEpochDropped;
        long staleMailboxGenerationDropped;
        long wrongSourceConnectionDropped;
        long invalidFrameSizeRejected;
        long projectionOverflowDiscarded;
        long urgentOverflowDiscarded;
        long reliableOverflowDiscarded;
        long backgroundDropped;
        long unknownEnvelopeCount;
        long renderSuccessCount;
        long renderFailureCount;
        long stateOnlyHandledCount;
        long inputAttemptCount;
        long inputRejectedNotLiveCount;
        long inputRejectedNoLeaseCount;
        long inputLocalQueueAcceptedCount;
        long inputWebSocketEnqueuedCount;
        long inputQueueFullCount;
        long inputTransportRejectedCount;
        long inputChannelNotOpenCount;
        long inputConnectionStoppedCount;
        long inputUnknownResultCount;
        long inputAbandonedAtCloseCount;
        long inputUnitsAttempted;
        long inputUnitsLocallyAccepted;
        long demandDeduplicatedCount;
        long pumpWhileFetchingCount;
        long demandChangedWhileFetchingCount;

        void merge(SessionDiagnosticsSnapshot snapshot) {
            if (snapshot == null) return;
            archivedSessionCount++;
            Map<String, Object> pipeline = snapshot.pipeline;
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

            Map<String, Long> input = snapshot.inputDelivery;
            inputAttemptCount += longOf(input, "inputAttemptCount");
            inputRejectedNotLiveCount += longOf(input, "inputRejectedNotLiveCount");
            inputRejectedNoLeaseCount += longOf(input, "inputRejectedNoLeaseCount");
            inputLocalQueueAcceptedCount += longOf(input, "inputLocalQueueAcceptedCount");
            inputWebSocketEnqueuedCount += longOf(input, "inputWebSocketEnqueuedCount");
            inputQueueFullCount += longOf(input, "inputQueueFullCount");
            inputTransportRejectedCount += longOf(input, "inputTransportRejectedCount");
            inputChannelNotOpenCount += longOf(input, "inputChannelNotOpenCount");
            inputConnectionStoppedCount += longOf(input, "inputConnectionStoppedCount");
            inputUnknownResultCount += longOf(input, "inputUnknownResultCount");
            inputAbandonedAtCloseCount += longOf(input, "inputAbandonedAtCloseCount");
            inputUnitsAttempted += longOf(input, "inputUnitsAttempted");
            inputUnitsLocallyAccepted += longOf(input, "inputUnitsLocallyAccepted");

            Map<String, Object> loader = snapshot.historyLoader;
            demandDeduplicatedCount += longOf(loader, "demandDeduplicatedCount");
            pumpWhileFetchingCount += longOf(loader, "pumpWhileFetchingCount");
            demandChangedWhileFetchingCount += longOf(loader, "demandChangedWhileFetchingCount");
        }

        void clear() {
            archivedSessionCount = 0L;
            receivedFrameCount = 0L;
            receivedBytes = 0L;
            staleConnectionEpochDropped = 0L;
            staleMailboxGenerationDropped = 0L;
            wrongSourceConnectionDropped = 0L;
            invalidFrameSizeRejected = 0L;
            projectionOverflowDiscarded = 0L;
            urgentOverflowDiscarded = 0L;
            reliableOverflowDiscarded = 0L;
            backgroundDropped = 0L;
            unknownEnvelopeCount = 0L;
            renderSuccessCount = 0L;
            renderFailureCount = 0L;
            stateOnlyHandledCount = 0L;
            inputAttemptCount = 0L;
            inputRejectedNotLiveCount = 0L;
            inputRejectedNoLeaseCount = 0L;
            inputLocalQueueAcceptedCount = 0L;
            inputWebSocketEnqueuedCount = 0L;
            inputQueueFullCount = 0L;
            inputTransportRejectedCount = 0L;
            inputChannelNotOpenCount = 0L;
            inputConnectionStoppedCount = 0L;
            inputUnknownResultCount = 0L;
            inputAbandonedAtCloseCount = 0L;
            inputUnitsAttempted = 0L;
            inputUnitsLocallyAccepted = 0L;
            demandDeduplicatedCount = 0L;
            pumpWhileFetchingCount = 0L;
            demandChangedWhileFetchingCount = 0L;
        }

        ArchivedTotals copy() {
            ArchivedTotals out = new ArchivedTotals();
            out.archivedSessionCount = archivedSessionCount;
            out.receivedFrameCount = receivedFrameCount;
            out.receivedBytes = receivedBytes;
            out.staleConnectionEpochDropped = staleConnectionEpochDropped;
            out.staleMailboxGenerationDropped = staleMailboxGenerationDropped;
            out.wrongSourceConnectionDropped = wrongSourceConnectionDropped;
            out.invalidFrameSizeRejected = invalidFrameSizeRejected;
            out.projectionOverflowDiscarded = projectionOverflowDiscarded;
            out.urgentOverflowDiscarded = urgentOverflowDiscarded;
            out.reliableOverflowDiscarded = reliableOverflowDiscarded;
            out.backgroundDropped = backgroundDropped;
            out.unknownEnvelopeCount = unknownEnvelopeCount;
            out.renderSuccessCount = renderSuccessCount;
            out.renderFailureCount = renderFailureCount;
            out.stateOnlyHandledCount = stateOnlyHandledCount;
            out.inputAttemptCount = inputAttemptCount;
            out.inputRejectedNotLiveCount = inputRejectedNotLiveCount;
            out.inputRejectedNoLeaseCount = inputRejectedNoLeaseCount;
            out.inputLocalQueueAcceptedCount = inputLocalQueueAcceptedCount;
            out.inputWebSocketEnqueuedCount = inputWebSocketEnqueuedCount;
            out.inputQueueFullCount = inputQueueFullCount;
            out.inputTransportRejectedCount = inputTransportRejectedCount;
            out.inputChannelNotOpenCount = inputChannelNotOpenCount;
            out.inputConnectionStoppedCount = inputConnectionStoppedCount;
            out.inputUnknownResultCount = inputUnknownResultCount;
            out.inputAbandonedAtCloseCount = inputAbandonedAtCloseCount;
            out.inputUnitsAttempted = inputUnitsAttempted;
            out.inputUnitsLocallyAccepted = inputUnitsLocallyAccepted;
            out.demandDeduplicatedCount = demandDeduplicatedCount;
            out.pumpWhileFetchingCount = pumpWhileFetchingCount;
            out.demandChangedWhileFetchingCount = demandChangedWhileFetchingCount;
            return out;
        }
    }

    /** 调用方必须已持有 {@link #LOCK}。 */
    private static void retainClosedLocked(SessionDiagnosticsSnapshot snapshot) {
        if (snapshot == null) return;
        while (RECENT_CLOSED.size() >= MAX_RECENT_CLOSED) {
            SessionDiagnosticsSnapshot oldest = RECENT_CLOSED.removeFirst();
            ARCHIVED_TOTALS.merge(oldest);
        }
        RECENT_CLOSED.addLast(snapshot);
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
