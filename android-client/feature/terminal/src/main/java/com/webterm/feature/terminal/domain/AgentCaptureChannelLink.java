package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.core.api.WebTermUrls;
import com.webterm.core.session.ChannelFailure;
import com.webterm.core.session.DeviceConnection;
import com.webterm.terminal.model.capture.AgentCaptureData;
import com.webterm.terminal.model.capture.AgentCaptureLink;
import com.webterm.terminal.model.capture.CaptureIdentity;
import com.webterm.terminal.model.capture.CaptureLimits;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 经 webterm.capture.v1 独立逻辑通道与 Agent 现场捕获交互。每个操作打开一条短期通道，
 * 发送一条 JSON 请求，收集应答（result 头 + 分块 blob）后关闭。该通道不混入 screen mailbox，
 * Direct/Relay 均透明路由（path 携带与 screen 通道一致的 localSessionId）。
 *
 * 所有方法同步执行（在捕获专用后台线程调用），带超时；任何失败返回 available=false / 静默，
 * 绝不抛异常进入终端业务路径。
 */
public final class AgentCaptureChannelLink implements AgentCaptureLink {

    private static final String CAPTURE_SUBPROTOCOL = "webterm.capture.v1";
    private static final long CONNECT_TIMEOUT_MILLIS = 3_000L;

    private final TerminalChannel screen;

    public AgentCaptureChannelLink(@NonNull TerminalChannel screen) {
        this.screen = screen;
    }

    @Override
    public void notifyStart(CaptureIdentity identity, CaptureLimits limits) {
        sendOneWay("start", identity, limits);
    }

    @Override
    public void notifyCancel(CaptureIdentity identity) {
        sendOneWay("cancel", identity, CaptureLimits.defaults());
    }

