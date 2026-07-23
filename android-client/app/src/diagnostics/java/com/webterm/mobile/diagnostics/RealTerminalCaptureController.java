package com.webterm.mobile.diagnostics;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenPatchV2;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.capture.AgentCaptureData;
import com.webterm.terminal.model.capture.AgentCaptureLink;
import com.webterm.terminal.model.capture.CaptureBinding;
import com.webterm.terminal.model.capture.CaptureCallback;
import com.webterm.terminal.model.capture.CaptureIdentity;
import com.webterm.terminal.model.capture.CaptureLimits;
import com.webterm.terminal.model.capture.CaptureResult;
import com.webterm.terminal.model.capture.CaptureSessionSource;
import com.webterm.terminal.model.capture.CaptureStatus;
import com.webterm.terminal.model.capture.CapturedModelState;
import com.webterm.terminal.model.capture.CapturedScreenshot;
import com.webterm.terminal.model.capture.CapturedViewState;
import com.webterm.terminal.model.capture.CaptureStreamIdentity;
import com.webterm.terminal.model.capture.TerminalCaptureController;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
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
 * Debug/Diag 专用的终端渲染路径现场捕获控制器（真实实现）。release 不含本类。
 *
 * 关键设计（对应审查修复）：
 * - 会话级隔离（P1-1）：record* 携带 CaptureStreamIdentity，复制/序列化前先 matchesActive 校验；
 *   bindSession 返回 token，unbindSession(token) 防旧页面清空新绑定；start 时快照 activeSource。
 * - 有界（P1-2）：wire/mapped/render ring 均有字节+条数预算；limits clamp 到硬上限。
 * - 热路径不做 JSON（P2）：mapped/render 只存不可变对象引用，JSON 在导出 executor 生成。
 * - 保存当前现场（P1-6）：无论是否记录，都抓取 Android 当前 model/render/view/screenshot。
 * - 截图（P2）：主线程仅取有界 ARGB 像素，PNG 压缩在后台。
 */
public final class RealTerminalCaptureController implements TerminalCaptureController {

    private static final int MAX_ARCHIVES = 5;
    private static final String ARCHIVE_PREFIX = "webterm-render-capture-";
    private static final String EXPORT_DIR = "diagnostics-export";
    private static final long AGENT_REQUEST_TIMEOUT_MILLIS = 8_000L;

    // 结构化对象 ring 字节预算（与 Agent 端硬上限对齐）。
    private static final long MAPPED_RING_MAX_BYTES = 4L << 20;
    private static final long RENDER_RING_MAX_BYTES = 4L << 20;
    private static final int MAPPED_RING_MAX_COUNT = 256;
    private static final int RENDER_RING_MAX_COUNT = 64;
    private static final int MODEL_RING_MAX_COUNT = 256;
    private static final long PNG_MAX_BYTES = 4L << 20;

