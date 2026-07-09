package com.webterm.feature.terminal.domain;

import android.os.Handler;
import android.util.Log;

import com.webterm.core.session.MuxSession;
import com.webterm.core.session.RelayMuxSessionManager;
import com.webterm.core.session.RelayMuxSessionRegistry;
import com.webterm.core.session.WebTermProtocol;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

public final class TerminalConnection {
    private static final String TAG = "TerminalConnection";
    private static final long RESIZE_DEBOUNCE_MS = 100L;
    private static final String BINARY_SUBPROTOCOL = "webterm.binary.v1";

    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    private final Handler mainHandler;
    private final RelayMuxSessionRegistry relayMuxRegistry;
    private final Listener listener;

    private RelayMuxSessionManager relayMuxSession;
    private String relayDeviceId;
    private String relayChannelId;
    private volatile State state = State.DISCONNECTED;
    private int socketGeneration;
    private int reconnectAttempts;
    private boolean pendingForceReconnect;

    private String baseUrl;
    private String cookie;
    private String sessionId;
    private long lastSeq;
    private int columns;
    private int rows;
    private int sentColumns;
    private int sentRows;

    private final Runnable sendResizeRunnable = this::sendResizeNow;

    @AssistedInject
    public TerminalConnection(Handler mainHandler, RelayMuxSessionRegistry relayMuxRegistry, @Assisted Listener listener) {
        this.mainHandler = mainHandler;
        this.relayMuxRegistry = relayMuxRegistry;
        this.listener = listener;
    }

    @AssistedFactory
    public interface Factory {
        TerminalConnection create(Listener listener);
    }

    public State getState() {
        return state;
    }

    public int getReconnectAttempts() {
        return reconnectAttempts;
    }

