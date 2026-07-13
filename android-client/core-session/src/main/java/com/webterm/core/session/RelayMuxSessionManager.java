package com.webterm.core.session;

import android.os.Handler;
import android.util.Log;

import com.webterm.core.api.WebTermUrls;
import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

public final class RelayMuxSessionManager {
    private static final String TAG = "RelayMuxSessionManager";
    private static final String SCREEN_SUBPROTOCOL = "webterm.screen.v1";
    private static final String MUX_SUBPROTOCOL = "webterm.mux.v1";

    public interface ChannelListener {
        void onConnected(String channelId);
        void onData(String channelId, byte[] payload, boolean binary);

        /**
         * channel 或 mux 失败时触发，携带结构化失败信息。
         * 恢复策略由上层按 failure.kind 决定，不再解析 message 文本。
         */
        void onFailure(String channelId, ChannelFailure failure);

        /** 物理 mux 连接每次自动重连尝试时触发，attempt 从 1 起递增。 */
        default void onReconnectAttempt(int attempt) {}
    }

    private static final ChannelListener NO_OP_LISTENER = new ChannelListener() {
        @Override public void onConnected(String channelId) {}
        @Override public void onData(String channelId, byte[] payload, boolean binary) {}
        @Override public void onFailure(String channelId, ChannelFailure failure) {}
    };

    private static final class Channel {
        enum State { CONNECTING, LIVE }

        final String id;
        final String path;
        final String[] protocols;
        ChannelListener listener;
        State state = State.CONNECTING;

        Channel(String id, String path, String[] protocols, ChannelListener listener) {
            this.id = id;
            this.path = path;
            this.protocols = protocols;
            this.listener = listener;
        }
    }

    private final OkHttpClient http;
    private final Handler mainHandler;
    private final TransportFactory transportFactory;
    private final String baseUrl;
    private volatile String cookie;
    private final String deviceId;
    private MuxSession muxSession;
    private int muxGeneration;
    private final Map<String, Channel> channels = new LinkedHashMap<>();

    RelayMuxSessionManager(OkHttpClient http, Handler mainHandler, String baseUrl, String cookie, String deviceId, TransportFactory transportFactory) {
        this.http = http;
        this.mainHandler = mainHandler;
        this.transportFactory = transportFactory;
        this.baseUrl = WebTermUrls.normalizeBaseUrl(baseUrl);
        this.cookie = cookie;
        this.deviceId = deviceId == null ? "" : deviceId;
        this.muxSession = createMuxSession(++muxGeneration);
    }

