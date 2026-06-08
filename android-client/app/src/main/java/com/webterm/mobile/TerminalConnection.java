package com.webterm.mobile;

import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

final class TerminalConnection {
    private static final String TAG = "TerminalConnection";
    private static final long RESIZE_DEBOUNCE_MS = 100L;

    private final OkHttpClient http;
    private final Handler mainHandler;
    private final Listener listener;

    private WebSocket webSocket;
    private boolean connected;
    private int socketGeneration;
    private int reconnectAttempts;
    private boolean reconnectScheduled;
    private boolean closed = true;

    private String baseUrl;
    private String cookie;
    private String sessionId;
    private long lastSeq;
    private int columns;
    private int rows;
    private int sentColumns;
    private int sentRows;

    private final Runnable reconnectRunnable = () -> {
        reconnectScheduled = false;
        if (!closed && !connected) connectNow();
    };
    private final Runnable sendResizeRunnable = this::sendResizeNow;

    TerminalConnection(OkHttpClient http, Handler mainHandler, Listener listener) {
        this.http = http;
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    void connect(String baseUrl, String cookie, String sessionId, long lastSeq) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.lastSeq = lastSeq;
        closed = false;
        reconnectAttempts = 0;
        reconnectScheduled = false;
        connectNow();
    }

    void reconnectNow() {
        mainHandler.removeCallbacks(reconnectRunnable);
        reconnectScheduled = false;
        closed = false;
        reconnectAttempts = 0;
        connectNow();
    }

    void close(String reason) {
        connected = false;
        reconnectScheduled = false;
        closed = true;
        socketGeneration++;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(sendResizeRunnable);
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            ws.close(1000, reason);
        }
    }

    boolean hasSocket() {
        return webSocket != null;
    }

    boolean isConnected() {
        return connected;
    }

    void updateLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }

    void updateSize(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        scheduleResize();
    }

    void sendInput(String data) {
        sendBinary(WebTermProtocol.MSG_INPUT, data.getBytes(StandardCharsets.UTF_8));
    }

    void sendTitle(String title) {
        sendBinary(WebTermProtocol.MSG_TITLE, title.getBytes(StandardCharsets.UTF_8));
    }

    private void connectNow() {
        if (sessionId == null || baseUrl == null || cookie == null) return;
        if (webSocket != null) {
            close("reconnecting");
            closed = false;
        }
        listener.onConnectionStatus("Connecting", false);
        reconnectScheduled = false;
        int generation = ++socketGeneration;
        String encodedId = WebTermUrls.encodePath(sessionId);
        Request request = new Request.Builder()
            .url(WebTermUrls.toWebSocketUrl(baseUrl) + "/ws/sessions/" + encodedId)
            .header("Cookie", cookie)
            .build();
        webSocket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (generation != socketGeneration) {
                    webSocket.close(1000, "stale socket");
                    return;
                }
                connected = true;
                reconnectScheduled = false;
                sentColumns = 0;
                sentRows = 0;
                Log.i(TAG, "websocket open gen=" + generation + " code=" + response.code());
                listener.onConnectionStatus("Connected", true);
                sendResizeNow();
                JSONObject hello = WebTermProtocol.put(WebTermProtocol.json(), "lastSeq", lastSeq);
                sendBinary(WebTermProtocol.MSG_HELLO, hello.toString().getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                if (generation != socketGeneration) return;
                handleServerMessage(bytes.toByteArray());
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                if (generation != socketGeneration) return;
                connected = false;
                String reason = describeFailure(t, response);
                Log.e(TAG, "websocket failure gen=" + generation + " reason=" + reason, t);
                if (!closed) scheduleReconnect(reason);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                if (generation != socketGeneration) return;
                connected = false;
                String description = "Connection closed: " + code + (reason.isEmpty() ? "" : " " + reason);
                Log.w(TAG, "websocket closed gen=" + generation + " reason=" + description);
                if (!closed) scheduleReconnect(description);
            }
        });
    }

    private void scheduleReconnect(String reason) {
        if (closed || sessionId == null || cookie == null || baseUrl == null) return;
        if (reconnectScheduled) return;
        int attempt = ++reconnectAttempts;
        if (attempt > 8) {
            listener.onConnectionStatus("Failed: " + reason, false);
            return;
        }
        long delayMs = Math.min(1000L * attempt, 5000L);
        reconnectScheduled = true;
        listener.onConnectionStatus("Disconnected; reconnecting in " + delayMs + "ms", false);
        mainHandler.postDelayed(reconnectRunnable, delayMs);
    }

    private void handleServerMessage(byte[] frame) {
        if (frame.length == 0) return;
        byte type = frame[0];
        byte[] payload = Arrays.copyOfRange(frame, 1, frame.length);
        if (type == WebTermProtocol.MSG_OUTPUT) {
            if (payload.length >= 8) {
                long seq = WebTermProtocol.readUint64(payload, 0);
                if (seq <= lastSeq) return;
                lastSeq = seq;
                listener.onOutput(seq, Arrays.copyOfRange(payload, 8, payload.length));
            } else {
                listener.onOutput(0, payload);
            }
            return;
        }
        if (type == WebTermProtocol.MSG_INFO) {
            try {
                listener.onInfo(WebTermProtocol.controlPayload(payload));
            } catch (JSONException ignored) {
            }
            return;
        }
        if (type == WebTermProtocol.MSG_PONG) {
            return;
        }
        try {
            JSONObject msg = WebTermProtocol.controlPayload(payload);
            if (type == WebTermProtocol.MSG_EXIT) {
                listener.onExit(msg.optInt("code", 0));
            }
        } catch (JSONException e) {
            listener.onProtocolError("Bad message: " + e.getMessage());
        }
    }

    private void scheduleResize() {
        mainHandler.removeCallbacks(sendResizeRunnable);
        if (webSocket == null || !connected) return;
        mainHandler.postDelayed(sendResizeRunnable, RESIZE_DEBOUNCE_MS);
    }

    private void sendResizeNow() {
        mainHandler.removeCallbacks(sendResizeRunnable);
        if (columns <= 0 || rows <= 0) return;
        if (columns == sentColumns && rows == sentRows) return;
        JSONObject resize = WebTermProtocol.json();
        WebTermProtocol.put(resize, "cols", columns);
        WebTermProtocol.put(resize, "rows", rows);
        sendBinary(WebTermProtocol.MSG_RESIZE, resize.toString().getBytes(StandardCharsets.UTF_8));
        if (webSocket != null && connected) {
            sentColumns = columns;
            sentRows = rows;
        }
    }

    private void sendBinary(byte type, byte[] payload) {
        WebSocket ws = webSocket;
        if (ws == null || !connected) return;
        ws.send(WebTermProtocol.frame(type, payload));
    }

    private String describeFailure(Throwable t, @Nullable Response response) {
        StringBuilder message = new StringBuilder();
        message.append(t.getClass().getSimpleName());
        if (t.getMessage() != null && !t.getMessage().trim().isEmpty()) {
            message.append(": ").append(t.getMessage().trim());
        }
        if (response != null) {
            message.append(" (HTTP ").append(response.code()).append(")");
        }
        return message.toString();
    }

    interface Listener {
        void onConnectionStatus(String text, boolean connected);
        void onOutput(long seq, byte[] data);
        void onInfo(JSONObject info);
        void onExit(int code);
        void onProtocolError(String message);
    }
}
