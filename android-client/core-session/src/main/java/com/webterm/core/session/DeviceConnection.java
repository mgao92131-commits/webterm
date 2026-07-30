package com.webterm.core.session;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.webterm.core.api.SessionIds;
import com.webterm.core.api.WebTermUrls;
import com.webterm.core.contract.diagnostics.DiagnosticIdHasher;
import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.core.session.traffic.NetworkTrafficStats;
import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import org.json.JSONObject;

import java.util.UUID;
import java.nio.ByteBuffer;
import java.util.Map;

public final class DeviceConnection {
    private static final String TAG = "DeviceConnection";
    private static final String SCREEN_SUBPROTOCOL = "webterm.screen.v2";
    private static final String MUX_SUBPROTOCOL = "webterm.mux.v1";
    private static final long CHANNEL_OPEN_TIMEOUT_MS = 10_000L;
    private static final long CHANNEL_CLOSE_TIMEOUT_MS = 10_000L;
    private static final long PHYSICAL_CONNECT_TIMEOUT_MS = 10_000L;
    private static final int MAX_PENDING_TUNNEL_FRAMES = 256;
    private static final long MAX_PENDING_TUNNEL_BYTES = 8L * 1024L * 1024L;
    private static final long[] CHANNEL_RETRY_BACKOFF_MS = {
        1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L
    };

    public interface ChannelListener {
        void onConnected(String channelId);
        void onData(String channelId, byte[] payload, boolean binary);
        default void onDataBuffer(String channelId, ByteBuffer payload, boolean binary) {
            ByteBuffer source = payload == null
                ? ByteBuffer.allocate(0) : payload.asReadOnlyBuffer();
            byte[] copied = new byte[source.remaining()];
            source.get(copied);
            onData(channelId, copied, binary);
        }

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
    private long credentialGeneration = 1L;
    private long installedCredentialGeneration;
    private boolean credentialsInvalidated;
    private final String deviceId;
    /** 跨物理 Mux 重连保持稳定；不同 Android 连接实例互不抢占。 */
    private final String screenOwnerId = UUID.randomUUID().toString();
    private MuxTransport transport;
    private int transportGeneration;
    /** 单次物理连接 attempt 的诊断 ID（非 transportGeneration）。 */
    private String connectionId = "";
    private long connectionStartedAtNanos;
    /** 物理 WS 已 open 的单调时钟；未连接时为 0。 */
    private long connectedAtNanos;
    private String recoveryId;
    private long recoveryStartedAtNanos;
    private int recoveryAttemptCount;
    private ChannelFailure.Kind recoveryInitialFailure;
    private boolean physicalDesired;
    private volatile boolean physicalConnected;
    private boolean physicalConnecting;
    /** 自然断线后延迟到下一次真实 connect attempt 才用最新凭据重建 Transport。 */
    private boolean transportRecreateRequired;
    private int physicalReconnectAttempts;
    /** 最近处理的 Android Network handle；仅在 state handler 上访问。 */
    private long lastHandledNetworkGeneration = Long.MIN_VALUE;
    private volatile boolean stopped;
    private volatile ConnectionCloseReason lastCloseReason;
    private final LogicalChannelRegistry channelRegistry = new LogicalChannelRegistry();
    private final MuxOutboundQueue outboundQueue =
        new MuxOutboundQueue(MAX_PENDING_TUNNEL_FRAMES, MAX_PENDING_TUNNEL_BYTES);
    private final MuxInboundMailbox inboundMailbox = new MuxInboundMailbox();
    private final Runnable inboundDrainRunnable = this::drainInboundEvents;
    private final MuxControlCodec controlCodec = new MuxControlCodec();
    private final DeviceControlPlane controlPlane;
    private volatile int activeChannelCount;

    private long staleTransportGenerationDropped;
    private long tunnelDecodeFailed;
    private long unknownChannelDropped;
    private long channelNotOpenDropped;
    private long normalCloseTailDropped;
    private long staleChannelLifecycleDropped;
    private long channelIdReusedDropped;
    private long wrongConnectionMappingDropped;
    private long framesWhileClosing;
    private long bytesWhileClosing;
    private long framesAfterCloseAck;
    private long bytesAfterCloseAck;
    private long closeRequestToAckCount;
    private long closeRequestToAckTotalMs;
    private long closeRequestToAckMaxMs;
    /** Agent recoveryHash 清除失败后，待下次控制发送机会重试。 */
    private boolean agentRecoveryContextClearPending;
    private boolean clearingAgentRecoveryContext;
    /** 仅在 state handler 上发布；导出线程只读此不可变快照。 */
    private volatile DiagnosticsSnapshot publishedDiagnosticsSnapshot;

    public static final class InboundDropSnapshot {
        public final long staleTransportGenerationDropped;
        public final long tunnelDecodeFailed;
        public final long unknownChannelDropped;
        public final long channelNotOpenDropped;
        public final long normalCloseTailDropped;
        public final long staleChannelLifecycleDropped;
        public final long channelIdReusedDropped;
        public final long wrongConnectionMappingDropped;
        public final long framesWhileClosing;
        public final long bytesWhileClosing;
        public final long framesAfterCloseAck;
        public final long bytesAfterCloseAck;
        public final long closeRequestToAckCount;
        public final long closeRequestToAckTotalMs;
        public final long closeRequestToAckMaxMs;

        InboundDropSnapshot(long staleTransportGenerationDropped, long tunnelDecodeFailed,
                            long unknownChannelDropped, long channelNotOpenDropped,
                            long normalCloseTailDropped, long staleChannelLifecycleDropped,
                            long channelIdReusedDropped, long wrongConnectionMappingDropped,
                            long framesWhileClosing, long bytesWhileClosing,
                            long framesAfterCloseAck, long bytesAfterCloseAck,
                            long closeRequestToAckCount, long closeRequestToAckTotalMs,
                            long closeRequestToAckMaxMs) {
            this.staleTransportGenerationDropped = staleTransportGenerationDropped;
            this.tunnelDecodeFailed = tunnelDecodeFailed;
            this.unknownChannelDropped = unknownChannelDropped;
            this.channelNotOpenDropped = channelNotOpenDropped;
            this.normalCloseTailDropped = normalCloseTailDropped;
            this.staleChannelLifecycleDropped = staleChannelLifecycleDropped;
            this.channelIdReusedDropped = channelIdReusedDropped;
            this.wrongConnectionMappingDropped = wrongConnectionMappingDropped;
            this.framesWhileClosing = framesWhileClosing;
            this.bytesWhileClosing = bytesWhileClosing;
            this.framesAfterCloseAck = framesAfterCloseAck;
            this.bytesAfterCloseAck = bytesAfterCloseAck;
            this.closeRequestToAckCount = closeRequestToAckCount;
            this.closeRequestToAckTotalMs = closeRequestToAckTotalMs;
            this.closeRequestToAckMaxMs = closeRequestToAckMaxMs;
        }
    }

    /** 诊断导出用连接快照（不含 Cookie / 原始 URL 明文落盘由导出层 hash）。 */
    public static final class DiagnosticsSnapshot {
        public final String baseUrl;
        public final String deviceId;
        public final String connectionId;
        public final String recoveryId;
        public final boolean physicalDesired;
        public final boolean physicalConnected;
        public final boolean physicalConnecting;
        public final boolean stopped;
        public final int transportGeneration;
        public final int activeChannelCount;
        public final ConnectionCloseReason lastCloseReason;
        /** 连接 attempt 起点（nanoTime）；未开始为 0。导出请用 {@link #connectElapsedMsNow()}。 */
        public final long connectionStartedAtNanos;
        /** 物理已 open 的单调时钟；未连接为 0。导出请用 {@link #connectedElapsedMsNow()}。 */
        public final long connectedAtNanos;
        /** 快照发布时刻（nanoTime）。 */
        public final long snapshotPublishedAtNanos;
        /** 发布时刻冻结的 connect 耗时；活跃连接导出优先用 {@link #connectElapsedMsNow()}。 */
        public final long connectElapsedMs;
        /** 发布时刻冻结的已连接存活时长；活跃连接导出优先用 {@link #connectedElapsedMsNow()}。 */
        public final long connectedElapsedMs;
        public final long recoveryStartedAtNanos;
        public final int recoveryAttemptCount;
        public final String recoveryInitialFailureKind;
        /** Agent recoveryHash 清除失败后待重试。 */
        public final boolean agentRecoveryContextClearPending;
        public final MuxOutboundQueue.Snapshot outboundQueue;
        public final InboundDropSnapshot inboundDrops;
        public final MuxInboundMailbox.Snapshot inboundMailbox;
        /** 仅 recentClosed 填充；活跃快照为 0。 */
        public final long closedAtEpochMs;
        /** 仅 recentClosed 填充；活跃快照为空。 */
        public final String closeReason;

