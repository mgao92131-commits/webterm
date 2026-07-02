package com.webterm.mobile;

import android.os.Handler;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import okhttp3.OkHttpClient;

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
    private final Handler mainHandler;
    private final Listener listener;

    private RelayMuxSessionManager relayMuxSession;
    private String relayDeviceId;
    private String relayChannelId;
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

    private final Runnable sendResizeRunnable = this::sendResizeNow;

    TerminalConnection(OkHttpClient http, Handler mainHandler, Listener listener) {
        this.http = http;
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    State getState() {
        return state;
    }

    void connect(String baseUrl, String cookie, String sessionId, long lastSeq, String relayDeviceId) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.lastSeq = lastSeq;
        this.relayDeviceId = relayDeviceId == null ? "" : relayDeviceId;
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        connectNow();
    }

    void reconnectNow() {
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        connectNow();
    }

    void close(String reason) {
        this.state = State.DISCONNECTED;
        this.socketGeneration++;
        mainHandler.removeCallbacks(sendResizeRunnable);
        if (relayMuxSession != null) {
            relayMuxSession.closeChannel(relayChannelId);
            relayMuxSession.stopIfIdle();
            relayMuxSession = null;
        }
        relayChannelId = null;
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
        connectRelayMux();
    }

    private void connectRelayMux() {
        state = State.CONNECTING;
        listener.onConnectionStatus(state, reconnectAttempts);
        String localSessionId = RelayMuxSessionManager.localSessionId(sessionId, relayDeviceId);
        String nextChannelId = RelayMuxSessionManager.terminalChannelId(localSessionId);
        if (relayMuxSession == null || !relayMuxSession.matches(baseUrl, cookie, relayDeviceId)) {
            if (relayMuxSession != null) {
                relayMuxSession.closeChannel(relayChannelId);
                relayMuxSession.stopIfIdle();
            }
            relayMuxSession = RelayMuxSessionManager.forDevice(http, mainHandler, baseUrl, cookie, relayDeviceId);
        }
        relayChannelId = nextChannelId;
        relayMuxSession.openTerminalChannel(localSessionId, new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) {
                if (!channelId.equals(relayChannelId)) return;
                state = State.CONNECTED;
                reconnectAttempts = 0;
                sentColumns = 0;
                sentRows = 0;
                listener.onConnectionStatus(state, reconnectAttempts);
                sendHello();
                sendResizeNow();
            }

            @Override public void onError(String channelId, String message) {
                if (!channelId.equals(relayChannelId)) return;
                listener.onProtocolError("tunnel error: " + message);
            }

            @Override public void onData(String channelId, byte[] payload, boolean binary) {
                if (!channelId.equals(relayChannelId)) return;
                handleServerMessage(payload);
            }

            @Override public void onMuxDisconnected(String reason) {
                if (state != State.DISCONNECTED) scheduleReconnect(reason);
            }
        });
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
        // MuxSession handles reconnection internally. Surface RECONNECTING
        // so the UI leaves CONNECTED and user input is dropped by sendBinary guard.
        state = State.RECONNECTING;
        Log.i(TAG, "mux disconnected, awaiting MuxSession reconnect. Reason: " + reason);
        listener.onConnectionStatus(state, reconnectAttempts);
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
        if (type == WebTermProtocol.MSG_EXIT) {
            try {
                JSONObject msg = WebTermProtocol.controlPayload(payload);
                listener.onExit(msg.optInt("code", 0));
            } catch (JSONException e) {
                listener.onProtocolError("Bad message: " + e.getMessage());
            }
            return;
        }
        // Unknown message types are silently ignored to avoid parsing
        // non-JSON payloads (e.g. plain text titles) as JSON objects.
    }

    private void scheduleResize() {
        mainHandler.removeCallbacks(sendResizeRunnable);
        if (state != State.CONNECTED) return;
        if (relayMuxSession == null || !relayMuxSession.isConnected()) return;
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
        if (relayMuxSession == null || relayChannelId == null || !relayMuxSession.isConnected() || state != State.CONNECTED) return;
        relayMuxSession.sendTunnelFrame(relayChannelId, WebTermProtocol.frame(type, payload).toByteArray(), true);
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
