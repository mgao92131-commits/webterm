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
    private static final String BINARY_SUBPROTOCOL = "webterm.binary.v1";
    private static final String MUX_SUBPROTOCOL = "webterm.mux.v1";

    public interface ChannelListener {
        void onConnected(String channelId);
        void onError(String channelId, String message);
        void onData(String channelId, byte[] payload, boolean binary);
        void onMuxDisconnected(String reason);

        /** 物理 mux 连接每次自动重连尝试时触发，attempt 从 1 起递增。 */
        default void onReconnectAttempt(int attempt) {}
    }

    private static final class Channel {
        final String id;
        final String path;
        final String[] protocols;
        final ChannelListener listener;

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
    private final String cookie;
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
        MuxTransport transport = null;
        if (transportFactory != null) {
            transport = transportFactory.createDataChannel(deviceId);
        }
        if (transport == null) {
            String wsUrl = WebTermUrls.toWebSocketUrl(this.baseUrl) + "/ws/sessions";
            if (this.deviceId != null && !this.deviceId.isEmpty()) {
                wsUrl += "?deviceId=" + WebTermUrls.encodePath(this.deviceId);
            }
            transport = transportFactory != null
                ? transportFactory.createWebSocket(wsUrl, cookie, MUX_SUBPROTOCOL)
                : null;
            Log.i(TAG, "using relay websocket transport for " + deviceId + " generation=" + generation);
        } else {
            Log.i(TAG, "using p2p transport for " + deviceId + " generation=" + generation);
        }
        return new MuxSession(transport, mainHandler, new MuxSession.Listener() {
            @Override public void onMuxConnected() {
                if (generation != muxGeneration) return;
                reopenChannels();
            }

            @Override public void onMuxDisconnected(String reason) {
                if (generation != muxGeneration) return;
                for (Channel channel : snapshotChannels()) {
                    channel.listener.onMuxDisconnected(reason);
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
                if (channel != null) channel.listener.onConnected(tunnelId);
            }

            @Override public void onTunnelError(String tunnelId, String message) {
                if (generation != muxGeneration) return;
                Channel channel = channels.get(tunnelId);
                if (channel != null) channel.listener.onError(tunnelId, message);
            }

            @Override public void onTunnelData(String tunnelId, byte[] payload, boolean binary) {
                if (generation != muxGeneration) return;
                Channel channel = channels.get(tunnelId);
                if (channel != null) channel.listener.onData(tunnelId, payload, binary);
            }
        });
    }

    public boolean matches(String baseUrl, String cookie, String deviceId) {
        return this.baseUrl.equals(WebTermUrls.normalizeBaseUrl(baseUrl))
            && safeEquals(this.cookie, cookie)
            && safeEquals(this.deviceId, deviceId);
    }

    public boolean isConnected() {
        return muxSession.isConnected();
    }

    public boolean isP2PConnected() {
        return muxSession.isConnected() && muxSession.isP2PTransport();
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

    public void openTerminalChannel(String localSessionId, ChannelListener listener) {
        String channelId = terminalChannelId(localSessionId);
        String path = "/ws/sessions/" + WebTermUrls.encodePath(localSessionId);
        openChannel(channelId, path, new String[]{BINARY_SUBPROTOCOL}, listener);
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
        MuxSession oldSession = muxSession;
        muxGeneration++;
        Log.i(TAG, "reconnect transport for " + deviceId + " reason=" + reason + " channels=" + channels.size() + " generation=" + muxGeneration);
        muxSession = createMuxSession(muxGeneration);
        if (oldSession != null) {
            oldSession.stop();
        }
        if (!channels.isEmpty()) {
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

    public static String terminalChannelId(String localSessionId) {
        return "term:" + localSessionId;
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
