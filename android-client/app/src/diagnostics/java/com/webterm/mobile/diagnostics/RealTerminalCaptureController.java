package com.webterm.mobile.diagnostics;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenPatch;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.model.capture.AgentCaptureData;
import com.webterm.terminal.model.capture.AgentCaptureLink;
import com.webterm.terminal.model.capture.CaptureCallback;
import com.webterm.terminal.model.capture.CaptureIdentity;
import com.webterm.terminal.model.capture.CaptureLimits;
import com.webterm.terminal.model.capture.CaptureResult;
import com.webterm.terminal.model.capture.CaptureSessionSource;
import com.webterm.terminal.model.capture.CaptureStatus;
import com.webterm.terminal.model.capture.CapturedModelState;
import com.webterm.terminal.model.capture.CapturedViewState;
import com.webterm.terminal.model.capture.TerminalCaptureController;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Debug/Diag 专用的终端渲染路径现场捕获控制器（真实实现）。release 构建不含本类
 * （由 src/release 的同名 NOOP stub 取代）。
 *
 * 设计要点：
 * - 旁路观察：record* 只接收正常业务路径已产出的不可变结果，写入有界 ring；绝不消费业务状态。
 * - 热路径只做一次 isRecording() 判断 + 有界入队；序列化/SHA-256/ZIP/Agent 请求全在专用后台线程。
 * - ring 严格有界（条数 + wire 字节），超限丢最旧并置截断标志。
 * - 现场包含终端正文，未脱敏；UI 分享前须提示用户。
 */
public final class RealTerminalCaptureController implements TerminalCaptureController {

    private static final int MAX_ARCHIVES = 5;
    private static final String ARCHIVE_PREFIX = "webterm-render-capture-";
    private static final String EXPORT_DIR = "diagnostics-export";
    private static final long AGENT_REQUEST_TIMEOUT_MILLIS = 8_000L;