        DiagnosticsSnapshot(String baseUrl, String deviceId, String connectionId, String recoveryId,
                            boolean physicalDesired, boolean physicalConnected,
                            boolean physicalConnecting, boolean stopped,
                            int transportGeneration, int activeChannelCount,
                            ConnectionCloseReason lastCloseReason,
                            long connectionStartedAtNanos, long connectedAtNanos,
                            long snapshotPublishedAtNanos,
                            long connectElapsedMs, long connectedElapsedMs,
                            long recoveryStartedAtNanos,
                            int recoveryAttemptCount, String recoveryInitialFailureKind,
                            boolean agentRecoveryContextClearPending,
                            MuxOutboundQueue.Snapshot outboundQueue,
                            InboundDropSnapshot inboundDrops,
                            MuxInboundMailbox.Snapshot inboundMailbox) {
            this(baseUrl, deviceId, connectionId, recoveryId, physicalDesired, physicalConnected,
                physicalConnecting, stopped, transportGeneration, activeChannelCount,
                lastCloseReason, connectionStartedAtNanos, connectedAtNanos, snapshotPublishedAtNanos,
                connectElapsedMs, connectedElapsedMs, recoveryStartedAtNanos,
                recoveryAttemptCount, recoveryInitialFailureKind, agentRecoveryContextClearPending,
                outboundQueue, inboundDrops, inboundMailbox, 0L, "");
        }

        DiagnosticsSnapshot(String baseUrl, String deviceId, String connectionId, String recoveryId,
                            boolean physicalDesired, boolean physicalConnected,
                            boolean physicalConnecting, boolean stopped,
                            int transportGeneration, int activeChannelCount,
                            ConnectionCloseReason lastCloseReason,
                            long connectionStartedAtNanos, long connectedAtNanos,
                            long snapshotPublishedAtNanos,
                            long connectElapsedMs, long connectedElapsedMs,
                            long recoveryStartedAtNanos,
                            int recoveryAttemptCount, String recoveryInitialFailureKind,
                            boolean agentRecoveryContextClearPending,
                            MuxOutboundQueue.Snapshot outboundQueue,
                            InboundDropSnapshot inboundDrops,
                            MuxInboundMailbox.Snapshot inboundMailbox,
                            long closedAtEpochMs, String closeReason) {
            this.baseUrl = baseUrl != null ? baseUrl : "";
            this.deviceId = deviceId != null ? deviceId : "";
            this.connectionId = connectionId != null ? connectionId : "";
            this.recoveryId = recoveryId;
            this.physicalDesired = physicalDesired;
            this.physicalConnected = physicalConnected;
            this.physicalConnecting = physicalConnecting;
            this.stopped = stopped;
            this.transportGeneration = transportGeneration;
            this.activeChannelCount = activeChannelCount;
            this.lastCloseReason = lastCloseReason;
            this.connectionStartedAtNanos = connectionStartedAtNanos;
            this.connectedAtNanos = connectedAtNanos;
            this.snapshotPublishedAtNanos = snapshotPublishedAtNanos;
            this.connectElapsedMs = connectElapsedMs;
            this.connectedElapsedMs = connectedElapsedMs;
            this.recoveryStartedAtNanos = recoveryStartedAtNanos;
            this.recoveryAttemptCount = recoveryAttemptCount;
            this.recoveryInitialFailureKind =
                recoveryInitialFailureKind != null ? recoveryInitialFailureKind : "";
            this.agentRecoveryContextClearPending = agentRecoveryContextClearPending;
            this.outboundQueue = outboundQueue;
            this.inboundDrops = inboundDrops;
            this.inboundMailbox = inboundMailbox;
            this.closedAtEpochMs = closedAtEpochMs;
            this.closeReason = closeReason != null ? closeReason : "";
        }

        /** 自 connectedAtNanos 起的实时存活时长；未连接为 0。 */
        public long connectedElapsedMsNow() {
            if (connectedAtNanos <= 0L) return 0L;
            return Math.max(0L, (System.nanoTime() - connectedAtNanos) / 1_000_000L);
        }

        /**
         * connecting 期间随时间增长；已连接则冻结为 open 时刻相对 started 的耗时；
         * 否则回落发布时刻冻结值。
         */
        public long connectElapsedMsNow() {
            if (connectionStartedAtNanos <= 0L) return 0L;
            if (physicalConnected && connectedAtNanos > 0L) {
                return Math.max(0L, (connectedAtNanos - connectionStartedAtNanos) / 1_000_000L);
            }
            if (physicalConnecting) {
                return Math.max(0L, (System.nanoTime() - connectionStartedAtNanos) / 1_000_000L);
            }
            return connectElapsedMs;
        }

        DiagnosticsSnapshot withClosed(long closedAtEpochMs, ConnectionCloseReason closeReason) {
            return new DiagnosticsSnapshot(
                baseUrl, deviceId, connectionId, recoveryId,
                physicalDesired, physicalConnected, physicalConnecting, stopped,
                transportGeneration, activeChannelCount, lastCloseReason,
                connectionStartedAtNanos, connectedAtNanos, snapshotPublishedAtNanos,
                connectElapsedMs, connectedElapsedMs, recoveryStartedAtNanos, recoveryAttemptCount,
                recoveryInitialFailureKind, agentRecoveryContextClearPending,
                outboundQueue, inboundDrops, inboundMailbox,
                closedAtEpochMs, closeReason != null ? closeReason.name() : "");
        }
    }

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
        publishDiagnosticsSnapshot();
        DeviceConnectionDiagnosticsRegistry.register(this);
    }

    private void installTransport() {
        int generation = ++transportGeneration;
        inboundMailbox.clearOverflowGeneration(generation - 1);
        String handshakeCookie = cookie == null ? "" : cookie;
        installedCredentialGeneration = credentialGeneration;
        String wsUrl = WebTermUrls.toWebSocketUrl(this.baseUrl) + "/ws/sessions";
        if (this.deviceId != null && !this.deviceId.isEmpty()) {
            wsUrl += "?deviceId=" + WebTermUrls.encodePath(this.deviceId);
        }
        transport = transportFactory != null
            ? transportFactory.create(wsUrl, handshakeCookie, MUX_SUBPROTOCOL)
            : null;
        if (transport != null) {
            transport.setTrafficAccumulator(
                NetworkTrafficStats.accumulatorForConnection(baseUrl, deviceId));
        }
        Log.i(TAG, "using relay websocket transport for " + deviceId + " generation=" + generation);
        publishDiagnosticsSnapshot();
    }

