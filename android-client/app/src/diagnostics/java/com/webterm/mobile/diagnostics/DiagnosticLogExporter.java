package com.webterm.mobile.diagnostics;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.webterm.core.contract.diagnostics.DiagnosticIdHasher;
import com.webterm.core.session.DeviceConnection;
import com.webterm.core.session.DeviceConnectionDiagnosticsRegistry;
import com.webterm.core.session.MuxOutboundQueue;
import com.webterm.core.session.traffic.NetworkTrafficStats;
import com.webterm.feature.terminal.domain.TerminalPipelineDiagnosticsRegistry;
import com.webterm.feature.terminal.domain.HistoryHttpMetrics;
import com.webterm.feature.terminal.domain.HistoryDemandMetrics;
import com.webterm.feature.terminal.domain.TerminalResumeMetrics;
import com.webterm.mobile.BuildConfig;
import com.webterm.terminal.model.HistoryPromotionMetrics;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.transport.api.MuxTransport;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Debug/Diag 专用：导出当前进程内存 Ring 与指标快照，不包含终端正文或 PTY 捕获。
 * 导出包脱敏：server/deviceId/channelId/sessionId 一律以进程级 {@link DiagnosticIdHasher#processHash}
 * 形式输出，保证同 ZIP 内 events / metrics / state 可关联。
 * 写入先落 .tmp、完成后 rename；分享返回后删除临时导出文件。
 */
public final class DiagnosticLogExporter {
    private static final String ARCHIVE_PREFIX = "webterm-diagnostics-";
    static final int SCHEMA_VERSION = 4;

    private static volatile File pendingShareArchive = null;

    private DiagnosticLogExporter() {}

    public static boolean isAvailable() {
        return true;
    }

    /** 分享流程结束后（用户从分享目标返回）清理临时导出文件。 */
    public static void cleanupAfterShareIfNeeded() {
        File archive = pendingShareArchive;
        if (archive != null) {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
            pendingShareArchive = null;
        }
    }

    public static void share(Activity activity) {
        Activity target = activity;
        new Thread(() -> {
            try {
                File archive = createArchive(target);
                target.runOnUiThread(() -> shareArchive(target, archive));
            } catch (IOException e) {
                target.runOnUiThread(() -> Toast.makeText(target, "诊断导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, "webterm-diagnostic-export").start();
    }

    private static File createArchive(Context context) throws IOException {
        clearExportDirectory(context);

        File exportDir = new File(context.getCacheDir(), "diagnostics-export");
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IOException("cannot create export directory");
        }
        File archive = new File(exportDir, newArchiveName());
        File temp = new File(exportDir, archive.getName() + ".tmp");
        try {
            try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(temp))) {
                writeEventsJsonl(output, DiagnosticMemoryRing.getInstance().snapshot());
                try {
                    writeJsonEntry(output, "manifest.json", buildManifestJson(context));
                    writeJsonEntry(output, "android-metrics.json", buildMetricsJson());
                    writeJsonEntry(output, "android-state.json", buildStateJson());
                } catch (JSONException e) {
                    throw new IOException("failed to build diagnostics json", e);
                }
            }
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw e;
        }
        if (!temp.renameTo(archive)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("cannot finalize diagnostics archive");
        }
        return archive;
    }

    private static void clearExportDirectory(Context context) {
        File exportDir = new File(context.getCacheDir(), "diagnostics-export");
        File[] files = exportDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static void writeEventsJsonl(ZipOutputStream output, java.util.List<DiagnosticEntry> entries)
        throws IOException {
        output.putNextEntry(new ZipEntry("events.jsonl"));
        for (DiagnosticEntry entry : entries) {
            try {
                String line = entry.toJson().toString() + "\n";
                output.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (JSONException e) {
                throw new IOException("encode diagnostic entry", e);
            }
        }
        output.closeEntry();
    }

    static String newArchiveName() {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        String suffix = DiagnosticIdHasher.randomSalt().substring(0, 8);
        return ARCHIVE_PREFIX + timestamp + "-" + suffix + ".zip";
    }

    private static void writeJsonEntry(ZipOutputStream output, String name, JSONObject json)
        throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(json.toString().getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static JSONObject buildManifestJson(Context context) throws JSONException {
        DiagnosticMemoryRing ring = DiagnosticMemoryRing.getInstance();
        JSONObject json = new JSONObject();
        json.put("schemaVersion", SCHEMA_VERSION);
        json.put("exportedAt", isoNow());
        json.put("runId", ring.runId());
        json.put("appVersion", appVersionName(context));
        json.put("gitCommit", BuildConfig.GIT_COMMIT);
        json.put("gitDirty", BuildConfig.GIT_DIRTY);
        json.put("sourceTreeHash", BuildConfig.SOURCE_TREE_HASH);
        json.put("buildTime", BuildConfig.BUILD_TIME_UTC);
        json.put("buildVariant", BuildConfig.BUILD_VARIANT_ID);
        json.put("protocolSchemaHash", BuildConfig.PROTOCOL_SCHEMA_HASH);
        json.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        json.put("sdkInt", Build.VERSION.SDK_INT);
        return json;
    }

    static JSONObject buildMetricsJson() throws JSONException {
        JSONObject json = new JSONObject();

        NetworkTrafficStats.Snapshot network = NetworkTrafficStats.snapshot();
        JSONObject uid = new JSONObject();
        uid.put("rxBytes", network.uid.rxBytes);
        uid.put("txBytes", network.uid.txBytes);
        uid.put("supported", network.uid.supported);
        json.put("uid", uid);
        JSONObject websocket = new JSONObject();
        websocket.put("rxFrames", network.websocket.rxFrames);
        websocket.put("rxBytes", network.websocket.rxBytes);
        websocket.put("txFrames", network.websocket.txFrames);
        websocket.put("txBytes", network.websocket.txBytes);
        json.put("websocket", websocket);
        JSONArray byDevice = new JSONArray();
        for (Map.Entry<String, MuxTransport.TrafficSnapshot> e : network.websocketByDevice.entrySet()) {
            MuxTransport.TrafficSnapshot s = e.getValue();
            JSONObject device = new JSONObject();
            device.put("serverHash", DiagnosticIdHasher.processHash(NetworkTrafficStats.serverOfKey(e.getKey())));
            device.put("deviceHash", DiagnosticIdHasher.processHash(NetworkTrafficStats.deviceOfKey(e.getKey())));
            device.put("rxFrames", s.rxFrames);
            device.put("rxBytes", s.rxBytes);
            device.put("txFrames", s.txFrames);
            device.put("txBytes", s.txBytes);
            byDevice.put(device);
        }
        json.put("byDevice", byDevice);
        json.put("historyHttp", mapToJson(HistoryHttpMetrics.processSnapshot()));
        json.put("historyDemand", longMapJson(HistoryDemandMetrics.snapshot()));
        json.put("historyPromotion", mapToJson(HistoryPromotionMetrics.snapshot()));

        TerminalRenderMetrics.Snapshot screen = TerminalRenderMetrics.snapshot();
        JSONObject render = new JSONObject();
        render.put("modelChangeCount", screen.modelChangeCount);
        render.put("uiCallbackScheduleCount", screen.uiCallbackScheduleCount);
        render.put("uiCallbackCoalescedCount", screen.uiCallbackCoalescedCount);
        render.put("renderRequestCount", screen.renderRequestCount);
        render.put("vsyncRenderCount", screen.vsyncRenderCount);
        render.put("fullInvalidateCount", screen.fullInvalidateCount);
        JSONObject fullInvalidateByReason = new JSONObject();
        TerminalRenderMetrics.FullInvalidateReason[] invalidateReasons =
            TerminalRenderMetrics.FullInvalidateReason.values();
        for (int i = 0; i < invalidateReasons.length; i++) {
            fullInvalidateByReason.put(
                invalidateReasons[i].name(), screen.fullInvalidateByReason[i]);
        }
        render.put("fullInvalidateByReason", fullInvalidateByReason);
        render.put("partialInvalidateCount", screen.partialInvalidateCount);
        render.put("dirtyRowCount", screen.dirtyRowCount);
        render.put("screenRegionInvalidateCount", screen.screenRegionInvalidateCount);
        render.put("partialRowInvalidateCount", screen.partialRowInvalidateCount);
        render.put("screenScrollEventCount", screen.screenScrollEventCount);
        render.put("screenScrollRowTotal", screen.screenScrollRowTotal);
        render.put("renderDurationCount", screen.renderDurationCount);
        render.put("onDrawCount", screen.renderDurationCount);
        render.put("renderDurationNanos", screen.renderDurationNanos);
        render.put("onDrawTotalNanos", screen.renderDurationNanos);
        render.put("renderDurationMaxNanos", screen.renderDurationMaxNanos);
        render.put("renderDurationLatencyBuckets",
            latencyBucketsJson(screen.renderDurationLatencyBuckets));
        render.put("viewportCalculationNanos", screen.viewportCalculationNanos);
        render.put("historyRowLookupNanos", screen.historyRowLookupNanos);
        render.put("screenRowLookupNanos", screen.screenRowLookupNanos);
        render.put("renderNodeDrawOrRecordNanos", screen.renderNodeDrawOrRecordNanos);
        render.put("canvasDrawNanos", screen.canvasDrawNanos);
        render.put("protobufParseNanos", screen.protobufParseNanos);
        render.put("protobufParseCount", screen.protobufParseCount);
        render.put("modelApplyNanos", screen.modelApplyNanos);
        render.put("mainThreadCallbackDelayNanos", screen.mainThreadCallbackDelayNanos);
        render.put("baselineFrameCount", screen.baselineFrameCount);
        render.put("baselineFrameBytes", screen.baselineFrameBytes);
        render.put("commitFrameCount", screen.commitFrameCount);
        render.put("commitFrameBytes", screen.commitFrameBytes);
        // schema v4: HTTP Range 独立计量；旧字段保留一个版本用于旧分析器兼容。
        render.put("wsHistoryRangeFrameCount", screen.historyRangeFrameCount);
        render.put("wsHistoryRangeFrameBytes", screen.historyRangeFrameBytes);
        render.put("historyRangeFrameCount", screen.historyRangeFrameCount);
        render.put("historyRangeFrameBytes", screen.historyRangeFrameBytes);
        render.put("otherFrameCount", screen.otherFrameCount);
        render.put("otherFrameBytes", screen.otherFrameBytes);
        render.put("mailboxResidenceNanos", screen.mailboxResidenceNanos);
        render.put("mailboxResidenceMaxNanos", screen.mailboxResidenceMaxNanos);
        render.put("terminalCommitApplyLatencyBuckets",
            latencyBucketsJson(screen.terminalCommitApplyLatencyBuckets));
        render.put("protobufParseLatencyBuckets",
            latencyBucketsJson(screen.protobufParseLatencyBuckets));
        render.put("mapperLatencyBuckets",
            latencyBucketsJson(screen.mapperLatencyBuckets));
        render.put("dictionaryStagingLatencyBuckets",
            latencyBucketsJson(screen.dictionaryStagingLatencyBuckets));
        render.put("renderPublicationLatencyBuckets",
            latencyBucketsJson(screen.renderPublicationLatencyBuckets));
        render.put("renderNodeRecordLatencyBuckets",
            latencyBucketsJson(screen.renderNodeRecordLatencyBuckets));
        render.put("vsyncDrawLatencyBuckets",
            latencyBucketsJson(screen.vsyncDrawLatencyBuckets));
        render.put("mailboxResidenceLatencyBuckets",
            latencyBucketsJson(screen.mailboxResidenceLatencyBuckets));
        render.put("historyCacheHitCount", screen.historyCacheHitCount);
        render.put("historyCacheMissCount", screen.historyCacheMissCount);
        render.put("rowCacheHitCount", screen.rowCacheHitCount);
        render.put("rowCacheMissCount", screen.rowCacheMissCount);
        render.put("rowCacheStaleFallbackCount", screen.rowCacheStaleFallbackCount);
        render.put("rowCachePinnedConflictCount", screen.rowCachePinnedConflictCount);
        render.put("rowNodeRecordCount", screen.rowNodeRecordCount);
        render.put("rowNodeReuseCount", screen.rowNodeReuseCount);
        render.put("historyOnlyNoDrawCount", screen.historyOnlyNoDrawCount);
        render.put("viewportRedrawRequestCount", screen.viewportRedrawRequestCount);
        render.put("viewportFullRedrawCount", screen.viewportFullRedrawCount);
        render.put("visibleHistoryRowsDrawn", screen.visibleHistoryRowsDrawn);
        render.put("renderNodeVictimScanCount", screen.renderNodeVictimScanCount);
        render.put("renderNodeVictimScannedEntries", screen.renderNodeVictimScannedEntries);
        render.put("renderNodeAllPinnedFallbackCount", screen.renderNodeAllPinnedFallbackCount);
        json.put("render", render);

        TerminalResumeMetrics.Snapshot resume = TerminalResumeMetrics.snapshot();
        JSONObject resumeJson = new JSONObject();
        resumeJson.put("pageReattachCount", resume.pageReattachCount);
        resumeJson.put("exactResumeCount", resume.exactResumeCount);
        resumeJson.put("cumulativePatchCount", resume.cumulativePatchCount);
        resumeJson.put("snapshotCount", resume.snapshotCount);
        resumeJson.put("resyncCount", resume.resyncCount);
        resumeJson.put("syncTimeoutCount", resume.syncTimeoutCount);
        resumeJson.put("hotToWarmCount", resume.hotToWarmCount);
        resumeJson.put("warmToColdCount", resume.warmToColdCount);
        resumeJson.put("leaseAcquireCount", resume.leaseAcquireCount);
        resumeJson.put("leaseDeniedCount", resume.leaseDeniedCount);
        resumeJson.put("leaseRetryCount", resume.leaseRetryCount);
        resumeJson.put("leaseRenewCount", resume.leaseRenewCount);
        resumeJson.put("leaseRevokedCount", resume.leaseRevokedCount);
        resumeJson.put("leaseStaleResponseCount", resume.leaseStaleResponseCount);
        resumeJson.put("mailboxOverflowCount", resume.mailboxOverflowCount);
        resumeJson.put("mailboxRecoveredCount", resume.mailboxRecoveredCount);
        resumeJson.put("mailboxMaxPendingBytes", resume.mailboxMaxPendingBytes);
        json.put("resume", resumeJson);

        json.put("outboundQueue",
            outboundQueueAggregateJson(DeviceConnectionDiagnosticsRegistry.aggregateOutboundQueue()));
        json.put("inboundDrops",
            longMapJson(DeviceConnectionDiagnosticsRegistry.aggregateInboundDrops()));
        json.put("connectionRecovery",
            longMapJson(DeviceConnectionDiagnosticsRegistry.aggregateConnectionRecovery()));
        json.put("diagnosticsContextSend",
            longMapJson(DeviceConnectionDiagnosticsRegistry.aggregateDiagnosticsContextSend()));
        json.put("sessionLifetime",
            longMapJson(TerminalPipelineDiagnosticsRegistry.lifetimeSessionCounts()));
        json.put("connectionLifetime",
            longMapJson(DeviceConnectionDiagnosticsRegistry.lifetimeConnectionCounts()));
        Map<String, Long> screenPipeline =
            TerminalPipelineDiagnosticsRegistry.aggregateScreenPipeline();
        json.put("screenPipelineAggregate", longMapJson(screenPipeline));
        json.put("historyLoaderAggregate",
            longMapJson(TerminalPipelineDiagnosticsRegistry.aggregateHistoryLoader()));
        // inputDelivery 不含 focus；关闭快照满足
        // localAccepted = enqueued + channelNotOpen + transportRejected + connectionStopped + abandonedAtClose。
        json.put("inputDelivery",
            longMapJson(TerminalPipelineDiagnosticsRegistry.aggregateInputDelivery()));
        return json;
    }

    private static JSONArray latencyBucketsJson(long[] buckets) {
        JSONArray json = new JSONArray();
        for (long bucket : buckets) json.put(bucket);
        return json;
    }

    private static JSONObject longMapJson(Map<String, Long> map) throws JSONException {
        JSONObject json = new JSONObject();
        if (map == null) return json;
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            json.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : 0L);
        }
        return json;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject outboundQueueAggregateJson(Map<String, Object> aggregate)
            throws JSONException {
        JSONObject json = new JSONObject();
        if (aggregate == null) return json;
        for (Map.Entry<String, Object> entry : aggregate.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("residenceLatencyBuckets".equals(key) && value instanceof long[]) {
                json.put(key, latencyBucketsJson((long[]) value));
            } else if ("byFrameKind".equals(key) && value instanceof Map) {
                JSONObject byFrameKind = new JSONObject();
                Map<String, Object> kinds = (Map<String, Object>) value;
                for (Map.Entry<String, Object> kindEntry : kinds.entrySet()) {
                    Object kindValue = kindEntry.getValue();
                    if (kindValue instanceof Map) {
                        byFrameKind.put(kindEntry.getKey(),
                            longMapJson((Map<String, Long>) kindValue));
                    }
                }
                json.put(key, byFrameKind);
            } else if (value instanceof Number) {
                json.put(key, ((Number) value).longValue());
            } else if (value != null) {
                json.put(key, value);
            }
        }
        return json;
    }

    static JSONObject buildStateJson() throws JSONException {
        DiagnosticMemoryRing ring = DiagnosticMemoryRing.getInstance();
        DiagnosticMemoryRing.RingStats stats = ring.ringStats();
        JSONObject json = new JSONObject();
        json.put("generatedAt", isoNow());
        json.put("runId", ring.runId());

        JSONObject eventRing = new JSONObject();
        eventRing.put("entryCount", stats.entryCount);
        eventRing.put("totalBytes", stats.totalBytes);
        eventRing.put("droppedEntryCount", stats.droppedEntryCount);
        eventRing.put("oldestSeq", stats.oldestSeq);
        eventRing.put("newestSeq", stats.newestSeq);
        eventRing.put("oldestAt", stats.oldestAt);
        eventRing.put("newestAt", stats.newestAt);
        json.put("eventRing", eventRing);

        JSONObject connections = new JSONObject();
        JSONArray activeConnections = new JSONArray();
        for (DeviceConnection.DiagnosticsSnapshot connection :
            DeviceConnectionDiagnosticsRegistry.snapshotActive()) {
            activeConnections.put(connectionStateJson(connection));
        }
        JSONArray recentClosedConnections = new JSONArray();
        for (DeviceConnection.DiagnosticsSnapshot connection :
            DeviceConnectionDiagnosticsRegistry.snapshotRecentClosed()) {
            recentClosedConnections.put(connectionStateJson(connection));
        }
        connections.put("active", activeConnections);
        connections.put("recentClosed", recentClosedConnections);
        json.put("connections", connections);

        JSONObject terminalSessions = new JSONObject();
        JSONArray activeSessions = new JSONArray();
        JSONArray recentClosedSessions = new JSONArray();
        JSONObject historyLoaders = new JSONObject();
        JSONArray activeLoaders = new JSONArray();
        JSONArray recentClosedLoaders = new JSONArray();

        for (TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot session :
            TerminalPipelineDiagnosticsRegistry.snapshotActive()) {
            activeSessions.put(terminalSessionStateJson(session, false));
            activeLoaders.put(historyLoaderStateJson(session, false));
        }
        for (TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot session :
            TerminalPipelineDiagnosticsRegistry.snapshotRecentClosed()) {
            recentClosedSessions.put(terminalSessionStateJson(session, true));
            recentClosedLoaders.put(historyLoaderStateJson(session, true));
        }
        terminalSessions.put("active", activeSessions);
        terminalSessions.put("recentClosed", recentClosedSessions);
        historyLoaders.put("active", activeLoaders);
        historyLoaders.put("recentClosed", recentClosedLoaders);
        json.put("terminalSessions", terminalSessions);
        json.put("historyLoaders", historyLoaders);
        return json;
    }

    private static JSONObject terminalSessionStateJson(
        TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot session,
        boolean closed) throws JSONException {
        String sessionHash = DiagnosticIdHasher.processHash(session.sessionId);
        JSONObject terminal = new JSONObject();
        terminal.put("sessionHash", sessionHash);
        terminal.put("state", session.state);
        terminal.put("pipeline", mapToJson(session.pipeline));
        if (session.inputDelivery != null && !session.inputDelivery.isEmpty()) {
            terminal.put("inputDelivery", longMapJson(session.inputDelivery));
        }
        terminal.put("connectionEpoch", session.connectionEpoch);
        terminal.put("syncGeneration", session.syncGeneration);
        terminal.put("projectionContinuity", session.projectionContinuity);
        terminal.put("renderConsumerAttached", session.renderConsumerAttached);
        terminal.put("listenerCount", session.listenerCount);
        terminal.put("mailboxMessages", session.mailboxMessages);
        terminal.put("mailboxBytes", session.mailboxBytes);
        if (closed) {
            if (session.closeRequestedAtEpochMs > 0L) {
                terminal.put("closeRequestedAt", isoFromEpochMs(session.closeRequestedAtEpochMs));
            }
            if (session.closedAtEpochMs > 0L) {
                terminal.put("closedAt", isoFromEpochMs(session.closedAtEpochMs));
            }
            if (session.finalState != null && !session.finalState.isEmpty()) {
                terminal.put("finalState", session.finalState);
            }
            terminal.put("mailboxMessagesAtCloseRequest", session.mailboxMessagesAtCloseRequest);
            terminal.put("mailboxBytesAtCloseRequest", session.mailboxBytesAtCloseRequest);
            terminal.put("finalMailboxMessages", session.finalMailboxMessages);
            terminal.put("finalMailboxBytes", session.finalMailboxBytes);
        }
        return terminal;
    }

    private static JSONObject historyLoaderStateJson(
        TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot session,
        boolean closed) throws JSONException {
        JSONObject loader = new JSONObject();
        loader.put("sessionHash", DiagnosticIdHasher.processHash(session.sessionId));
        for (Map.Entry<String, Object> entry : session.historyLoader.entrySet()) {
            loader.put(entry.getKey(), entry.getValue());
        }
        if (closed && session.closedAtEpochMs > 0L) {
            loader.put("closedAt", isoFromEpochMs(session.closedAtEpochMs));
        }
        return loader;
    }

    private static JSONObject connectionStateJson(DeviceConnection.DiagnosticsSnapshot connection)
        throws JSONException {
        JSONObject json = new JSONObject();
        json.put("serverHash", DiagnosticIdHasher.processHash(connection.baseUrl));
        json.put("deviceHash", DiagnosticIdHasher.processHash(connection.deviceId));
        json.put("connectionHash", DiagnosticIdHasher.processHash(connection.connectionId));
        if (connection.recoveryId != null && !connection.recoveryId.isEmpty()) {
            json.put("recoveryHash", DiagnosticIdHasher.processHash(connection.recoveryId));
        }
        json.put("physicalDesired", connection.physicalDesired);
        json.put("physicalConnected", connection.physicalConnected);
        json.put("physicalConnecting", connection.physicalConnecting);
        json.put("stopped", connection.stopped);
        json.put("transportGeneration", connection.transportGeneration);
        json.put("activeChannelCount", connection.activeChannelCount);
        if (connection.lastCloseReason != null) {
            json.put("lastCloseReason", connection.lastCloseReason.name());
        }
        json.put("connectElapsedMs", connection.connectElapsedMsNow());
        json.put("connectedElapsedMs", connection.connectedElapsedMsNow());
        json.put("recoveryStartedAtNanos", connection.recoveryStartedAtNanos);
        json.put("recoveryAttemptCount", connection.recoveryAttemptCount);
        json.put("agentRecoveryContextClearPending", connection.agentRecoveryContextClearPending);
        if (connection.recoveryInitialFailureKind != null
                && !connection.recoveryInitialFailureKind.isEmpty()) {
            json.put("recoveryInitialFailureKind", connection.recoveryInitialFailureKind);
        }
        if (connection.closedAtEpochMs > 0L) {
            json.put("closedAt", isoFromEpochMs(connection.closedAtEpochMs));
        }
        if (connection.closeReason != null && !connection.closeReason.isEmpty()) {
            json.put("closeReason", connection.closeReason);
        }
        MuxOutboundQueue.Snapshot q = connection.outboundQueue;
        if (q != null) {
            JSONObject outbound = new JSONObject();
            outbound.put("currentFrames", q.currentFrames);
            outbound.put("currentBytes", q.currentBytes);
            outbound.put("highWaterFrames", q.highWaterFrames);
            outbound.put("highWaterBytes", q.highWaterBytes);
            outbound.put("acceptedCount", q.acceptedCount);
            outbound.put("webSocketEnqueuedCount", q.webSocketEnqueuedCount);
            outbound.put("queueFullCount", q.queueFullCount);
            outbound.put("channelNotOpenCount", q.channelNotOpenCount);
            outbound.put("transportRejectedCount", q.transportRejectedCount);
            outbound.put("connectionStoppedCount", q.connectionStoppedCount);
            json.put("outboundQueue", outbound);
        }
        DeviceConnection.InboundDropSnapshot drops = connection.inboundDrops;
        if (drops != null) {
            JSONObject inbound = new JSONObject();
            inbound.put("staleTransportGenerationDropped", drops.staleTransportGenerationDropped);
            inbound.put("tunnelDecodeFailed", drops.tunnelDecodeFailed);
            inbound.put("unknownChannelDropped", drops.unknownChannelDropped);
            inbound.put("channelNotOpenDropped", drops.channelNotOpenDropped);
            inbound.put("normalCloseTailDropped", drops.normalCloseTailDropped);
            inbound.put("staleChannelLifecycleDropped", drops.staleChannelLifecycleDropped);
            inbound.put("channelIdReusedDropped", drops.channelIdReusedDropped);
            inbound.put("wrongConnectionMappingDropped", drops.wrongConnectionMappingDropped);
            json.put("inboundDrops", inbound);
        }
        return json;
    }

    private static String isoFromEpochMs(long epochMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMs));
    }

    private static JSONObject mapToJson(Map<String, Object> map) throws JSONException {
        JSONObject json = new JSONObject();
        if (map == null) return json;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                json.put(entry.getKey(), nestedMapToJson((Map<?, ?>) value));
            } else {
                json.put(entry.getKey(), value);
            }
        }
        return json;
    }

    private static JSONObject nestedMapToJson(Map<?, ?> map) throws JSONException {
        JSONObject json = new JSONObject();
        if (map == null) return json;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                json.put(String.valueOf(entry.getKey()), nestedMapToJson((Map<?, ?>) value));
            } else if (value instanceof Number) {
                json.put(String.valueOf(entry.getKey()), ((Number) value).longValue());
            } else {
                json.put(String.valueOf(entry.getKey()), value);
            }
        }
        return json;
    }

    private static String appVersionName(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = pm.getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                info = pm.getPackageInfo(context.getPackageName(), 0);
            }
            return info.versionName != null ? info.versionName : "unknown";
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private static void shareArchive(Activity activity, File archive) {
        if (activity.isFinishing()) {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
            return;
        }
        pendingShareArchive = archive;
        Uri uri = FileProvider.getUriForFile(activity,
            activity.getPackageName() + ".diagnostics", archive);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(Intent.EXTRA_SUBJECT, "WebTerm 诊断日志");
        send.putExtra(Intent.EXTRA_TEXT, "导出包已脱敏：服务器地址与设备/通道标识均以哈希形式呈现。");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("WebTerm 诊断日志", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(send, "导出诊断日志"));
    }
}