    private final Context appContext;
    private final Handler mainHandler; // 无主 Looper（纯 JVM 测试）时为 null，回调改为同步触发
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "webterm-render-capture"));

    private final Object lock = new Object();
    private final ByteBoundedRing wireRing = new ByteBoundedRing();
    private final ArrayDeque<JSONObject> mappedRing = new ArrayDeque<>();
    private final ArrayDeque<CapturedModelState> modelRing = new ArrayDeque<>();
    private final ArrayDeque<RenderUpdate> renderRing = new ArrayDeque<>();
    private boolean wireTruncated;
    private boolean mappedTruncated;
    private boolean modelTruncated;
    private boolean renderTruncated;

    private volatile boolean recording;
    private volatile CaptureSessionSource source;
    private volatile String activeCaptureId = "";
    private volatile CaptureLimits activeLimits = CaptureLimits.defaults();
    private volatile long startedAtMillis;

    public RealTerminalCaptureController(Context context) {
        this.appContext = context.getApplicationContext();
        Looper looper = Looper.getMainLooper();
        this.mainHandler = looper != null ? new Handler(looper) : null;
    }

    @Override public boolean isSupported() { return true; }
    @Override public boolean isRecording() { return recording; }

    @Override
    public CaptureStatus status() {
        return new CaptureStatus(recording, activeCaptureId, startedAtMillis);
    }

    @Override
    public void bindSession(CaptureSessionSource src) {
        this.source = src;
    }

    @Override
    public void startCapture(CaptureLimits limits) {
        if (recording) return;
        CaptureLimits effective = limits != null ? limits : CaptureLimits.defaults();
        String captureId = "cap-" + UUID.randomUUID().toString().substring(0, 12);
        synchronized (lock) {
            clearRingsLocked();
            wireRing.configure(effective.maxStructuredFrames * 4, effective.maxAndroidWireBytes);
            activeLimits = effective;
            activeCaptureId = captureId;
            startedAtMillis = System.currentTimeMillis();
            recording = true;
        }
        // 通知 Agent 开始记录（best-effort，后台）。
        CaptureSessionSource src = source;
        if (src != null) {
            CaptureIdentity identity = safeIdentity(src).withCaptureId(captureId);
            executor.execute(() -> {
                AgentCaptureLink link = src.agentLink();
                if (link != null) link.notifyStart(identity, effective);
            });
        }
    }

    @Override
    public void cancelCapture() {
        String captureId;
        CaptureSessionSource src = source;
        CaptureIdentity identity;
        synchronized (lock) {
            recording = false;
            captureId = activeCaptureId;
            clearRingsLocked();
            identity = src != null ? safeIdentity(src).withCaptureId(captureId) : null;
        }
        if (src != null && identity != null) {
            executor.execute(() -> {
                AgentCaptureLink link = src.agentLink();
                if (link != null) link.notifyCancel(identity);
            });
        }
    }

    @Override
    public void finishCapture(CaptureCallback callback) {
        buildPackage(true, callback);
    }

    @Override
    public void saveCurrentScene(CaptureCallback callback) {
        buildPackage(false, callback);
    }

    /**
     * 在主线程抓取必须在主线程读取的数据（View 诊断 + 画面截图 + 当前身份），
     * 再切换到后台线程做序列化/Agent 请求/ZIP。
     */
    private void buildPackage(boolean stop, CaptureCallback callback) {
        CaptureSessionSource src = source;
        if (src == null) {
            deliver(callback, CaptureResult.failure(activeCaptureId, "no_session"));
            return;
        }
        // 主线程读取（调用方在 UI 线程触发菜单动作）。
        final CapturedViewState viewState = safeOnMain(src::viewState);
        final byte[] screenshot = safeOnMain(src::screenshotPng);
        final CaptureIdentity current = safeIdentity(src);
        final String captureId;
        final CaptureLimits limits;
        final boolean wasRecording;
        final List<WireEntry> wireSnap;
        final List<JSONObject> mappedSnap;
        final List<CapturedModelState> modelSnap;
        final List<RenderUpdate> renderSnap;
        final boolean wTrunc, mTrunc, moTrunc, rTrunc;
        synchronized (lock) {
            wasRecording = recording;
            captureId = (stop && wasRecording) ? activeCaptureId
                    : (activeCaptureId.isEmpty() ? "cap-" + UUID.randomUUID().toString().substring(0, 12) : activeCaptureId);
            limits = activeLimits;
            wireSnap = wireRing.snapshot();
            mappedSnap = new ArrayList<>(mappedRing);
            modelSnap = new ArrayList<>(modelRing);
            renderSnap = new ArrayList<>(renderRing);
            wTrunc = wireTruncated; mTrunc = mappedTruncated; moTrunc = modelTruncated; rTrunc = renderTruncated;
            if (stop) {
                recording = false;
                clearRingsLocked();
            }
        }
        final CaptureIdentity identity = current.withCaptureId(captureId);

        executor.execute(() -> {
            try {
                AgentCaptureData agentData = null;
                AgentCaptureLink link = src.agentLink();
                if (link != null) {
                    agentData = link.requestAgentCapture(identity, limits, stop, AGENT_REQUEST_TIMEOUT_MILLIS);
                }
                File archive = writeArchive(identity, limits, viewState, screenshot,
                        wireSnap, mappedSnap, modelSnap, renderSnap,
                        wTrunc, mTrunc, moTrunc, rTrunc, agentData);
                deliver(callback, CaptureResult.ok(archive.getAbsolutePath(), captureId));
            } catch (Throwable t) {
                deliver(callback, CaptureResult.failure(captureId, "build_failed"));
            }
        });
    }

    // ---- 热路径 record*（有界入队，绝不消费业务状态）----

    @Override
    public void recordWireFrame(long connectionEpoch, long receivedAtMillis, String messageKind, byte[] payload) {
        if (!recording || payload == null) return;
        synchronized (lock) {
            if (!recording) return;
            if (wireRing.add(new WireEntry(connectionEpoch, receivedAtMillis, messageKind, payload))) {
                wireTruncated = true;
            }
        }
    }

    @Override
    public void recordMappedSnapshot(ScreenSnapshot snapshot) {
        if (!recording || snapshot == null) return;
        try {
            JSONObject json = CaptureSerializer.mappedSnapshot(snapshot);
            synchronized (lock) {
                if (!recording) return;
                mappedRing.addLast(json);
                while (mappedRing.size() > activeLimits.maxStructuredFrames) {
                    mappedRing.removeFirst();
                    mappedTruncated = true;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void recordMappedPatch(ScreenPatch patch) {
        if (!recording || patch == null) return;
        try {
            JSONObject json = CaptureSerializer.mappedPatch(patch);
            synchronized (lock) {
                if (!recording) return;
                mappedRing.addLast(json);
                while (mappedRing.size() > activeLimits.maxStructuredFrames) {
                    mappedRing.removeFirst();
                    mappedTruncated = true;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void recordModelState(CapturedModelState state) {
        if (!recording || state == null) return;
        synchronized (lock) {
            if (!recording) return;
            modelRing.addLast(state);
            while (modelRing.size() > activeLimits.maxStructuredFrames) {
                modelRing.removeFirst();
                modelTruncated = true;
            }
        }
    }

    @Override
    public void recordRenderUpdate(RenderUpdate update) {
        if (!recording || update == null) return;
        int cap = Math.min(activeLimits.maxStructuredFrames, 64);
        synchronized (lock) {
            if (!recording) return;
            renderRing.addLast(update);
            while (renderRing.size() > cap) {
                renderRing.removeFirst();
                renderTruncated = true;
            }
        }
    }

    // ---- 包级测试访问器 ----

    int wireEntryCount() { synchronized (lock) { return wireRing.snapshot().size(); } }
    int mappedCount() { synchronized (lock) { return mappedRing.size(); } }
    int modelCount() { synchronized (lock) { return modelRing.size(); } }
    int renderCount() { synchronized (lock) { return renderRing.size(); } }

    // ---- ZIP 组装 ----

    // 包级可见，供单元测试直接驱动 ZIP 组装（绕过 executor/主线程 Handler）。
    File writeArchive(CaptureIdentity identity, CaptureLimits limits,
                              CapturedViewState viewState, byte[] screenshot,
                              List<WireEntry> wire, List<JSONObject> mapped,
                              List<CapturedModelState> model, List<RenderUpdate> render,
                              boolean wTrunc, boolean mTrunc, boolean moTrunc, boolean rTrunc,
                              AgentCaptureData agentData) throws Exception {
        File outDir = new File(appContext.getCacheDir(), EXPORT_DIR);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("cannot create export dir");
        }
        String name = ARCHIVE_PREFIX + identity.captureId + ".zip";
        File target = new File(outDir, name);
        File tmp = new File(outDir, name + ".tmp-" + System.nanoTime());

        Map<String, String> checksums = new LinkedHashMap<>();
        boolean committed = false;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tmp))) {
            // android/ 文件
            writeEntry(zip, checksums, "android/view-state.json",
                    jsonBytes(CaptureSerializer.viewState(viewState)));
            writeEntry(zip, checksums, "android/model-state.json", jsonBytes(modelArray(model)));
            writeEntry(zip, checksums, "android/mapped-frames.jsonl", jsonlBytes(mapped));
            writeRenderEntries(zip, checksums, render);
            writeWireEntries(zip, checksums, wire);
            boolean screenshotAvailable = screenshot != null && screenshot.length > 0;
            if (screenshotAvailable) {
                writeEntry(zip, checksums, "android/actual-screen.png", screenshot);
            }

            // agent/ 文件（来自 capture 通道；不可用时在 manifest 标注）
            boolean agentAvailable = agentData != null && agentData.available;
            if (agentAvailable) {
                for (AgentCaptureData.FileEntry f : agentData.files) {
                    writeEntry(zip, checksums, f.path, f.data);
                }
            }

            // manifest.json
            JSONObject manifest = buildManifest(identity, limits, viewState, screenshotAvailable,
                    agentAvailable, agentData, wTrunc, mTrunc, moTrunc, rTrunc, wire.size());
            writeEntry(zip, checksums, "manifest.json", jsonBytes(manifest));

            // checksums.sha256
            writeEntry(zip, checksums, "checksums.sha256", checksumBytes(checksums));

            zip.closeEntry();
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw t;
        }
        if (target.exists() && !target.delete()) {
            // 同名极少出现（captureId 唯一）；删除失败则换一个唯一名。
            target = new File(outDir, ARCHIVE_PREFIX + identity.captureId + "-" + System.nanoTime() + ".zip");
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("commit zip failed");
        }
        committed = true;
        pruneOldArchives(outDir);
        return target;
    }

    private void writeRenderEntries(ZipOutputStream zip, Map<String, String> checksums,
                                    List<RenderUpdate> render) throws Exception {
        // 取最近一帧作为 render-snapshot.json + render-dirty.json（“当前用于绘制的 RenderSnapshot”）。
        RenderSnapshotHolder holder = new RenderSnapshotHolder();
        JSONArray all = new JSONArray();
        for (RenderUpdate u : render) {
            JSONObject one = new JSONObject();
            one.put("snapshot", CaptureSerializer.renderSnapshot(u.snapshot));
            one.put("dirty", CaptureSerializer.dirty(u.dirty));
            one.put("state", CaptureSerializer.terminalState(u.state));
            all.put(one);
            holder.last = u; // 迭代到最后一个即最近
        }
        writeEntry(zip, checksums, "android/render-updates.jsonl", jsonlBytesFromObjects(all));
        if (holder.last != null) {
            writeEntry(zip, checksums, "android/render-snapshot.json",
                    jsonBytes(CaptureSerializer.renderSnapshot(holder.last.snapshot)));
            writeEntry(zip, checksums, "android/render-dirty.json",
                    jsonBytes(CaptureSerializer.dirty(holder.last.dirty)));
        } else {
            writeEntry(zip, checksums, "android/render-snapshot.json",
                    jsonBytes(CaptureSerializer.renderSnapshot(null)));
        }
    }

    private void writeWireEntries(ZipOutputStream zip, Map<String, String> checksums,
                                  List<WireEntry> wire) throws Exception {
        JSONArray index = new JSONArray();
        int seq = 0;
        for (WireEntry e : wire) {
            seq++;
            String fileName = "android/wire/" + String.format(Locale.US, "%06d", seq) + ".pb";
            writeEntry(zip, checksums, fileName, e.payload);
            JSONObject row = new JSONObject();
            row.put("seq", seq);
            row.put("file", fileName);
            row.put("connectionEpoch", e.connectionEpoch);
            row.put("receivedAtMillis", e.receivedAtMillis);
            row.put("messageKind", e.messageKind);
            row.put("length", e.payload.length);
            row.put("sha256", sha256Hex(e.payload));
            index.put(row);
        }
        writeEntry(zip, checksums, "android/wire/index.json", jsonBytes(index));
    }

    private JSONObject buildManifest(CaptureIdentity identity, CaptureLimits limits,
                                     CapturedViewState viewState, boolean screenshotAvailable,
                                     boolean agentAvailable, AgentCaptureData agentData,
                                     boolean wTrunc, boolean mTrunc, boolean moTrunc, boolean rTrunc,
                                     int wireCount) throws Exception {
        JSONObject m = new JSONObject();
        m.put("schemaVersion", 1);
        m.put("captureId", identity.captureId);
        m.put("createdAt", isoNow());
        m.put("captureStartedAt", startedAtMillis > 0 ? isoMillis(startedAtMillis) : "");
        m.put("captureFinishedAt", isoNow());

        m.put("androidAppVersion", appVersionName());
        m.put("androidBuildType", Build.TYPE);
        m.put("androidDevice", Build.MANUFACTURER + " " + Build.MODEL);
        m.put("androidSdk", Build.VERSION.SDK_INT);

        // Agent 构建身份与 revision 来自 Agent 返回的 capture-meta（若有）。
        JSONObject agentMeta = parseAgentMeta(agentData);
        m.put("agentVersion", agentMeta.optJSONObject("agent") != null
                ? agentMeta.optJSONObject("agent").optString("agentVersion") : "");
        m.put("agentPlatform", agentMeta.optJSONObject("agent") != null
                ? agentMeta.optJSONObject("agent").optString("agentPlatform") : "");
        m.put("agentBuildMode", agentMeta.optJSONObject("agent") != null
                ? agentMeta.optJSONObject("agent").optString("agentBuildMode") : "");

        m.put("sessionId", identity.sessionId);
        m.put("clientInstanceId", identity.clientInstanceId);
        m.put("terminalInstanceId", identity.terminalInstanceId);
        m.put("layoutEpoch", identity.layoutEpoch);

        m.put("androidModelRevision", identity.androidModelRevision);
        m.put("androidRenderedRevision", identity.androidRenderedRevision);
        m.put("agentRevision", agentMeta.optLong("agentRevision", 0));

        if (viewState != null) {
            int approxCols = viewState.cellWidth > 0 ? (int) (viewState.viewWidth / viewState.cellWidth) : 0;
            int approxRows = viewState.lineHeight > 0 ? (int) (viewState.viewHeight / viewState.lineHeight) : 0;
            m.put("cols", approxCols);
            m.put("rows", approxRows);
            m.put("viewWidth", viewState.viewWidth);
            m.put("viewHeight", viewState.viewHeight);
            m.put("cellWidth", viewState.cellWidth);
            m.put("lineHeight", viewState.lineHeight);
            m.put("fontSize", viewState.fontSizeSp);
            m.put("typeface", viewState.typefaceDescription);
            m.put("keyboardVisible", viewState.keyboardVisible);
            m.put("activeBuffer", "");
            m.put("compactLineEncoding", true);
        }

        m.put("screenshotAvailable", screenshotAvailable);
        m.put("agentAvailable", agentAvailable);
        if (!agentAvailable && agentData != null) {
            m.put("agentError", agentData.error);
        }

        JSONObject trunc = new JSONObject();
        trunc.put("androidWire", wTrunc);
        trunc.put("androidMapped", mTrunc);
        trunc.put("androidModel", moTrunc);
        trunc.put("androidRender", rTrunc);
        // Agent 截断标志透传。
        JSONObject agentTrunc = agentMeta.optJSONObject("truncated");
        if (agentTrunc != null) trunc.put("agent", agentTrunc);
        m.put("truncated", trunc);

        m.put("note", "本现场包包含终端输出正文，未脱敏，分享前请确认敏感内容。");
        return m;
    }

    private static JSONObject parseAgentMeta(AgentCaptureData agentData) {
        if (agentData == null || !agentData.available) return new JSONObject();
        for (AgentCaptureData.FileEntry f : agentData.files) {
            if ("agent/capture-meta.json".equals(f.path)) {
                try {
                    return new JSONObject(new String(f.data, StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            }
        }
        // 退化：用 result.meta JSON。
        if (agentData.metaJson != null && !agentData.metaJson.isEmpty()) {
            try {
                return new JSONObject(agentData.metaJson);
            } catch (Exception ignored) {
            }
        }
        return new JSONObject();
    }

    // ---- 工具方法 ----

    private void deliver(CaptureCallback callback, CaptureResult result) {
        if (callback == null) return;
        if (mainHandler != null) {
            mainHandler.post(() -> callback.onResult(result));
        } else {
            callback.onResult(result); // 无主 Looper（纯 JVM 测试）时同步回调
        }
    }

    private CaptureIdentity safeIdentity(CaptureSessionSource src) {
        try {
            CaptureIdentity id = src.currentIdentity();
            return id != null ? id : new CaptureIdentity("", "", "", "", 0, 0, 0);
        } catch (Throwable t) {
            return new CaptureIdentity("", "", "", "", 0, 0, 0);
        }
    }

    private static <T> T safeOnMain(java.util.concurrent.Callable<T> call) {
        try {
            return call.call();
        } catch (Throwable t) {
            return null;
        }
    }

    private void clearRingsLocked() {
        wireRing.clear();
        mappedRing.clear();
        modelRing.clear();
        renderRing.clear();
        wireTruncated = mappedTruncated = modelTruncated = renderTruncated = false;
    }

    private static void writeEntry(ZipOutputStream zip, Map<String, String> checksums,
                                   String path, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        if (data != null && data.length > 0) zip.write(data);
        zip.closeEntry();
        checksums.put(path, sha256Hex(data != null ? data : new byte[0]));
    }

    private static byte[] jsonBytes(JSONObject o) {
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jsonBytes(JSONArray a) {
        return a.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jsonlBytes(List<JSONObject> objects) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject o : objects) sb.append(o).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jsonlBytesFromObjects(JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) sb.append(arr.opt(i)).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private JSONArray modelArray(List<CapturedModelState> model) {
        JSONArray arr = new JSONArray();
        try {
            for (CapturedModelState m : model) arr.put(CaptureSerializer.modelState(m));
        } catch (Exception ignored) {
        }
        return arr;
    }

    private static byte[] checksumBytes(Map<String, String> checksums) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : checksums.entrySet()) {
            sb.append(e.getValue()).append("  ").append(e.getKey()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String isoNow() {
        return isoMillis(System.currentTimeMillis());
    }

    private static String isoMillis(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private String appVersionName() {
        try {
            android.content.pm.PackageManager pm = appContext.getPackageManager();
            android.content.pm.PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = pm.getPackageInfo(appContext.getPackageName(),
                        android.content.pm.PackageManager.PackageInfoFlags.of(0));
            } else {
                info = pm.getPackageInfo(appContext.getPackageName(), 0);
            }
            return info.versionName != null ? info.versionName : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void pruneOldArchives(File outDir) {
        File[] archives = outDir.listFiles((dir, n) -> n.startsWith(ARCHIVE_PREFIX) && n.endsWith(".zip"));
        // 清理遗留 .tmp。
        File[] tmps = outDir.listFiles((dir, n) -> n.contains(".tmp-"));
        if (tmps != null) for (File t : tmps) //noinspection ResultOfMethodCallIgnored
            t.delete();
        if (archives == null || archives.length <= MAX_ARCHIVES) return;
        java.util.Arrays.sort(archives, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int excess = archives.length - MAX_ARCHIVES;
        for (int i = 0; i < excess; i++) {
            //noinspection ResultOfMethodCallIgnored
            archives[i].delete();
        }
    }

    /** 用 FileProvider 分享现场包（与诊断日志导出同一 authority/路径）。 */
    public void share(Activity activity, String filePath) {
        if (activity == null || activity.isFinishing() || filePath == null) return;
        File archive = new File(filePath);
        if (!archive.exists()) return;
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".diagnostics", archive);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(Intent.EXTRA_SUBJECT, "WebTerm 渲染现场包");
        send.putExtra(Intent.EXTRA_TEXT,
                "警告：本现场包包含终端输出、命令、路径等敏感内容，未脱敏，请确认后再分享。");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("WebTerm 渲染现场包", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(send, "分享渲染现场包"));
    }

    // ---- 内部数据类型 ----

    private static final class RenderSnapshotHolder {
        RenderUpdate last;
    }

    static final class WireEntry {
        final long connectionEpoch;
        final long receivedAtMillis;
        final String messageKind;
        final byte[] payload;

        WireEntry(long connectionEpoch, long receivedAtMillis, String messageKind, byte[] payload) {
            this.connectionEpoch = connectionEpoch;
            this.receivedAtMillis = receivedAtMillis;
            this.messageKind = messageKind;
            this.payload = payload;
        }
    }

    /** 条数 + 字节双上限的有界 ring，超限丢最旧并返回 true（截断）。 */
    static final class ByteBoundedRing {
        private final ArrayDeque<WireEntry> items = new ArrayDeque<>();
        private int maxCount = 256;
        private long maxBytes = 4L << 20;
        private long currentBytes;

        void configure(int maxCount, long maxBytes) {
            this.maxCount = maxCount > 0 ? maxCount : 256;
            this.maxBytes = maxBytes > 0 ? maxBytes : (4L << 20);
        }

        boolean add(WireEntry entry) {
            boolean truncated = false;
            if (entry.payload.length > maxBytes) {
                return true; // 单条超预算，丢弃并置截断
            }
            items.addLast(entry);
            currentBytes += entry.payload.length;
            while ((items.size() > maxCount || currentBytes > maxBytes) && items.size() > 1) {
                WireEntry old = items.removeFirst();
                currentBytes -= old.payload.length;
                truncated = true;
            }
            return truncated;
        }

        List<WireEntry> snapshot() {
            return new ArrayList<>(items);
        }

        void clear() {
            items.clear();
            currentBytes = 0;
        }
    }
}
