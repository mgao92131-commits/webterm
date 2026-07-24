package com.webterm.mobile.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.capture.AgentCaptureData;
import com.webterm.terminal.model.capture.AgentCaptureLink;
import com.webterm.terminal.model.capture.CaptureIdentity;
import com.webterm.terminal.model.capture.CaptureLimits;
import com.webterm.terminal.model.capture.CaptureSessionSource;
import com.webterm.terminal.model.capture.CaptureStreamIdentity;
import com.webterm.terminal.model.capture.CapturedModelState;
import com.webterm.terminal.model.capture.CapturedScreenshot;
import com.webterm.terminal.model.capture.CapturedViewState;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 真实捕获控制器测试（纯 JVM，Context 经 Mockito 提供临时缓存目录）。 */
public class RealTerminalCaptureControllerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Context mockContext() throws Exception {
        Context ctx = mock(Context.class);
        when(ctx.getApplicationContext()).thenReturn(ctx);
        when(ctx.getCacheDir()).thenReturn(folder.getRoot());
        when(ctx.getPackageName()).thenReturn("com.webterm.test");
        when(ctx.getPackageManager()).thenReturn(mock(PackageManager.class));
        return ctx;
    }

    /** 测试用会话源：返回固定身份，其余返回 null。 */
    private static final class FakeSource implements CaptureSessionSource {
        final CaptureIdentity identity;

        FakeSource(String sessionId, String terminalInstanceId, String clientInstanceId) {
            this.identity = new CaptureIdentity("", sessionId, clientInstanceId, terminalInstanceId, 1, 5, 4);
        }

        @Override public CaptureIdentity currentIdentity() { return identity; }
        @Override public CaptureStreamIdentity streamIdentity() {
            return new CaptureStreamIdentity(identity.sessionId, identity.terminalInstanceId, identity.clientInstanceId);
        }
        @Override public CapturedViewState viewState() { return null; }
        @Override public CapturedScreenshot captureScreenshot() { return null; }
        @Override public RemoteTerminalModel.RenderSnapshot currentModelSnapshot() { return null; }
        @Override public RemoteTerminalModel.RenderSnapshot currentRenderedSnapshot() { return null; }
        @Override public RenderDirtyState lastAppliedDirty() { return null; }
        @Override public AgentCaptureLink agentLink() { return null; }
    }

    private static CaptureStreamIdentity stream(String session, String term, String client) {
        return new CaptureStreamIdentity(session, term, client);
    }

    private static CapturedModelState modelState(long revision, boolean afterBaseline) {
        return new CapturedModelState(
                1L, "i", 1, revision, revision + 2,
                1, 1, 0, true, afterBaseline,
                new HistoryExtent(10, 20), new HistoryExtent(10, 24));
    }

    // 要求 2：ring buffer 严格受条数与字节限制，超限置截断。
    @Test
    public void byteBoundedRingEnforcesLimits() {
        RealTerminalCaptureController.ByteBoundedRing ring =
                new RealTerminalCaptureController.ByteBoundedRing();
        ring.configure(3, 1000);
        boolean truncated = false;
        for (int i = 0; i < 5; i++) {
            truncated |= ring.add(new RealTerminalCaptureController.WireEntry(
                    1, i, "PATCH", new byte[]{(byte) i, (byte) i, (byte) i}));
        }
        assertEquals(3, ring.snapshot().size());
        assertTrue(truncated);

        RealTerminalCaptureController.ByteBoundedRing byteRing =
                new RealTerminalCaptureController.ByteBoundedRing();
        byteRing.configure(100, 25);
        boolean t2 = false;
        for (int i = 0; i < 4; i++) {
            t2 |= byteRing.add(new RealTerminalCaptureController.WireEntry(1, i, "PATCH", new byte[10]));
        }
        assertEquals(2, byteRing.snapshot().size());
        assertTrue(t2);
    }

    // 要求 1：未记录时所有 record* 为 NOOP（不入队）。
    @Test
    public void notRecordingRecordsNothing() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        assertFalse(c.isRecording());
        CaptureStreamIdentity id = stream("s1", "term-1", "client-1");
        c.recordWireFrame(id, 1, 1L, "SNAPSHOT", new byte[]{1, 2, 3});
        c.recordModelState(id, modelState(1, true));
        assertEquals(0, c.wireEntryCount());
        assertEquals(0, c.modelCount());
    }

    // 要求（P1-1）：record* 按流身份做会话级隔离——非匹配 session/instance 的事件被丢弃。
    @Test
    public void sessionIsolationFiltering() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        c.bindSession(new FakeSource("sA", "termA", "clientA"));
        c.startCapture(CaptureLimits.defaults());
        assertTrue(c.isRecording());

        // 匹配：记录。
        c.recordWireFrame(stream("sA", "termA", "clientA"), 1, 1L, "PATCH", new byte[]{1});
        // 不同 session：丢弃。
        c.recordWireFrame(stream("sB", "termB", "clientB"), 1, 1L, "PATCH", new byte[]{2});
        // 同 session 但不同实例（双方非空）：丢弃。
        c.recordWireFrame(stream("sA", "termX", "clientA"), 1, 1L, "PATCH", new byte[]{3});

        assertEquals(1, c.wireEntryCount());
        c.cancelCapture();
    }

    // 要求 10：cancel 后清空正文数据。
    @Test
    public void cancelClearsBodyData() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        c.bindSession(new FakeSource("s1", "term-1", "client-1"));
        c.startCapture(CaptureLimits.defaults());
        assertTrue(c.isRecording());
        CaptureStreamIdentity id = stream("s1", "term-1", "client-1");
        c.recordWireFrame(id, 1, 1L, "SNAPSHOT", "some-terminal-body".getBytes(StandardCharsets.UTF_8));
        c.recordModelState(id, modelState(1, true));
        assertTrue(c.wireEntryCount() > 0);
        c.cancelCapture();
        assertFalse(c.isRecording());
        assertEquals(0, c.wireEntryCount());
        assertEquals(0, c.modelCount());
    }

    // 要求（P1-1）：unbind 仅在 token 匹配时生效，旧页面不能清空新绑定。
    @Test
    public void unbindRequiresMatchingToken() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        FakeSource sourceA = new FakeSource("sA", "termA", "clientA");
        com.webterm.terminal.model.capture.CaptureBinding tokenA = c.bindSession(sourceA);
        // 新页面绑定覆盖。
        FakeSource sourceB = new FakeSource("sB", "termB", "clientB");
        com.webterm.terminal.model.capture.CaptureBinding tokenB = c.bindSession(sourceB);
        // 旧页面 stop() 用旧 token 解绑：应被忽略，当前绑定仍是 B。
        c.unbindSession(tokenA);
        c.startCapture(CaptureLimits.defaults()); // 使用当前绑定 B
        assertTrue(c.isRecording());
        // 记录 sA 事件应被丢弃（当前活跃身份是 B）。
        c.recordWireFrame(stream("sA", "termA", "clientA"), 1, 1L, "PATCH", new byte[]{1});
        assertEquals(0, c.wireEntryCount());
        c.recordWireFrame(stream("sB", "termB", "clientB"), 1, 1L, "PATCH", new byte[]{2});
        assertEquals(1, c.wireEntryCount());
        assertNotNull(tokenB);
        c.cancelCapture();
    }

    // 要求 2：记录中条数上限生效（model ring）。
    @Test
    public void recordingBoundsStructuredRings() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        c.bindSession(new FakeSource("s1", "term-1", "client-1"));
        c.startCapture(CaptureLimits.defaults());
        CaptureStreamIdentity id = stream("s1", "term-1", "client-1");
        for (int i = 0; i < 400; i++) {
            c.recordModelState(id, modelState(i, false));
        }
        // model ring 上限 256。
        assertEquals(256, c.modelCount());
        c.cancelCapture();
    }

    // 要求 6：宽字符 / Emoji / 组合字符能够序列化且文本完整保留。
    @Test
  public void wideEmojiCombiningCharsSerialize() throws Exception {
        String combiningText = "é";
        com.webterm.terminal.model.TerminalCell wide =
                new com.webterm.terminal.model.TerminalCell("中", (byte) 2, null, null);
        com.webterm.terminal.model.TerminalCell emoji =
                new com.webterm.terminal.model.TerminalCell("😀", (byte) 2, null, null);
        com.webterm.terminal.model.TerminalCell combining =
                new com.webterm.terminal.model.TerminalCell(combiningText, (byte) 1, null, null);
        com.webterm.terminal.model.TerminalCell ascii =
                new com.webterm.terminal.model.TerminalCell("A", (byte) 1, null, null);
        com.webterm.terminal.model.TerminalLine line = new com.webterm.terminal.model.TerminalLine(42L, 7L, false,
                new com.webterm.terminal.model.TerminalCell[]{ascii, wide, emoji, combining});

        JSONObject json = CaptureSerializer.line(line);
        String s = json.toString();
        assertEquals(42L, json.getLong("lineId"));
        assertTrue("must keep 中文", s.contains("中"));
        JSONArray cells = json.getJSONArray("cells");
        assertEquals(4, cells.length());
        assertEquals(2, cells.getJSONObject(1).getInt("width"));
        assertEquals(2, cells.getJSONObject(2).getInt("width"));
        assertEquals(combiningText, cells.getJSONObject(3).getString("text"));
        assertEquals("中", cells.getJSONObject(1).getString("text"));
  }

  @Test
  public void viewCaptureSerializesFreezeBoundaryAndIntent() throws Exception {
    CapturedViewState state = new CapturedViewState(
            1L, 1080, 720,
            0, 0, 0, 0,
            14f, "monospace", 9f, 18f, 14f,
            850, false, "FROZEN_HISTORY", 720, true,
            false, 5, 1, "term-1", true, false);

    JSONObject json = CaptureSerializer.viewState(state);

    assertEquals(850, json.getInt("scrollOffsetPixels"));
    assertEquals(720, json.getInt("liveScreenExitOffsetPixels"));
    assertEquals("FROZEN_HISTORY", json.getString("contentStreamIntent"));
    assertTrue(json.getBoolean("pureHistory"));
  }

    // 要求 7 + 8：ZIP manifest 与文件索引一致，每个文件 SHA-256 正确。
    @Test
    public void zipManifestAndChecksumsConsistent() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        CaptureIdentity identity = new CaptureIdentity("cap-zip", "s1", "client-1", "term-1", 1, 5, 4);

        List<RealTerminalCaptureController.WireEntry> wire = new ArrayList<>();
        byte[] payload = new byte[]{0x0a, 0x0b, 0x0c, 0x00, (byte) 0xff};
        wire.add(new RealTerminalCaptureController.WireEntry(1, 1000L, "PATCH", payload));

        List<CapturedModelState> model = new ArrayList<>();
        model.add(new CapturedModelState(
                1L, "term-1", 1, 5, 8,
                24, 80, 0, true, true,
                new HistoryExtent(10, 20), new HistoryExtent(10, 25)));

        File archive = c.writeArchive(identity, CaptureLimits.defaults(), null, null,
                null, null, null,
                wire, new ArrayList<>(), new ArrayList<>(), model,
                false, false, false, false, null);

        assertNotNull(archive);
        assertTrue(archive.exists());

        Map<String, byte[]> entries = readZip(archive);
        assertTrue(entries.containsKey("manifest.json"));
        assertTrue(entries.containsKey("checksums.sha256"));
        assertTrue(entries.containsKey("android/wire/index.json"));
        assertTrue(entries.containsKey("android/wire/000001.pb"));
        assertTrue(entries.containsKey("android/model-state.jsonl"));
        assertTrue(entries.containsKey("android/render-snapshot.json"));

        JSONObject capturedModel = new JSONArray(
                new String(entries.get("android/model-state.jsonl"), StandardCharsets.UTF_8).trim())
                .getJSONObject(0);
        assertEquals(5, capturedModel.getLong("screenRevision"));
        assertEquals(8, capturedModel.getLong("remoteScreenRevision"));
        assertEquals(20,
                capturedModel.getJSONObject("displayHistoryExtent").getLong("lastSeq"));
        assertEquals(25,
                capturedModel.getJSONObject("remoteHistoryExtent").getLong("lastSeq"));
        assertTrue(capturedModel.getBoolean("afterBaseline"));

        // checksums.sha256 每个条目与实际字节一致。
        String checksums = new String(entries.get("checksums.sha256"), StandardCharsets.UTF_8);
        for (String line : checksums.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("  ", 2);
            assertEquals(2, parts.length);
            byte[] data = entries.get(parts[1]);
            assertNotNull("checksum references missing entry " + parts[1], data);
            assertEquals("sha mismatch for " + parts[1], parts[0], sha256Hex(data));
        }

        JSONObject manifest = new JSONObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        assertEquals(1, manifest.getInt("schemaVersion"));
        assertEquals("cap-zip", manifest.getString("captureId"));
        assertEquals("term-1", manifest.getString("terminalInstanceId"));
        assertEquals(5, manifest.getLong("androidModelRevision"));
        assertFalse(manifest.getBoolean("screenshotAvailable"));
        assertFalse(manifest.getBoolean("agentAvailable"));
        assertEquals(com.webterm.mobile.BuildConfig.GIT_COMMIT,
                manifest.getString("gitCommit"));
        assertEquals(com.webterm.mobile.BuildConfig.GIT_DIRTY,
                manifest.getBoolean("gitDirty"));
        assertEquals(com.webterm.mobile.BuildConfig.SOURCE_TREE_HASH,
                manifest.getString("sourceTreeHash"));
        assertEquals(com.webterm.mobile.BuildConfig.BUILD_TIME_UTC,
                manifest.getString("buildTime"));
        assertEquals("debug", manifest.getString("buildVariant"));
        assertEquals(com.webterm.mobile.BuildConfig.PROTOCOL_SCHEMA_HASH,
                manifest.getString("protocolSchemaHash"));
        assertFalse(manifest.toString().contains("/Users/"));

        JSONArray arr = new JSONArray(new String(entries.get("android/wire/index.json"), StandardCharsets.UTF_8));
        assertEquals(1, arr.length());
        JSONObject row = arr.getJSONObject(0);
        assertEquals(sha256Hex(payload), row.getString("sha256"));
        assertEquals(payload.length, row.getInt("length"));
        assertEquals("android/wire/000001.pb", row.getString("file"));
    }

    // 要求 9：截断标志正确写入 manifest。
    @Test
    public void truncationFlagsPropagate() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        CaptureIdentity identity = new CaptureIdentity("cap-trunc", "s1", "c", "t", 1, 1, 1);
        File archive = c.writeArchive(identity, CaptureLimits.defaults(), null, null,
                null, null, null,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                true, false, true, false, null);
        Map<String, byte[]> entries = readZip(archive);
        JSONObject manifest = new JSONObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        JSONObject trunc = manifest.getJSONObject("truncated");
        assertTrue(trunc.getBoolean("androidWire"));
        assertFalse(trunc.getBoolean("androidMapped"));
        assertTrue(trunc.getBoolean("androidRender"));
        assertFalse(trunc.getBoolean("androidModel"));
    }

    // Agent 数据合并：agent/ 文件被写入现场包，agentAvailable=true。
    @Test
    public void agentFilesMergedIntoArchive() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        CaptureIdentity identity = new CaptureIdentity("cap-agent", "s1", "c", "t", 1, 1, 1);
        List<AgentCaptureData.FileEntry> agentFiles = new ArrayList<>();
        agentFiles.add(new AgentCaptureData.FileEntry("agent/canonical-state.json",
                "{\"agentRevision\":9}".getBytes(StandardCharsets.UTF_8)));
        agentFiles.add(new AgentCaptureData.FileEntry("agent/pty.bin", new byte[]{1, 2, 3}));
        AgentCaptureData agentData = new AgentCaptureData(true,
                "{\"agentRevision\":9,\"agent\":{\"agentVersion\":\"1.2.3\"},\"initialSyncCaptured\":false}",
                agentFiles, null);

        File archive = c.writeArchive(identity, CaptureLimits.defaults(), null, null,
                null, null, null,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                false, false, false, false, agentData);
        Map<String, byte[]> entries = readZip(archive);
        assertTrue(entries.containsKey("agent/canonical-state.json"));
        assertTrue(entries.containsKey("agent/pty.bin"));
        JSONObject manifest = new JSONObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        assertTrue(manifest.getBoolean("agentAvailable"));
        assertEquals(9, manifest.getLong("agentRevision"));
        assertEquals("1.2.3", manifest.getString("agentVersion"));
        assertFalse(manifest.getBoolean("initialSyncCaptured"));
    }

    // ---- helpers ----

    private static Map<String, byte[]> readZip(File archive) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream is = zip.getInputStream(e)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                }
                out.put(e.getName(), bos.toByteArray());
            }
        }
        return out;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
