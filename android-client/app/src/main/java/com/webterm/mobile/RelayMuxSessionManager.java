package com.webterm.mobile;

import android.os.Handler;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

final class RelayMuxSessionManager {
    private static final String TAG = "RelayMuxSessionManager";
    private static final String BINARY_SUBPROTOCOL = "webterm.binary.v1";
    private static final String MUX_SUBPROTOCOL = "webterm.mux.v1";
    private static final Map<String, RelayMuxSessionManager> INSTANCES = new LinkedHashMap<>();
    private static TransportProvider transportProvider;

    interface ChannelListener {
        void onConnected(String channelId);
        void onError(String channelId, String message);
        void onData(String channelId, byte[] payload, boolean binary);
        void onMuxDisconnected(String reason);
    }

    interface TransportProvider {
        MuxTransport createTransport(String deviceId);
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
    private final String key;
    private final String baseUrl;
    private final String cookie;
    private final String deviceId;
    private MuxSession muxSession;
    private int muxGeneration;
    private final Map<String, Channel> channels = new LinkedHashMap<>();

    static synchronized void setTransportProvider(TransportProvider provider) {
        transportProvider = provider;
    }

    static synchronized void reconnectDevice(String deviceId, String reason) {
        for (RelayMuxSessionManager manager : INSTANCES.values().toArray(new RelayMuxSessionManager[0])) {
            if (safeEquals(manager.deviceId, deviceId)) {
                manager.reconnectTransport(reason);
            }
        }
    }

    static synchronized RelayMuxSessionManager forDevice(OkHttpClient http, Handler mainHandler, String baseUrl, String cookie, String deviceId) {
        String key = key(baseUrl, cookie, deviceId);
        RelayMuxSessionManager manager = INSTANCES.get(key);
        if (manager == null) {
            manager = new RelayMuxSessionManager(http, mainHandler, key, baseUrl, cookie, deviceId);
            INSTANCES.put(key, manager);
        }
        return manager;
    }

    private RelayMuxSessionManager(OkHttpClient http, Handler mainHandler, String key, String baseUrl, String cookie, String deviceId) {
        this.http = http;
        this.mainHandler = mainHandler;
        this.key = key;
        this.baseUrl = WebTermUrls.normalizeBaseUrl(baseUrl);
        this.cookie = cookie;
        this.deviceId = deviceId == null ? "" : deviceId;
        this.muxSession = createMuxSession(++muxGeneration);
    }

    private MuxSession createMuxSession(int generation) {
        MuxTransport transport = null;
        TransportProvider provider = transportProvider;
        if (provider != null) {
            transport = provider.createTransport(deviceId);
        }
        if (transport == null) {
            String wsUrl = WebTermUrls.toWebSocketUrl(this.baseUrl) + "/ws/sessions";
            if (this.deviceId != null && !this.deviceId.isEmpty()) {
                wsUrl += "?deviceId=" + WebTermUrls.encodePath(this.deviceId);
            }
            transport = new WebSocketMuxTransport(http, wsUrl, cookie, MUX_SUBPROTOCOL);
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

    boolean matches(String baseUrl, String cookie, String deviceId) {
        return this.baseUrl.equals(WebTermUrls.normalizeBaseUrl(baseUrl))
            && safeEquals(this.cookie, cookie)
            && safeEquals(this.deviceId, deviceId);
    }

    boolean isConnected() {
        return muxSession.isConnected();
    }

    void start() {
        muxSession.start();
    }

    void openTerminalChannel(String localSessionId, ChannelListener listener) {
        String channelId = terminalChannelId(localSessionId);
        String path = "/ws/sessions/" + WebTermUrls.encodePath(localSessionId);
        openChannel(channelId, path, new String[]{BINARY_SUBPROTOCOL}, listener);
    }

    void openChannel(String channelId, String path, String[] protocols, ChannelListener listener) {
        channels.put(channelId, new Channel(channelId, path, protocols, listener));
        start();
        if (muxSession.isConnected()) {
            muxSession.sendWsConnect(channelId, path, protocols);
        }
    }

    void closeChannel(String channelId) {
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
        synchronized (RelayMuxSessionManager.class) {
            INSTANCES.remove(key);
        }
    }

    boolean sendTunnelFrame(String channelId, byte[] payload, boolean binary) {
        return muxSession.sendTunnelFrame(channelId, payload, binary);
    }

    void stop() {
        for (Channel channel : snapshotChannels()) {
            muxSession.sendWsClose(channel.id);
        }
        channels.clear();
        muxSession.stop();
        synchronized (RelayMuxSessionManager.class) {
            INSTANCES.remove(key);
        }
    }

    static String terminalChannelId(String localSessionId) {
        return "term:" + localSessionId;
    }

    static String localSessionId(String sessionId, String deviceId) {
        if (sessionId == null) return "";
        String prefix = deviceId == null || deviceId.isEmpty() ? "" : deviceId + ":";
        if (!prefix.isEmpty() && sessionId.startsWith(prefix)) {
            return sessionId.substring(prefix.length());
        }
        return sessionId;
    }

    private void reopenChannels() {
        for (Channel channel : snapshotChannels()) {
            muxSession.sendWsConnect(channel.id, channel.path, channel.protocols);
        }
    }

    private Channel[] snapshotChannels() {
        return channels.values().toArray(new Channel[0]);
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null) return b == null || b.isEmpty();
        if (b == null) return a.isEmpty();
        return a.equals(b);
    }

    private static String key(String baseUrl, String cookie, String deviceId) {
        return WebTermUrls.normalizeBaseUrl(baseUrl) + "\n" + (cookie == null ? "" : cookie) + "\n" + (deviceId == null ? "" : deviceId);
    }
}
