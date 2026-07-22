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
        LinkedBlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
        CountDownLatch connected = new CountDownLatch(1);
        final boolean[] failed = {false};

        conn.openChannel(channelId, capturePath(), new String[]{CAPTURE_SUBPROTOCOL},
                new DeviceConnection.ChannelListener() {
                    @Override public void onConnected(String id) {
                        connected.countDown();
                    }

                    @Override public void onData(String id, byte[] payload, boolean binary) {
                        inbox.offer(payload);
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

    /** 解析 result 头 + 分块 blob，重组为文件集。 */
    private AgentCaptureData readResponse(LinkedBlockingQueue<byte[]> inbox, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        JSONObject resultMeta = null;
        Map<String, java.io.ByteArrayOutputStream> buffers = new HashMap<>();
        Map<String, Boolean> finalReceived = new HashMap<>();
        List<String> expectedPaths = new ArrayList<>();
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
                    for (int i = 0; i < files.length(); i++) {
                        String path = files.optJSONObject(i).optString("path");
                        expectedPaths.add(path);
                        buffers.put(path, new java.io.ByteArrayOutputStream());
                        finalReceived.put(path, false);
                    }
                }
                if (expectedPaths.isEmpty()) {
                    break; // 无文件的 result（异常），直接返回
                }
            } else if ("blob".equals(op)) {
                String path = json.optString("path");
                java.io.ByteArrayOutputStream buf = buffers.get(path);
                if (buf == null) continue;
                String dataB64 = json.optString("data");
                if (dataB64 != null && !dataB64.isEmpty()) {
                    byte[] chunk = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT);
                    buf.write(chunk, 0, chunk.length);
                }
                if (json.optBoolean("final", false)) {
                    finalReceived.put(path, true);
                }
                if (allFinal(expectedPaths, finalReceived)) {
                    break;
                }
            } else if ("ack".equals(op)) {
                // finish/barrier 不应收到 ack；若收到且 ok=false 视为错误。
                if (!json.optBoolean("ok", true)) {
                    return AgentCaptureData.unavailable(json.optString("error", "agent_error"));
                }
            }
        }

        if (!sawResult) {
            return AgentCaptureData.unavailable("no_result");
        }
        List<AgentCaptureData.FileEntry> files = new ArrayList<>();
        for (String path : expectedPaths) {
            java.io.ByteArrayOutputStream buf = buffers.get(path);
            files.add(new AgentCaptureData.FileEntry(path, buf != null ? buf.toByteArray() : new byte[0]));
        }
        return new AgentCaptureData(true,
                resultMeta != null ? resultMeta.toString() : "", files, null);
    }

    private static boolean allFinal(List<String> paths, Map<String, Boolean> finalReceived) {
        for (String path : paths) {
            if (!Boolean.TRUE.equals(finalReceived.get(path))) return false;
        }
        return true;
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
