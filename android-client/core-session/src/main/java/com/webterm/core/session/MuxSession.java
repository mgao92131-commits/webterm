package com.webterm.core.session;

import android.os.Handler;
import android.util.Log;

import com.webterm.transport.api.MuxTransport;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端角色 mux：一条 /ws/sessions 连接（webterm.mux.v1 子协议）复用多个终端通道。
 * 通道由 ws-connect 建立；数据经 tunnel frame 收发。第一阶段仅用于 direct 路径。
 */
public final class MuxSession {
    private static final String TAG = "MuxSession";
    private static final long CONNECT_TIMEOUT_MS = 10000L;

    interface Listener {
        void onMuxConnected();
        void onMuxDisconnected(String reason);
        void onTunnelConnected(String tunnelId);
        void onTunnelError(String tunnelId, int code, String message);
        void onTunnelData(String tunnelId, byte[] payload, boolean binary);
        void onTunnelClosed(String tunnelId, int code, String reason);

        /** 物理连接每次自动重连尝试时触发，attempt 从 1 起递增。 */
        default void onReconnectAttempt(int attempt) {}

        /** 设备级文本控制消息（如 file_send.*、agent_notification），不走虚拟通道。 */
        default void onControlMessage(JSONObject msg) {}
    }

    private final Handler mainHandler;
    private final MuxTransport transport;
    private final Listener listener;

    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean enabled;
    private int reconnectAttempts;
    private final Runnable connectTimeoutRunnable = this::onConnectTimeout;

    // 待发 ws-connect 后等待 ws-connected 的回调登记（仅记录已发 connect 的 tunnelId）。
    private final Map<String, Boolean> pendingConnects = new HashMap<>();

    public MuxSession(MuxTransport transport, Handler mainHandler, Listener listener) {
        this.mainHandler = mainHandler;
        this.transport = transport;
        this.listener = listener;
    }

    void start() {
        if (transport == null) {
            stop();
            return;
        }
        enabled = true;
        if (connected && transport.isConnected()) return;
        if (connecting) return;
        connectNow();
    }

    void stop() {
        enabled = false;
        connected = false;
        connecting = false;
        mainHandler.removeCallbacks(connectTimeoutRunnable);
        if (transport != null) transport.close();
    }

    boolean isConnected() {
        return connected;
    }