    private void connectPhysical() {
        if (stopped) return;
        physicalDesired = true;
        if (transportRecreateRequired) {
            MuxTransport staleTransport = transport;
            installTransport();
            transportRecreateRequired = false;
            if (staleTransport != null) staleTransport.close();
        }
        if (transport == null) {
            handlePhysicalDisconnected(
                transportGeneration, transport, 0, "transport unavailable");
            return;
        }
        if (physicalConnected || physicalConnecting) return;
        physicalConnecting = true;
        connectionId = UUID.randomUUID().toString();
        connectionStartedAtNanos = System.nanoTime();
        connectedAtNanos = 0L;
        int generation = transportGeneration;
        MuxTransport sourceTransport = transport;
        if (recoveryId != null) {
            recoveryAttemptCount++;
        }
        Diagnostics.info("device_connection", "transport_connect_requested", physicalFields(
            "stateBefore", "DISCONNECTED",
            "stateAfter", "CONNECTING"));
        publishDiagnosticsSnapshot();
        stateHandler.postDelayed(
            () -> onPhysicalConnectTimeout(generation), PHYSICAL_CONNECT_TIMEOUT_MS);
        sourceTransport.start(new MuxTransport.Listener() {
            @Override public void onOpen() {
                offerInbound(MuxInboundMailbox.InboundEvent.Open.of(generation, sourceTransport));
            }

            @Override public void onText(String text) {
                offerInbound(MuxInboundMailbox.InboundEvent.Text.of(
                    generation, sourceTransport, text));
            }

            @Override public void onBinary(byte[] data) {
                onBinaryBuffer(ByteBuffer.wrap(data));
            }

            @Override public void onBinaryBuffer(ByteBuffer data) {
                ByteBuffer frame = data.asReadOnlyBuffer();
                offerInbound(MuxInboundMailbox.InboundEvent.Binary.of(
                    generation, sourceTransport, frame));
            }

            @Override public void onClosed(int code, String reason) {
                offerInbound(MuxInboundMailbox.InboundEvent.Closed.of(
                    generation, sourceTransport, code, reason));
            }

            @Override public void onError(String message) {
                onError(0, message);
            }

            @Override public void onError(int code, String message) {
                offerInbound(MuxInboundMailbox.InboundEvent.Error.of(
                    generation, sourceTransport, code, message));
            }
        });
    }

    private void offerInbound(MuxInboundMailbox.InboundEvent event) {
        MuxInboundMailbox.Offer offer = inboundMailbox.offer(event);
        if (offer.scheduleDrain) {
            if (!stateHandler.post(inboundDrainRunnable)) {
                // Handler 已关闭时同步清空，避免持有 ByteBuffer。
                inboundMailbox.clear();
            }
        }
        if (offer.overflowed) {
            Diagnostics.warn("device_connection", "inbound_mailbox_overflow", physicalFields(
                "overflowGeneration", offer.overflowGeneration,
                "overflowFrames", offer.overflowFrames,
                "overflowBytes", offer.overflowBytes));
            stateHandler.post(() -> handleInboundOverflow(offer.overflowGeneration));
        }
    }

    private void drainInboundEvents() {
        long deadline = System.nanoTime() + MuxInboundMailbox.MAX_DRAIN_NANOS;
        int processed = 0;
        while (processed < MuxInboundMailbox.MAX_DRAIN_EVENTS
                && System.nanoTime() < deadline) {
            MuxInboundMailbox.InboundEvent event = inboundMailbox.poll();
            if (event == null) break;
            dispatchInboundEvent(event);
            processed++;
        }
        inboundMailbox.noteDrainBatch(processed);
        if (inboundMailbox.finishDrainOrReschedule()) {
            stateHandler.post(inboundDrainRunnable);
        } else {
            publishDiagnosticsSnapshot();
        }
    }

    private void dispatchInboundEvent(MuxInboundMailbox.InboundEvent event) {
        if (event instanceof MuxInboundMailbox.InboundEvent.Open open) {
            onPhysicalOpen(open.generation(), open.sourceTransport());
        } else if (event instanceof MuxInboundMailbox.InboundEvent.Text text) {
            handleControlMessage(text.generation(), text.sourceTransport(), text.text());
        } else if (event instanceof MuxInboundMailbox.InboundEvent.Binary binary) {
            dispatchBinaryFrame(binary.generation(), binary.sourceTransport(), binary.data());
        } else if (event instanceof MuxInboundMailbox.InboundEvent.Closed closed) {
            handlePhysicalDisconnected(
                closed.generation(), closed.sourceTransport(), closed.code(), closed.reason());
        } else if (event instanceof MuxInboundMailbox.InboundEvent.Error error) {
            handlePhysicalDisconnected(
                error.generation(), error.sourceTransport(), error.code(), error.message());
        }
    }

    private void handleInboundOverflow(int generation) {
        if (stopped) return;
        if (generation != transportGeneration) {
            inboundMailbox.clearOverflowGeneration(generation);
            return;
        }
        reconnectTransport(
            TransportReconnectTrigger.INBOUND_OVERFLOW,
            ConnectionCloseReason.RECONNECT_RESET,
            true,
            "inbound mailbox overflow");
        inboundMailbox.clearOverflowGeneration(generation);
    }

    private void onPhysicalOpen(int generation, MuxTransport sourceTransport) {
        if (generation != transportGeneration || sourceTransport != transport || !physicalDesired) {
            staleTransportGenerationDropped++;
            if (sourceTransport != null) sourceTransport.close();
            publishDiagnosticsSnapshot();
            return;
        }
        physicalConnecting = false;
        physicalConnected = true;
        physicalReconnectAttempts = 0;
        long connectDurationMs = connectDurationMs();
        connectedAtNanos = System.nanoTime();
        Diagnostics.info("device_connection", "transport_connected", physicalFields(
            "connectDurationMs", connectDurationMs,
            "stateBefore", "CONNECTING",
            "stateAfter", "CONNECTED"));
        boolean sent = controlPlane.sendDiagnosticsConnection(
            connectionId, recoveryId, transportGeneration);
        DeviceConnectionDiagnosticsRegistry.noteDiagnosticsContextSend(sent);
        if (!sent) {
            Diagnostics.warn("device_connection", "diagnostics_context_send_failed", physicalFields(
                "transportGeneration", transportGeneration,
                "stateBefore", "CONNECTED",
                "stateAfter", "CONNECTED"));
        }
        controlPlane.onConnected();
        reconcileChannels();
        maybeEmitTransportRecovered();
        tryClearAgentRecoveryContextIfPending();
        publishDiagnosticsSnapshot();
    }

    private void maybeEmitTransportRecovered() {
        if (recoveryId == null) return;
        long downtimeMs = Math.max(0L, (System.nanoTime() - recoveryStartedAtNanos) / 1_000_000L);
        Diagnostics.info("device_connection", "transport_recovered", physicalFields(
            "attemptCount", recoveryAttemptCount,
            "downtimeMs", downtimeMs,
            "initialFailureKind",
                recoveryInitialFailure != null ? recoveryInitialFailure.name() : "",
            "stateBefore", "RETRY_WAIT",
            "stateAfter", "CONNECTED"));
        DeviceConnectionDiagnosticsRegistry.noteRecoveryCompleted(downtimeMs);
        clearRecoveryState();
        boolean cleared = controlPlane.clearDiagnosticsRecovery(connectionId, transportGeneration);
        if (cleared) {
            agentRecoveryContextClearPending = false;
        } else {
            agentRecoveryContextClearPending = true;
            Diagnostics.warn("device_connection", "diagnostics_recovery_context_clear_failed",
                physicalFields(
                    "transportGeneration", transportGeneration,
                    "stateBefore", "CONNECTED",
                    "stateAfter", "CONNECTED"));
        }
        publishDiagnosticsSnapshot();
    }

    /**
     * 若上次清除 Agent recoveryHash 失败且物理仍连接，则再试一次。
     * 由 sendControlInternal / onPhysicalOpen 等控制发送机会触发。
     */
    private void tryClearAgentRecoveryContextIfPending() {
        if (!agentRecoveryContextClearPending || !physicalConnected || clearingAgentRecoveryContext) {
            return;
        }
        clearingAgentRecoveryContext = true;
        try {
            boolean cleared = controlPlane.clearDiagnosticsRecovery(connectionId, transportGeneration);
            if (cleared) {
                agentRecoveryContextClearPending = false;
                publishDiagnosticsSnapshot();
            }
        } finally {
            clearingAgentRecoveryContext = false;
        }
    }

    private void clearRecoveryState() {
        recoveryId = null;
        recoveryStartedAtNanos = 0L;
        recoveryAttemptCount = 0;
        recoveryInitialFailure = null;
    }

    private void beginRecoveryIfNeeded(ChannelFailure failure) {
        if (recoveryId != null) return;
        recoveryId = UUID.randomUUID().toString();
        recoveryStartedAtNanos = System.nanoTime();
        recoveryAttemptCount = 0;
        recoveryInitialFailure = failure != null ? failure.kind : ChannelFailure.Kind.MUX_TEMPORARY;
        DeviceConnectionDiagnosticsRegistry.noteRecoveryStarted();
        Diagnostics.warn("device_connection", "recovery_started", physicalFields(
            "initialFailureKind", recoveryInitialFailure.name(),
            "closeCode", failure != null ? failure.code : 0,
            "stateBefore", "DISCONNECTED",
            "stateAfter", "RETRY_WAIT"));
        publishDiagnosticsSnapshot();
    }