    private MuxSession createMuxSession(int generation) {
        String wsUrl = WebTermUrls.toWebSocketUrl(this.baseUrl) + "/ws/sessions";
        if (this.deviceId != null && !this.deviceId.isEmpty()) {
            wsUrl += "?deviceId=" + WebTermUrls.encodePath(this.deviceId);
        }
        MuxTransport transport = transportFactory != null
            ? transportFactory.create(wsUrl, cookie, MUX_SUBPROTOCOL)
            : null;
        Log.i(TAG, "using relay websocket transport for " + deviceId + " generation=" + generation);
        MuxSession session = new MuxSession(transport, mainHandler, new MuxSession.Listener() {
            @Override public void onMuxConnected() {
                if (generation != muxGeneration) return;
                reopenChannels();
            }

            @Override public void onMuxDisconnected(int code, String reason) {
                if (generation != muxGeneration) return;
                ChannelFailure failure = ChannelFailure.muxTemporary(code, reason);
                for (Channel channel : snapshotChannels()) {
                    channel.state = Channel.State.CONNECTING;
                    channel.listener.onFailure(channel.id, failure);
                }
            }

            @Override public void onReconnectAttempt(int attempt) {
                if (generation != muxGeneration) return;
                for (Channel channel : snapshotChannels()) {
                    channel.listener.onReconnectAttempt(attempt);
                }
            }

            @Override public void onTunnelConnected(String tunnelId) {
                if (generation != muxGeneration) return;
                Channel channel = channels.get(tunnelId);
                if (channel != null) {
                    channel.state = Channel.State.LIVE;
                    channel.listener.onConnected(tunnelId);
                }
            }

            @Override public void onTunnelError(String tunnelId, int code, String message) {
                if (generation != muxGeneration) return;
                Channel channel = channels.get(tunnelId);
                if (channel == null) return;
                if (code == 404) {
                    channels.remove(tunnelId);
                    channel.listener.onFailure(tunnelId, ChannelFailure.channelNotFound(code, message));
                } else if (code == 401) {
                    channels.remove(tunnelId);
                    channel.listener.onFailure(tunnelId, ChannelFailure.authRequired(code, message));
                } else if (code >= 500 && code < 600) {
                    // recoverable server-side error: reopen the channel
                    channel.state = Channel.State.CONNECTING;
                    channel.listener.onFailure(tunnelId, ChannelFailure.serverTemporary(code, message));
                    if (channels.containsKey(tunnelId) && muxSession.isConnected()) {
                        muxSession.sendWsConnect(tunnelId, channel.path, channel.protocols);
                    }
                } else {
                    channel.listener.onFailure(tunnelId, ChannelFailure.muxTemporary(code, message));
                }
            }

            @Override public void onTunnelData(String tunnelId, byte[] payload, boolean binary) {
                if (generation != muxGeneration) return;
                Channel channel = channels.get(tunnelId);
                if (channel != null) channel.listener.onData(tunnelId, payload, binary);
            }

            @Override public void onTunnelClosed(String tunnelId, int code, String reason) {
                if (generation != muxGeneration) return;
                Channel channel = channels.get(tunnelId);
                if (channel == null) return;
                if (code == 404) {
                    channels.remove(tunnelId);
                    channel.listener.onFailure(tunnelId, ChannelFailure.channelNotFound(code, reason));
                } else if (code == 401) {
                    channels.remove(tunnelId);
                    channel.listener.onFailure(tunnelId, ChannelFailure.authRequired(code, reason));
                } else if (code == 1000) {
                    channels.remove(tunnelId);
                    channel.listener.onFailure(tunnelId, ChannelFailure.remoteClosed(code, reason));
                } else {
                    // recoverable: network/backpressure; keep channel alive and reopen after reconnect
                    channel.state = Channel.State.CONNECTING;
                    ChannelFailure failure = code >= 500 && code < 600
                        ? ChannelFailure.serverTemporary(code, reason)
                        : ChannelFailure.muxTemporary(code, reason);
                    channel.listener.onFailure(tunnelId, failure);
                    if (channels.containsKey(tunnelId) && muxSession.isConnected()) {
                        muxSession.sendWsConnect(tunnelId, channel.path, channel.protocols);
                    }
                }
            }
        });
        return session;
    }

    public boolean matches(String baseUrl, String cookie, String deviceId) {
        // Cookie is a rotating auth token; identity is baseUrl + deviceId only.
        return this.baseUrl.equals(WebTermUrls.normalizeBaseUrl(baseUrl))
            && safeEquals(this.deviceId, deviceId);
    }

    public void updateCookie(String cookie) {
        String newCookie = cookie == null ? "" : cookie;
        if (newCookie.equals(this.cookie)) {
            return;
        }
        this.cookie = newCookie;
        reconnectTransport("cookie updated");
    }

    public boolean isConnected() {
        return muxSession.isConnected();
    }

    boolean isIdle() {
        return channels.isEmpty();
    }

    String deviceId() {
        return deviceId;
    }

    public void start() {
        muxSession.start();
    }

    public void forceReconnect(String reason) {
        reconnectTransport(reason, true);
    }

    public String openScreenChannel(String localSessionId, ChannelListener listener) {
        return openProtocolChannel(localSessionId, SCREEN_SUBPROTOCOL, listener);
    }

