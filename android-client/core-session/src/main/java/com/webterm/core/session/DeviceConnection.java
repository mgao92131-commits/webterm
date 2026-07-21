package com.webterm.core.session;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.webterm.core.api.WebTermUrls;
import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.core.session.traffic.NetworkTrafficStats;
import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import org.json.JSONObject;

import java.util.UUID;
import java.util.Map;

public final class DeviceConnection {
    private static final String TAG = "DeviceConnection";
    private static final String SCREEN_SUBPROTOCOL = "webterm.screen.v1";
    private static final String MUX_SUBPROTOCOL = "webterm.mux.v1";
    private static final long CHANNEL_OPEN_TIMEOUT_MS = 10_000L;
    private static final long PHYSICAL_CONNECT_TIMEOUT_MS = 10_000L;
    private static final int MAX_PENDING_TUNNEL_FRAMES = 256;
    private static final long MAX_PENDING_TUNNEL_BYTES = 8L * 1024L * 1024L;
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

    public enum TunnelSendResult {
        WEBSOCKET_ENQUEUED,
        LOCAL_QUEUE_FULL,
        CHANNEL_NOT_OPEN,
        TRANSPORT_REJECTED,
        CONNECTION_STOPPED
    }

    public interface TunnelSendCallback {
        void onResult(TunnelSendResult result);
    }

    private static final ChannelListener NO_OP_LISTENER = new ChannelListener() {
        @Override public void onConnected(String channelId) {}
        @Override public void onData(String channelId, byte[] payload, boolean binary) {}
        @Override public void onFailure(String channelId, ChannelFailure failure) {}
    };

