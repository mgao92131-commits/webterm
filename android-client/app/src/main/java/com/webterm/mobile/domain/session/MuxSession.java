package com.webterm.mobile.domain.session;

import android.os.Handler;
import android.util.Log;

import com.webterm.mobile.transport.WebRtcDataChannelTransport;
import com.webterm.mobile.transport.WebSocketMuxTransport;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

/**
 * 客户端角色 mux：一条 /ws/sessions 连接（webterm.mux.v1 子协议）复用多个终端通道。
 * 通道由 ws-connect 建立；数据经 tunnel frame 收发。第一阶段仅用于 direct 路径。
 */
public final class MuxSession {
    private static final String TAG = "MuxSession";
    private static final String MUX_SUBPROTOCOL = "webterm.mux.v1";

    interface Listener {
        void onMuxConnected();
        void onMuxDisconnected(String reason);
        void onTunnelConnected(String tunnelId);
        void onTunnelError(String tunnelId, String message);
        void onTunnelData(String tunnelId, byte[] payload, boolean binary);

        /** 物理连接每次自动重连尝试时触发，attempt 从 1 起递增。 */
        default void onReconnectAttempt(int attempt) {}
    }

    private final Handler mainHandler;
    private final MuxTransport transport;
    private final Listener listener;

    private volatile boolean connected;
    private volatile boolean enabled;
    private int reconnectAttempts;

    // 待发 ws-connect 后等待 ws-connected 的回调登记（仅记录已发 connect 的 tunnelId）。
    private final Map<String, Boolean> pendingConnects = new HashMap<>();

    @Deprecated
    MuxSession(OkHttpClient http, Handler mainHandler, String wsUrl, String cookie, Listener listener) {
        this(new WebSocketMuxTransport(http, wsUrl, cookie, MUX_SUBPROTOCOL), mainHandler, listener);
    }

    MuxSession(MuxTransport transport, Handler mainHandler, Listener listener) {
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
        if (connected) return;
        connectNow();
    }

    void stop() {
        enabled = false;
        connected = false;
        if (transport != null) transport.close();
    }

    boolean isConnected() {
        return connected;
    }

    boolean isP2PTransport() {
        return transport instanceof WebRtcDataChannelTransport;
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

    private boolean sendText(String text) {
        if (transport == null || !connected) return false;
        return transport.sendText(text);
    }

    private void connectNow() {
        transport.start(new MuxTransport.Listener() {
            @Override
            public void onOpen() {
                if (!enabled) {
                    transport.close();
                    return;
                }
                connected = true;
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
                    connected = false;
                    Log.e(TAG, "mux failure: " + message);
                    listener.onMuxDisconnected(message);
                    scheduleReconnect();
                });
            }

            @Override
            public void onClosed(String reason) {
                mainHandler.post(() -> {
                    if (!enabled) return;
                    connected = false;
                    listener.onMuxDisconnected(reason);
                    scheduleReconnect();
                });
            }
        });
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
            String message = msg.optString("message");
            mainHandler.post(() -> listener.onTunnelError(tunnelId, message));
        }
        // 其它控制消息（服务端角色不发送 ws-connect）忽略。
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
