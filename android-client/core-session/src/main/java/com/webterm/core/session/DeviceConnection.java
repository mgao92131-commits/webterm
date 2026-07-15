package com.webterm.core.session;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.webterm.core.api.WebTermUrls;
import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class DeviceConnection {
    private static final String TAG = "DeviceConnection";
    private static final String SCREEN_SUBPROTOCOL = "webterm.screen.v1";
    private static final String MUX_SUBPROTOCOL = "webterm.mux.v1";
    private static final long CHANNEL_OPEN_TIMEOUT_MS = 10_000L;
    private static final long PHYSICAL_CONNECT_TIMEOUT_MS = 10_000L;
    private static final long[] CHANNEL_RETRY_BACKOFF_MS = {
        1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L
    };

    public interface ChannelListener {
        void onConnected(String channelId);
        void onData(String channelId, byte[] payload, boolean binary);

        /**
         * logical channel 或设备连接失败时触发，携带结构化失败信息。
         * 恢复策略由上层按 failure.kind 决定，不再解析 message 文本。
         */
        void onFailure(String channelId, ChannelFailure failure);

        /** 物理设备连接每次自动重连尝试时触发，attempt 从 1 起递增。 */
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
        enum State { CLOSED, OPENING, OPEN, RETRY_WAIT }

        final String id;
        final String path;
        final String[] protocols;
        final String screenRouteKey;
        ChannelListener listener;
        boolean desiredOpen = true;
        State state = State.CLOSED;
        long openGeneration;
        long retryGeneration;
        int retryAttempt;

        Channel(String id, String path, String[] protocols, String screenRouteKey,
                ChannelListener listener) {
            this.id = id;
            this.path = path;
            this.protocols = protocols;
            this.screenRouteKey = screenRouteKey;
            this.listener = listener;
        }
    }

    private final Handler stateHandler;
    private final Handler callbackHandler;
    private final Runnable eventLoopShutdown;
    private final TransportFactory transportFactory;
    private final String baseUrl;
    private volatile String cookie;
    private final String deviceId;
    private MuxTransport transport;
    private int transportGeneration;
    private boolean physicalDesired;
    private boolean physicalConnected;
    private boolean physicalConnecting;
    private int physicalReconnectAttempts;
    private final Map<String, Channel> channels = new LinkedHashMap<>();
    /** 每个 terminal screen route 当前唯一的 runtime owner channel。 */
    private final Map<String, String> screenChannelOwners = new LinkedHashMap<>();
    private volatile ControlListener controlListener;
    private volatile String clientId = "";
    private volatile String clientName = "Android";

    DeviceConnection(Handler handler, String baseUrl, String cookie, String deviceId, TransportFactory transportFactory) {
        this(handler, handler, baseUrl, cookie, deviceId, transportFactory, () -> {});
    }

    DeviceConnection(Handler stateHandler, Handler callbackHandler,
            String baseUrl, String cookie, String deviceId, TransportFactory transportFactory,
            Runnable eventLoopShutdown) {
        this.stateHandler = stateHandler;
        this.callbackHandler = callbackHandler;
        this.eventLoopShutdown = eventLoopShutdown != null ? eventLoopShutdown : () -> {};
        this.transportFactory = transportFactory;
        this.baseUrl = WebTermUrls.normalizeBaseUrl(baseUrl);
        this.cookie = cookie;
        this.deviceId = deviceId == null ? "" : deviceId;
        installTransport();
    }

    private void installTransport() {
        int generation = ++transportGeneration;
        String wsUrl = WebTermUrls.toWebSocketUrl(this.baseUrl) + "/ws/sessions";
        if (this.deviceId != null && !this.deviceId.isEmpty()) {
            wsUrl += "?deviceId=" + WebTermUrls.encodePath(this.deviceId);
        }
        transport = transportFactory != null
            ? transportFactory.create(wsUrl, cookie, MUX_SUBPROTOCOL)
            : null;
        Log.i(TAG, "using relay websocket transport for " + deviceId + " generation=" + generation);
    }

    private void connectPhysical() {
        physicalDesired = true;
        if (transport == null) {
            handlePhysicalDisconnected(transportGeneration, 0, "transport unavailable");
            return;
        }
        if (physicalConnected || physicalConnecting) return;
        physicalConnecting = true;
        int generation = transportGeneration;
        stateHandler.postDelayed(
            () -> onPhysicalConnectTimeout(generation), PHYSICAL_CONNECT_TIMEOUT_MS);
        transport.start(new MuxTransport.Listener() {
            @Override public void onOpen() {
                runOnState(() -> onPhysicalOpen(generation));
            }

            @Override public void onText(String text) {
                runOnState(() -> handleControlMessage(generation, text));
            }

            @Override public void onBinary(byte[] data) {
                runOnState(() -> dispatchBinaryFrame(generation, data));
            }

            @Override public void onClosed(int code, String reason) {
                runOnState(() -> handlePhysicalDisconnected(generation, code, reason));
            }

            @Override public void onError(String message) {
                onError(0, message);
            }

            @Override public void onError(int code, String message) {
                runOnState(() -> handlePhysicalDisconnected(generation, code, message));
            }
        });
    }

    private void onPhysicalOpen(int generation) {
        if (generation != transportGeneration || !physicalDesired) {
            if (transport != null) transport.close();
            return;
        }
        physicalConnecting = false;
        physicalConnected = true;
        physicalReconnectAttempts = 0;
        sendClientRegistration();
        reconcileChannels();
    }

    private void onPhysicalConnectTimeout(int generation) {
        if (generation != transportGeneration || !physicalDesired
                || physicalConnected || !physicalConnecting) return;
        physicalConnecting = false;
        physicalConnected = false;
        if (transport != null) transport.close();
        notifyPhysicalFailure(ChannelFailure.muxTemporary(0, "connect timeout"));
        schedulePhysicalReconnect();
    }

    private void handlePhysicalDisconnected(int generation, int code, String reason) {
        if (generation != transportGeneration || !physicalDesired) return;
        physicalConnected = false;
        physicalConnecting = false;
        ChannelFailure failure = code == 401 || code == 403
            ? ChannelFailure.authRequired(code, reason)
            : ChannelFailure.muxTemporary(code, reason);
        notifyPhysicalFailure(failure);
        if (failure.kind == ChannelFailure.Kind.AUTH_REQUIRED) {
            physicalDesired = false;
            if (transport != null) transport.close();
            return;
        }
        schedulePhysicalReconnect();
    }

    private void notifyPhysicalFailure(ChannelFailure failure) {
        for (Channel channel : snapshotChannels()) {
            markWaiting(channel);
            notifyFailure(channel, failure);
        }
    }

    private void schedulePhysicalReconnect() {
        if (!physicalDesired) return;
        int attempt = ++physicalReconnectAttempts;
        for (Channel channel : snapshotChannels()) {
            ChannelListener listener = channel.listener;
            callbackHandler.post(() -> listener.onReconnectAttempt(attempt));
        }
        long cap = Math.min(1_000L * attempt, 8_000L);
        long delayMs = Math.max(200L, (long) (Math.random() * cap));
        int generation = transportGeneration;
        stateHandler.postDelayed(() -> {
            if (physicalDesired && generation == transportGeneration && !physicalConnected) {
                connectPhysical();
            }
        }, delayMs);
    }

    private void handleControlMessage(int generation, String text) {
        if (generation != transportGeneration || !physicalConnected) return;
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (JSONException ignored) {
            return;
        }
        String type = msg.optString("type");
        String tunnelId = msg.optString("tunnelConnectionId");
        if ("ws-connected".equals(type)) {
            onTunnelConnected(tunnelId);
        } else if ("ws-error".equals(type)) {
            onTunnelError(tunnelId, msg.optInt("code", 0), msg.optString("message", ""));
        } else if ("ws-close".equals(type)) {
            onTunnelClosed(tunnelId, msg.optInt("code", 1000), msg.optString("reason", ""));
        } else if (type != null && !type.isEmpty()) {
            ControlListener listener = controlListener;
            if (listener != null) callbackHandler.post(() -> listener.onControlMessage(msg));
        }
    }

    private void onTunnelConnected(String tunnelId) {
        Channel channel = channels.get(tunnelId);
        if (channel != null && channel.desiredOpen && channel.state == Channel.State.OPENING) {
            channel.openGeneration++;
            channel.retryGeneration++;
            channel.retryAttempt = 0;
            channel.state = Channel.State.OPEN;
            notifyConnected(channel);
        }
    }

    private void onTunnelError(String tunnelId, int code, String message) {
        Channel channel = channels.get(tunnelId);
        if (channel == null) return;
        if (code == 404) {
            removeChannelIfCurrent(channel);
            notifyFailure(channel, ChannelFailure.channelNotFound(code, message));
        } else if (code == 401) {
            removeChannelIfCurrent(channel);
            notifyFailure(channel, ChannelFailure.authRequired(code, message));
        } else if (code >= 500 && code < 600) {
            scheduleChannelRetry(channel, ChannelFailure.serverTemporary(code, message));
        } else {
            scheduleChannelRetry(channel, ChannelFailure.muxTemporary(code, message));
        }
    }

    private void onTunnelClosed(String tunnelId, int code, String reason) {
        Channel channel = channels.get(tunnelId);
        if (channel == null) return;
        if (code == 404) {
            removeChannelIfCurrent(channel);
            notifyFailure(channel, ChannelFailure.channelNotFound(code, reason));
        } else if (code == 401) {
            removeChannelIfCurrent(channel);
            notifyFailure(channel, ChannelFailure.authRequired(code, reason));
        } else if (code == 1000) {
            removeChannelIfCurrent(channel);
            notifyFailure(channel, ChannelFailure.remoteClosed(code, reason));
        } else {
            ChannelFailure failure = code >= 500 && code < 600
                ? ChannelFailure.serverTemporary(code, reason)
                : ChannelFailure.muxTemporary(code, reason);
            scheduleChannelRetry(channel, failure);
        }
    }

    private void dispatchBinaryFrame(int generation, byte[] data) {
        if (generation != transportGeneration || !physicalConnected) return;
        WebTermProtocol.TunnelFrame frame = WebTermProtocol.decodeTunnelFrame(data);
        if (frame == null) return;
        Channel channel = channels.get(frame.tunnelId);
        if (channel == null || channel.state != Channel.State.OPEN) return;
        boolean binary = (frame.extraByte & 0xff) == WebTermProtocol.WS_DATA_BINARY;
        channel.listener.onData(frame.tunnelId, frame.payload, binary);
    }

    private boolean sendChannelOpen(Channel channel) {
        if (!physicalConnected || transport == null) return false;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "ws-connect");
            msg.put("tunnelConnectionId", channel.id);
            msg.put("path", channel.path);
            if (channel.protocols != null && channel.protocols.length > 0) {
                JSONArray protocols = new JSONArray();
                for (String protocol : channel.protocols) protocols.put(protocol);
                msg.put("protocols", protocols);
            }
        } catch (JSONException ignored) {
            return false;
        }
        return transport.sendText(msg.toString());
    }

    private boolean sendChannelClose(String channelId) {
        if (!physicalConnected || transport == null) return false;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "ws-close");
            msg.put("tunnelConnectionId", channelId);
        } catch (JSONException ignored) {
            return false;
        }
        return transport.sendText(msg.toString());
    }

    private boolean sendTunnelFrameInternal(String channelId, byte[] payload, boolean binary) {
        if (!physicalConnected || transport == null) return false;
        return transport.sendBinary(WebTermProtocol.encodeTunnelFrame(channelId, payload, binary));
    }

    private boolean sendControlInternal(JSONObject msg) {
        return msg != null && physicalConnected && transport != null
            && transport.sendText(msg.toString());
    }

    private void stopPhysical() {
        physicalDesired = false;
        physicalConnected = false;
        physicalConnecting = false;
        physicalReconnectAttempts = 0;
        if (transport != null) transport.close();
    }

    public boolean matches(String baseUrl, String cookie, String deviceId) {
        // Cookie is a rotating auth token; identity is baseUrl + deviceId only.
        return this.baseUrl.equals(WebTermUrls.normalizeBaseUrl(baseUrl))
            && safeEquals(this.deviceId, deviceId);
    }

    public void updateCookie(String cookie) {
        String newCookie = cookie == null ? "" : cookie;
        runOnState(() -> {
            if (newCookie.equals(this.cookie)) return;
            this.cookie = newCookie;
            reconnectTransport("cookie updated");
        });
    }

    public boolean isConnected() {
        return callOnState(() -> physicalConnected, false);
    }

    boolean isIdle() {
        // 设备后台服务可以只占用 control plane，没有 logical channel。
        // 只要仍有 control listener，registry 就不能回收物理连接。
        return callOnState(() -> channels.isEmpty() && controlListener == null, true);
    }

    String deviceId() {
        return deviceId;
    }

    public void start() {
        runOnState(this::connectPhysical);
    }

    public void forceReconnect(String reason) {
        runOnState(() -> reconnectTransport(reason, true));
    }

    /**
     * 为一个 Terminal runtime 打开独占 screen channel。
     * ownerId 必须在 runtime 生命周期内稳定；新 owner 接管同一 session 时会先关闭旧
     * logical channel，禁止把新 Hello 发送到旧服务端 screen handler。
     */
    public String openScreenChannel(String localSessionId, String ownerId,
                                    ChannelListener listener) {
        String normalizedOwner = ownerId == null ? "" : ownerId.trim();
        if (normalizedOwner.isEmpty()) {
            throw new IllegalArgumentException("screen channel ownerId is required");
        }
        return callOnState(
            () -> openProtocolChannel(localSessionId, SCREEN_SUBPROTOCOL,
                normalizedOwner, listener),
            terminalChannelId(localSessionId, SCREEN_SUBPROTOCOL, normalizedOwner));
    }

    /** 仅保留给 core-session 包内的旧行为测试；产品代码必须显式提供 runtime owner。 */
    String openScreenChannel(String localSessionId, ChannelListener listener) {
        return openScreenChannel(localSessionId, "legacy", listener);
    }

    private String openProtocolChannel(String localSessionId, String subprotocol,
                                       String ownerId, ChannelListener listener) {
        String routeKey = terminalChannelRouteKey(localSessionId, subprotocol);
        String channelId = terminalChannelId(localSessionId, subprotocol, ownerId);
        String previousChannelId = screenChannelOwners.get(routeKey);
        if (previousChannelId != null && !previousChannelId.equals(channelId)) {
            supersedeScreenChannel(previousChannelId);
        }
        screenChannelOwners.put(routeKey, channelId);

        Channel existing = channels.get(channelId);
        if (existing != null) {
            boolean wasDetached = existing.listener == NO_OP_LISTENER;
            existing.listener = listener;
            if (wasDetached && existing.state == Channel.State.OPEN) {
                // 新终端对象接管仍存活的 logical id 时，显式重建远端 channel。
                markWaiting(existing);
                reconcileChannel(existing);
            } else if (existing.state == Channel.State.CLOSED) {
                reconcileChannel(existing);
            } else if (!wasDetached && existing.state == Channel.State.OPEN) {
                notifyConnected(existing);
            }
            // OPENING 只替换 listener，等待唯一在途 ws-connect 的 ACK。
            return channelId;
        }
        String path = "/ws/sessions/" + WebTermUrls.encodePath(localSessionId);
        openChannelInternal(channelId, path, new String[]{subprotocol}, routeKey, listener);
        return channelId;
    }

    private void supersedeScreenChannel(String channelId) {
        Channel previous = channels.get(channelId);
        if (previous == null) return;
        removeChannelIfCurrent(previous);
        previous.desiredOpen = false;
        previous.retryGeneration++;
        previous.openGeneration++;
        previous.state = Channel.State.CLOSED;
        sendChannelClose(previous.id);
        notifyFailure(previous,
            ChannelFailure.clientClosed(0, "screen channel superseded by a new runtime owner"));
    }

    public void detachChannelListener(String channelId) {
        runOnState(() -> {
            Channel ch = channels.get(channelId);
            if (ch != null) ch.listener = NO_OP_LISTENER;
        });
    }

    public void openChannel(String channelId, String path, String[] protocols, ChannelListener listener) {
        runOnState(() -> openChannelInternal(channelId, path, protocols, null, listener));
    }

    private void openChannelInternal(String channelId, String path, String[] protocols,
                                     String screenRouteKey, ChannelListener listener) {
        Channel previous = channels.put(channelId,
            new Channel(channelId, path, protocols, screenRouteKey, listener));
        if (previous != null) {
            clearScreenOwnerIfCurrent(previous);
            previous.desiredOpen = false;
            previous.retryGeneration++;
            previous.openGeneration++;
        }
        start();
        reconcileChannel(channels.get(channelId));
    }

    public void closeChannel(String channelId) {
        if (channelId == null || channelId.isEmpty()) return;
        runOnState(() -> closeChannelInternal(channelId));
    }

    private void closeChannelInternal(String channelId) {
        Channel channel = channels.get(channelId);
        if (channel != null) removeChannelIfCurrent(channel);
        if (channel != null) {
            channel.desiredOpen = false;
            channel.retryGeneration++;
            channel.openGeneration++;
            channel.state = Channel.State.CLOSED;
        }
        sendChannelClose(channelId);
    }

    void reconnectTransport(String reason) {
        reconnectTransport(reason, true);
    }

    private void reconnectTransport(String reason, boolean autoStart) {
        boolean wasDesired = physicalDesired;
        Log.i(TAG, "reconnect transport for " + deviceId + " reason=" + reason
            + " channels=" + channels.size() + " generation=" + (transportGeneration + 1));
        ChannelFailure failure = ChannelFailure.muxTemporary(0, reason);
        for (Channel channel : snapshotChannels()) {
            markWaiting(channel);
            notifyFailure(channel, failure);
        }
        stopPhysical();
        installTransport();
        if (autoStart && (wasDesired || !channels.isEmpty())) connectPhysical();
    }

    public boolean sendTunnelFrame(String channelId, byte[] payload, boolean binary) {
        return callOnState(() -> {
            Channel channel = channels.get(channelId);
            return channel != null && channel.state == Channel.State.OPEN
                && sendTunnelFrameInternal(channelId, payload, binary);
        }, false);
    }

    /** 注册设备级控制消息监听，不改变 screen channel 的所有权。 */
    public void setControlListener(ControlListener listener) {
        controlListener = listener;
    }

    public boolean sendControl(JSONObject msg) {
        return callOnState(() -> sendControlInternal(msg), false);
    }

    /** 配置当前 Android 安装实例身份；每次 mux 重连成功后都会重新注册。 */
    public void setClientRegistration(String clientId, String clientName) {
        this.clientId = clientId == null ? "" : clientId;
        this.clientName = clientName == null || clientName.trim().isEmpty() ? "Android" : clientName.trim();
        runOnState(() -> {
            if (physicalConnected) sendClientRegistration();
        });
    }

    /** 上报真实用户活跃；心跳与自动重连不调用此方法。 */
    public void markClientActive() {
        if (clientId.isEmpty()) return;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "client.active");
            msg.put("client_id", clientId);
        } catch (Exception ignored) { return; }
        runOnState(() -> sendControlInternal(msg));
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
        sendControlInternal(msg);
    }

    void stop() {
        runOnState(() -> {
            stopInternal();
            eventLoopShutdown.run();
        });
    }

    private void stopInternal() {
        for (Channel channel : snapshotChannels()) {
            sendChannelClose(channel.id);
        }
        channels.clear();
        screenChannelOwners.clear();
        stopPhysical();
    }

    private static String terminalChannelRouteKey(String localSessionId, String subprotocol) {
        return "term:" + localSessionId + ":" + subprotocol;
    }

    private static String terminalChannelId(String localSessionId, String subprotocol,
                                            String ownerId) {
        return terminalChannelRouteKey(localSessionId, subprotocol) + ":" + ownerId;
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

    private void reconcileChannels() {
        for (Channel channel : snapshotChannels()) {
            reconcileChannel(channel);
        }
    }

    private void reconcileChannel(Channel channel) {
        if (channel == null || !channel.desiredOpen || channel.state != Channel.State.CLOSED
                || !physicalConnected) return;
        if (sendChannelOpen(channel)) {
            channel.state = Channel.State.OPENING;
            long generation = ++channel.openGeneration;
                stateHandler.postDelayed(
                () -> onChannelOpenTimeout(channel.id, generation),
                CHANNEL_OPEN_TIMEOUT_MS);
        }
    }

    private void onChannelOpenTimeout(String channelId, long generation) {
        Channel channel = channels.get(channelId);
        if (channel == null || channel.state != Channel.State.OPENING
                || channel.openGeneration != generation) return;
        scheduleChannelRetry(channel,
            ChannelFailure.muxTemporary(0, "channel open timeout"));
    }

    private void scheduleChannelRetry(Channel channel, ChannelFailure failure) {
        if (channel == null || !channel.desiredOpen || channels.get(channel.id) != channel) return;
        channel.openGeneration++;
        channel.state = Channel.State.RETRY_WAIT;
        notifyFailure(channel, failure);
        int index = Math.min(channel.retryAttempt, CHANNEL_RETRY_BACKOFF_MS.length - 1);
        long delayMs = CHANNEL_RETRY_BACKOFF_MS[index];
        channel.retryAttempt++;
        long generation = ++channel.retryGeneration;
        stateHandler.postDelayed(() -> onChannelRetryDue(channel.id, generation), delayMs);
    }

    private void onChannelRetryDue(String channelId, long generation) {
        Channel channel = channels.get(channelId);
        if (channel == null || !channel.desiredOpen
                || channel.retryGeneration != generation
                || channel.state != Channel.State.RETRY_WAIT) return;
        channel.state = Channel.State.CLOSED;
        reconcileChannel(channel);
    }

    private static void markWaiting(Channel channel) {
        channel.openGeneration++;
        channel.retryGeneration++;
        channel.state = Channel.State.CLOSED;
    }

    private Channel[] snapshotChannels() {
        return channels.values().toArray(new Channel[0]);
    }

    private boolean removeChannelIfCurrent(Channel channel) {
        if (channel == null || channels.get(channel.id) != channel) return false;
        channels.remove(channel.id);
        clearScreenOwnerIfCurrent(channel);
        return true;
    }

    private void clearScreenOwnerIfCurrent(Channel channel) {
        if (channel == null || channel.screenRouteKey == null) return;
        if (channel.id.equals(screenChannelOwners.get(channel.screenRouteKey))) {
            screenChannelOwners.remove(channel.screenRouteKey);
        }
    }

    private void notifyConnected(Channel channel) {
        ChannelListener listener = channel.listener;
        callbackHandler.post(() -> listener.onConnected(channel.id));
    }

    private void notifyFailure(Channel channel, ChannelFailure failure) {
        ChannelListener listener = channel.listener;
        callbackHandler.post(() -> listener.onFailure(channel.id, failure));
    }

    private void runOnState(Runnable task) {
        if (isOnStateThread()) {
            task.run();
            return;
        }
        stateHandler.post(task);
    }

    private <T> T callOnState(Callable<T> task, T fallback) {
        if (isOnStateThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                Log.e(TAG, "device connection event loop task failed", e);
                return fallback;
            }
        }
        FutureTask<T> future = new FutureTask<>(task);
        if (!stateHandler.post(future)) return fallback;
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "device connection event loop did not respond", e);
            return fallback;
        }
    }

    private boolean isOnStateThread() {
        Looper looper = stateHandler.getLooper();
        return looper != null && looper.getThread() == Thread.currentThread();
    }

    public static boolean safeEquals(String a, String b) {
        if (a == null) return b == null || b.isEmpty();
        if (b == null) return a.isEmpty();
        return a.equals(b);
    }
}
