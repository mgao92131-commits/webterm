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
import com.webterm.mobile.BuildConfig;
import com.webterm.core.session.traffic.NetworkTrafficStats;
import com.webterm.feature.terminal.domain.TerminalResumeMetrics;
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
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Debug/Diag 专用：导出当前进程内存 Ring 与指标快照，不包含终端正文或 PTY 捕获。
 * 导出包脱敏：server/deviceId/channelId 一律以每次导出随机 salt 的哈希形式输出。
 * 写入先落 .tmp、完成后 rename；分享返回后删除临时导出文件。
 */
public final class DiagnosticLogExporter {
    private static final String ARCHIVE_PREFIX = "webterm-diagnostics-";

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
        String salt = DiagnosticIdHasher.randomSalt();
        try {
            try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(temp))) {
                writeEventsJsonl(output, DiagnosticMemoryRing.getInstance().snapshot());
                output.putNextEntry(new ZipEntry("network-traffic-summary.txt"));
                output.write(buildTrafficSummary(salt).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
                try {
                    writeJsonEntry(output, "manifest.json", buildManifestJson(context));
                    writeJsonEntry(output, "android-metrics.json", buildMetricsJson(salt));
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
        json.put("schemaVersion", 1);
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

    static JSONObject buildMetricsJson(String salt) throws JSONException {
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
            device.put("serverHash", DiagnosticIdHasher.hash(salt, NetworkTrafficStats.serverOfKey(e.getKey())));
            device.put("deviceHash", DiagnosticIdHasher.hash(salt, NetworkTrafficStats.deviceOfKey(e.getKey())));
            device.put("rxFrames", s.rxFrames);
            device.put("rxBytes", s.rxBytes);
            device.put("txFrames", s.txFrames);
            device.put("txBytes", s.txBytes);
            byDevice.put(device);
        }
        json.put("byDevice", byDevice);

        TerminalRenderMetrics.Snapshot screen = TerminalRenderMetrics.snapshot();
        JSONObject render = new JSONObject();
        render.put("modelChangeCount", screen.modelChangeCount);
        render.put("uiCallbackScheduleCount", screen.uiCallbackScheduleCount);
        render.put("uiCallbackCoalescedCount", screen.uiCallbackCoalescedCount);
        render.put("renderRequestCount", screen.renderRequestCount);
        render.put("vsyncRenderCount", screen.vsyncRenderCount);
        render.put("fullInvalidateCount", screen.fullInvalidateCount);
        render.put("partialInvalidateCount", screen.partialInvalidateCount);
        render.put("dirtyRowCount", screen.dirtyRowCount);
        render.put("renderDurationNanos", screen.renderDurationNanos);
        render.put("renderDurationMaxNanos", screen.renderDurationMaxNanos);
        render.put("protobufParseNanos", screen.protobufParseNanos);
        render.put("protobufParseCount", screen.protobufParseCount);
        render.put("modelApplyNanos", screen.modelApplyNanos);
        render.put("mainThreadCallbackDelayNanos", screen.mainThreadCallbackDelayNanos);
        render.put("baselineFrameCount", screen.baselineFrameCount);
        render.put("baselineFrameBytes", screen.baselineFrameBytes);
        render.put("commitFrameCount", screen.commitFrameCount);
        render.put("commitFrameBytes", screen.commitFrameBytes);
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
        render.put("visibleHistoryRowsDrawn", screen.visibleHistoryRowsDrawn);
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
        return json;
    }

    private static JSONArray latencyBucketsJson(long[] buckets) {
        JSONArray json = new JSONArray();
        for (long bucket : buckets) json.put(bucket);
        return json;
    }

    private static JSONObject buildStateJson() throws JSONException {
        DiagnosticMemoryRing ring = DiagnosticMemoryRing.getInstance();
        JSONObject json = new JSONObject();
        json.put("generatedAt", isoNow());
        json.put("runId", ring.runId());
        json.put("memoryEntryCount", ring.entryCount());
        json.put("memoryTotalBytes", ring.totalBytes());
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

    static String buildTrafficSummary(String salt) {
        NetworkTrafficStats.Snapshot network = NetworkTrafficStats.snapshot();
        TerminalRenderMetrics.Snapshot screen = TerminalRenderMetrics.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("WebTerm Network Traffic Summary (Android only)\n");
        sb.append("===============================================\n");
        sb.append("NOTE: This file contains Android-side statistics only.\n");
        sb.append("For Go Agent statistics, run `webterm diagnostics summary` or\n");
        sb.append("`webterm diagnostics export` in a terminal on the computer running the Agent.\n\n");
        sb.append("uidRxBytes=").append(network.uid.rxBytes).append('\n');
        sb.append("uidTxBytes=").append(network.uid.txBytes).append('\n');
        sb.append("uidSupported=").append(network.uid.supported).append('\n');
        sb.append("websocketRxFrames=").append(network.websocket.rxFrames).append('\n');
        sb.append("websocketRxBytes=").append(network.websocket.rxBytes).append('\n');
        sb.append("websocketTxFrames=").append(network.websocket.txFrames).append('\n');
        sb.append("websocketTxBytes=").append(network.websocket.txBytes).append('\n');
        if (!network.websocketByDevice.isEmpty()) {
            sb.append("\n[WebSocket by device]\n");
            for (Map.Entry<String, MuxTransport.TrafficSnapshot> e : network.websocketByDevice.entrySet()) {
                MuxTransport.TrafficSnapshot s = e.getValue();
                sb.append("serverHash=").append(DiagnosticIdHasher.hash(salt, NetworkTrafficStats.serverOfKey(e.getKey())))
                  .append(" deviceHash=").append(DiagnosticIdHasher.hash(salt, NetworkTrafficStats.deviceOfKey(e.getKey())))
                  .append(" rxFrames=").append(s.rxFrames)
                  .append(" rxBytes=").append(s.rxBytes)
                  .append(" txFrames=").append(s.txFrames)
                  .append(" txBytes=").append(s.txBytes)
                  .append('\n');
            }
        }
        sb.append('\n');
        sb.append("screenBaselineCount=").append(screen.baselineFrameCount).append('\n');
        sb.append("screenBaselineBytes=").append(screen.baselineFrameBytes).append('\n');
        sb.append("screenPatchCount=").append(screen.commitFrameCount).append('\n');
        sb.append("screenPatchBytes=").append(screen.commitFrameBytes).append('\n');
        sb.append("screenHistoryRangeCount=").append(screen.historyRangeFrameCount).append('\n');
        sb.append("screenHistoryRangeBytes=").append(screen.historyRangeFrameBytes).append('\n');
        sb.append("screenOtherCount=").append(screen.otherFrameCount).append('\n');
        sb.append("screenOtherBytes=").append(screen.otherFrameBytes).append('\n');
        return sb.toString();
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