    public void connect(String baseUrl, String cookie, String sessionId, long lastSeq, String relayDeviceId) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.lastSeq = lastSeq;
        this.relayDeviceId = relayDeviceId == null ? "" : relayDeviceId;
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        connectNow();
    }

    public void reconnectNow() {
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        if (relayMuxSession != null && relayMuxSession.matches(baseUrl, cookie, relayDeviceId)) {
            this.pendingForceReconnect = true;
        }
        connectNow();
    }

    public void detach() {
        this.state = State.DISCONNECTED;
        this.socketGeneration++;
        mainHandler.removeCallbacks(sendResizeRunnable);
        // Remove our listener so future events don't reach a dead UI,
        // but do NOT close the channel — it stays alive for reattach.
        // Clearing the manager reference is enough: reattach will look up
        // the same manager again, and openTerminalChannel replaces the listener.
        relayMuxSession = null;
        relayChannelId = null;
    }

    public void closeSession() {
        this.state = State.DISCONNECTED;
        this.socketGeneration++;
        mainHandler.removeCallbacks(sendResizeRunnable);
        if (relayMuxSession != null) {
            relayMuxSession.closeChannel(relayChannelId);
            relayMuxRegistry.releaseIfIdle(relayMuxSession);
            relayMuxSession = null;
        }
        relayChannelId = null;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public boolean isP2PConnected() {
        return relayMuxSession != null && relayMuxSession.isP2PConnected();
    }

    public void updateLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void updateSize(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        scheduleResize();
    }

    public void sendInput(String data) {
        sendBinary(WebTermProtocol.MSG_INPUT, data.getBytes(StandardCharsets.UTF_8));
    }

    public void sendTitle(String title) {
        sendBinary(WebTermProtocol.MSG_TITLE, title.getBytes(StandardCharsets.UTF_8));
    }

    private void connectNow() {
        if (state == State.DISCONNECTED) return;
        if (sessionId == null || baseUrl == null || cookie == null) return;
        connectRelayMux();
    }

    private void connectRelayMux() {
        state = State.CONNECTING;
        listener.onConnectionStatus(state, reconnectAttempts);
        String localSessionId = RelayMuxSessionManager.localSessionId(sessionId, relayDeviceId);
        if (relayMuxSession == null || !relayMuxSession.matches(baseUrl, cookie, relayDeviceId)) {
            if (relayMuxSession != null) {
                // Don't close the channel here; just release the idle reference.
                // The old manager keeps its channels alive for other consumers.
                relayMuxRegistry.releaseIfIdle(relayMuxSession);
            }
            relayMuxSession = relayMuxRegistry.forDevice(baseUrl, cookie, relayDeviceId);
            relayMuxSession.updateCookie(cookie);
        } else {
            // Reuse the same physical session, but sync the latest cookie in case it rotated.
            relayMuxSession.updateCookie(cookie);
            if (pendingForceReconnect) {
                // Manual reconnect: force a new physical session to break a stale connected state.
                relayMuxSession.forceReconnect("manual reconnect");
                pendingForceReconnect = false;
            }
        }
        String existingChannelId = RelayMuxSessionManager.terminalChannelId(localSessionId);
        long channelSeq = relayMuxSession.getChannelLastSeq(existingChannelId);
        if (channelSeq > 0) {
            this.lastSeq = channelSeq;
        }
        relayChannelId = existingChannelId;
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

            @Override public void onError(String channelId, int code, String message) {
                if (!channelId.equals(relayChannelId)) return;
                if (state == State.DISCONNECTED) return;
                listener.onProtocolError("tunnel error: " + message);
            }

            @Override public void onData(String channelId, byte[] payload, boolean binary) {
                if (!channelId.equals(relayChannelId)) return;
                handleServerMessage(payload);
                if (relayMuxSession != null && relayChannelId != null) {
                    relayMuxSession.updateChannelLastSeq(relayChannelId, lastSeq);
                }
            }

            @Override public void onMuxDisconnected(String reason) {
                if (state != State.DISCONNECTED) scheduleReconnect(reason);
            }

            @Override public void onReconnectAttempt(int attempt) {
                if (state == State.DISCONNECTED) return;
                reconnectAttempts = attempt;
                state = State.RECONNECTING;
                listener.onConnectionStatus(state, reconnectAttempts);
            }

            @Override public void onClosed(String channelId, int code, String reason) {
                if (!channelId.equals(relayChannelId)) return;
                if (state == State.DISCONNECTED) return;
                Log.i(TAG, "channel closed: " + channelId);
                socketGeneration++;
                mainHandler.removeCallbacks(sendResizeRunnable);
                scheduleChannelReconnect();
            }

            @Override public void onChannelGone(String channelId, int code, String reason) {
                if (!channelId.equals(relayChannelId)) return;
                if (state == State.DISCONNECTED) return;
                state = State.DISCONNECTED;
                socketGeneration++;
                mainHandler.removeCallbacks(sendResizeRunnable);
                listener.onExit(0);
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

    private void scheduleChannelReconnect() {
        if (state == State.DISCONNECTED || sessionId == null || cookie == null || baseUrl == null) return;
        if (state == State.RECONNECTING) return;
        reconnectAttempts++;
        state = State.RECONNECTING;
        Log.i(TAG, "channel closed, reconnecting after delay. attempt=" + reconnectAttempts);
        listener.onConnectionStatus(state, reconnectAttempts);
        long delayMs = Math.min(100L * reconnectAttempts, 2000L);
        mainHandler.postDelayed(this::connectNow, delayMs);
    }

    private void handleServerMessage(byte[] frame) {
        if (frame.length == 0) return;
        byte type = frame[0];
        Log.d(TAG, "handleServerMessage type=" + type + " len=" + frame.length);
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
        if (type == WebTermProtocol.MSG_HOOK) {
            try {
                JSONObject hook = WebTermProtocol.controlPayload(payload);
                listener.onHook(hook);
                if ("download".equals(hook.optString("type"))) {
                    listener.onDownloadHook(
                        hook.optString("download_id"),
                        hook.optString("file_name"),
                        hook.optLong("file_size", -1),
                        hook.optString("session_id")
                    );
                }
            } catch (JSONException ignored) {
            }
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

    public void sendDownloadProgress(String downloadId, long current, long total) {
        String payload = downloadId + ":" + current + ":" + total;
        sendBinary(WebTermProtocol.MSG_DOWNLOAD_PROGRESS, payload.getBytes(StandardCharsets.UTF_8));
    }

    public interface Listener {
        void onConnectionStatus(State state, int reconnectAttempts);
        void onOutput(long seq, byte[] data);
        void onState(long seq, byte[] data);
        void onInfo(JSONObject info);
        void onHook(JSONObject ev);
        void onDownloadHook(String downloadId, String fileName, long fileSize, String sessionId);
        void onExit(int code);
        void onProtocolError(String message);
    }
}
