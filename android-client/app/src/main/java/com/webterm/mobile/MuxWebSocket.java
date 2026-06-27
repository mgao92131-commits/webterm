package com.webterm.mobile;

import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Multiplexed WebSocket client — mirrors Go direct server's mux.ConnectionTransport.
 *
 * Physical connection: /ws/sessions
 * Control plane: text JSON frames (ws-connect, ws-connected, ws-close)
 * Data plane: binary tunnel frames  [0x01][idLen(1B)][id(N)][extraByte(1B)][payload]
 *
 * Virtual channels:
 *   - "manager" (auto-created by server) → ManagerListener.onSessions(...)
 *   - user-created (via ws-connect)       → ChannelCallback
 *
 * ChannelInfo stores per-channel metadata so that on physical reconnect,
 * all registered channels are automatically re-established via ws-connect.
 */
final class MuxWebSocket {

    private static final String TAG = "MuxWebSocket";
    private static final long RECONNECT_BASE_DELAY_MS = 200L;
    private static final long RECONNECT_CAP_MS = 15000L;

    private static final byte MSG_TYPE_WS_DATA = 0x01;
    private static final byte WS_DATA_TEXT = 0x01;
    private static final byte WS_DATA_BINARY = 0x02;

    enum State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    // ── Channel callback interface ──────────────────────────────────

    interface ChannelCallback {
        void onConnected();
        void onBinaryMessage(byte[] data);
        void onTextMessage(String text);
        void onClosed(int code, String reason);
    }

    // ── Manager listener ────────────────────────────────────────────

    interface ManagerListener {
        void onSessions(JSONObject message);
    }

    // ── State listener ──────────────────────────────────────────────

    interface StateListener {
        void onStateChanged(State state, int reconnectAttempts);
    }

    // ── Channel metadata (survives reconnects) ──────────────────────

    private static class ChannelInfo {
        final String id;
        final String path;
        final String[] protocols;
        final ChannelCallback callback;
        boolean connected;

        ChannelInfo(String id, String path, String[] protocols, ChannelCallback callback) {
            this.id = id;
            this.path = path;
            this.protocols = protocols;
            this.callback = callback;
        }
    }

    // ── Instance fields ─────────────────────────────────────────────

    private final OkHttpClient http;
    private final OkHttpClient wsHttp;
    private final Handler mainHandler;
    private final Map<String, ChannelInfo> channels = new HashMap<>();

    private WebSocket physicalSocket;
    private volatile State state = State.DISCONNECTED;
    private int socketGeneration;
    private int reconnectAttempts;

    private String baseUrl;
    private String cookie;

    private StateListener stateListener;
    private ManagerListener managerListener;

    private final Runnable reconnectRunnable = this::connectNow;

    // ── Constructor ─────────────────────────────────────────────────

    MuxWebSocket(OkHttpClient http, Handler mainHandler) {
        this.http = http;
        this.wsHttp = http.newBuilder()
                .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.mainHandler = mainHandler;
    }

    // ── Public API ──────────────────────────────────────────────────

    void setStateListener(StateListener listener) { this.stateListener = listener; }
    void setManagerListener(ManagerListener listener) { this.managerListener = listener; }
    State getState() { return state; }

