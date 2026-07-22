package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.capture.AgentCaptureData;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** AgentCaptureChannelLink 的接收端完整性/安全校验测试（P1-3）。 */
public class AgentCaptureChannelLinkTest {

    private static final String EMPTY_SHA =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // sha256("")

    private final AgentCaptureChannelLink link = new AgentCaptureChannelLink(null);

    // 路径白名单。
    @Test
    public void pathWhitelist() {
        assertTrue(AgentCaptureChannelLink.isSafeAgentPath("agent/pty.bin"));
        assertTrue(AgentCaptureChannelLink.isSafeAgentPath("agent/wire/000001.pb"));
        assertFalse(AgentCaptureChannelLink.isSafeAgentPath(null));
        assertFalse(AgentCaptureChannelLink.isSafeAgentPath(""));
        assertFalse(AgentCaptureChannelLink.isSafeAgentPath("agent/../secret"));
        assertFalse(AgentCaptureChannelLink.isSafeAgentPath("agent\\evil"));
        assertFalse(AgentCaptureChannelLink.isSafeAgentPath("/etc/passwd"));
        assertFalse(AgentCaptureChannelLink.isSafeAgentPath("android/wire/000001.pb"));
        assertFalse(AgentCaptureChannelLink.isSafeAgentPath("agent//x"));
        assertFalse(AgentCaptureChannelLink.isSafeAgentPath("manifest.json"));
    }

    private static byte[] frame(JSONObject o) {
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JSONObject result(JSONArray files) throws Exception {
        JSONObject o = new JSONObject();
        o.put("op", "result");
        o.put("ok", true);
        o.put("meta", new JSONObject());
        o.put("files", files);
        return o;
    }

    private static JSONObject fileEntry(String path, long length, String sha) throws Exception {
        JSONObject o = new JSONObject();
        o.put("path", path);
        o.put("length", length);
        o.put("sha256", sha);
        return o;
    }

    private static JSONObject blob(String path, int seq, boolean fin, String dataB64) throws Exception {
        JSONObject o = new JSONObject();
        o.put("op", "blob");
        o.put("path", path);
        o.put("seq", seq);
        o.put("final", fin);
        o.put("data", dataB64);
        return o;
    }

    private AgentCaptureData run(LinkedBlockingQueue<byte[]> q) throws Exception {
        return link.readResponse(q, 2000);
    }

    // 成功：声明文件 + final 块，长度与 SHA 匹配。
    @Test
    public void validResponseAssemblesFiles() throws Exception {
        JSONArray files = new JSONArray();
        files.put(fileEntry("agent/pty.bin", 0, EMPTY_SHA));
        LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        q.offer(frame(result(files)));
        q.offer(frame(blob("agent/pty.bin", 1, true, ""))); // 空数据，避免 base64 依赖
        AgentCaptureData data = run(q);
        assertTrue(data.available);
        assertEquals(1, data.files.size());
        assertEquals("agent/pty.bin", data.files.get(0).path);
    }

    // SHA 不匹配 → 失败。
    @Test
    public void shaMismatchRejected() throws Exception {
        JSONArray files = new JSONArray();
        files.put(fileEntry("agent/pty.bin", 0, "0000000000000000000000000000000000000000000000000000000000000000"));
        LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        q.offer(frame(result(files)));
        q.offer(frame(blob("agent/pty.bin", 1, true, "")));
        AgentCaptureData data = run(q);
        assertFalse(data.available);
        assertEquals("sha256_mismatch", data.error);
    }

    // 长度不匹配 → 失败。
    @Test
    public void lengthMismatchRejected() throws Exception {
        JSONArray files = new JSONArray();
        files.put(fileEntry("agent/pty.bin", 5, EMPTY_SHA));
        LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        q.offer(frame(result(files)));
        q.offer(frame(blob("agent/pty.bin", 1, true, "")));
        AgentCaptureData data = run(q);
        assertFalse(data.available);
        assertEquals("length_mismatch", data.error);
    }

    // 缺少 final → 不完整。
    @Test
    public void missingFinalRejected() throws Exception {
        JSONArray files = new JSONArray();
        files.put(fileEntry("agent/pty.bin", 0, EMPTY_SHA));
        LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        q.offer(frame(result(files)));
        q.offer(frame(blob("agent/pty.bin", 1, false, ""))); // 非 final
        // 无后续帧，超时后判定不完整。
        AgentCaptureData data = link.readResponse(q, 300);
        assertFalse(data.available);
        assertEquals("incomplete_file", data.error);
    }

    // chunk seq 不严格递增 → 失败。
    @Test
    public void seqNotIncreasingRejected() throws Exception {
        JSONArray files = new JSONArray();
        files.put(fileEntry("agent/pty.bin", 0, EMPTY_SHA));
        LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        q.offer(frame(result(files)));
        q.offer(frame(blob("agent/pty.bin", 1, false, "")));
        q.offer(frame(blob("agent/pty.bin", 1, true, ""))); // 重复 seq
        AgentCaptureData data = run(q);
        assertFalse(data.available);
        assertEquals("blob_seq_not_increasing", data.error);
    }

    // 未声明的文件路径 → 失败。
    @Test
    public void unknownPathRejected() throws Exception {
        JSONArray files = new JSONArray();
        files.put(fileEntry("agent/pty.bin", 0, EMPTY_SHA));
        LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        q.offer(frame(result(files)));
        q.offer(frame(blob("agent/evil.bin", 1, true, "")));
        AgentCaptureData data = run(q);
        assertFalse(data.available);
        assertEquals("unknown_file_path", data.error);
    }

    // result 声明不安全路径 → 失败。
    @Test
    public void unsafeDeclaredPathRejected() throws Exception {
        JSONArray files = new JSONArray();
        files.put(fileEntry("agent/../secret", 0, EMPTY_SHA));
        LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        q.offer(frame(result(files)));
        AgentCaptureData data = run(q);
        assertFalse(data.available);
        assertEquals("unsafe_or_duplicate_path", data.error);
    }
}
