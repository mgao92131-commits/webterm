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
import okio.ByteString;

final class TerminalConnection {
    private static final String TAG = "TerminalConnection";
    private static final long RESIZE_DEBOUNCE_MS = 100L;
    private static final String CELL_SUBPROTOCOL = "webterm.cell.v1";

    enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    private final OkHttpClient http;
    private final Handler mainHandler;
    private final Listener listener;
    private final MuxWebSocket muxSocket;

    private volatile State state = State.DISCONNECTED;
    private int reconnectAttempts;

    private String baseUrl;
    private String cookie;
    private String sessionId;
    private String channelId;
    private long lastSeq;
    private int columns;
    private int rows;
    private int sentColumns;
    private int sentRows;

    private final Runnable reconnectRunnable = this::connectNow;
    private final Runnable sendResizeRunnable = this::sendResizeNow;

    TerminalConnection(OkHttpClient http, Handler mainHandler, Listener listener,
                       MuxWebSocket muxSocket) {
        this.http = http;
        this.mainHandler = mainHandler;
        this.listener = listener;
        this.muxSocket = muxSocket;
    }

    State getState() {
        return state;
    }

    void connect(String baseUrl, String cookie, String sessionId, long lastSeq) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.channelId = "terminal-" + sessionId;
        this.lastSeq = lastSeq;
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        listener.onConnectionStatus(state, reconnectAttempts);

        // Ensure physical MuxWebSocket is connected before opening channels
        if (muxSocket.getState() == MuxWebSocket.State.DISCONNECTED) {
            muxSocket.connect(baseUrl, cookie);
        }
        connectNow();
    }

    void reconnectNow() {
        mainHandler.removeCallbacks(reconnectRunnable);
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        listener.onConnectionStatus(state, reconnectAttempts);
        connectNow();
    }

    void close(String reason) {
        this.state = State.DISCONNECTED;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(sendResizeRunnable);
        if (channelId != null) {
            muxSocket.closeChannel(channelId);
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
        if (sessionId == null || baseUrl == null || cookie == null || channelId == null) return;

        state = State.CONNECTING;
        listener.onConnectionStatus(state, reconnectAttempts);

        muxSocket.openChannel(channelId, "/ws/sessions/" + sessionId,
                new String[]{CELL_SUBPROTOCOL},
                new MuxWebSocket.ChannelCallback() {

                    @Override
                    public void onConnected() {
                        state = State.CONNECTED;
                        reconnectAttempts = 0;
                        sentColumns = 0;
                        sentRows = 0;
                        Log.i(TAG, "tunnel connected channel=" + channelId);
                        listener.onConnectionStatus(state, reconnectAttempts);

                        JSONObject hello = WebTermProtocol.put(WebTermProtocol.json(), "lastSeq", lastSeq);
                        if (columns > 0 && rows > 0) {
                            WebTermProtocol.put(hello, "cols", columns);
                            WebTermProtocol.put(hello, "rows", rows);
                            sentColumns = columns;
                            sentRows = rows;
                        }
                        sendBinary(WebTermProtocol.MSG_HELLO,
                                hello.toString().getBytes(StandardCharsets.UTF_8));
                        sendResizeNow();
                    }

                    @Override
                    public void onBinaryMessage(byte[] data) {
                        handleServerMessage(data);
                    }

                    @Override
                    public void onTextMessage(String text) {
                        // Terminal data is always binary; text on terminal channel
                        // is unexpected but handle gracefully.
                        Log.w(TAG, "unexpected text on terminal channel: "
                                + (text.length() > 100 ? text.substring(0, 100) : text));
                    }

                    @Override
                    public void onClosed(int code, String reason) {
                        mainHandler.post(() -> {
                            String description = "Channel closed: " + code
                                    + (reason.isEmpty() ? "" : " " + reason);
                            Log.w(TAG, "channel closed channel=" + channelId + " " + description);
                            if (state != State.DISCONNECTED) {
                                scheduleReconnect(description);
                            }
                        });
                    }
                });
    }

    private void scheduleReconnect(String reason) {
        if (state == State.DISCONNECTED || sessionId == null || cookie == null || baseUrl == null) return;
        if (state == State.RECONNECTING) return;
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
        if (type == WebTermProtocol.MSG_SNAPSHOT) {
            listener.onSnapshot(payload);
            return;
        }
        if (type == WebTermProtocol.MSG_PATCH) {
            listener.onPatch(payload);
            return;
        }
        if (type == WebTermProtocol.MSG_SCROLLBACK) {
            listener.onScrollback(payload);
            return;
        }
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
        if (state != State.CONNECTED || channelId == null) return;
        muxSocket.sendBinary(channelId, WebTermProtocol.frame(type, payload).toByteArray());
    }

    // ── Listener interface (unchanged) ──────────────────────────────

    interface Listener {
        void onConnectionStatus(State state, int reconnectAttempts);
        void onOutput(long seq, byte[] data);
        void onState(long seq, byte[] data);
        void onInfo(JSONObject info);
        void onExit(int code);
        void onProtocolError(String message);
        void onSnapshot(byte[] data);
        void onPatch(byte[] data);
        void onScrollback(byte[] data);
    }
}