    boolean sendWsConnect(String tunnelId, String path, String[] protocols) {
        if (!connected) return false;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "ws-connect");
            msg.put("tunnelConnectionId", tunnelId);
            msg.put("path", path);
            if (protocols != null && protocols.length > 0) {
                JSONArray arr = new JSONArray();
                for (String p : protocols) arr.put(p);
                msg.put("protocols", arr);
            }
        } catch (JSONException ignored) {
            return false;
        }
        synchronized (pendingConnects) {
            pendingConnects.put(tunnelId, true);
        }
        return sendText(msg.toString());
    }

    boolean sendWsClose(String tunnelId) {
        if (!connected) return false;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "ws-close");
            msg.put("tunnelConnectionId", tunnelId);
        } catch (JSONException ignored) {
            return false;
        }
        return sendText(msg.toString());
    }

    boolean sendTunnelFrame(String tunnelId, byte[] payload, boolean binary) {
        if (!connected) return false;
        return transport.sendBinary(WebTermProtocol.encodeTunnelFrame(tunnelId, payload, binary));
    }

    boolean sendTunnelFrameText(String tunnelId, String text) {
        return sendTunnelFrame(tunnelId, text.getBytes(StandardCharsets.UTF_8), false);
    }

    /** 发送设备级文本控制消息（如 file_send.*、agent_notification.ack）。 */
    public boolean sendControl(JSONObject msg) {
        if (msg == null || !connected) return false;
        return sendText(msg.toString());
    }

    private boolean sendText(String text) {
        if (transport == null || !connected) return false;
        return transport.sendText(text);
    }

    private void connectNow() {
        connecting = true;
        mainHandler.removeCallbacks(connectTimeoutRunnable);
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);
        transport.start(new MuxTransport.Listener() {
            @Override
            public void onOpen() {
                if (!enabled) {
                    transport.close();
                    return;
                }
                mainHandler.removeCallbacks(connectTimeoutRunnable);
                connected = true;
                connecting = false;
                reconnectAttempts = 0;
                Log.i(TAG, "mux open");
                mainHandler.post(() -> listener.onMuxConnected());
            }

            @Override
            public void onText(String text) {
                if (!enabled) return;
                handleControlMessage(text);
            }

            @Override
            public void onBinary(byte[] data) {
                if (!enabled) return;
                mainHandler.post(() -> dispatchBinaryFrame(data, listener));
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    if (!enabled) return;
                    mainHandler.removeCallbacks(connectTimeoutRunnable);
                    connected = false;
                    connecting = false;
                    Log.e(TAG, "mux failure: " + message);
                    listener.onMuxDisconnected(message);
                    scheduleReconnect();
                });
            }

            @Override
            public void onClosed(int code, String reason) {
                mainHandler.post(() -> {
                    if (!enabled) return;
                    mainHandler.removeCallbacks(connectTimeoutRunnable);
                    connected = false;
                    connecting = false;
                    listener.onMuxDisconnected(reason + " (" + code + ")");
                    scheduleReconnect();
                });
            }
        });
    }

    private void onConnectTimeout() {
        if (!enabled || !connecting || connected) return;
        connecting = false;
        connected = false;
        if (transport != null) {
            transport.close();
        }
        listener.onMuxDisconnected("connect timeout");
        scheduleReconnect();
    }

    private void handleControlMessage(String text) {
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (JSONException e) {
            return;
        }
        String type = msg.optString("type");
        String tunnelId = msg.optString("tunnelConnectionId");
        if ("ws-connected".equals(type)) {
            synchronized (pendingConnects) {
                pendingConnects.remove(tunnelId);
            }
            mainHandler.post(() -> listener.onTunnelConnected(tunnelId));
        } else if ("ws-error".equals(type)) {
            synchronized (pendingConnects) {
                pendingConnects.remove(tunnelId);
            }
            int code = msg.optInt("code", 0);
            String message = msg.optString("message", "");
            mainHandler.post(() -> listener.onTunnelError(tunnelId, code, message));
        } else if ("ws-close".equals(type)) {
            synchronized (pendingConnects) {
                pendingConnects.remove(tunnelId);
            }
            int code = msg.optInt("code", 1000);
            String reason = msg.optString("reason", "");
            mainHandler.post(() -> listener.onTunnelClosed(tunnelId, code, reason));
        } else if (type != null && !type.isEmpty()) {
            // 设备级控制消息（file_send.*、agent_notification 等）交给上层处理。
            mainHandler.post(() -> listener.onControlMessage(msg));
        }
    }

    // dispatchBinaryFrame 是纯方法，单测覆盖（见 MuxSessionRoutingTest）。
    static void dispatchBinaryFrame(byte[] data, Listener listener) {
        WebTermProtocol.TunnelFrame frame = WebTermProtocol.decodeTunnelFrame(data);
        if (frame == null) return;
        boolean binary = (frame.extraByte & 0xff) == WebTermProtocol.WS_DATA_BINARY;
        listener.onTunnelData(frame.tunnelId, frame.payload, binary);
    }

    private void scheduleReconnect() {
        if (!enabled) return;
        int attempt = ++reconnectAttempts;
        mainHandler.post(() -> listener.onReconnectAttempt(attempt));
        long cap = Math.min(1000L * attempt, 8000L);
        long delayMs = Math.max(200L, (long) (Math.random() * cap));
        mainHandler.postDelayed(() -> {
            if (enabled && !transport.isConnected()) connectNow();
        }, delayMs);
    }
}
