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
    private static final String BINARY_SUBPROTOCOL = "webterm.binary.v1";

    enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    private final OkHttpClient http;
    private final OkHttpClient terminalHttp;
    private final Handler mainHandler;
    private final Listener listener;

    private WebSocket webSocket;
    private MuxSession muxSession;
    private boolean relayDevice;
    private volatile State state = State.DISCONNECTED;
    private int socketGeneration;
    private int reconnectAttempts;

    private String baseUrl;
    private String cookie;
    private String sessionId;
    private long lastSeq;
    private int columns;
    private int rows;
    private int sentColumns;
    private int sentRows;

    private final Runnable reconnectRunnable = () -> {
        if (state == State.RECONNECTING) {
            connectNow();
        }
    };
    private final Runnable sendResizeRunnable = this::sendResizeNow;

    TerminalConnection(OkHttpClient http, Handler mainHandler, Listener listener) {
        this.http = http;
        this.terminalHttp = http.newBuilder()
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    State getState() {
        return state;
    }

    void connect(String baseUrl, String cookie, String sessionId, long lastSeq, boolean isRelayDevice) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.lastSeq = lastSeq;
        this.relayDevice = isRelayDevice;
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

    private void releaseSocket(String reason) {
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.close(1000, reason);
            } catch (Exception e) {
                Log.w(TAG, "Failed to close socket gracefully: " + e.getMessage(), e);
            }
        }
    }

    void close(String reason) {
        this.state = State.DISCONNECTED;
        this.socketGeneration++;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(sendResizeRunnable);
        if (relayDevice) {
            releaseSocket(reason);
        } else if (muxSession != null) {
            muxSession.sendWsClose(channelId());
            muxSession.stop();
            muxSession = null;
        }
    }

    boolean isConnected() {
        return state == State.CONNECTED;
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
        if (relayDevice) {
            connectRelayLegacy();
            return;
        }
        connectDirectMux();
    }

    private void connectRelayLegacy() {
        if (webSocket != null) {
            releaseSocket("reconnecting");
        }
        state = State.CONNECTING;
        listener.onConnectionStatus(state, reconnectAttempts);
        int generation = ++socketGeneration;
        String encodedId = WebTermUrls.encodePath(sessionId);
        Request request = new Request.Builder()
            .url(WebTermUrls.toWebSocketUrl(baseUrl) + "/ws/sessions/" + encodedId)
            .header("Cookie", cookie)
            .header("Sec-WebSocket-Protocol", BINARY_SUBPROTOCOL)
            .build();
        webSocket = terminalHttp.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (generation != socketGeneration) {
                    webSocket.close(1000, "stale socket");
                    return;
                }
                state = State.CONNECTED;
                reconnectAttempts = 0;
                sentColumns = 0;
                sentRows = 0;
                Log.i(TAG, "websocket open gen=" + generation + " code=" + response.code());
                listener.onConnectionStatus(state, reconnectAttempts);
                JSONObject hello = WebTermProtocol.put(WebTermProtocol.json(), "lastSeq", lastSeq);
                if (columns > 0 && rows > 0) {
                    WebTermProtocol.put(hello, "cols", columns);
                    WebTermProtocol.put(hello, "rows", rows);
                    sentColumns = columns;
                    sentRows = rows;
                }
                sendBinary(WebTermProtocol.MSG_HELLO, hello.toString().getBytes(StandardCharsets.UTF_8));
                sendResizeNow();
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                if (generation != socketGeneration) return;
                handleServerMessage(bytes.toByteArray());
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                mainHandler.post(() -> {
                    if (generation != socketGeneration) return;
                    if (TerminalConnection.this.webSocket == webSocket) {
                        releaseSocket("failed");
                    }
                    String reason = describeFailure(t, response);
                    Log.e(TAG, "websocket failure gen=" + generation + " reason=" + reason, t);
                    if (state != State.DISCONNECTED) scheduleReconnect(reason);
                });
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                mainHandler.post(() -> {
                    if (generation != socketGeneration) return;
                    if (TerminalConnection.this.webSocket == webSocket) {
                        releaseSocket("closed");
                    }
                    String description = "Connection closed: " + code + (reason.isEmpty() ? "" : " " + reason);
                    Log.w(TAG, "websocket closed gen=" + generation + " reason=" + description);
                    if (state != State.DISCONNECTED) scheduleReconnect(description);
                });
            }
        });
    }

    private void connectDirectMux() {
        if (muxSession == null || !muxSession.isConnected()) {
            if (muxSession != null) muxSession.stop();
            String wsUrl = WebTermUrls.toWebSocketUrl(baseUrl) + "/ws/sessions";
            muxSession = new MuxSession(http, mainHandler, wsUrl, cookie, new MuxSession.Listener() {
                @Override public void onMuxConnected() {
                    openTerminalChannel();
                }
                @Override public void onMuxDisconnected(String reason) {
                    if (state != State.DISCONNECTED) scheduleReconnect(reason);
                }
                @Override public void onTunnelConnected(String tunnelId) {
                    if (!tunnelId.equals(channelId())) return;
                    state = State.CONNECTED;
                    reconnectAttempts = 0;
                    sentColumns = 0;
                    sentRows = 0;
                    listener.onConnectionStatus(state, reconnectAttempts);
                    sendHello();
                    sendResizeNow();
                }
                @Override public void onTunnelError(String tunnelId, String message) {
                    listener.onProtocolError("tunnel error: " + message);
                }
                @Override public void onTunnelData(String tunnelId, byte[] payload, boolean binary) {
                    if (!tunnelId.equals(channelId())) return;
                    handleServerMessage(payload);
                }
            });
            muxSession.start();
        } else {
            openTerminalChannel();
        }
    }

    private String channelId() {
        return "term:" + sessionId;
    }

    private void openTerminalChannel() {
        if (muxSession == null || !muxSession.isConnected()) return;
        muxSession.sendWsConnect(channelId(), "/ws/sessions/" + WebTermUrls.encodePath(sessionId),
            new String[]{BINARY_SUBPROTOCOL});
    }

    private void sendHello() {
        JSONObject hello = WebTermProtocol.put(WebTermProtocol.json(), "lastSeq", lastSeq);
        if (columns > 0 && rows > 0) {
            WebTermProtocol.put(hello, "cols", columns);
            WebTermProtocol.put(hello, "rows", rows);
            sentColumns = columns;
            sentRows = rows;
        }
        sendBinary(WebTermProtocol.MSG_HELLO, hello.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void scheduleReconnect(String reason) {
        if (state == State.DISCONNECTED || sessionId == null || cookie == null || baseUrl == null) return;
        if (state == State.RECONNECTING) return;
        // mux path: reconnection is handled internally by MuxSession. Surface RECONNECTING
        // so the UI leaves CONNECTED, the status bar shows a reconnecting state, and user
        // input is explicitly dropped (sendBinary guards on state==CONNECTED) instead of
        // being silently swallowed while the physical socket is down.
        if (!relayDevice) {
            state = State.RECONNECTING;
            Log.i(TAG, "mux disconnected, awaiting MuxSession reconnect. Reason: " + reason);
            listener.onConnectionStatus(state, reconnectAttempts);
            return;
        }
        Log.i(TAG, "Connection lost, scheduling reconnect. Reason: " + reason);
        state = State.RECONNECTING;
        int attempt = ++reconnectAttempts;

        int shift = Math.min(attempt - 1, 15);
        long cap = Math.min(15000L, 1000L * (1L << shift));
        long delayMs = Math.max(200L, (long) (Math.random() * cap));

        listener.onConnectionStatus(state, reconnectAttempts);
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
        if (type == WebTermProtocol.MSG_STATE) {
            if (payload.length >= 8) {
                long seq = WebTermProtocol.readUint64(payload, 0);
                if (seq < lastSeq) return;
                lastSeq = seq;
                listener.onState(seq, Arrays.copyOfRange(payload, 8, payload.length));
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
        if (state != State.CONNECTED) return;
        if (relayDevice && webSocket == null) return;
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
        if (state == State.CONNECTED) {
            sentColumns = columns;
            sentRows = rows;
        }
    }

    private void sendBinary(byte type, byte[] payload) {
        if (relayDevice) {
            WebSocket ws = webSocket;
            if (ws == null || state != State.CONNECTED) return;
            ws.send(WebTermProtocol.frame(type, payload));
            return;
        }
        // direct mux: wrap in tunnel frame
        if (muxSession == null || !muxSession.isConnected() || state != State.CONNECTED) return;
        muxSession.sendTunnelFrame(channelId(), WebTermProtocol.frame(type, payload).toByteArray(), true);
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
        void onConnectionStatus(State state, int reconnectAttempts);
        void onOutput(long seq, byte[] data);
        void onState(long seq, byte[] data);
        void onInfo(JSONObject info);
        void onExit(int code);
        void onProtocolError(String message);
    }
}
