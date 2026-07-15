package com.webterm.core.session;

import android.os.Handler;
import android.util.Log;

import com.webterm.core.api.WebTermUrls;
import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

public final class RelayMuxSessionManager {
    private static final String TAG = "RelayMuxSessionManager";
    private static final String SCREEN_SUBPROTOCOL = "webterm.screen.v1";
    private static final String MUX_SUBPROTOCOL = "webterm.mux.v1";
    private static final long CHANNEL_OPEN_TIMEOUT_MS = 10_000L;

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

    public interface ControlListener {
        void onControlMessage(JSONObject msg);
    }

    private static final ChannelListener NO_OP_LISTENER = new ChannelListener() {
        @Override public void onConnected(String channelId) {}
        @Override public void onData(String channelId, byte[] payload, boolean binary) {}
        @Override public void onFailure(String channelId, ChannelFailure failure) {}
    };

    private static final class Channel {
        enum State { WAITING_FOR_MUX, OPENING, LIVE }

        final String id;
        final String path;
        final String[] protocols;
        ChannelListener listener;
        State state = State.WAITING_FOR_MUX;
        long openGeneration;

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
    private volatile ControlListener controlListener;
    private volatile String clientId = "";
    private volatile String clientName = "Android";

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
                sendClientRegistration();
                reopenChannels();
            }

            @Override public void onMuxDisconnected(int code, String reason) {
                if (generation != muxGeneration) return;
                ChannelFailure failure = code == 401 || code == 403
                    ? ChannelFailure.authRequired(code, reason)
                    : ChannelFailure.muxTemporary(code, reason);
                for (Channel channel : snapshotChannels()) {
                    markWaiting(channel);
                    channel.listener.onFailure(channel.id, failure);
                }
                // Authentication cannot recover through transport backoff with the
                // same rejected cookie. Pause here; updateCookie() replaces and
                // restarts the physical mux while preserving logical channels.
                if (failure.kind == ChannelFailure.Kind.AUTH_REQUIRED) {
                    muxSession.stop();
                }
            }

            @Override public void onReconnectAttempt(int attempt) {
                if (generation != muxGeneration) return;
                for (Channel channel : snapshotChannels()) {
                    channel.listener.onReconnectAttempt(attempt);
                }
            }

            @Override public void onControlMessage(JSONObject msg) {
                if (generation != muxGeneration) return;
                ControlListener listener = controlListener;
                if (listener != null) listener.onControlMessage(msg);
            }

            @Override public void onTunnelConnected(String tunnelId) {
                if (generation != muxGeneration) return;
                Channel channel = channels.get(tunnelId);
                if (channel != null && channel.state == Channel.State.OPENING) {
                    channel.openGeneration++;
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
                    reopenAfterFailure(channel, ChannelFailure.serverTemporary(code, message));
                } else {
                    // 既然上层把它归类为可恢复的 mux 临时错误，就必须推进 channel
                    // 状态并真正重开；只通知 Runtime 会让它永远停在 RECONNECTING。
                    reopenAfterFailure(channel, ChannelFailure.muxTemporary(code, message));
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
                    ChannelFailure failure = code >= 500 && code < 600
                        ? ChannelFailure.serverTemporary(code, reason)
                        : ChannelFailure.muxTemporary(code, reason);
                    reopenAfterFailure(channel, failure);
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
            if (wasDetached && existing.state == Channel.State.LIVE) {
                // 新连接对象接管仍存活的 logical id 时，显式替换服务端 VirtualSocket。
                existing.state = Channel.State.WAITING_FOR_MUX;
                openChannelIfReady(existing);
            } else if (existing.state == Channel.State.WAITING_FOR_MUX) {
                openChannelIfReady(existing);
            } else if (!wasDetached && existing.state == Channel.State.LIVE) {
                listener.onConnected(channelId);
            }
            // OPENING 只替换 listener，等待唯一在途 ws-connect 的 ACK。
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
        openChannelIfReady(channels.get(channelId));
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
        ChannelFailure failure = ChannelFailure.muxTemporary(0, reason);
        for (Channel channel : snapshotChannels()) {
            markWaiting(channel);
            channel.listener.onFailure(channel.id, failure);
        }
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

    /** 注册设备级控制消息监听，不改变 screen channel 的所有权。 */
    public void setControlListener(ControlListener listener) {
        controlListener = listener;
    }

    public boolean sendControl(JSONObject msg) {
        return muxSession.sendControl(msg);
    }

    /** 配置当前 Android 安装实例身份；每次 mux 重连成功后都会重新注册。 */
    public void setClientRegistration(String clientId, String clientName) {
        this.clientId = clientId == null ? "" : clientId;
        this.clientName = clientName == null || clientName.trim().isEmpty() ? "Android" : clientName.trim();
        if (muxSession.isConnected()) sendClientRegistration();
    }

    /** 上报真实用户活跃；心跳与自动重连不调用此方法。 */
    public void markClientActive() {
        if (clientId.isEmpty()) return;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "client.active");
            msg.put("client_id", clientId);
        } catch (Exception ignored) { return; }
        muxSession.sendControl(msg);
    }

    private void sendClientRegistration() {
        if (clientId.isEmpty()) return;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "client.register");
            msg.put("protocol_version", 1);
            msg.put("client_id", clientId);
            msg.put("client_kind", "android");
            msg.put("client_name", clientName);
            msg.put("capabilities", new JSONArray().put("file_receive").put("agent_notification"));
        } catch (Exception ignored) { return; }
        muxSession.sendControl(msg);
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
            openChannelIfReady(channel);
        }
    }

    private void openChannelIfReady(Channel channel) {
        if (channel == null || channel.state != Channel.State.WAITING_FOR_MUX
                || !muxSession.isConnected()) return;
        if (muxSession.sendWsConnect(channel.id, channel.path, channel.protocols)) {
            channel.state = Channel.State.OPENING;
            long generation = ++channel.openGeneration;
            mainHandler.postDelayed(
                () -> onChannelOpenTimeout(channel.id, generation),
                CHANNEL_OPEN_TIMEOUT_MS);
        }
    }

    private void onChannelOpenTimeout(String channelId, long generation) {
        Channel channel = channels.get(channelId);
        if (channel == null || channel.state != Channel.State.OPENING
                || channel.openGeneration != generation) return;
        reopenAfterFailure(channel,
            ChannelFailure.muxTemporary(0, "channel open timeout"));
    }

    private void reopenAfterFailure(Channel channel, ChannelFailure failure) {
        markWaiting(channel);
        channel.listener.onFailure(channel.id, failure);
        if (channels.get(channel.id) == channel) openChannelIfReady(channel);
    }

    private static void markWaiting(Channel channel) {
        channel.openGeneration++;
        channel.state = Channel.State.WAITING_FOR_MUX;
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