    void connect(String baseUrl, String cookie) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        connectNow();
    }

    void reconnectNow() {
        mainHandler.removeCallbacks(reconnectRunnable);
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        connectNow();
    }

    void openChannel(String id, String path, String[] protocols, ChannelCallback callback) {
        ChannelInfo info = new ChannelInfo(id, path, protocols, callback);
        channels.put(id, info);

        // If physical socket is disconnected and we now have a reason to connect, do so.
        if (state == State.DISCONNECTED && baseUrl != null && cookie != null) {
            connectNow();
            return;
        }

        // If already connected, send ws-connect immediately
        if (state == State.CONNECTED && physicalSocket != null) {
            sendWSConnect(physicalSocket, id, path, protocols);
        }
        // Otherwise, ws-connect will be sent in onOpen after physical connect
    }

    void sendBinary(String id, byte[] data) {
        byte[] frame = encodeTunnelFrame(id, WS_DATA_BINARY, data);
        WebSocket ws = physicalSocket;
        if (ws != null && state == State.CONNECTED) {
            ws.send(ByteString.of(frame));
        }
    }

    void sendText(String id, String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] frame = encodeTunnelFrame(id, WS_DATA_TEXT, payload);
        WebSocket ws = physicalSocket;
        if (ws != null && state == State.CONNECTED) {
            ws.send(ByteString.of(frame));
        }
    }

    void closeChannel(String id) {
        channels.remove(id);
        WebSocket ws = physicalSocket;
        if (ws != null && state == State.CONNECTED) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "ws-close");
                msg.put("tunnelConnectionId", id);
                ws.send(msg.toString());
            } catch (JSONException e) {
                Log.w(TAG, "Failed to send ws-close", e);
            }
        }
    }

    void close(String reason) {
        this.state = State.DISCONNECTED;
        this.socketGeneration++;
        mainHandler.removeCallbacks(reconnectRunnable);
        for (ChannelInfo info : new ArrayList<>(channels.values())) {
            try { info.callback.onClosed(1000, reason); } catch (Exception ignored) {}
        }
        channels.clear();
        releaseSocket(reason);
    }

    // ── Internal connection ─────────────────────────────────────────

    private void connectNow() {
        if (baseUrl == null || cookie == null) return;
        releaseSocket("reconnecting");

        state = State.CONNECTING;
        if (stateListener != null) stateListener.onStateChanged(state, reconnectAttempts);

        int generation = ++socketGeneration;

        Request request = new Request.Builder()
                .url(WebTermUrls.toWebSocketUrl(baseUrl) + "/ws/sessions")
                .header("Cookie", cookie)
                .build();

        physicalSocket = wsHttp.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (generation != socketGeneration) {
                    webSocket.close(1000, "stale socket");
                    return;
                }
                Log.i(TAG, "physical socket open gen=" + generation + " code=" + response.code());

                mainHandler.post(() -> {
                    if (generation != socketGeneration) return;
                    state = State.CONNECTED;
                    reconnectAttempts = 0;
                    if (stateListener != null) stateListener.onStateChanged(state, reconnectAttempts);

                    // Re-establish all registered channels on reconnect.
                    // Manager channel ("manager") is auto-created by the server —
                    // no ws-connect needed. All other channels need a fresh ws-connect.
                    for (ChannelInfo info : new ArrayList<>(channels.values())) {
                        info.connected = false;
                        if (!"manager".equals(info.id)) {
                            sendWSConnect(webSocket, info.id, info.path, info.protocols);
                        }
                    }
                });
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (generation != socketGeneration) return;
                handleControlMessage(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                if (generation != socketGeneration) return;
                handleBinaryFrame(bytes.toByteArray());
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t,
                                  @Nullable Response response) {
                mainHandler.post(() -> {
                    if (generation != socketGeneration) return;
                    if (MuxWebSocket.this.physicalSocket == webSocket) {
                        releaseSocket("failed");
                    }
                    String reason = t.getClass().getSimpleName()
                            + (t.getMessage() != null ? ": " + t.getMessage() : "");
                    Log.e(TAG, "socket failure gen=" + generation + " " + reason, t);
                    // Notify all channels immediately — don't wait for onClosed
                    // which may be delayed or never arrive.
                    for (ChannelInfo info : new ArrayList<>(channels.values())) {
                        try { info.callback.onClosed(1006, reason); } catch (Exception ignored) {}
                    }
                    if (state != State.DISCONNECTED) scheduleReconnect(reason);
                });
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                mainHandler.post(() -> {
                    if (generation != socketGeneration) return;
                    if (MuxWebSocket.this.physicalSocket == webSocket) {
                        releaseSocket("closed");
                    }
                    Log.w(TAG, "socket closed gen=" + generation + " code=" + code + " " + reason);
                    // Notify all channels of physical disconnection
                    for (ChannelInfo info : new ArrayList<>(channels.values())) {
                        try { info.callback.onClosed(code, reason); } catch (Exception ignored) {}
                    }
                    if (state != State.DISCONNECTED) scheduleReconnect(reason);
                });
            }
        });
    }

    private void handleControlMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            String type = json.optString("type", "");

            if ("ws-connected".equals(type)) {
                String id = json.optString("tunnelConnectionId", "");
                ChannelInfo info = channels.get(id);
                if (info != null) {
                    info.connected = true;
                    info.callback.onConnected();
                }
                return;
            }

            if ("ws-error".equals(type)) {
                String id = json.optString("tunnelConnectionId", "");
                ChannelInfo info = channels.get(id);
                if (info != null) {
                    channels.remove(id);
                    info.callback.onClosed(json.optInt("code", 4500),
                            json.optString("message", "tunnel error"));
                }
                return;
            }

            Log.d(TAG, "unhandled control message: " + type);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse control message", e);
        }
    }

    private void handleBinaryFrame(byte[] data) {
        TunnelFrame tf = decodeTunnelFrame(data);
        if (tf == null) return;

        // Manager channel: route to manager listener
        if ("manager".equals(tf.id) && tf.extraByte == WS_DATA_TEXT && managerListener != null) {
            try {
                JSONObject msg = new JSONObject(new String(tf.payload, StandardCharsets.UTF_8));
                managerListener.onSessions(msg);
            } catch (JSONException e) {
                Log.w(TAG, "Failed to parse manager message", e);
            }
            return;
        }

        ChannelInfo info = channels.get(tf.id);
        if (info == null) return;

        if (tf.extraByte == WS_DATA_BINARY) {
            info.callback.onBinaryMessage(tf.payload);
        } else if (tf.extraByte == WS_DATA_TEXT) {
            info.callback.onTextMessage(new String(tf.payload, StandardCharsets.UTF_8));
        }
    }

    // ── Reconnect ───────────────────────────────────────────────────

    private void scheduleReconnect(String reason) {
        if (state == State.DISCONNECTED || baseUrl == null || cookie == null) return;
        if (state == State.RECONNECTING) return;
        // Don't waste battery reconnecting when no active channels need it.
        // openChannel() will reconnect when a channel is registered.
        if (channels.isEmpty()) {
            Log.i(TAG, "No active channels, skipping reconnect");
            return;
        }
        Log.i(TAG, "Connection lost, scheduling reconnect. Reason: " + reason);
        state = State.RECONNECTING;
        int attempt = ++reconnectAttempts;

        int shift = Math.min(attempt - 1, 15);
        long cap = Math.min(RECONNECT_CAP_MS, 1000L * (1L << shift));
        long delayMs = Math.max(RECONNECT_BASE_DELAY_MS, (long) (Math.random() * cap));

        if (stateListener != null) stateListener.onStateChanged(state, reconnectAttempts);
        mainHandler.postDelayed(reconnectRunnable, delayMs);
    }

    // ── Socket cleanup ──────────────────────────────────────────────

    private void releaseSocket(String reason) {
        WebSocket ws = physicalSocket;
        physicalSocket = null;
        if (ws != null) {
            try {
                ws.close(1000, reason);
            } catch (Exception e) {
                Log.w(TAG, "Failed to close socket: " + e.getMessage(), e);
            }
        }
    }

    // ── Tunnel frame encode/decode ──────────────────────────────────

    private byte[] encodeTunnelFrame(String id, byte extraByte, byte[] payload) {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        int idLen = idBytes.length;
        byte[] frame = new byte[1 + 1 + idLen + 1 + payload.length];
        frame[0] = MSG_TYPE_WS_DATA;
        frame[1] = (byte) idLen;
        System.arraycopy(idBytes, 0, frame, 2, idLen);
        frame[2 + idLen] = extraByte;
        System.arraycopy(payload, 0, frame, 3 + idLen, payload.length);
        return frame;
    }

    private TunnelFrame decodeTunnelFrame(byte[] data) {
        if (data.length < 3) return null;
        if (data[0] != MSG_TYPE_WS_DATA) return null;
        int idLen = data[1] & 0xFF;
        if (data.length < 3 + idLen) return null;
        String id = new String(data, 2, idLen, StandardCharsets.UTF_8);
        byte extraByte = data[2 + idLen];
        int payloadLen = data.length - (3 + idLen);
        byte[] payload = new byte[payloadLen];
        System.arraycopy(data, 3 + idLen, payload, 0, payloadLen);
        return new TunnelFrame(id, extraByte, payload);
    }

    private static class TunnelFrame {
        final String id;
        final byte extraByte;
        final byte[] payload;

        TunnelFrame(String id, byte extraByte, byte[] payload) {
            this.id = id;
            this.extraByte = extraByte;
            this.payload = payload;
        }
    }

    // ── ws-connect helper ───────────────────────────────────────────

    private void sendWSConnect(WebSocket ws, String id, String path, String[] protocols) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "ws-connect");
            msg.put("tunnelConnectionId", id);
            msg.put("path", path);
            JSONArray arr = new JSONArray();
            if (protocols != null) {
                for (String p : protocols) arr.put(p);
            }
            msg.put("protocols", arr);
            ws.send(msg.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create ws-connect message", e);
        }
    }
}