    private String openProtocolChannel(String localSessionId, String subprotocol, ChannelListener listener) {
        String channelId = terminalChannelId(localSessionId, subprotocol);
        Channel existing = channels.get(channelId);
        if (existing != null) {
            boolean wasDetached = existing.listener == NO_OP_LISTENER;
            existing.listener = listener;
            if (wasDetached || existing.state == Channel.State.CONNECTING) {
                existing.state = Channel.State.CONNECTING;
                if (muxSession.isConnected()) {
                    muxSession.sendWsConnect(channelId, existing.path, existing.protocols);
                }
            } else if (muxSession.isConnected() && existing.state == Channel.State.LIVE) {
                listener.onConnected(channelId);
            }
            return channelId;
        }
        String path = "/ws/sessions/" + WebTermUrls.encodePath(localSessionId);
        openChannel(channelId, path, new String[]{subprotocol}, listener);
        return channelId;
    }

    public void detachChannelListener(String channelId) {
        Channel ch = channels.get(channelId);
        if (ch != null) {
            ch.listener = NO_OP_LISTENER;
        }
    }

    public void openChannel(String channelId, String path, String[] protocols, ChannelListener listener) {
        channels.put(channelId, new Channel(channelId, path, protocols, listener));
        start();
        if (muxSession.isConnected()) {
            muxSession.sendWsConnect(channelId, path, protocols);
        }
    }

    public void closeChannel(String channelId) {
        if (channelId == null || channelId.isEmpty()) return;
        channels.remove(channelId);
        muxSession.sendWsClose(channelId);
    }

    void reconnectTransport(String reason) {
        reconnectTransport(reason, true);
    }

    private void reconnectTransport(String reason, boolean autoStart) {
        MuxSession oldSession = muxSession;
        muxGeneration++;
        Log.i(TAG, "reconnect transport for " + deviceId + " reason=" + reason + " channels=" + channels.size() + " generation=" + muxGeneration);
        muxSession = createMuxSession(muxGeneration);
        if (oldSession != null) {
            oldSession.stop();
        }
        if (autoStart && !channels.isEmpty()) {
            muxSession.start();
        }
    }

    void stopIfIdle() {
        if (!channels.isEmpty()) return;
        muxSession.stop();
    }

    public boolean sendTunnelFrame(String channelId, byte[] payload, boolean binary) {
        return muxSession.sendTunnelFrame(channelId, payload, binary);
    }

    void stop() {
        for (Channel channel : snapshotChannels()) {
            muxSession.sendWsClose(channel.id);
        }
        channels.clear();
        muxSession.stop();
    }

    private static String terminalChannelId(String localSessionId, String subprotocol) {
        return "term:" + localSessionId + ":" + subprotocol;
    }

    public static String localSessionId(String sessionId, String deviceId) {
        if (sessionId == null) return "";
        String prefix = deviceId == null || deviceId.isEmpty() ? "" : deviceId + ":";
        if (!prefix.isEmpty() && sessionId.startsWith(prefix)) {
            return sessionId.substring(prefix.length());
        }
        return sessionId;
    }

    public static String canonicalSessionId(String sessionId, String deviceId) {
        if (sessionId == null) return "";
        if (deviceId == null || deviceId.isEmpty() || sessionId.contains(":")) return sessionId;
        return deviceId + ":" + sessionId;
    }

    private void reopenChannels() {
        for (Channel channel : snapshotChannels()) {
            muxSession.sendWsConnect(channel.id, channel.path, channel.protocols);
        }
    }

    private Channel[] snapshotChannels() {
        return channels.values().toArray(new Channel[0]);
    }

    public static boolean safeEquals(String a, String b) {
        if (a == null) return b == null || b.isEmpty();
        if (b == null) return a.isEmpty();
        return a.equals(b);
    }
}