    private final Context appContext;
    private final Handler mainHandler; // 无主 Looper（纯 JVM 测试）时为 null
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "webterm-render-capture"));

    private final Object lock = new Object();
    private final ByteBoundedRing wireRing = new ByteBoundedRing();
    private final BoundedObjects<MappedFrame> mappedRing = new BoundedObjects<>();
    private final BoundedObjects<RenderUpdate> renderRing = new BoundedObjects<>();
    private final ArrayDeque<CapturedModelState> modelRing = new ArrayDeque<>();
    private boolean wireTruncated;
    private boolean mappedTruncated;
    private boolean renderTruncated;
    private boolean modelTruncated;

    // 当前绑定（token 保护）。
    private volatile CaptureSessionSource boundSource;
    private volatile CaptureBinding bindingToken;

    // 活跃记录状态（start 时快照，finish/cancel 清除）。
    private volatile boolean recording;
    private volatile CaptureIdentity activeIdentity;
    private volatile CaptureSessionSource activeSource;
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
        return new CaptureStatus(recording,
                activeIdentity != null ? activeIdentity.captureId : "", startedAtMillis);
    }

    @Override
    public CaptureBinding bindSession(CaptureSessionSource source) {
        CaptureBinding token = new CaptureBinding(source);
        this.boundSource = source;
        this.bindingToken = token;
        return token;
    }

    @Override
    public void unbindSession(CaptureBinding token) {
        // 仅当 token 仍是当前绑定时解绑，防旧页面 stop() 清空新页面的绑定。
        if (token != null && token == bindingToken) {
            boundSource = null;
            bindingToken = null;
        }
    }

    @Override
    public void startCapture(CaptureLimits limits) {
        CaptureSessionSource source = boundSource;
        if (source == null || recording) return;
        CaptureLimits effective = clampLimits(limits != null ? limits : CaptureLimits.defaults());
        String captureId = "cap-" + UUID.randomUUID().toString().substring(0, 12);
        CaptureIdentity identity = source.currentIdentity().withCaptureId(captureId);
        synchronized (lock) {
            clearRingsLocked();
            wireRing.configure(effective.maxStructuredFrames * 4, effective.maxAndroidWireBytes);
            mappedRing.configure(MAPPED_RING_MAX_COUNT, MAPPED_RING_MAX_BYTES);
            renderRing.configure(RENDER_RING_MAX_COUNT, RENDER_RING_MAX_BYTES);
            activeIdentity = identity;
            activeSource = source; // 快照当前源，finish 期间即使新页面绑定也不受影响
            activeLimits = effective;
            startedAtMillis = System.currentTimeMillis();
            recording = true;
        }
        AgentCaptureLink link = source.agentLink();
        if (link != null) {
            executor.execute(() -> safeNotifyStart(link, identity, effective));
        }
    }

    @Override
    public void cancelCapture() {
        CaptureIdentity identity;
        CaptureSessionSource source;
        synchronized (lock) {
            recording = false;
            identity = activeIdentity;
            source = activeSource;
            clearRingsLocked();
            activeIdentity = null;
            activeSource = null;
        }
        if (source != null && identity != null) {
            AgentCaptureLink link = source.agentLink();
            if (link != null) {
                executor.execute(() -> {
                    try { link.notifyCancel(identity); } catch (Throwable ignored) {}
                });
            }
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
     * 组装现场包。stop=true（保存并结束）使用 activeSource 并停止记录；stop=false（保存当前现场）
     * 使用当前 boundSource，不停止记录。无论是否记录，都抓取 Android 当前 model/render/view/screenshot。
     * 主线程抓取必须在主线程读取的数据，随后切换到后台 executor 做序列化/Agent 请求/ZIP。
     */
    private void buildPackage(boolean stop, CaptureCallback callback) {
        CaptureSessionSource source = stop ? activeSource : boundSource;
        if (source == null) source = boundSource;
        if (source == null) {
            deliver(callback, CaptureResult.failure("", "no_session"));
            return;
        }
        // —— 主线程：抓取必须在主线程/模型锁读取的当前状态 ——
        final CaptureSessionSource src = source;
        final CaptureIdentity identity = resolveIdentity(src, stop);
        final CaptureLimits limits = activeLimits;
        final CapturedViewState viewState = safeCall(src::viewState);
        final CapturedScreenshot screenshot = safeCall(src::captureScreenshot);
        final RemoteTerminalModel.RenderSnapshot currentModel = safeCall(src::currentModelSnapshot);
        final RemoteTerminalModel.RenderSnapshot currentRendered = safeCall(src::currentRenderedSnapshot);
        final RenderDirtyState lastDirty = safeCall(src::lastAppliedDirty);

        // —— 快照 ring（停止时清空）——
        final List<WireEntry> wireSnap;
        final List<MappedFrame> mappedSnap;
        final List<RenderUpdate> renderSnap;
        final List<CapturedModelState> modelSnap;
        final boolean wTrunc, mTrunc, rTrunc, moTrunc;
        synchronized (lock) {
            wireSnap = wireRing.snapshot();
            mappedSnap = mappedRing.snapshot();
            renderSnap = renderRing.snapshot();
            modelSnap = new ArrayList<>(modelRing);
            wTrunc = wireTruncated; mTrunc = mappedTruncated; rTrunc = renderTruncated; moTrunc = modelTruncated;
            if (stop) {
                recording = false;
                clearRingsLocked();
                activeIdentity = null;
                activeSource = null;
            }
        }

        executor.execute(() -> {
            try {
                AgentCaptureData agentData = null;
                AgentCaptureLink link = src.agentLink();
                if (link != null) {
                    agentData = link.requestAgentCapture(identity, limits, stop, AGENT_REQUEST_TIMEOUT_MILLIS);
                }
                File archive = writeArchive(identity, limits, viewState, screenshot,
                        currentModel, currentRendered, lastDirty,
                        wireSnap, mappedSnap, renderSnap, modelSnap,
                        wTrunc, mTrunc, rTrunc, moTrunc, agentData);
                deliver(callback, CaptureResult.ok(archive.getAbsolutePath(), identity.captureId));
            } catch (Throwable t) {
                deliver(callback, CaptureResult.failure(identity.captureId, "build_failed"));
            }
        });
    }

    private CaptureIdentity resolveIdentity(CaptureSessionSource src, boolean stop) {
        if (stop && activeIdentity != null) {
            // finish：沿用 start 的 captureId，但刷新当前 revision。
            CaptureIdentity cur = safeCall(src::currentIdentity);
            if (cur != null) {
                return new CaptureIdentity(activeIdentity.captureId, cur.sessionId,
                        cur.clientInstanceId, cur.terminalInstanceId, cur.layoutEpoch,
                        cur.androidModelRevision, cur.androidRenderedRevision);
            }
            return activeIdentity;
        }
        CaptureIdentity cur = safeCall(src::currentIdentity);
        String captureId = "cap-" + UUID.randomUUID().toString().substring(0, 12);
        if (cur != null) return cur.withCaptureId(captureId);
        return new CaptureIdentity(captureId, "", "", "", 0, 0, 0);
    }

    // ---- 热路径 record*（会话级隔离 + 有界，绝不消费业务状态）----

    @Override
    public void recordWireFrame(CaptureStreamIdentity identity, long connectionEpoch,
                                long receivedAtMillis, String messageKind, byte[] payload) {
        if (!recording || payload == null || !matches(identity)) return;
        byte[] copy = payload.clone(); // 有界拷贝，避免上游复用
        synchronized (lock) {
            if (!recording || !matches(identity)) return;
            if (wireRing.add(new WireEntry(connectionEpoch, receivedAtMillis, messageKind, copy))) {
                wireTruncated = true;
            }
        }
    }

    @Override
    public void recordMappedSnapshot(CaptureStreamIdentity identity, ScreenBaseline snapshot) {
        if (!recording || snapshot == null || !matches(identity)) return;
        synchronized (lock) {
            if (!recording || !matches(identity)) return;
            if (mappedRing.add(new MappedFrame(snapshot, null, System.currentTimeMillis()),
                    estimateSnapshotBytes(snapshot))) {
                mappedTruncated = true;
            }
        }
    }

    @Override
    public void recordMappedPatch(CaptureStreamIdentity identity, ScreenPatchV2 patch) {
        if (!recording || patch == null || !matches(identity)) return;
        synchronized (lock) {
            if (!recording || !matches(identity)) return;
            if (mappedRing.add(new MappedFrame(null, patch, System.currentTimeMillis()),
                    estimatePatchBytes(patch))) {
                mappedTruncated = true;
            }
        }
    }

    @Override
    public void recordModelState(CaptureStreamIdentity identity, CapturedModelState state) {
        if (!recording || state == null || !matches(identity)) return;
        synchronized (lock) {
            if (!recording || !matches(identity)) return;
            modelRing.addLast(state);
            while (modelRing.size() > MODEL_RING_MAX_COUNT) {
                modelRing.removeFirst();
                modelTruncated = true;
            }
        }
    }

    @Override
    public void recordRenderUpdate(CaptureStreamIdentity identity, RenderUpdate update) {
        if (!recording || update == null || !matches(identity)) return;
        synchronized (lock) {
            if (!recording || !matches(identity)) return;
            if (renderRing.add(update, estimateRenderBytes(update))) {
                renderTruncated = true;
            }
        }
    }

    private boolean matches(CaptureStreamIdentity identity) {
        CaptureIdentity active = activeIdentity;
        return identity != null && identity.matchesActive(active);
    }

    // ---- ZIP 组装 ----

    // 包级可见，供单元测试直接驱动 ZIP 组装（绕过 executor/主线程）。
    File writeArchive(CaptureIdentity identity, CaptureLimits limits,
                              CapturedViewState viewState, CapturedScreenshot screenshot,
                              RemoteTerminalModel.RenderSnapshot currentModel,
                              RemoteTerminalModel.RenderSnapshot currentRendered,
                              RenderDirtyState lastDirty,
                              List<WireEntry> wire, List<MappedFrame> mapped,
                              List<RenderUpdate> render, List<CapturedModelState> model,
                              boolean wTrunc, boolean mTrunc, boolean rTrunc, boolean moTrunc,
                              AgentCaptureData agentData) throws Exception {
        File outDir = new File(appContext.getCacheDir(), EXPORT_DIR);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("cannot create export dir");
        }
        String name = ARCHIVE_PREFIX + identity.captureId + ".zip";
        File target = new File(outDir, name);
        File tmp = new File(outDir, name + ".tmp-" + System.nanoTime());

        Map<String, String> checksums = new LinkedHashMap<>();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tmp))) {
            // android/view-state.json
            writeEntry(zip, checksums, "android/view-state.json",
                    jsonBytes(CaptureSerializer.viewState(viewState)));

            // android/current-model-state.json（当前完整模型状态，含 screen+history 窗口）
            writeEntry(zip, checksums, "android/current-model-state.json",
                    jsonBytes(CaptureSerializer.renderSnapshot(currentModel, true)));

            // android/render-snapshot.json（当前实际绘制状态，含 history 窗口）
            writeEntry(zip, checksums, "android/render-snapshot.json",
                    jsonBytes(CaptureSerializer.renderSnapshot(currentRendered, true)));

            // android/render-dirty.json（最近一次脏区）
            writeEntry(zip, checksums, "android/render-dirty.json",
                    jsonBytes(CaptureSerializer.dirty(lastDirty)));

            // android/model-state.jsonl（记录期间的模型摘要序列）
            JSONArray modelArr = new JSONArray();
            for (CapturedModelState m : model) modelArr.put(CaptureSerializer.modelState(m));
            writeEntry(zip, checksums, "android/model-state.jsonl", jsonBytes(modelArr));

            // android/mapped-frames.jsonl（记录期间 Mapper 输出，导出时序列化）
            StringBuilder mappedBuf = new StringBuilder();
            for (MappedFrame mf : mapped) {
                JSONObject one = mf.snapshot != null
                        ? CaptureSerializer.mappedSnapshot(mf.snapshot)
                        : CaptureSerializer.mappedPatch(mf.patch);
                one.put("capturedAtMillis", mf.capturedAtMillis);
                mappedBuf.append(one).append('\n');
            }
            writeEntry(zip, checksums, "android/mapped-frames.jsonl",
                    mappedBuf.toString().getBytes(StandardCharsets.UTF_8));

            // android/render-updates.jsonl + 最近一帧 render snapshot/dirty
            StringBuilder renderBuf = new StringBuilder();
            RenderUpdate lastUpdate = render.isEmpty() ? null : render.get(render.size() - 1);
            for (RenderUpdate u : render) {
                JSONObject one = new JSONObject();
                one.put("snapshot", CaptureSerializer.renderSnapshot(u.snapshot, false));
                one.put("dirty", CaptureSerializer.dirty(u.dirty));
                one.put("state", CaptureSerializer.terminalState(u.state));
                renderBuf.append(one).append('\n');
            }
            writeEntry(zip, checksums, "android/render-updates.jsonl",
                    renderBuf.toString().getBytes(StandardCharsets.UTF_8));
            if (lastUpdate != null) {
                writeEntry(zip, checksums, "android/render-snapshot-latest.json",
                        jsonBytes(CaptureSerializer.renderSnapshot(lastUpdate.snapshot, true)));
                writeEntry(zip, checksums, "android/render-dirty-latest.json",
                        jsonBytes(CaptureSerializer.dirty(lastUpdate.dirty)));
            }

            // android/wire/index.json + .pb
            JSONArray wireIdx = new JSONArray();
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
                wireIdx.put(row);
            }
            writeEntry(zip, checksums, "android/wire/index.json", jsonBytes(wireIdx));

            // android/actual-screen.png（后台压缩）
            boolean screenshotAvailable = false;
            int capturedW = 0, capturedH = 0, originalW = 0, originalH = 0;
            boolean scaled = false;
            if (screenshot != null) {
                byte[] png = compressPng(screenshot);
                if (png != null && png.length > 0 && png.length <= PNG_MAX_BYTES) {
                    writeEntry(zip, checksums, "android/actual-screen.png", png);
                    screenshotAvailable = true;
                    capturedW = screenshot.width; capturedH = screenshot.height;
                    originalW = screenshot.originalWidth; originalH = screenshot.originalHeight;
                    scaled = screenshot.scaled;
                }
            }

            // agent/ 文件（来自 capture 通道，已在 link 中校验完整性/路径白名单）
            boolean agentAvailable = agentData != null && agentData.available;
            if (agentAvailable) {
                for (AgentCaptureData.FileEntry f : agentData.files) {
                    writeEntry(zip, checksums, f.path, f.data);
                }
            }

            // manifest.json
            JSONObject manifest = buildManifest(identity, viewState, screenshotAvailable,
                    capturedW, capturedH, originalW, originalH, scaled,
                    agentAvailable, agentData, wTrunc, mTrunc, rTrunc, moTrunc,
                    wire.size(), mapped.size(), render.size());
            writeEntry(zip, checksums, "manifest.json", jsonBytes(manifest));

            // checksums.sha256
            writeEntry(zip, checksums, "checksums.sha256", checksumBytes(checksums));
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw t;
        }
        if (target.exists() && !target.delete()) {
            target = new File(outDir, ARCHIVE_PREFIX + identity.captureId + "-" + System.nanoTime() + ".zip");
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("commit zip failed");
        }
        pruneOldArchives(outDir);
        return target;
    }

    private JSONObject buildManifest(CaptureIdentity identity, CapturedViewState viewState,
                                     boolean screenshotAvailable, int capturedW, int capturedH,
                                     int originalW, int originalH, boolean scaled,
                                     boolean agentAvailable, AgentCaptureData agentData,
                                     boolean wTrunc, boolean mTrunc, boolean rTrunc, boolean moTrunc,
                                     int wireCount, int mappedCount, int renderCount) throws Exception {
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

        JSONObject agentMeta = parseAgentMeta(agentData);
        JSONObject agentInfo = agentMeta.optJSONObject("agent");
        m.put("agentVersion", agentInfo != null ? agentInfo.optString("agentVersion") : "");
        m.put("agentPlatform", agentInfo != null ? agentInfo.optString("agentPlatform") : "");
        m.put("agentBuildMode", agentInfo != null ? agentInfo.optString("agentBuildMode") : "");
        m.put("initialSyncCaptured", agentMeta.optBoolean("initialSyncCaptured", false));

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
        }

        m.put("screenshotAvailable", screenshotAvailable);
        JSONObject shot = new JSONObject();
        shot.put("capturedWidth", capturedW);
        shot.put("capturedHeight", capturedH);
        shot.put("originalWidth", originalW);
        shot.put("originalHeight", originalH);
        shot.put("scaled", scaled);
        m.put("screenshot", shot);

        m.put("agentAvailable", agentAvailable);
        if (!agentAvailable && agentData != null && agentData.error != null) {
            m.put("agentError", agentData.error);
        }

        JSONObject trunc = new JSONObject();
        trunc.put("androidWire", wTrunc);
        trunc.put("androidMapped", mTrunc);
        trunc.put("androidRender", rTrunc);
        trunc.put("androidModel", moTrunc);
        JSONObject agentTrunc = agentMeta.optJSONObject("truncated");
        if (agentTrunc != null) trunc.put("agent", agentTrunc);
        m.put("truncated", trunc);

        JSONObject counts = new JSONObject();
        counts.put("androidWire", wireCount);
        counts.put("androidMapped", mappedCount);
        counts.put("androidRender", renderCount);
        m.put("counts", counts);

        m.put("note", "本现场包包含终端输出正文，未脱敏，分享前请确认敏感内容。");
        return m;
    }

    private static JSONObject parseAgentMeta(AgentCaptureData agentData) {
        if (agentData == null || !agentData.available) return new JSONObject();
        for (AgentCaptureData.FileEntry f : agentData.files) {
            if ("agent/capture-meta.json".equals(f.path)) {
                try {
                    return new JSONObject(new String(f.data, StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }
        }
        if (agentData.metaJson != null && !agentData.metaJson.isEmpty()) {
            try { return new JSONObject(agentData.metaJson); } catch (Exception ignored) {}
        }
        return new JSONObject();
    }

    // ---- 截图后台压缩 ----

    private byte[] compressPng(CapturedScreenshot shot) {
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(shot.width, shot.height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(shot.argbPixels));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)) return null;
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    // ---- 工具 ----

    private static void safeNotifyStart(AgentCaptureLink link, CaptureIdentity identity, CaptureLimits limits) {
        try { link.notifyStart(identity, limits); } catch (Throwable ignored) {}
    }

    private interface Call<T> { T call(); }

    private static <T> T safeCall(Call<T> c) {
        try { return c.call(); } catch (Throwable t) { return null; }
    }

    private void deliver(CaptureCallback callback, CaptureResult result) {
        if (callback == null) return;
        if (mainHandler != null) {
            mainHandler.post(() -> callback.onResult(result));
        } else {
            callback.onResult(result);
        }
    }

    private void clearRingsLocked() {
        wireRing.clear();
        mappedRing.clear();
        renderRing.clear();
        modelRing.clear();
        wireTruncated = mappedTruncated = renderTruncated = modelTruncated = false;
    }

    // ---- 包级测试访问器 ----
    int wireEntryCount() { synchronized (lock) { return wireRing.snapshot().size(); } }
    int mappedCount() { synchronized (lock) { return mappedRing.snapshot().size(); } }
    int modelCount() { synchronized (lock) { return modelRing.size(); } }
    int renderCount() { synchronized (lock) { return renderRing.snapshot().size(); } }

    private static CaptureLimits clampLimits(CaptureLimits in) {
        // 客户端只能降低、不能提高硬上限（与 Agent 端 Hard* 对齐）。
        long duration = clamp(in.maxDurationMillis, 30_000L, 60_000L);
        int wire = (int) clamp(in.maxAndroidWireBytes, 4 << 20, 8 << 20);
        int frames = (int) clamp(in.maxStructuredFrames, 256, 512);
        int shots = (int) clamp(in.maxScreenshots, 3, 3);
        return new CaptureLimits(duration, wire, frames, shots);
    }

    private static long clamp(long requested, long def, long hardMax) {
        long v = requested <= 0 ? def : requested;
        return Math.min(v, hardMax);
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

    private static String isoNow() { return isoMillis(System.currentTimeMillis()); }

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

    // ---- 大小估算（上界，仅用于有界淘汰）----

    private static long estimateSnapshotBytes(ScreenBaseline s) {
        long n = 64;
        if (s.screen != null) for (com.webterm.terminal.model.TerminalLine l : s.screen) n += estimateLineBytes(l);
        if (s.historyTail != null)
            for (com.webterm.terminal.model.TerminalLine l : s.historyTail) n += estimateLineBytes(l);
        return n;
    }

    private static long estimatePatchBytes(ScreenPatchV2 p) {
        long n = 64;
        if (p.lineUpdates != null) for (com.webterm.terminal.model.TerminalLine l : p.lineUpdates) n += estimateLineBytes(l);
        return n;
    }

    private static long estimateRenderBytes(RenderUpdate u) {
        if (u == null || u.snapshot == null) return 32;
        long n = 64;
        if (u.snapshot.screen != null)
            for (com.webterm.terminal.model.TerminalLine l : u.snapshot.screen) n += estimateLineBytes(l);
        return n;
    }

    private static long estimateLineBytes(com.webterm.terminal.model.TerminalLine line) {
        if (line == null || line.cells == null) return 32;
        long n = 32;
        for (com.webterm.terminal.model.TerminalCell c : line.cells) {
            n += (c.text != null ? c.text.length() : 0) + 8L;
        }
        return n;
    }

    // ---- 内部数据类型 ----

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

    static final class MappedFrame {
        final ScreenBaseline snapshot;
        final ScreenPatchV2 patch;
        final long capturedAtMillis;

        MappedFrame(ScreenBaseline snapshot, ScreenPatchV2 patch, long capturedAtMillis) {
            this.snapshot = snapshot;
            this.patch = patch;
            this.capturedAtMillis = capturedAtMillis;
        }
    }

    /** 条数 + 字节双上限的 wire ring。 */
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
            if (entry.payload.length > maxBytes) return true;
            items.addLast(entry);
            currentBytes += entry.payload.length;
            while ((items.size() > maxCount || currentBytes > maxBytes) && items.size() > 1) {
                WireEntry old = items.removeFirst();
                currentBytes -= old.payload.length;
                truncated = true;
            }
            return truncated;
        }

        List<WireEntry> snapshot() { return new ArrayList<>(items); }

        void clear() { items.clear(); currentBytes = 0; }
    }

    /** 条数 + 字节双上限的通用对象 ring（mapped/render）。 */
    static final class BoundedObjects<T> {
        private final ArrayDeque<T> items = new ArrayDeque<>();
        private int maxCount = 256;
        private long maxBytes = 4L << 20;
        private long currentBytes;

        void configure(int maxCount, long maxBytes) {
            this.maxCount = maxCount > 0 ? maxCount : 256;
            this.maxBytes = maxBytes > 0 ? maxBytes : (4L << 20);
        }

        boolean add(T item, long size) {
            boolean truncated = false;
            if (size > maxBytes) return true;
            items.addLast(item);
            currentBytes += size;
            while ((items.size() > maxCount || currentBytes > maxBytes) && items.size() > 1) {
                items.removeFirst();
                currentBytes = Math.max(0, currentBytes - size); // 近似：按当前 size 估计回收
                truncated = true;
            }
            return truncated;
        }

        List<T> snapshot() { return new ArrayList<>(items); }

        void clear() { items.clear(); currentBytes = 0; }
    }
}
