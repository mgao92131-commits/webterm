package com.webterm.mobile;

import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 客户端角色 mux：一条 /ws/sessions 连接（webterm.mux.v1 子协议）复用多个终端通道。
 * 通道由 ws-connect 建立；数据经 tunnel frame 收发。第一阶段仅用于 direct 路径。
 */
final class MuxSession {
    private static final String TAG = "MuxSession";
    private static final String MUX_SUBPROTOCOL = "webterm.mux.v1";

    interface Listener {
        void onMuxConnected();
        void onMuxDisconnected(String reason);
        void onTunnelConnected(String tunnelId);
        void onTunnelError(String tunnelId, String message);
        void onTunnelData(String tunnelId, byte[] payload, boolean binary);
    }

    private final OkHttpClient http;
    private final OkHttpClient muxHttp;
    private final Handler mainHandler;
    private final String wsUrl;
    private final String cookie;
    private final Listener listener;

    private WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean enabled;
    private int reconnectAttempts;

    // 待发 ws-connect 后等待 ws-connected 的回调登记（仅记录已发 connect 的 tunnelId）。
    private final Map<String, Boolean> pendingConnects = new HashMap<>();

    MuxSession(OkHttpClient http, Handler mainHandler, String wsUrl, String cookie, Listener listener) {
        this.http = http;
        this.muxHttp = http.newBuilder()
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        this.mainHandler = mainHandler;
        this.wsUrl = wsUrl;
        this.cookie = cookie;
        this.listener = listener;
    }

    void start() {
        if (wsUrl == null || wsUrl.isEmpty()) {
            stop();
            return;
        }
        enabled = true;
        if (webSocket != null) return;
        connectNow();
    }

    void stop() {
        enabled = false;
        connected = false;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try { ws.close(1000, "closing mux"); } catch (Exception ignored) {}
        }
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
        WebSocket ws = webSocket;
        if (ws == null) return false;
        return ws.send(okio.ByteString.of(WebTermProtocol.encodeTunnelFrame(tunnelId, payload, binary)));
    }

    boolean sendTunnelFrameText(String tunnelId, String text) {
        return sendTunnelFrame(tunnelId, text.getBytes(StandardCharsets.UTF_8), false);
    }

    private boolean sendText(String text) {
        WebSocket ws = webSocket;
        if (ws == null || !connected) return false;
        return ws.send(text);
    }

    private void connectNow() {
        Request request = new Request.Builder()
            .url(wsUrl)
            .header("Cookie", cookie != null ? cookie : "")
            .header("Sec-WebSocket-Protocol", MUX_SUBPROTOCOL)
            .build();
        webSocket = muxHttp.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (!enabled) {
                    webSocket.close(1000, "stale mux socket");
                    return;
                }
                connected = true;
                reconnectAttempts = 0;
                Log.i(TAG, "mux open");
                mainHandler.post(() -> listener.onMuxConnected());
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (!enabled) return;
                handleControlMessage(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull okio.ByteString bytes) {
                if (!enabled) return;
                byte[] data = bytes.toByteArray();
                mainHandler.post(() -> dispatchBinaryFrame(data, listener));
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                mainHandler.post(() -> {
                    if (!enabled) return;
                    connected = false;
                    MuxSession.this.webSocket = null;
                    Log.e(TAG, "mux failure: " + t.getMessage(), t);
                    listener.onMuxDisconnected(t.getMessage());
                    scheduleReconnect();
                });
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                mainHandler.post(() -> {
                    if (!enabled) return;
                    connected = false;
                    MuxSession.this.webSocket = null;
                    listener.onMuxDisconnected("closed: " + code);
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
        long cap = Math.min(1000L * attempt, 8000L);
        long delayMs = Math.max(200L, (long) (Math.random() * cap));
        mainHandler.postDelayed(() -> {
            if (enabled && webSocket == null) connectNow();
        }, delayMs);
    }
}