    private void sendOneWay(String op, CaptureIdentity identity, CaptureLimits limits) {
        DeviceConnection conn = screen.captureDeviceConnection();
        if (conn == null) return;
        String channelId = "capture-" + UUID.randomUUID();
        CountDownLatch done = new CountDownLatch(1);
        conn.openChannel(channelId, capturePath(), new String[]{CAPTURE_SUBPROTOCOL},
                new DeviceConnection.ChannelListener() {
                    @Override public void onConnected(String id) {
                        try {
                            byte[] req = buildRequest(op, identity, limits, 0, 0).toString()
                                    .getBytes(StandardCharsets.UTF_8);
                            conn.tryEnqueueTunnelFrame(id, req, true, null);
                        } finally {
                            done.countDown();
                        }
                    }

                    @Override public void onData(String id, byte[] payload, boolean binary) {
                        // start/cancel 的 ack 不需要消费；收到即关闭。
                        done.countDown();
                    }

                    @Override public void onFailure(String id, ChannelFailure failure) {
                        done.countDown();
                    }
                });
        try {
            done.await(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            conn.closeChannel(channelId);
        }
    }

    @Override
    public AgentCaptureData requestAgentCapture(CaptureIdentity identity, CaptureLimits limits,
                                                boolean stop, long timeoutMillis) {
        DeviceConnection conn = screen.captureDeviceConnection();
        if (conn == null) {
            return AgentCaptureData.unavailable("no_connection");
        }
        String channelId = "capture-" + UUID.randomUUID();
        // 有界接收队列：读取线程持续 drain，容量仅作背压上界；溢出即中止本次捕获。
        LinkedBlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>(256);
        CountDownLatch connected = new CountDownLatch(1);
        final boolean[] failed = {false};

        conn.openChannel(channelId, capturePath(), new String[]{CAPTURE_SUBPROTOCOL},
                new DeviceConnection.ChannelListener() {
                    @Override public void onConnected(String id) {
                        connected.countDown();
                    }

                    @Override public void onData(String id, byte[] payload, boolean binary) {
                        if (!inbox.offer(payload)) {
                            failed[0] = true; // 队列溢出：中止，避免无界堆积
                            inbox.clear();
                            inbox.offer(new byte[0]);
                        }
                    }

                    @Override public void onFailure(String id, ChannelFailure failure) {
                        failed[0] = true;
                        connected.countDown();
                        inbox.offer(new byte[0]); // 唤醒等待
                    }
                });

        try {
            if (!connected.await(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) || failed[0]) {
                return AgentCaptureData.unavailable("connect_failed");
            }
            String op = stop ? "finish" : "barrier";
            byte[] req = buildRequest(op, identity, limits,
                    identity.androidModelRevision, identity.androidRenderedRevision)
                    .toString().getBytes(StandardCharsets.UTF_8);
            conn.tryEnqueueTunnelFrame(channelId, req, true, null);
            return readResponse(inbox, Math.max(1_000L, timeoutMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentCaptureData.unavailable("interrupted");
        } finally {
            conn.closeChannel(channelId);
        }
    }

    // Agent 返回数据的接收端硬上限（与 Agent 端对齐），超限即关闭并判失败。
    private static final long RECV_MAX_PAYLOAD_BYTES = 16L << 20;
    private static final int RECV_MAX_FILES = 512;
    private static final long RECV_MAX_FILE_BYTES = 8L << 20;

    /** 期望文件：来自 result 的声明长度与 SHA，用于最终完整性校验。 */
    private static final class ExpectedFile {
        final long length;
        final String sha256;
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int lastSeq = 0;
        boolean finalReceived = false;

        ExpectedFile(long length, String sha256) {
            this.length = length;
            this.sha256 = sha256;
        }
    }

    /**
     * 解析 result 头 + 分块 blob，重组为文件集，并做完整性与安全校验：
     * 路径白名单（仅 agent/ 相对路径）、chunk seq 严格递增、拒绝重复/未知/重复 final、
     * 字节/文件数硬上限、最终长度与 SHA-256 校验。任一失败返回 available=false。
     */
    // 包级可见，供单元测试直接驱动校验逻辑。
    AgentCaptureData readResponse(LinkedBlockingQueue<byte[]> inbox, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        JSONObject resultMeta = null;
        Map<String, ExpectedFile> expected = new java.util.LinkedHashMap<>();
        List<String> expectedPaths = new ArrayList<>();
        long totalBytes = 0;
        boolean sawResult = false;

        while (System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            byte[] frame = inbox.poll(remaining, TimeUnit.MILLISECONDS);
            if (frame == null) break;
            if (frame.length == 0) break; // 失败哨兵
            JSONObject json;
            try {
                json = new JSONObject(new String(frame, StandardCharsets.UTF_8));
            } catch (Exception e) {
                continue;
            }
            String op = json.optString("op");
            if ("result".equals(op)) {
                sawResult = true;
                if (!json.optBoolean("ok", false)) {
                    return AgentCaptureData.unavailable(json.optString("error", "agent_error"));
                }
                resultMeta = json.optJSONObject("meta");
                JSONArray files = json.optJSONArray("files");
                if (files != null) {
                    if (files.length() > RECV_MAX_FILES) {
                        return AgentCaptureData.unavailable("payload_too_large");
                    }
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject fi = files.optJSONObject(i);
                        String path = fi.optString("path");
                        if (!isSafeAgentPath(path) || expected.containsKey(path)) {
                            return AgentCaptureData.unavailable("unsafe_or_duplicate_path");
                        }
                        expectedPaths.add(path);
                        expected.put(path, new ExpectedFile(fi.optLong("length", 0), fi.optString("sha256", "")));
                    }
                }
                if (expectedPaths.isEmpty()) {
                    break; // 无文件的 result（异常）
                }
            } else if ("blob".equals(op)) {
                String path = json.optString("path");
                ExpectedFile ef = expected.get(path);
                if (ef == null) {
                    return AgentCaptureData.unavailable("unknown_file_path"); // 未声明的文件
                }
                int seq = json.optInt("seq", 0);
                if (ef.finalReceived) {
                    return AgentCaptureData.unavailable("blob_after_final"); // 重复 final 后续块
                }
                if (seq <= ef.lastSeq) {
                    return AgentCaptureData.unavailable("blob_seq_not_increasing"); // 乱序/重复
                }
                ef.lastSeq = seq;
                String dataB64 = json.optString("data");
                if (dataB64 != null && !dataB64.isEmpty()) {
                    byte[] chunk = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT);
                    if (ef.buffer.size() + chunk.length > RECV_MAX_FILE_BYTES
                            || totalBytes + chunk.length > RECV_MAX_PAYLOAD_BYTES) {
                        return AgentCaptureData.unavailable("payload_too_large");
                    }
                    ef.buffer.write(chunk, 0, chunk.length);
                    totalBytes += chunk.length;
                }
                if (json.optBoolean("final", false)) {
                    ef.finalReceived = true;
                }
                if (allFinal(expectedPaths, expected)) {
                    break;
                }
            } else if ("ack".equals(op)) {
                if (!json.optBoolean("ok", true)) {
                    return AgentCaptureData.unavailable(json.optString("error", "agent_error"));
                }
            }
        }

        if (!sawResult) {
            return AgentCaptureData.unavailable("no_result");
        }
        // 完整性校验：所有声明文件必须收到 final，长度与 SHA-256 必须匹配。
        List<AgentCaptureData.FileEntry> files = new ArrayList<>();
        for (String path : expectedPaths) {
            ExpectedFile ef = expected.get(path);
            byte[] data = ef.buffer.toByteArray();
            if (!ef.finalReceived) {
                return AgentCaptureData.unavailable("incomplete_file");
            }
            if (ef.length > 0 && data.length != ef.length) {
                return AgentCaptureData.unavailable("length_mismatch");
            }
            if (ef.sha256 != null && !ef.sha256.isEmpty() && !ef.sha256.equals(sha256Hex(data))) {
                return AgentCaptureData.unavailable("sha256_mismatch");
            }
            files.add(new AgentCaptureData.FileEntry(path, data));
        }
        return new AgentCaptureData(true,
                resultMeta != null ? resultMeta.toString() : "", files, null);
    }

    private static boolean allFinal(List<String> paths, Map<String, ExpectedFile> expected) {
        for (String path : paths) {
            ExpectedFile ef = expected.get(path);
            if (ef == null || !ef.finalReceived) return false;
        }
        return true;
    }

    /**
     * 路径白名单：仅接受 agent/ 前缀的安全相对路径。拒绝 ../、\、绝对路径、空路径、
     * 以及 android/... 前缀（防止 Agent 覆盖 Android 侧文件）。
     */
    static boolean isSafeAgentPath(String path) {
        if (path == null || path.isEmpty()) return false;
        if (!path.startsWith("agent/")) return false;
        if (path.contains("..") || path.contains("\\") || path.startsWith("/")) return false;
        if (path.contains("//")) return false;
        return true;
    }

    private static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format(java.util.Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String capturePath() {
        return "/ws/capture/" + WebTermUrls.encodePath(screen.captureLocalSessionId());
    }

    private JSONObject buildRequest(String op, CaptureIdentity identity, CaptureLimits limits,
                                    long androidModelRevision, long androidRenderedRevision) {
        JSONObject req = new JSONObject();
        try {
            req.put("op", op);
            req.put("captureId", identity.captureId);
            req.put("sessionId", identity.sessionId);
            req.put("clientInstanceId", identity.clientInstanceId);
            req.put("terminalInstanceId", identity.terminalInstanceId);
            req.put("layoutEpoch", identity.layoutEpoch);
            req.put("androidModelRevision", androidModelRevision);
            req.put("androidRenderedRevision", androidRenderedRevision);
            JSONObject lim = new JSONObject();
            lim.put("maxDurationNanos", limits.maxDurationMillis * 1_000_000L);
            lim.put("maxPtyBytes", 2 << 20);
            lim.put("maxAgentWireBytes", limits.maxAndroidWireBytes);
            lim.put("maxStructuredFrames", limits.maxStructuredFrames);
            lim.put("maxCanonicalFrames", 16);
            lim.put("maxWireFrames", limits.maxStructuredFrames);
            req.put("limits", lim);
        } catch (Exception ignored) {
        }
        return req;
    }
}