    private final Handler stateHandler;
    private final Handler callbackHandler;
    private final Runnable eventLoopShutdown;
    private final TransportFactory transportFactory;
    private final String baseUrl;
    private volatile String cookie;
    private final String deviceId;
    /** 跨物理 Mux 重连保持稳定；不同 Android 连接实例互不抢占。 */
    private final String screenOwnerId = UUID.randomUUID().toString();
    private MuxTransport transport;
    private int transportGeneration;
    private boolean physicalDesired;
    private volatile boolean physicalConnected;
    private boolean physicalConnecting;
    private int physicalReconnectAttempts;
    private final LogicalChannelRegistry channelRegistry = new LogicalChannelRegistry();
    private final MuxOutboundQueue outboundQueue =
        new MuxOutboundQueue(MAX_PENDING_TUNNEL_FRAMES, MAX_PENDING_TUNNEL_BYTES);
    private final MuxControlCodec controlCodec = new MuxControlCodec();
    private final DeviceControlPlane controlPlane;
    private volatile int activeChannelCount;

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
        this.controlPlane = new DeviceControlPlane(this::sendControlInternal);
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
        if (transport != null) {
            transport.setTrafficAccumulator(
                NetworkTrafficStats.accumulatorForConnection(baseUrl, deviceId));
        }
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
        controlPlane.onConnected();
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
        Diagnostics.warn("device_connection", "channel_failed", physicalFields(
            "transportGeneration", generation,
            "failureKind", failure.kind.name(),
            "closeCode", code,
            "stateBefore", "CONNECTED",
            "stateAfter", failure.kind == ChannelFailure.Kind.AUTH_REQUIRED ? "STOPPED" : "RETRY_WAIT"));
        notifyPhysicalFailure(failure);
        if (failure.kind == ChannelFailure.Kind.AUTH_REQUIRED) {
            physicalDesired = false;
            if (transport != null) transport.close();
            return;
        }
        schedulePhysicalReconnect();
    }

    private void notifyPhysicalFailure(ChannelFailure failure) {
        for (LogicalChannelRegistry.Channel channel : snapshotChannels()) {
            markWaiting(channel);
            notifyFailure(channel, failure);
        }
    }

    private void schedulePhysicalReconnect() {
        if (!physicalDesired) return;
        int attempt = ++physicalReconnectAttempts;
        for (LogicalChannelRegistry.Channel channel : snapshotChannels()) {
            ChannelListener listener = channel.listener;
            callbackHandler.post(() -> listener.onReconnectAttempt(attempt));
        }
        long cap = Math.min(1_000L * attempt, 8_000L);
        long delayMs = Math.max(200L, (long) (Math.random() * cap));
        Diagnostics.info("device_connection", "physical_reconnect_scheduled", physicalFields(
            "transportGeneration", transportGeneration,
            "retryAttempt", attempt,
            "delayMs", delayMs,
            "stateBefore", "DISCONNECTED",
            "stateAfter", "RETRY_WAIT"));
        int generation = transportGeneration;
        stateHandler.postDelayed(() -> {
            if (physicalDesired && generation == transportGeneration && !physicalConnected) {
                connectPhysical();
            }
        }, delayMs);
    }

    private void handleControlMessage(int generation, String text) {
        if (generation != transportGeneration || !physicalConnected) return;
        MuxControlCodec.Message msg = controlCodec.decode(text);
        if (msg == null) return;
        String type = msg.type;
        String tunnelId = msg.channelId;
        if ("ws-connected".equals(type)) {
            onTunnelConnected(tunnelId);
        } else if ("ws-error".equals(type)) {
            onTunnelError(tunnelId, msg.code, msg.message);
        } else if ("ws-close".equals(type)) {
            onTunnelClosed(tunnelId, msg.code == 0 ? 1000 : msg.code, msg.reason);
        } else if (type != null && !type.isEmpty()) {
            ControlListener listener = controlPlane.listener();
            if (listener != null) callbackHandler.post(() -> listener.onControlMessage(msg.raw));
        }
    }

    private void onTunnelConnected(String tunnelId) {
        LogicalChannelRegistry.Channel channel = channelRegistry.get(tunnelId);
        if (channel != null && channel.desiredOpen && channel.state == LogicalChannelRegistry.Channel.State.OPENING) {
            channel.openGeneration++;
            channel.retryGeneration++;
            channel.retryAttempt = 0;
            channel.state = LogicalChannelRegistry.Channel.State.OPEN;
            Diagnostics.info("device_connection", "channel_connected", channelFields(channel,
                "stateBefore", "OPENING", "stateAfter", "OPEN"));
            notifyConnected(channel);
        }
    }

    private void onTunnelError(String tunnelId, int code, String message) {
        LogicalChannelRegistry.Channel channel = channelRegistry.get(tunnelId);
        if (channel == null) return;
        Diagnostics.warn("device_connection", "channel_failed", channelFields(channel,
            "failureKind", channelFailureKind(code),
            "closeCode", code,
            "stateBefore", channel.state.name(),
            "stateAfter", code == 401 || code == 404 ? "CLOSED" : "RETRY_WAIT"));
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
        LogicalChannelRegistry.Channel channel = channelRegistry.get(tunnelId);
        if (channel == null) return;
        Diagnostics.info("device_connection", "channel_closed", channelFields(channel,
            "closeCode", code,
            "stateBefore", channel.state.name(),
            "stateAfter", code == 1000 || code == 401 || code == 404 ? "CLOSED" : "RETRY_WAIT"));
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
        LogicalChannelRegistry.Channel channel = channelRegistry.get(frame.tunnelId);
        if (channel == null || channel.state != LogicalChannelRegistry.Channel.State.OPEN) return;
        boolean binary = (frame.extraByte & 0xff) == WebTermProtocol.WS_DATA_BINARY;
        channel.listener.onData(frame.tunnelId, frame.payload, binary);
    }

    private boolean sendChannelOpen(LogicalChannelRegistry.Channel channel) {
        if (!physicalConnected || transport == null) return false;
        Diagnostics.info("device_connection", "channel_open_requested", channelFields(channel,
            "stateBefore", channel.state.name(), "stateAfter", "OPENING"));
        String message = controlCodec.connect(channel.id, channel.path, channel.screenRouteKey,
            screenOwnerId + ":" + channel.screenRouteKey, channel.protocols);
        return message != null && transport.sendText(message);
    }

    private boolean sendChannelClose(String channelId) {
        if (!physicalConnected || transport == null) return false;
        String message = controlCodec.close(channelId);
        return message != null && transport.sendText(message);
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
        return physicalConnected;
    }

    boolean isIdle() {
        // 设备后台服务可以只占用 control plane，没有 logical channel。
        // 只要仍有 control listener，registry 就不能回收物理连接。
        return activeChannelCount == 0 && !controlPlane.hasListener();
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
        String channelId = terminalChannelId(localSessionId, SCREEN_SUBPROTOCOL, normalizedOwner);
        runOnState(() -> openProtocolChannel(
            localSessionId, SCREEN_SUBPROTOCOL, normalizedOwner, listener));
        return channelId;
    }

    /** 仅保留给 core-session 包内的旧行为测试；产品代码必须显式提供 runtime owner。 */
    String openScreenChannel(String localSessionId, ChannelListener listener) {
        return openScreenChannel(localSessionId, "legacy", listener);
    }

    private String openProtocolChannel(String localSessionId, String subprotocol,
                                       String ownerId, ChannelListener listener) {
        String routeKey = terminalChannelRouteKey(localSessionId, subprotocol);
        String channelId = terminalChannelId(localSessionId, subprotocol, ownerId);
        String previousChannelId = channelRegistry.claimScreenOwner(routeKey, channelId);
        if (previousChannelId != null && !previousChannelId.equals(channelId)) {
            supersedeScreenChannel(previousChannelId);
        }
        LogicalChannelRegistry.Channel existing = channelRegistry.get(channelId);
        if (existing != null) {
            boolean wasDetached = existing.listener == NO_OP_LISTENER;
            existing.listener = listener;
            if (wasDetached && existing.state == LogicalChannelRegistry.Channel.State.OPEN) {
                // 新终端对象接管仍存活的 logical id 时，显式重建远端 channel。
                markWaiting(existing);
                reconcileChannel(existing);
            } else if (existing.state == LogicalChannelRegistry.Channel.State.CLOSED) {
                reconcileChannel(existing);
            } else if (!wasDetached && existing.state == LogicalChannelRegistry.Channel.State.OPEN) {
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
        LogicalChannelRegistry.Channel previous = channelRegistry.get(channelId);
        if (previous == null) return;
        removeChannelIfCurrent(previous);
        previous.desiredOpen = false;
        previous.retryGeneration++;
        previous.openGeneration++;
        previous.state = LogicalChannelRegistry.Channel.State.CLOSED;
        boolean closeSent = sendChannelClose(previous.id);
        notifyFailure(previous,
            ChannelFailure.clientClosed(0, "screen channel superseded by a new runtime owner"));
        if (physicalConnected && !closeSent) {
            // 本地已经放弃旧 channel，不能再重试该 ws-close。强制重建物理 Mux，
            // 让 Go 的 closeAllChannels 释放旧 handler，再在新连接上打开当前 owner。
            reconnectTransport("failed to close superseded screen channel", true);
        }
    }

    public void detachChannelListener(String channelId) {
        runOnState(() -> {
            LogicalChannelRegistry.Channel ch = channelRegistry.get(channelId);
            if (ch != null) ch.listener = NO_OP_LISTENER;
        });
    }

    public void openChannel(String channelId, String path, String[] protocols, ChannelListener listener) {
        runOnState(() -> openChannelInternal(channelId, path, protocols, null, listener));
    }

    private void openChannelInternal(String channelId, String path, String[] protocols,
                                     String screenRouteKey, ChannelListener listener) {
        LogicalChannelRegistry.Channel created =
            new LogicalChannelRegistry.Channel(channelId, path, protocols, screenRouteKey, listener);
        LogicalChannelRegistry.Channel previous = channelRegistry.put(created);
        activeChannelCount = channelRegistry.size();
        if (previous != null) {
            clearScreenOwnerIfCurrent(previous);
            previous.desiredOpen = false;
            previous.retryGeneration++;
            previous.openGeneration++;
        }
        start();
        reconcileChannel(channelRegistry.get(channelId));
    }

    public void closeChannel(String channelId) {
        if (channelId == null || channelId.isEmpty()) return;
        runOnState(() -> closeChannelInternal(channelId));
    }

    private void closeChannelInternal(String channelId) {
        LogicalChannelRegistry.Channel channel = channelRegistry.get(channelId);
        if (channel != null) removeChannelIfCurrent(channel);
        if (channel != null) {
            channel.desiredOpen = false;
            channel.retryGeneration++;
            channel.openGeneration++;
            channel.state = LogicalChannelRegistry.Channel.State.CLOSED;
        }
        boolean closeSent = sendChannelClose(channelId);
        if (channel != null && channel.screenRouteKey != null
                && physicalConnected && !closeSent) {
            reconnectTransport("failed to close screen channel", true);
        }
    }

    void reconnectTransport(String reason) {
        reconnectTransport(reason, true);
    }

    private void reconnectTransport(String reason, boolean autoStart) {
        if (physicalConnecting) {
            // 当前 generation 已有在途连接（例如 drain 循环中上一帧刚触发重建）。
            // 重复重建只会串联创建多个 Transport，其中只有一个能活到 ws_open；
            // 在途连接失败时仍由断线/超时路径退避重连。
            Log.i(TAG, "skip duplicate transport reconnect for " + deviceId
                + " reason=" + reason + " generation=" + transportGeneration);
            return;
        }
        boolean wasDesired = physicalDesired;
        Log.i(TAG, "reconnect transport for " + deviceId + " reason=" + reason
            + " channels=" + channelRegistry.size() + " generation=" + (transportGeneration + 1));
        ChannelFailure failure = ChannelFailure.muxTemporary(0, reason);
        for (LogicalChannelRegistry.Channel channel : snapshotChannels()) {
            markWaiting(channel);
            notifyFailure(channel, failure);
        }
        stopPhysical();
        installTransport();
        if (autoStart && (wasDesired || channelRegistry.size() > 0)) connectPhysical();
    }

    public boolean sendTunnelFrame(String channelId, byte[] payload, boolean binary) {
        return tryEnqueueTunnelFrame(channelId, payload, binary, result -> {
            if (result == TunnelSendResult.CHANNEL_NOT_OPEN) {
                recoverDroppedTunnelFrame(channelId,
                    "tunnel frame reached a closed logical channel");
            }
            // TRANSPORT_REJECTED 已在唯一 event loop 中原子触发重连；
            // CONNECTION_STOPPED 是显式生命周期终点，不允许重新拉起连接。
        });
    }

    private void recoverDroppedTunnelFrame(String channelId, String reason) {
        runOnState(() -> {
            LogicalChannelRegistry.Channel channel = channelRegistry.get(channelId);
            // A frame drained after an intentional logical-channel close belongs to
            // the old lifecycle and must not reconnect the shared physical socket.
            if (channel != null && channel.desiredOpen) {
                reconnectTransport(reason, true);
            }
        });
    }

    /**
     * 非阻塞地进入设备级有界发送队列。true 仅表示本地队列已接受；最终是否进入
     * OkHttp/WebSocket 队列通过 callback 报告。所有 channel 状态检查和物理写入仍由
     * DeviceConnection 的唯一 event loop 串行执行。
     */
    public boolean tryEnqueueTunnelFrame(String channelId, byte[] payload, boolean binary,
                                         TunnelSendCallback callback) {
        if (channelId == null || channelId.isEmpty() || payload == null) {
            Diagnostics.warn("device_connection", "outbound_queue_rejected", physicalFields(
                "channelId", channelId == null ? "" : channelId,
                "failureKind", "INVALID_FRAME"));
            notifySendResult(callback, TunnelSendResult.CHANNEL_NOT_OPEN);
            return false;
        }
        MuxOutboundQueue.Offer offer = outboundQueue.offer(channelId, payload, binary,
            result -> notifySendResult(callback, mapSendResult(result)));
        if (offer.result != MuxOutboundQueue.Result.LOCAL_ACCEPTED) {
            Diagnostics.warn("device_connection", "outbound_queue_rejected", physicalFields(
                "channelId", channelId,
                "failureKind", offer.result.name()));
            notifySendResult(callback, mapSendResult(offer.result));
            return false;
        }
        if (offer.scheduleDrain && !stateHandler.post(this::drainTunnelFrames)) {
            failPendingTunnelFrames(TunnelSendResult.CONNECTION_STOPPED);
            return false;
        }
        return true;
    }

    /** 注册设备级控制消息监听，不改变 screen channel 的所有权。 */
    public void setControlListener(ControlListener listener) {
        controlPlane.setListener(listener);
    }

    public boolean sendControl(JSONObject msg) {
        if (msg == null) return false;
        return stateHandler.post(() -> {
            if (!sendControlInternal(msg)) {
                reconnectTransport("control frame enqueue rejected", true);
            }
        });
    }

    /** 配置当前 Android 安装实例身份；每次 mux 重连成功后都会重新注册。 */
    public void setClientRegistration(String clientId, String clientName) {
        controlPlane.setRegistration(clientId, clientName);
        runOnState(() -> {
            if (physicalConnected) controlPlane.onConnected();
        });
    }

    /** 上报真实用户活跃；心跳与自动重连不调用此方法。 */
    public void markClientActive() {
        runOnState(controlPlane::markActive);
    }

    void stop() {
        runOnState(() -> {
            stopInternal();
            eventLoopShutdown.run();
        });
    }

    private void stopInternal() {
        for (LogicalChannelRegistry.Channel channel : snapshotChannels()) {
            sendChannelClose(channel.id);
        }
        channelRegistry.clear();
        activeChannelCount = 0;
        failPendingTunnelFrames(TunnelSendResult.CONNECTION_STOPPED);
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
        for (LogicalChannelRegistry.Channel channel : snapshotChannels()) {
            reconcileChannel(channel);
        }
    }

    private void reconcileChannel(LogicalChannelRegistry.Channel channel) {
        if (channel == null || !channel.desiredOpen || channel.state != LogicalChannelRegistry.Channel.State.CLOSED
                || !physicalConnected) return;
        if (sendChannelOpen(channel)) {
            channel.state = LogicalChannelRegistry.Channel.State.OPENING;
            long generation = ++channel.openGeneration;
                stateHandler.postDelayed(
                () -> onChannelOpenTimeout(channel.id, generation),
                CHANNEL_OPEN_TIMEOUT_MS);
        }
    }

    private void onChannelOpenTimeout(String channelId, long generation) {
        LogicalChannelRegistry.Channel channel = channelRegistry.get(channelId);
        if (channel == null || channel.state != LogicalChannelRegistry.Channel.State.OPENING
                || channel.openGeneration != generation) return;
        scheduleChannelRetry(channel,
            ChannelFailure.muxTemporary(0, "channel open timeout"));
    }

    private void scheduleChannelRetry(LogicalChannelRegistry.Channel channel, ChannelFailure failure) {
        if (channel == null || !channel.desiredOpen || channelRegistry.get(channel.id) != channel) return;
        channel.openGeneration++;
        channel.state = LogicalChannelRegistry.Channel.State.RETRY_WAIT;
        notifyFailure(channel, failure);
        int index = Math.min(channel.retryAttempt, CHANNEL_RETRY_BACKOFF_MS.length - 1);
        long delayMs = CHANNEL_RETRY_BACKOFF_MS[index];
        channel.retryAttempt++;
        long generation = ++channel.retryGeneration;
        stateHandler.postDelayed(() -> onChannelRetryDue(channel.id, generation), delayMs);
    }

    private void onChannelRetryDue(String channelId, long generation) {
        LogicalChannelRegistry.Channel channel = channelRegistry.get(channelId);
        if (channel == null || !channel.desiredOpen
                || channel.retryGeneration != generation
                || channel.state != LogicalChannelRegistry.Channel.State.RETRY_WAIT) return;
        channel.state = LogicalChannelRegistry.Channel.State.CLOSED;
        reconcileChannel(channel);
    }

    private static void markWaiting(LogicalChannelRegistry.Channel channel) {
        channel.openGeneration++;
        channel.retryGeneration++;
        channel.state = LogicalChannelRegistry.Channel.State.CLOSED;
    }

    private LogicalChannelRegistry.Channel[] snapshotChannels() {
        return channelRegistry.snapshot();
    }

    private boolean removeChannelIfCurrent(LogicalChannelRegistry.Channel channel) {
        boolean removed = channelRegistry.removeIfCurrent(channel);
        if (removed) activeChannelCount = channelRegistry.size();
        return removed;
    }

    private void clearScreenOwnerIfCurrent(LogicalChannelRegistry.Channel channel) {
        channelRegistry.clearScreenOwnerIfCurrent(channel);
    }

    private void notifyConnected(LogicalChannelRegistry.Channel channel) {
        ChannelListener listener = channel.listener;
        callbackHandler.post(() -> listener.onConnected(channel.id));
    }

    private void notifyFailure(LogicalChannelRegistry.Channel channel, ChannelFailure failure) {
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

    private void drainTunnelFrames() {
        while (true) {
            MuxOutboundQueue.Frame frame = outboundQueue.poll();
            if (frame == null) return;
            LogicalChannelRegistry.Channel channel = channelRegistry.get(frame.channelId);
            if (channel == null || channel.state != LogicalChannelRegistry.Channel.State.OPEN) {
                Diagnostics.warn("device_connection", "outbound_queue_rejected", physicalFields(
                    "channelId", frame.channelId,
                    "failureKind", "CHANNEL_NOT_OPEN"));
                frame.completion.onResult(MuxOutboundQueue.Result.CHANNEL_NOT_OPEN);
                continue;
            }
            if (sendTunnelFrameInternal(frame.channelId, frame.payload, frame.binary)) {
                frame.completion.onResult(MuxOutboundQueue.Result.WEBSOCKET_ENQUEUED);
                continue;
            }
            frame.completion.onResult(MuxOutboundQueue.Result.TRANSPORT_REJECTED);
            Diagnostics.warn("device_connection", "outbound_queue_rejected", physicalFields(
                "channelId", frame.channelId,
                "failureKind", "TRANSPORT_REJECTED"));
            reconnectTransport("tunnel frame enqueue rejected", true);
        }
    }

    private void failPendingTunnelFrames(TunnelSendResult result) {
        for (MuxOutboundQueue.Frame frame : outboundQueue.stopAndDrain()) {
            frame.completion.onResult(mapQueueResult(result));
        }
    }

    private static TunnelSendResult mapSendResult(MuxOutboundQueue.Result result) {
        switch (result) {
            case WEBSOCKET_ENQUEUED:
                return TunnelSendResult.WEBSOCKET_ENQUEUED;
            case QUEUE_FULL:
                return TunnelSendResult.LOCAL_QUEUE_FULL;
            case CHANNEL_NOT_OPEN:
                return TunnelSendResult.CHANNEL_NOT_OPEN;
            case TRANSPORT_REJECTED:
                return TunnelSendResult.TRANSPORT_REJECTED;
            case CONNECTION_STOPPED:
                return TunnelSendResult.CONNECTION_STOPPED;
            case LOCAL_ACCEPTED:
            default:
                throw new IllegalArgumentException("local acceptance is not a completion result");
        }
    }

    private static MuxOutboundQueue.Result mapQueueResult(TunnelSendResult result) {
        switch (result) {
            case WEBSOCKET_ENQUEUED:
                return MuxOutboundQueue.Result.WEBSOCKET_ENQUEUED;
            case LOCAL_QUEUE_FULL:
                return MuxOutboundQueue.Result.QUEUE_FULL;
            case CHANNEL_NOT_OPEN:
                return MuxOutboundQueue.Result.CHANNEL_NOT_OPEN;
            case TRANSPORT_REJECTED:
                return MuxOutboundQueue.Result.TRANSPORT_REJECTED;
            case CONNECTION_STOPPED:
            default:
                return MuxOutboundQueue.Result.CONNECTION_STOPPED;
        }
    }

    private void notifySendResult(TunnelSendCallback callback, TunnelSendResult result) {
        if (callback != null) callbackHandler.post(() -> callback.onResult(result));
    }

    private boolean isOnStateThread() {
        Looper looper = stateHandler.getLooper();
        return looper != null && looper.getThread() == Thread.currentThread();
    }

    private Map<String, Object> physicalFields(Object... pairs) {
        java.util.HashMap<String, Object> fields = new java.util.HashMap<>();
        fields.put("deviceId", deviceId);
        fields.put("transportGeneration", transportGeneration);
        fields.put("activeChannelCount", activeChannelCount);
        addFields(fields, pairs);
        return fields;
    }

    private Map<String, Object> channelFields(LogicalChannelRegistry.Channel channel, Object... pairs) {
        java.util.HashMap<String, Object> fields = new java.util.HashMap<>();
        fields.put("deviceId", deviceId);
        fields.put("channelId", channel.id);
        fields.put("transportGeneration", transportGeneration);
        fields.put("activeChannelCount", activeChannelCount);
        addFields(fields, pairs);
        return fields;
    }

    private static void addFields(Map<String, Object> fields, Object... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            fields.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
    }

    private static String channelFailureKind(int code) {
        if (code == 401) return "AUTH_REQUIRED";
        if (code == 404) return "CHANNEL_NOT_FOUND";
        return code >= 500 && code < 600 ? "SERVER_TEMPORARY" : "MUX_TEMPORARY";
    }

    public static boolean safeEquals(String a, String b) {
        if (a == null) return b == null || b.isEmpty();
        if (b == null) return a.isEmpty();
        return a.equals(b);
    }
}