    private void onPhysicalConnectTimeout(int generation) {
        if (generation != transportGeneration || !physicalDesired
                || physicalConnected || !physicalConnecting) return;
        long connectDurationMs = connectDurationMs();
        physicalConnecting = false;
        physicalConnected = false;
        ChannelFailure failure = ChannelFailure.muxTemporary(0, "connect timeout");
        beginRecoveryIfNeeded(failure);
        Diagnostics.warn("device_connection", "transport_connect_failed", physicalFields(
            "failureKind", failure.kind.name(),
            "failureStage", "CONNECT",
            "closeCode", 0,
            "connectDurationMs", connectDurationMs,
            "stateBefore", "CONNECTING",
            "stateAfter", "RETRY_WAIT"));
        publishDiagnosticsSnapshot();
        if (transport != null) transport.close();
        transportRecreateRequired = true;
        notifyPhysicalFailure(failure);
        schedulePhysicalReconnect();
    }

    private void handlePhysicalDisconnected(
            int generation, MuxTransport sourceTransport, int code, String reason) {
        if (generation != transportGeneration || sourceTransport != transport || !physicalDesired) {
            staleTransportGenerationDropped++;
            publishDiagnosticsSnapshot();
            return;
        }
        boolean wasConnecting = physicalConnecting;
        boolean wasConnected = physicalConnected;
        long connectDurationMs = connectDurationMs();
        long connectedDurationMs = connectedDurationMs();
        physicalConnected = false;
        physicalConnecting = false;
        connectedAtNanos = 0L;
        ChannelFailure failure = code == 401 || code == 403
            ? ChannelFailure.authRequired(code, reason)
            : ChannelFailure.muxTemporary(code, reason);
        boolean authFailure = failure.kind == ChannelFailure.Kind.AUTH_REQUIRED;
        String stateAfter = authFailure ? "STOPPED" : "RETRY_WAIT";
        if (wasConnecting && !wasConnected) {
            if (!authFailure) beginRecoveryIfNeeded(failure);
            Diagnostics.warn("device_connection", "transport_connect_failed", physicalFields(
                "failureKind", failure.kind.name(),
                "failureStage", "WEBSOCKET",
                "closeCode", code,
                "connectDurationMs", connectDurationMs,
                "stateBefore", "CONNECTING",
                "stateAfter", stateAfter));
        } else if (wasConnected) {
            if (!authFailure) beginRecoveryIfNeeded(failure);
            Diagnostics.warn("device_connection", "transport_disconnected", physicalFields(
                "failureKind", failure.kind.name(),
                "failureStage", "WEBSOCKET",
                "closeCode", code,
                "connectedDurationMs", connectedDurationMs,
                "stateBefore", "CONNECTED",
                "stateAfter", stateAfter));
        } else if (!authFailure) {
            beginRecoveryIfNeeded(failure);
        }
        publishDiagnosticsSnapshot();
        notifyPhysicalFailure(failure);
        if (authFailure) {
            lastCloseReason = ConnectionCloseReason.AUTH_REQUIRED;
            physicalDesired = false;
            clearRecoveryState();
            agentRecoveryContextClearPending = false;
            if (transport != null) transport.close();
            publishDiagnosticsSnapshot();
            return;
        }
        transportRecreateRequired = true;
        schedulePhysicalReconnect();
    }

    private long connectDurationMs() {
        return connectionStartedAtNanos > 0L
            ? Math.max(0L, (System.nanoTime() - connectionStartedAtNanos) / 1_000_000L)
            : 0L;
    }

    private long connectedDurationMs() {
        return connectedAtNanos > 0L
            ? Math.max(0L, (System.nanoTime() - connectedAtNanos) / 1_000_000L)
            : 0L;
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
        Diagnostics.info("device_connection", "transport_reconnect_scheduled", physicalFields(
            "retryAttempt", attempt,
            "delayMs", delayMs,
            "stateBefore", "DISCONNECTED",
            "stateAfter", "RETRY_WAIT"));
        publishDiagnosticsSnapshot();
        int generation = transportGeneration;
        stateHandler.postDelayed(() -> {
            if (physicalDesired && generation == transportGeneration && !physicalConnected) {
                connectPhysical();
            }
        }, delayMs);
    }

    private void handleControlMessage(
            int generation, MuxTransport sourceTransport, String text) {
        if (generation != transportGeneration || sourceTransport != transport
                || !physicalConnected) {
            staleTransportGenerationDropped++;
            publishDiagnosticsSnapshot();
            return;
        }
        MuxControlCodec.Message msg = controlCodec.decode(text);
        if (msg == null) return;
        String type = msg.type;
        String tunnelId = msg.channelId;
        if ("ws-connected".equals(type)) {
            onTunnelConnected(tunnelId, msg.closeFenceVersion);
        } else if ("ws-error".equals(type)) {
            onTunnelError(tunnelId, msg.code, msg.message);
        } else if ("ws-close".equals(type)) {
            onTunnelClosed(tunnelId, msg.code == 0 ? 1000 : msg.code, msg.reason);
        } else if (type != null && !type.isEmpty()) {
            ControlListener listener = controlPlane.listener();
            if (listener != null) callbackHandler.post(() -> listener.onControlMessage(msg.raw));
        }
    }

    private void onTunnelConnected(String tunnelId, int closeFenceVersion) {
        LogicalChannelRegistry.Channel channel = channelRegistry.get(tunnelId);
        if (channel == null) {
            classifyMissingChannel(tunnelId, transportGeneration);
            publishDiagnosticsSnapshot();
            return;
        }
        if (channel.desiredOpen && channel.state == LogicalChannelRegistry.Channel.State.OPENING) {
            channel.openGeneration++;
            channel.retryGeneration++;
            channel.retryAttempt = 0;
            channel.state = LogicalChannelRegistry.Channel.State.OPEN;
            channel.openedTransportGeneration = transportGeneration;
            channel.closeFenceVersion = closeFenceVersion;
            channelRegistry.acknowledgeLifecycle(channel.id, channel.lifecycleId);
            Diagnostics.info("device_connection", "channel_connected", channelFields(channel,
                "stateBefore", "OPENING", "stateAfter", "OPEN"));
            notifyConnected(channel);
        }
    }

