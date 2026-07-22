package com.webterm.mobile.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;

import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.capture.AgentCaptureData;
import com.webterm.terminal.model.capture.CaptureIdentity;
import com.webterm.terminal.model.capture.CaptureLimits;
import com.webterm.terminal.model.capture.CapturedModelState;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
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

        // 字节预算：每条 10 字节，预算 25 → 至多 2 条。
        RealTerminalCaptureController.ByteBoundedRing byteRing =
                new RealTerminalCaptureController.ByteBoundedRing();
        byteRing.configure(100, 25);
        boolean t2 = false;
        for (int i = 0; i < 4; i++) {
            t2 |= byteRing.add(new RealTerminalCaptureController.WireEntry(
                    1, i, "PATCH", new byte[10]));
        }
        assertEquals(2, byteRing.snapshot().size());
        assertTrue(t2);
    }

    // 要求 1：未记录时所有 record* 为 NOOP（不入队）。
    @Test
    public void notRecordingRecordsNothing() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        assertFalse(c.isRecording());
        c.recordWireFrame(1, 1L, "SNAPSHOT", new byte[]{1, 2, 3});
        c.recordModelState(new CapturedModelState(1L, "i", 1, 1, 1, 1, 0, true, true));
        assertEquals(0, c.wireEntryCount());
        assertEquals(0, c.modelCount());
    }

    // 要求 10：cancel 后清空正文数据。
    @Test
    public void cancelClearsBodyData() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        c.startCapture(CaptureLimits.defaults());
        assertTrue(c.isRecording());
        c.recordWireFrame(1, 1L, "SNAPSHOT", "some-terminal-body".getBytes(StandardCharsets.UTF_8));
        c.recordModelState(new CapturedModelState(1L, "i", 1, 1, 1, 1, 0, true, true));
        assertTrue(c.wireEntryCount() > 0);
        c.cancelCapture();
        assertFalse(c.isRecording());
        assertEquals(0, c.wireEntryCount());
        assertEquals(0, c.modelCount());
    }

    // 要求 2：记录中条数上限生效（mapped ring）。
    @Test
    public void recordingBoundsStructuredRings() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        c.startCapture(new CaptureLimits(30_000L, 4 << 20, 4, 3)); // maxStructuredFrames=4
        for (int i = 0; i < 10; i++) {
            c.recordModelState(new CapturedModelState(i, "i", 1, i, 1, 1, 0, true, false));
        }
        assertEquals(4, c.modelCount());
        c.cancelCapture();
    }

    // 要求 6：宽字符 / Emoji / 组合字符能够序列化且文本完整保留。
    @Test
    public void wideEmojiCombiningCharsSerialize() throws Exception {
        TerminalCell wide = new TerminalCell("中", (byte) 2, 1, 0);
        TerminalCell emoji = new TerminalCell("😀", (byte) 2, 1, 0); // 😀
        TerminalCell combining = new TerminalCell("é", (byte) 1, 1, 0); // é (组合字符)
        TerminalCell ascii = new TerminalCell("A", (byte) 1, 0, 0);
        TerminalLine line = new TerminalLine(42L, 7L, false,
                new TerminalCell[]{ascii, wide, emoji, combining});

        JSONObject json = CaptureSerializer.line(line);
        String s = json.toString();
        assertEquals(42L, json.getLong("lineId"));
        assertEquals(7L, json.getLong("version"));
        assertTrue("must keep 中文", s.contains("中文") || s.contains("中"));
        assertTrue("must keep emoji", s.contains("😀"));
        assertTrue("must keep combining char", s.contains("é"));
        JSONArray cells = json.getJSONArray("cells");
        assertEquals(4, cells.length());
        assertEquals(2, cells.getJSONObject(1).getInt("width")); // 宽字符宽度 2
        assertEquals(2, cells.getJSONObject(2).getInt("width")); // emoji 宽度 2
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
        model.add(new CapturedModelState(1L, "term-1", 1, 5, 24, 80, 0, true, true));

        File archive = c.writeArchive(identity, CaptureLimits.defaults(), null, null,
                wire, new ArrayList<>(), model, new ArrayList<>(),
                false, false, false, false, null);

        assertNotNull(archive);
        assertTrue(archive.exists());
        assertTrue(archive.getName().startsWith("webterm-render-capture-cap-zip"));

        Map<String, byte[]> entries = readZip(archive);
        assertTrue(entries.containsKey("manifest.json"));
        assertTrue(entries.containsKey("checksums.sha256"));
        assertTrue(entries.containsKey("android/wire/index.json"));
        assertTrue(entries.containsKey("android/wire/000001.pb"));
        assertTrue(entries.containsKey("android/model-state.json"));
        assertTrue(entries.containsKey("android/render-snapshot.json"));

        // 校验 checksums.sha256 中每个条目与实际字节一致（要求 8）。
        String checksums = new String(entries.get("checksums.sha256"), StandardCharsets.UTF_8);
        for (String line : checksums.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("  ", 2);
            assertEquals(2, parts.length);
            String expectedHash = parts[0];
            String path = parts[1];
            // checksums.sha256 自身不在索引内。
            byte[] data = entries.get(path);
            assertNotNull("checksum references missing entry " + path, data);
            assertEquals("sha mismatch for " + path, expectedHash, sha256Hex(data));
        }

        // manifest 含关键字段（要求 7）。
        JSONObject manifest = new JSONObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        assertEquals(1, manifest.getInt("schemaVersion"));
        assertEquals("cap-zip", manifest.getString("captureId"));
        assertEquals("term-1", manifest.getString("terminalInstanceId"));
        assertEquals(5, manifest.getLong("androidModelRevision"));
        assertEquals(4, manifest.getLong("androidRenderedRevision"));
        assertFalse(manifest.getBoolean("screenshotAvailable"));
        assertFalse(manifest.getBoolean("agentAvailable"));
        assertNotNull(manifest.getJSONObject("truncated"));

        // wire 索引 sha 与 .pb 文件一致（index.json 为 JSONArray）。
        JSONArray arr = new JSONArray(new String(entries.get("android/wire/index.json"), StandardCharsets.UTF_8));
        assertEquals(1, arr.length());
        JSONObject row = arr.getJSONObject(0);
        assertEquals(sha256Hex(payload), row.getString("sha256"));
        assertEquals(payload.length, row.getInt("length"));
        assertEquals("android/wire/000001.pb", row.getString("file"));
        assertEquals("PATCH", row.getString("messageKind"));
    }

    // 要求 9：截断标志正确写入 manifest。
    @Test
    public void truncationFlagsPropagate() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        CaptureIdentity identity = new CaptureIdentity("cap-trunc", "s1", "c", "t", 1, 1, 1);
        File archive = c.writeArchive(identity, CaptureLimits.defaults(), null, null,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                true, false, true, false, null);
        Map<String, byte[]> entries = readZip(archive);
        JSONObject manifest = new JSONObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        JSONObject trunc = manifest.getJSONObject("truncated");
        assertTrue(trunc.getBoolean("androidWire"));
        assertFalse(trunc.getBoolean("androidMapped"));
        assertTrue(trunc.getBoolean("androidModel"));
        assertFalse(trunc.getBoolean("androidRender"));
    }

    // Agent 数据合并：agent/ 文件被写入现场包，agentAvailable=true。
    @Test
    public void agentFilesMergedIntoArchive() throws Exception {
        RealTerminalCaptureController c = new RealTerminalCaptureController(mockContext());
        CaptureIdentity identity = new CaptureIdentity("cap-agent", "s1", "c", "t", 1, 1, 1);
        List<AgentCaptureData.FileEntry> agentFiles = new ArrayList<>();
        agentFiles.add(new AgentCaptureData.FileEntry("agent/canonical-state.json",
                "{\"agentRevision\":9}".getBytes(StandardCharsets.UTF_8)));
        agentFiles.add(new AgentCaptureData.FileEntry("agent/pty.bin",
                new byte[]{1, 2, 3}));
        AgentCaptureData agentData = new AgentCaptureData(true,
                "{\"agentRevision\":9,\"agent\":{\"agentVersion\":\"1.2.3\"}}", agentFiles, null);

        File archive = c.writeArchive(identity, CaptureLimits.defaults(), null, null,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                false, false, false, false, agentData);
        Map<String, byte[]> entries = readZip(archive);
        assertTrue(entries.containsKey("agent/canonical-state.json"));
        assertTrue(entries.containsKey("agent/pty.bin"));
        JSONObject manifest = new JSONObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        assertTrue(manifest.getBoolean("agentAvailable"));
        assertEquals(9, manifest.getLong("agentRevision"));
        assertEquals("1.2.3", manifest.getString("agentVersion"));
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