    private void onTunnelError(String tunnelId, int code, String message) {
        LogicalChannelRegistry.Channel channel = channelRegistry.get(tunnelId);
        if (channel == null) {
            classifyMissingChannel(tunnelId, transportGeneration);
            publishDiagnosticsSnapshot();
            return;
        }
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
        if (channel == null) {
            classifyMissingChannel(tunnelId, transportGeneration);
            publishDiagnosticsSnapshot();
            return;
        }
        if (channel.state == LogicalChannelRegistry.Channel.State.CLOSING) {
            finalizeChannelClose(channel, "CLOSE_ACK");
            return;
        }
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

    private void dispatchBinaryFrame(
            int generation, MuxTransport sourceTransport, ByteBuffer data) {
        if (generation != transportGeneration || sourceTransport != transport
                || !physicalConnected) {
            staleTransportGenerationDropped++;
            publishDiagnosticsSnapshot();
            return;
        }
        WebTermProtocol.TunnelFrame frame = WebTermProtocol.decodeTunnelFrame(data);
        if (frame == null) {
            tunnelDecodeFailed++;
            publishDiagnosticsSnapshot();
            return;
        }
        LogicalChannelRegistry.Channel channel = channelRegistry.get(frame.tunnelId);
        if (channel == null) {
            classifyMissingChannel(frame.tunnelId, generation, data.remaining());
            publishDiagnosticsSnapshot();
            return;
        }
        if (channel.state == LogicalChannelRegistry.Channel.State.CLOSING) {
            channelNotOpenDropped++;
            framesWhileClosing++;
            bytesWhileClosing += data.remaining();
            publishDiagnosticsSnapshot();
            return;
        }
        if (channel.state != LogicalChannelRegistry.Channel.State.OPEN) {
            channelNotOpenDropped++;
            LogicalChannelRegistry.Tombstone tombstone =
                channelRegistry.tombstone(frame.tunnelId);
            if (tombstone != null
                    && "CHANNEL_ID_REUSED".equals(tombstone.closeReason)) {
                channelIdReusedDropped++;
            } else {
                staleChannelLifecycleDropped++;
            }
            publishDiagnosticsSnapshot();
            return;
        }
        if (channel.openedTransportGeneration != generation) {
            wrongConnectionMappingDropped++;
            publishDiagnosticsSnapshot();
            return;
        }
        channel.lastFrameAtNanos = System.nanoTime();
        boolean binary = (frame.extraByte & 0xff) == WebTermProtocol.WS_DATA_BINARY;
        channel.listener.onDataBuffer(frame.tunnelId, frame.payload, binary);
    }

    InboundDropSnapshot inboundDropSnapshot() {
        return new InboundDropSnapshot(
            staleTransportGenerationDropped, tunnelDecodeFailed,
            unknownChannelDropped, channelNotOpenDropped,
            normalCloseTailDropped, staleChannelLifecycleDropped,
            channelIdReusedDropped, wrongConnectionMappingDropped,
            framesWhileClosing, bytesWhileClosing,
            framesAfterCloseAck, bytesAfterCloseAck,
            closeRequestToAckCount, closeRequestToAckTotalMs,
            closeRequestToAckMaxMs);
    }

    private void classifyMissingChannel(String channelId, int generation) {
        classifyMissingChannel(channelId, generation, -1);
    }

    private void classifyMissingChannel(String channelId, int generation, int frameBytes) {
        LogicalChannelRegistry.MissingClassification classification =
            channelRegistry.classifyMissing(channelId, generation);
        switch (classification) {
            case NORMAL_CLOSE_TAIL:
                normalCloseTailDropped++;
                LogicalChannelRegistry.Tombstone tombstone =
                    channelRegistry.tombstone(channelId);
                if (frameBytes >= 0 && tombstone != null && tombstone.closeAcknowledged) {
                    framesAfterCloseAck++;
                    bytesAfterCloseAck += frameBytes;
                }
                break;
            case STALE_TRANSPORT_GENERATION:
                staleTransportGenerationDropped++;
                break;
            case STALE_CHANNEL_LIFECYCLE:
                staleChannelLifecycleDropped++;
                break;
            case CHANNEL_ID_REUSED:
                channelIdReusedDropped++;
                break;
            case WRONG_CONNECTION_MAPPING:
                wrongConnectionMappingDropped++;
                break;
            case UNKNOWN_CHANNEL:
            default:
                unknownChannelDropped++;
                break;
        }
        LogicalChannelRegistry.Tombstone tombstone = channelRegistry.tombstone(channelId);
        if (tombstone == null) {
            Diagnostics.info("device_connection", "inbound_channel_frame_dropped", physicalFields(
                "channelHash", DiagnosticIdHasher.processHash(channelId),
                "dropClassification", classification.name()));
        } else {
            Diagnostics.info("device_connection", "inbound_channel_frame_dropped", physicalFields(
                "channelHash", DiagnosticIdHasher.processHash(channelId),
                "channelLifecycleId", tombstone.lifecycleId,
                "tombstoneTransportGeneration", tombstone.transportGeneration,
                "closeReason", tombstone.closeReason,
                "dropClassification", classification.name()));
        }
    }

    MuxOutboundQueue.Snapshot outboundQueueSnapshot() {
        return outboundQueue.snapshot();
    }

    private void publishDiagnosticsSnapshot() {
        long publishedAt = System.nanoTime();
        publishedDiagnosticsSnapshot = new DiagnosticsSnapshot(
            baseUrl, deviceId, connectionId, recoveryId,
            physicalDesired, physicalConnected, physicalConnecting, stopped,
            transportGeneration, activeChannelCount, lastCloseReason,
            connectionStartedAtNanos, connectedAtNanos, publishedAt,
            connectDurationMs(), connectedDurationMs(),
            recoveryStartedAtNanos, recoveryAttemptCount,
            recoveryInitialFailure != null ? recoveryInitialFailure.name() : "",
            agentRecoveryContextClearPending,
            outboundQueue.snapshot(), inboundDropSnapshot(), inboundMailbox.snapshot());
    }

    MuxInboundMailbox.Snapshot inboundMailboxSnapshot() {
        return inboundMailbox.snapshot();
    }

    /** 诊断导出：只返回 state handler 已发布的不可变快照。 */
    public DiagnosticsSnapshot diagnosticsSnapshot() {
        DiagnosticsSnapshot snapshot = publishedDiagnosticsSnapshot;
        if (snapshot != null) return snapshot;
        long publishedAt = System.nanoTime();
        return new DiagnosticsSnapshot(
            baseUrl, deviceId, connectionId, recoveryId,
            physicalDesired, physicalConnected, physicalConnecting, stopped,
            transportGeneration, activeChannelCount, lastCloseReason,
            connectionStartedAtNanos, connectedAtNanos, publishedAt,
            connectDurationMs(), connectedDurationMs(),
            recoveryStartedAtNanos, recoveryAttemptCount,
            recoveryInitialFailure != null ? recoveryInitialFailure.name() : "",
            agentRecoveryContextClearPending,
            outboundQueue.snapshot(), inboundDropSnapshot(), inboundMailbox.snapshot());
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
        if (!clearingAgentRecoveryContext) {
            tryClearAgentRecoveryContextIfPending();
        }
        return msg != null && physicalConnected && transport != null
            && transport.sendText(msg.toString());
    }

    private void stopPhysical(ConnectionCloseReason reason) {
        stopPhysical(reason, false, true);
    }

    private void stopPhysical(ConnectionCloseReason reason, boolean preserveRecovery) {
        stopPhysical(reason, preserveRecovery, true);
    }

    private void stopPhysical(ConnectionCloseReason reason, boolean preserveRecovery,
                              boolean closeInstalledTransport) {
        ConnectionCloseReason closeReason =
            reason != null ? reason : ConnectionCloseReason.CHANNELS_IDLE;
        boolean wasDesired = physicalDesired;
        boolean wasConnected = physicalConnected;
        boolean wasConnecting = physicalConnecting;
        long connectedDurationMs = connectedDurationMs();
        if (wasDesired || wasConnected || wasConnecting) {
            Diagnostics.info("device_connection", "transport_close_requested", physicalFields(
                "reason", closeReason.name(),
                "pendingOutboundFrames", outboundQueue.pendingFrames(),
                "pendingOutboundBytes", outboundQueue.pendingBytes(),
                "connectedDurationMs", connectedDurationMs,
                "stateBefore", wasConnected ? "CONNECTED" : (wasConnecting ? "CONNECTING" : "DISCONNECTED"),
                "stateAfter", "STOPPED"));
        }
        lastCloseReason = closeReason;
        physicalDesired = false;
        physicalConnected = false;
        physicalConnecting = false;
        physicalReconnectAttempts = 0;
        connectedAtNanos = 0L;
        agentRecoveryContextClearPending = false;
        if (!preserveRecovery) clearRecoveryState();
        if (closeInstalledTransport && transport != null) transport.close();
        inboundMailbox.clear();
        publishDiagnosticsSnapshot();
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
            credentialGeneration++;
            if (newCookie.isEmpty()) {
                credentialsInvalidated = true;
                ChannelFailure failure =
                    ChannelFailure.authRequired(401, "credentials cleared");
                for (LogicalChannelRegistry.Channel channel : snapshotChannels()) {
                    channel.desiredOpen = false;
                    notifyFailure(channel, failure);
                    finalizeChannelClose(channel, "CREDENTIALS_CLEARED");
                }
                transportRecreateRequired = true;
                stopPhysical(ConnectionCloseReason.AUTH_REQUIRED);
                return;
            }
            boolean resumeAfterInvalidation = credentialsInvalidated;
            credentialsInvalidated = false;
            Diagnostics.info("device_connection", "credentials_updated", physicalFields(
                "credentialGeneration", credentialGeneration,
                "transportCredentialGeneration", installedCredentialGeneration,
                "healthyTransportPreserved", physicalConnected));
            // 健康物理 WS 不因 Cookie 轮换而中断；下一次自然重连会重新创建
            // Transport，并在构造时原子读取这里的最新 Cookie。
            publishDiagnosticsSnapshot();
            if (resumeAfterInvalidation && controlPlane.hasListener()) {
                connectPhysical();
            }
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

    /** Registry 用：已 stop 的实例不得再复用，必须新建 DeviceConnection。 */
    public boolean isStopped() {
        return stopped;
    }

    public String deviceId() {
        return deviceId;
    }

    /** 当前物理连接 attempt 的原始 ID（仅用于可选 diagnostics.connection 控制消息）。 */
    String connectionIdForDiagnostics() {
        return connectionId;
    }

    /** 当前恢复事务 ID；无恢复时为 null。 */
    String recoveryIdForDiagnostics() {
        return recoveryId;
    }

    int transportGenerationForDiagnostics() {
        return transportGeneration;
    }

    /** Screen runtime 恢复仲裁只读使用；不得据此修改 transport 状态。 */
    public int transportGeneration() {
        return transportGeneration;
    }

    /** 测试用：是否处于恢复事务中。 */
    boolean hasActiveRecovery() {
        return recoveryId != null;
    }

    /** 归一化后的 Relay 服务器地址（诊断流量统计按 baseUrl+deviceId 隔离累计）。 */
    public String baseUrl() {
        return baseUrl;
    }

    public void start() {
        runOnState(this::connectPhysical);
    }

    public void forceReconnect(String reason) {
        // reason 仅用于 Logcat；结构化诊断使用 MANUAL_FORCE_RECONNECT。
        runOnState(() -> reconnectTransport(
            TransportReconnectTrigger.MANUAL_FORCE_RECONNECT,
            ConnectionCloseReason.RECONNECT_RESET,
            true,
            reason));
    }

    /**
     * Android 报告网络可用。健康或正在建立的物理 Mux 不因 onAvailable 被主动拆除；
     * 已断开的连接才用新的 transport generation 立即恢复。
     */
    public void onNetworkAvailable(long networkGeneration) {
        runOnState(() -> {
            if (stopped) return;
            if (networkGeneration == lastHandledNetworkGeneration) {
                Diagnostics.info(
                    "device_connection", "network_available_reconnect_skipped", physicalFields(
                        "reason", "duplicate_network_generation",
                        "networkGeneration", networkGeneration));
                return;
            }
            lastHandledNetworkGeneration = networkGeneration;
            if (physicalConnected || physicalConnecting) {
                Diagnostics.info(
                    "device_connection", "network_available_reconnect_skipped", physicalFields(
                        "reason", physicalConnected ? "transport_healthy" : "transport_connecting",
                        "networkGeneration", networkGeneration));
                return;
            }
            reconnectTransport(
                TransportReconnectTrigger.NETWORK_AVAILABLE,
                ConnectionCloseReason.RECONNECT_RESET,
                true,
                "network-available");
        });
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
        runOnState(() -> {
            if (!stopped) {
                openProtocolChannel(localSessionId, SCREEN_SUBPROTOCOL, normalizedOwner, listener);
            }
        });
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
            if (existing.state == LogicalChannelRegistry.Channel.State.CLOSING) {
                existing.reopenAfterClose = true;
                existing.reopenListener = listener;
                return channelId;
            }
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
        notifyFailure(previous,
            ChannelFailure.clientClosed(0, "screen channel superseded by a new runtime owner"));
        closeChannelInternal(previous.id, ConnectionCloseReason.RECONNECT_RESET, null);
    }

    public void detachChannelListener(String channelId) {
        runOnState(() -> {
            LogicalChannelRegistry.Channel ch = channelRegistry.get(channelId);
            if (ch != null) ch.listener = NO_OP_LISTENER;
        });
    }

    public void openChannel(String channelId, String path, String[] protocols, ChannelListener listener) {
        runOnState(() -> {
            if (!stopped) openChannelInternal(channelId, path, protocols, null, listener);
        });
    }

    private void openChannelInternal(String channelId, String path, String[] protocols,
                                     String screenRouteKey, ChannelListener listener) {
        LogicalChannelRegistry.Channel existing = channelRegistry.get(channelId);
        if (existing != null
                && existing.state == LogicalChannelRegistry.Channel.State.CLOSING) {
            existing.reopenAfterClose = true;
            existing.reopenListener = listener;
            return;
        }
        LogicalChannelRegistry.Channel created =
            new LogicalChannelRegistry.Channel(channelId, path, protocols, screenRouteKey, listener);
        if (screenRouteKey != null) {
            channelRegistry.claimScreenOwner(screenRouteKey, channelId);
        }
        LogicalChannelRegistry.Channel previous = channelRegistry.put(created);
        activeChannelCount = channelRegistry.size();
        if (previous != null) {
            clearScreenOwnerIfCurrent(previous);
            previous.desiredOpen = false;
            previous.retryGeneration++;
            previous.openGeneration++;
        }
        publishDiagnosticsSnapshot();
        start();
        reconcileChannel(channelRegistry.get(channelId));
    }

    public void closeChannel(String channelId) {
        if (channelId == null || channelId.isEmpty()) return;
        runOnState(() -> closeChannelInternal(
            channelId, ConnectionCloseReason.CHANNELS_IDLE, null));
    }

    /**
     * 在唯一 stateHandler 上原子执行：关闭 logical channel → 更新计数 →
     * 若已 idle 则 stopInternal，再通知 Registry 移除本实例。
     * 产品代码不得再自行组合 {@link #closeChannel} + {@code releaseIfIdle}。
     *
     * @param onReleased 已在 stateHandler 上、且仅在本实例因 idle 被 stop 后调用；
     *                   典型实现为 {@code registry.removeIfSame(this)}。
     */
    public void closeChannelAndReleaseIfIdle(String channelId, ConnectionCloseReason reason,
                                             Runnable onReleased) {
        if (channelId == null || channelId.isEmpty()) return;
        final ConnectionCloseReason closeReason =
            reason != null ? reason : ConnectionCloseReason.CHANNELS_IDLE;
        runOnState(() -> closeChannelInternal(channelId, closeReason,
            () -> releaseIfIdleOnState(closeReason, onReleased)));
    }

    /** 将 idle 回收投递到 stateHandler（供 Registry.releaseIfIdle 使用）。 */
    void scheduleReleaseIfIdle(ConnectionCloseReason reason, Runnable onReleased) {
        final ConnectionCloseReason closeReason =
            reason != null ? reason : ConnectionCloseReason.CHANNELS_IDLE;
        runOnState(() -> releaseIfIdleOnState(closeReason, onReleased));
    }

    /**
     * 已在 stateHandler 上：若 idle 则 stop 并回调 Registry。
     * control-listener 移除后的回收也走此路径，保证与 channel 关闭同一线程判定。
     */
    private void releaseIfIdleOnState(ConnectionCloseReason reason, Runnable onReleased) {
        if (!isIdle()) return;
        lastCloseReason = reason != null ? reason : ConnectionCloseReason.CHANNELS_IDLE;
        if (!stopped) {
            stopInternal();
            eventLoopShutdown.run();
        }
        if (onReleased != null) onReleased.run();
    }

    private void closeChannelInternal(String channelId, ConnectionCloseReason closeReason,
                                      Runnable completion) {
        LogicalChannelRegistry.Channel channel = channelRegistry.get(channelId);
        if (channel == null) {
            if (completion != null) completion.run();
            return;
        }
        if (channel.state == LogicalChannelRegistry.Channel.State.CLOSING) {
            if (completion != null) channel.closeCompletion = completion;
            return;
        }
        channel.desiredOpen = false;
        channel.retryGeneration++;
        channel.openGeneration++;
        channel.localCloseReason = closeReason;
        channel.closeCompletion = completion;
        channel.closeRequestedAtNanos = System.nanoTime();

        // 旧 Agent 没有 close fence 能力，维持旧客户端语义，避免永久等待 ACK。
        if (channel.closeFenceVersion < 1 || !physicalConnected) {
            boolean closeSent = sendChannelClose(channelId);
            finalizeChannelClose(channel, "LEGACY_LOCAL_CLOSE");
            if (channel.screenRouteKey != null && physicalConnected && !closeSent) {
                reconnectTransport(
                    TransportReconnectTrigger.SUPERSEDED_CHANNEL_CLOSE_FAILED,
                    ConnectionCloseReason.RECONNECT_RESET,
                    true);
            }
            return;
        }

        channel.state = LogicalChannelRegistry.Channel.State.CLOSING;
        long closeGeneration = ++channel.closeGeneration;
        publishDiagnosticsSnapshot();
        if (!sendChannelClose(channelId)) {
            finalizeChannelClose(channel, "CLOSE_SEND_FAILED");
            if (channel.screenRouteKey != null && physicalConnected) {
                reconnectTransport(
                    TransportReconnectTrigger.SCREEN_CHANNEL_REBUILD,
                    ConnectionCloseReason.RECONNECT_RESET,
                    true);
            }
            return;
        }
        stateHandler.postDelayed(
            () -> onChannelCloseTimeout(channelId, closeGeneration),
            CHANNEL_CLOSE_TIMEOUT_MS);
    }

    private void onChannelCloseTimeout(String channelId, long closeGeneration) {
        LogicalChannelRegistry.Channel channel = channelRegistry.get(channelId);
        if (channel == null
                || channel.state != LogicalChannelRegistry.Channel.State.CLOSING
                || channel.closeGeneration != closeGeneration) {
            return;
        }
        boolean rebuildPhysical = physicalConnected;
        finalizeChannelClose(channel, "CLOSE_ACK_TIMEOUT");
        if (rebuildPhysical) {
            reconnectTransport(
                TransportReconnectTrigger.SCREEN_CHANNEL_REBUILD,
                ConnectionCloseReason.RECONNECT_RESET,
                true);
        }
    }

    private void finalizeChannelClose(LogicalChannelRegistry.Channel channel, String reason) {
        if (channel == null || channelRegistry.get(channel.id) != channel) return;
        if ("CLOSE_ACK".equals(reason) && channel.closeRequestedAtNanos > 0L) {
            long elapsedMs = Math.max(0L,
                (System.nanoTime() - channel.closeRequestedAtNanos) / 1_000_000L);
            closeRequestToAckCount++;
            closeRequestToAckTotalMs += elapsedMs;
            closeRequestToAckMaxMs = Math.max(closeRequestToAckMaxMs, elapsedMs);
        }
        channel.closeGeneration++;
        channel.state = LogicalChannelRegistry.Channel.State.CLOSED;
        Runnable completion = channel.closeCompletion;
        channel.closeCompletion = null;
        boolean reopen = channel.reopenAfterClose;
        DeviceConnection.ChannelListener reopenListener = channel.reopenListener;
        removeChannelIfCurrent(channel, reason);
        if (reopen) {
            openChannelInternal(channel.id, channel.path, channel.protocols,
                channel.screenRouteKey,
                reopenListener != null ? reopenListener : channel.listener);
        } else if (completion != null) {
            completion.run();
        }
    }

    private void reconnectTransport(TransportReconnectTrigger trigger,
                                    ConnectionCloseReason closeReason,
                                    boolean autoStart) {
        reconnectTransport(trigger, closeReason, autoStart,
            trigger != null ? trigger.name() : TransportReconnectTrigger.UNKNOWN.name());
    }

    private void reconnectTransport(TransportReconnectTrigger trigger,
                                    ConnectionCloseReason closeReason,
                                    boolean autoStart,
                                    String logReason) {
        TransportReconnectTrigger resolvedTrigger =
            trigger != null ? trigger : TransportReconnectTrigger.UNKNOWN;
        ConnectionCloseReason resolvedClose =
            closeReason != null ? closeReason : ConnectionCloseReason.RECONNECT_RESET;
        String resolvedLog = logReason != null && !logReason.isEmpty()
            ? logReason : resolvedTrigger.name();
        if (stopped) return;
        if (physicalConnecting) {
            // 当前 generation 已有在途连接（例如 drain 循环中上一帧刚触发重建）。
            // 重复重建只会串联创建多个 Transport，其中只有一个能活到 ws_open；
            // 在途连接失败时仍由断线/超时路径退避重连。
            Log.i(TAG, "skip duplicate transport reconnect for " + deviceId
                + " reason=" + resolvedLog + " trigger=" + resolvedTrigger.name()
                + " generation=" + transportGeneration);
            return;
        }
        boolean wasDesired = physicalDesired;
        Log.i(TAG, "reconnect transport for " + deviceId + " reason=" + resolvedLog
            + " trigger=" + resolvedTrigger.name()
            + " channels=" + channelRegistry.size() + " generation=" + (transportGeneration + 1));
        Diagnostics.info("device_connection", "transport_reconnect_requested", physicalFields(
            "trigger", resolvedTrigger.name(),
            "closeReason", resolvedClose.name(),
            "stateBefore", physicalConnected ? "CONNECTED"
                : (physicalConnecting ? "CONNECTING" : "DISCONNECTED"),
            "stateAfter", "RECONNECTING"));
        ChannelFailure failure = ChannelFailure.muxTemporary(0, resolvedLog);
        // Cookie/EOF/send failure 若属于同一恢复链，沿用 recoveryId；后续 ws_open
        // 统一闭合指标。不要在 stopPhysical 中把已有恢复事务清掉后另起一轮。
        beginRecoveryIfNeeded(failure);
        for (LogicalChannelRegistry.Channel channel : snapshotChannels()) {
            if (!channel.desiredOpen) {
                finalizeChannelClose(channel, "TRANSPORT_REPLACED");
                continue;
            }
            markWaiting(channel);
            notifyFailure(channel, failure);
        }
        if (stopped) return;
        MuxTransport staleTransport = transport;
        // 先结束旧状态但不触发 close callback；installTransport 发布新 generation 后，
        // 旧 reader 的任何迟到回调都会被 sourceTransport + generation 双重隔离。
        stopPhysical(resolvedClose, true, false);
        installTransport();
        transportRecreateRequired = false;
        if (staleTransport != null) staleTransport.close();
        if (autoStart && (wasDesired || channelRegistry.size() > 0)) connectPhysical();
    }

    public boolean sendTunnelFrame(String channelId, byte[] payload, boolean binary) {
        return sendTunnelFrame(channelId, payload, binary, MuxOutboundQueue.FrameKind.OTHER, null);
    }

    public boolean sendTunnelFrame(String channelId, byte[] payload, boolean binary,
                                   MuxOutboundQueue.FrameKind kind) {
        return sendTunnelFrame(channelId, payload, binary, kind, null);
    }

    public boolean sendTunnelFrame(String channelId, byte[] payload, boolean binary,
                                   MuxOutboundQueue.FrameKind kind,
                                   TunnelSendCallback extraCallback) {
        MuxOutboundQueue.FrameKind resolved =
            kind != null ? kind : MuxOutboundQueue.FrameKind.OTHER;
        return tryEnqueueTunnelFrame(channelId, payload, binary, resolved, result -> {
            if (extraCallback != null) extraCallback.onResult(result);
            if (result == TunnelSendResult.CHANNEL_NOT_OPEN) {
                recoverDroppedTunnelFrame(channelId, resolved,
                    "tunnel frame reached a closed logical channel");
            }
            // TRANSPORT_REJECTED 已在唯一 event loop 中原子触发重连；
            // CONNECTION_STOPPED 是显式生命周期终点，不允许重新拉起连接。
        });
    }

    private void recoverDroppedTunnelFrame(String channelId, MuxOutboundQueue.FrameKind kind,
                                           String reason) {
        runOnState(() -> {
            LogicalChannelRegistry.Channel channel = channelRegistry.get(channelId);
            // A frame drained after an intentional logical-channel close belongs to
            // the old lifecycle and must not reconnect the shared physical socket.
            if (channel != null && channel.desiredOpen) {
                reconnectTransport(
                    channelNotOpenTrigger(kind),
                    ConnectionCloseReason.RECONNECT_RESET,
                    true,
                    reason);
            }
        });
    }

    private static TransportReconnectTrigger channelNotOpenTrigger(
            MuxOutboundQueue.FrameKind kind) {
        MuxOutboundQueue.FrameKind resolved =
            kind != null ? kind : MuxOutboundQueue.FrameKind.OTHER;
        switch (resolved) {
            case INPUT:
                return TransportReconnectTrigger.INPUT_CHANNEL_NOT_OPEN;
            case CONTROL:
                return TransportReconnectTrigger.CONTROL_CHANNEL_NOT_OPEN;
            case SCREEN:
                return TransportReconnectTrigger.SCREEN_CHANNEL_NOT_OPEN;
            default:
                return TransportReconnectTrigger.OTHER_CHANNEL_NOT_OPEN;
        }
    }

    /**
     * 业务侧请求按结构化 trigger 重建物理 Mux（例如 screen Hello 发送失败）。
     */
    public void requestTransportReconnect(TransportReconnectTrigger trigger, String reason) {
        String logReason = reason != null && !reason.isEmpty()
            ? reason
            : (trigger != null ? trigger.name() : TransportReconnectTrigger.UNKNOWN.name());
        runOnState(() -> reconnectTransport(
            trigger != null ? trigger : TransportReconnectTrigger.UNKNOWN,
            ConnectionCloseReason.RECONNECT_RESET,
            true,
            logReason));
    }

    /**
     * 非阻塞地进入设备级有界发送队列。true 仅表示本地队列已接受；最终是否进入
     * OkHttp/WebSocket 队列通过 callback 报告。所有 channel 状态检查和物理写入仍由
     * DeviceConnection 的唯一 event loop 串行执行。默认 FrameKind=OTHER。
     */
    public boolean tryEnqueueTunnelFrame(String channelId, byte[] payload, boolean binary,
                                         TunnelSendCallback callback) {
        return tryEnqueueTunnelFrame(channelId, payload, binary,
            MuxOutboundQueue.FrameKind.OTHER, callback);
    }

    public boolean tryEnqueueTunnelFrame(String channelId, byte[] payload, boolean binary,
                                         MuxOutboundQueue.FrameKind kind,
                                         TunnelSendCallback callback) {
        if (channelId == null || channelId.isEmpty() || payload == null) {
            Diagnostics.warn("device_connection", "outbound_queue_rejected", physicalFields(
                "channelHash", DiagnosticIdHasher.processHash(channelId),
                "failureKind", "INVALID_FRAME"));
            notifySendResult(callback, TunnelSendResult.CHANNEL_NOT_OPEN);
            return false;
        }
        MuxOutboundQueue.FrameKind resolved =
            kind != null ? kind : MuxOutboundQueue.FrameKind.OTHER;
        MuxOutboundQueue.Offer offer = outboundQueue.offer(channelId, payload, binary, resolved,
            result -> notifySendResult(callback, mapSendResult(result)));
        // offer 可能在非 state 线程；指标变化后投递到 state handler 发布快照。
        runOnState(this::publishDiagnosticsSnapshot);
        if (offer.result != MuxOutboundQueue.Result.LOCAL_ACCEPTED) {
            Diagnostics.warn("device_connection", "outbound_queue_rejected", physicalFields(
                "channelHash", DiagnosticIdHasher.processHash(channelId),
                "failureKind", offer.result.name(),
                "frameKind", resolved.name()));
            notifySendResult(callback, mapSendResult(offer.result));
            return false;
        }
        if (offer.scheduleDrain && !stateHandler.post(this::drainTunnelFrames)) {
            failPendingTunnelFrames(TunnelSendResult.CONNECTION_STOPPED);
            runOnState(this::publishDiagnosticsSnapshot);
            return false;
        }
        return true;
    }

    /** 注册设备级控制消息监听，不改变 screen channel 的所有权。 */
    public void setControlListener(ControlListener listener) {
        if (stopped && listener != null) return;
        controlPlane.setListener(listener);
    }

    public boolean sendControl(JSONObject msg) {
        if (msg == null) return false;
        return stateHandler.post(() -> {
            if (!sendControlInternal(msg)) {
                reconnectTransport(
                    TransportReconnectTrigger.CONTROL_SEND_REJECTED,
                    ConnectionCloseReason.RECONNECT_RESET,
                    true);
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
            if (stopped) return;
            stopInternal();
            eventLoopShutdown.run();
        });
    }

    private void stopInternal() {
        if (stopped) return;
        stopped = true;
        controlPlane.setListener(null);
        for (LogicalChannelRegistry.Channel channel : snapshotChannels()) {
            sendChannelClose(channel.id);
        }
        channelRegistry.clear();
        activeChannelCount = 0;
        failPendingTunnelFrames(TunnelSendResult.CONNECTION_STOPPED);
        stopPhysical(lastCloseReason != null ? lastCloseReason : ConnectionCloseReason.APP_SHUTDOWN);
        publishDiagnosticsSnapshot();
        DeviceConnectionDiagnosticsRegistry.unregister(this);
    }

    private static String terminalChannelRouteKey(String localSessionId, String subprotocol) {
        return "term:" + localSessionId + ":" + subprotocol;
    }

    private static String terminalChannelId(String localSessionId, String subprotocol,
                                            String ownerId) {
        return terminalChannelRouteKey(localSessionId, subprotocol) + ":" + ownerId;
    }

    public static String localSessionId(String sessionId, String deviceId) {
        return SessionIds.agentLocal(sessionId, deviceId);
    }

    public static String canonicalSessionId(String sessionId, String deviceId) {
        return SessionIds.canonical(sessionId, deviceId);
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
        return removeChannelIfCurrent(channel, "REMOVED");
    }

    private boolean removeChannelIfCurrent(LogicalChannelRegistry.Channel channel,
                                           String closeReason) {
        boolean removed = channelRegistry.removeIfCurrent(channel, closeReason);
        if (removed) {
            activeChannelCount = channelRegistry.size();
            publishDiagnosticsSnapshot();
        }
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
            if (frame == null) {
                publishDiagnosticsSnapshot();
                return;
            }
            LogicalChannelRegistry.Channel channel = channelRegistry.get(frame.channelId);
            if (channel == null || channel.state != LogicalChannelRegistry.Channel.State.OPEN) {
                Diagnostics.warn("device_connection", "outbound_queue_rejected", physicalFields(
                    "channelHash", DiagnosticIdHasher.processHash(frame.channelId),
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
                "channelHash", DiagnosticIdHasher.processHash(frame.channelId),
                "failureKind", "TRANSPORT_REJECTED"));
            publishDiagnosticsSnapshot();
            reconnectTransport(
                TransportReconnectTrigger.TUNNEL_SEND_REJECTED,
                ConnectionCloseReason.RECONNECT_RESET,
                true);
            return;
        }
    }

    private void failPendingTunnelFrames(TunnelSendResult result) {
        for (MuxOutboundQueue.Frame frame : outboundQueue.stopAndDrain()) {
            frame.completion.onResult(mapQueueResult(result));
        }
        publishDiagnosticsSnapshot();
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
        // 诊断字段只写进程级 hash，原始 deviceId/channelId 不落日志（随导出包外发）。
        fields.put("deviceHash", DiagnosticIdHasher.processHash(deviceId));
        fields.put("transportGeneration", transportGeneration);
        fields.put("activeChannelCount", activeChannelCount);
        if (connectionId != null && !connectionId.isEmpty()) {
            fields.put("connectionHash", DiagnosticIdHasher.processHash(connectionId));
        }
        if (recoveryId != null && !recoveryId.isEmpty()) {
            fields.put("recoveryHash", DiagnosticIdHasher.processHash(recoveryId));
        }
        addFields(fields, pairs);
        return fields;
    }

    private Map<String, Object> channelFields(LogicalChannelRegistry.Channel channel, Object... pairs) {
        java.util.HashMap<String, Object> fields = new java.util.HashMap<>();
        fields.put("deviceHash", DiagnosticIdHasher.processHash(deviceId));
        fields.put("channelHash", DiagnosticIdHasher.processHash(channel.id));
        fields.put("channelLifecycleId", channel.lifecycleId);
        fields.put("channelTransportGeneration", channel.openedTransportGeneration);
        fields.put("transportGeneration", transportGeneration);
        fields.put("activeChannelCount", activeChannelCount);
        if (connectionId != null && !connectionId.isEmpty()) {
            fields.put("connectionHash", DiagnosticIdHasher.processHash(connectionId));
        }
        if (recoveryId != null && !recoveryId.isEmpty()) {
            fields.put("recoveryHash", DiagnosticIdHasher.processHash(recoveryId));
        }
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
