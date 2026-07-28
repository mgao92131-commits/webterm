package com.webterm.feature.terminal.domain;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.HistoryRangeResult;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.EvictionPins;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCommit;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.CommitFailure;
import com.webterm.terminal.model.CommitValidationException;
import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.protocol.ScreenMessageV2Mapper;
import com.webterm.terminal.protocol.ScreenMessageV2Validator;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.io.IOException;

/**
 * 无 Activity 的终端会话运行时。持有连接、远端模型和模型执行器。
 * View detach 不关闭连接；只有显式 close 或进程销毁才结束。
 */
public final class TerminalSessionRuntime {

  public interface AuthenticationListener {
    void onAuthenticationRequired(@Nullable String reason);
  }

  /** Bound retained wire data per session when a remote PTY outpaces model parsing. */
  private static final int MAX_PENDING_SCREEN_MESSAGES = 64;
  /** 单会话待解析屏幕帧的总内存预算；不能只限制条数，因为单帧上限接近 2 MiB。 */
  private static final long MAX_PENDING_SCREEN_BYTES = 4L * 1024L * 1024L;
  /** resync 最多重发次数；耗尽后升级为 channel 重建。 */
  private static final int MAX_RESYNC_RETRIES = 3;
  /** 发送 resync 后等待权威 snapshot 的时间，超时按有界退避重发。 */
  private static final long RESYNC_SNAPSHOT_TIMEOUT_MS = 2000L;
  /** 第 1/2/3 次重发 resync 前的退避延迟。 */
  private static final long[] RETRY_BACKOFF_MS = {1000L, 2000L, 4000L};
  private static final long[] LEASE_RETRY_BACKOFF_MS = {250L, 500L, 1000L, 2000L};
  private static final long LEASE_REQUEST_TIMEOUT_MS = 3000L;
  private static final long LEASE_FALLBACK_RENEW_MS = 120_000L;
  private static final long LEASE_MIN_RENEW_DELAY_MS = 1000L;
  /** Prevent sustained output from monopolizing the serial model executor. */
  private static final int MAX_DRAIN_MESSAGES_PER_SLICE = 8;
  private static final long MAX_DRAIN_NANOS_PER_SLICE = 4_000_000L;

  public interface Listener {
    /** 无数据唤醒；VSync 时从模型原子取得 RenderUpdate。 */
    default void onRenderNeeded() {}
    void onEffect(@NonNull TerminalScreenEffect effect);
    void onConnectionStateChange(@NonNull State state);
    default void onLayoutLeaseStateChange(boolean ready) {}
    default void onInputDeliveryUncertain(@NonNull String message) {}
  }

  private interface ListenerInvocation {
    void invoke(@NonNull Listener listener);
  }

  /** 不依赖 Activity/View 的副作用处理器；页面不存在时仍必须持续存在。 */
  public interface EffectSink {
    void onEffect(@NonNull TerminalSessionRuntime runtime,
                  @NonNull TerminalScreenEffect effect,
                  boolean hasPageListener);
  }

  public enum State {
    DISCONNECTED,
    CONNECTING,
    SYNCING,
    LIVE,
    RECONNECTING,
    CLOSING,
    CLOSED
  }

  public enum ProjectionContinuityState {
    EMPTY,
    SYNCING,
    CONTINUOUS,
    LOST
  }

  /** 可注入的延迟调度器；回调内部必须重新投递到 modelExecutor 并校验 generation。 */
  public interface TimeoutScheduler {
    void schedule(@NonNull Runnable task, long delayMs);
  }

  /** 屏幕协议连接抽象。 */
  public interface ScreenConnection {
    void setListener(@NonNull Listener listener);
    default boolean beginSync(@Nullable TerminalScreenV2Proto.ResumeToken resume) {
      return beginSync(resume, false);
    }
    default boolean beginSync(@Nullable TerminalScreenV2Proto.ResumeToken resume,
                              boolean forceBaseline) {
      return false;
    }
    void setLayoutLeaseId(@NonNull String leaseId);
    /** @return true 表示本地 outbound 队列已接受；最终 WebSocket 入队经 {@link Listener#onInputSendResult}。 */
    boolean sendTextInput(@NonNull String text);
    boolean sendPasteInput(@NonNull String text);
    boolean sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                         boolean meta, boolean pressed);
    boolean sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                           boolean shift, boolean alt, boolean ctrl, boolean meta,
                           boolean pressed);
    void sendFocusInput(boolean focused);
    /** 返回 true 表示 resize 已被本地发送队列接受；false 表示当前无可用通道，调用方不得记录"已发送"。 */
    boolean requestResize(int cols, int rows);
    default void requestResync(long layoutEpoch, long screenRevision, @NonNull String reason) {}
    /** resync 重试耗尽后的最终恢复：重建 channel，依赖服务端 hello 触发新 snapshot。 */
    default void requestReconnect(@NonNull String reason) {}
    /**
     * beginSynchronization 中 Hello 本地发送失败专用恢复路径。
     * 默认回落到 {@link #requestReconnect}；TerminalChannel 映射为
     * {@link com.webterm.core.session.TransportReconnectTrigger#SCREEN_HELLO_SEND_FAILED}。
     */
    default void requestHelloSendFailedReconnect() {
      requestReconnect("screen Hello send failed");
    }
    void acquireLayout(boolean interactive);
    default void acquireLayout(@NonNull String requestId, boolean interactive) {
      acquireLayout(interactive);
    }
    void releaseLayout();
    void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data);
    void close();

    interface Listener {
      void onScreenMessage(@NonNull byte[] payload);
      void onConnected();
      void onDisconnected(@Nullable String reason);
      default void onAuthenticationRequired(@Nullable String reason) {}
      default void onInputDeliveryUncertain(@NonNull String message) {}
      /** 输入帧最终入队结果：WEBSOCKET_ENQUEUED / LOCAL_QUEUE_FULL / TRANSPORT_REJECTED 等。 */
      default void onInputSendResult(@NonNull String result) {}
      void onClosed();
    }
  }

  private final String sessionId;
  private final RemoteTerminalModel model;
  private final Executor modelExecutor;
  private final Executor callbackExecutor;
  private final RenderWakeDispatcher renderWakeDispatcher;
  private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
  /** Listener 可多个，但破坏性 RenderUpdate consumer 每个 runtime 只允许一个。 */
  private final AtomicReference<Object> renderConsumer = new AtomicReference<>();
  /** 让 owner 校验与破坏性 consume 成为同一极短临界区。 */
  private final Object renderConsumerLock = new Object();
  /** 不暴露给 Controller；只用于证明破坏性 model consume 来自所属 Runtime。 */
  private final Object renderPublicationAuthority = new Object();
  /** UI full-render 请求的有界 mailbox；至多排队一个 modelExecutor 任务。 */
  private final AtomicBoolean fullRenderRequestScheduled = new AtomicBoolean();
  private final ScreenMailbox screenMailbox =
      new ScreenMailbox(MAX_PENDING_SCREEN_MESSAGES, MAX_PENDING_SCREEN_BYTES);
  private final TerminalPipelineMetrics pipelineMetrics = new TerminalPipelineMetrics();

  private volatile State state = State.DISCONNECTED;
  private final AtomicBoolean closeRequested = new AtomicBoolean();
  private final LayoutLeaseCoordinator layoutLeaseCoordinator;
  /**
   * Screen 连接代际。网络回调可以早于 modelExecutor 中的任务完成；任何断线、替换或
   * 关闭都必须同步推进代际，让旧连接排队的 Hello/同步任务在执行时自行失效。
   */
  private final AtomicLong connectionEpoch = new AtomicLong();

  private final ResyncCoordinator resyncCoordinator;
  private final HistoryRangeLoader historyLoader = new HistoryRangeLoader();
  @Nullable private volatile HistoryRangeSource historyRangeSource;
  private volatile ScreenConnection connection;
  private volatile boolean connectionRequiresReplacement;
  @Nullable private volatile AuthenticationListener authenticationListener;
  @Nullable private volatile EffectSink effectSink;
  private final TimeoutScheduler timeoutScheduler;
  private final TimeoutScheduler leaseScheduler;
  private long syncGeneration;
  /** 供诊断快照读取的 syncGeneration 发布值（model executor 更新后同步）。 */
  private volatile long publishedSyncGeneration;
  private boolean forceBaselineOnNextSync;
  private volatile ProjectionContinuityState projectionContinuity =
      ProjectionContinuityState.EMPTY;
  private static final long HISTORY_REQUEST_TIMEOUT_MS = 5_000L;
  private static final long HISTORY_RETRY_MIN_MS = 200L;
  private static final long HISTORY_RETRY_MAX_MS = 5_000L;

  private final AtomicLong inputAttemptCount = new AtomicLong();
  private final AtomicLong inputRejectedNotLiveCount = new AtomicLong();
  private final AtomicLong inputRejectedNoLeaseCount = new AtomicLong();
  private final AtomicLong inputLocalQueueAcceptedCount = new AtomicLong();
  private final AtomicLong inputWebSocketEnqueuedCount = new AtomicLong();
  private final AtomicLong inputQueueFullCount = new AtomicLong();
  private final AtomicLong inputTransportRejectedCount = new AtomicLong();
  private final AtomicLong inputChannelNotOpenCount = new AtomicLong();
  private final AtomicLong inputConnectionStoppedCount = new AtomicLong();
  private final AtomicLong inputUnknownResultCount = new AtomicLong();
  private final AtomicLong inputUnitsAttempted = new AtomicLong();
  private final AtomicLong inputUnitsLocallyAccepted = new AtomicLong();
  /** 本地队列已接受、尚待最终入队结果的输入帧数。 */
  private final AtomicLong inputPendingFinalResultCount = new AtomicLong();
  /** close 时仍未收到最终结果的输入帧数（并入关闭快照方程）。 */
  private final AtomicLong inputAbandonedAtCloseCount = new AtomicLong();

  public TerminalSessionRuntime(@NonNull String sessionId) {
    this(sessionId, HistoryBudget.defaults());
  }

  public TerminalSessionRuntime(@NonNull String sessionId, @NonNull HistoryBudget historyBudget) {
    this(sessionId, new RemoteTerminalModel(historyBudget), defaultModelExecutor(sessionId),
        command -> new Handler(Looper.getMainLooper()).post(command));
  }

  private static Executor defaultModelExecutor(String sessionId) {
    return Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "TerminalModel-" + sessionId);
      t.setUncaughtExceptionHandler((thread, ex) -> {
        // 仅上报异常类型，避免异常正文携带终端内容进入诊断。
        Diagnostics.warn("terminal_runtime", "model_executor_uncaught",
            java.util.Map.of("exceptionType", ex.getClass().getSimpleName()));
      });
      return t;
    });
  }

  public TerminalSessionRuntime(@NonNull String sessionId,
                                @NonNull RemoteTerminalModel model,
                                @NonNull Executor modelExecutor) {
    this(sessionId, model, modelExecutor, Runnable::run);
  }

  public TerminalSessionRuntime(@NonNull String sessionId,
                                @NonNull RemoteTerminalModel model,
                                @NonNull Executor modelExecutor,
                                @NonNull Executor callbackExecutor) {
    this(sessionId, model, modelExecutor, callbackExecutor,
        (task, delayMs) -> new Handler(Looper.getMainLooper()).postDelayed(task, delayMs));
  }

  public TerminalSessionRuntime(@NonNull String sessionId,
                                @NonNull RemoteTerminalModel model,
                                @NonNull Executor modelExecutor,
                                @NonNull Executor callbackExecutor,
                                @NonNull TimeoutScheduler timeoutScheduler) {
    this(sessionId, model, modelExecutor, callbackExecutor, timeoutScheduler, timeoutScheduler);
  }

  TerminalSessionRuntime(@NonNull String sessionId,
                         @NonNull RemoteTerminalModel model,
                         @NonNull Executor modelExecutor,
                         @NonNull Executor callbackExecutor,
                         @NonNull TimeoutScheduler timeoutScheduler,
                         @NonNull TimeoutScheduler leaseScheduler) {
    this.sessionId = sessionId;
    this.model = model;
    this.model.bindRenderPublicationAuthority(renderPublicationAuthority);
    this.modelExecutor = modelExecutor;
    this.callbackExecutor = callbackExecutor;
    this.renderWakeDispatcher = new RenderWakeDispatcher(callbackExecutor,
        callbackDelayNanos -> notifyListeners("render_needed", Listener::onRenderNeeded));
    this.timeoutScheduler = timeoutScheduler;
    this.leaseScheduler = leaseScheduler;
    this.resyncCoordinator = new ResyncCoordinator(
        timeoutScheduler, modelExecutor, new ResyncCoordinator.Actions() {
          @Override
          public void sendResync(@NonNull String reason) {
            TerminalSessionRuntime.this.sendResync(reason);
          }

          @Override
          public void rebuildScreenChannel(@NonNull String reason) {
            ScreenConnection current = connection;
            if (current == null) return;
            connectionEpoch.incrementAndGet();
            layoutLeaseCoordinator.invalidate();
            updateState(State.RECONNECTING);
            current.requestReconnect(reason);
          }
        }, MAX_RESYNC_RETRIES, RESYNC_SNAPSHOT_TIMEOUT_MS, RETRY_BACKOFF_MS);
    this.layoutLeaseCoordinator = new LayoutLeaseCoordinator(
        leaseScheduler, modelExecutor, new LayoutLeaseCoordinator.Environment() {
          @Override public boolean isTerminalConnected() { return state == State.LIVE; }
          @Override public ScreenConnection connection() {
            return TerminalSessionRuntime.this.connection;
          }
          @Override public void onInputReadyChanged(boolean ready) {
            notifyLayoutLeaseState(ready);
          }
        }, LEASE_RETRY_BACKOFF_MS, LEASE_REQUEST_TIMEOUT_MS,
        LEASE_FALLBACK_RENEW_MS, LEASE_MIN_RENEW_DELAY_MS);
    TerminalPipelineDiagnosticsRegistry.register(this);
  }

  /** 诊断导出：会话 pipeline / history loader 快照。 */
  @NonNull
  TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot diagnosticsSnapshot() {
    return new TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot(
        sessionId, state.name(), pipelineMetrics.snapshot(), historyLoader.diagnosticsSnapshot(),
        inputDeliverySnapshot(),
        0L, 0L, "", connectionEpoch.get(), publishedSyncGeneration,
        projectionContinuity.name(), renderConsumer.get() != null, listeners.size(),
        screenMailbox.pendingMessages(), screenMailbox.pendingBytes(),
        0, 0L, 0, 0L);
  }

  /**
   * 关闭清理完成后的最终快照：history/mailbox 已复位，供 recentClosed 保留。
   * Registry 不得再主动读取 runtime，必须使用本快照。
   */
  @NonNull
  TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot diagnosticsSnapshotForClose(
      long closeRequestedAtEpochMs,
      int mailboxMessagesAtCloseRequest,
      long mailboxBytesAtCloseRequest) {
    int finalMailboxMessages = screenMailbox.pendingMessages();
    long finalMailboxBytes = screenMailbox.pendingBytes();
    return new TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot(
        sessionId, State.CLOSED.name(), pipelineMetrics.snapshot(),
        historyLoader.diagnosticsSnapshot(),
        inputDeliverySnapshot(),
        closeRequestedAtEpochMs, System.currentTimeMillis(), State.CLOSED.name(),
        connectionEpoch.get(), publishedSyncGeneration,
        projectionContinuity.name(), renderConsumer.get() != null, listeners.size(),
        finalMailboxMessages, finalMailboxBytes,
        mailboxMessagesAtCloseRequest, mailboxBytesAtCloseRequest,
        finalMailboxMessages, finalMailboxBytes);
  }

  @NonNull
  public String sessionId() {
    return sessionId;
  }

  @NonNull
  public RemoteTerminalModel model() {
    return model;
  }

  @NonNull
  public TerminalPipelineMetrics pipelineMetrics() {
    return pipelineMetrics;
  }

  /**
   * 当前 screen 连接（可能为 null）。仅供现场捕获会话源取得 TerminalChannel 以打开独立
   * capture 通道使用，不参与 screen 业务状态。
   */
  @Nullable
  public ScreenConnection connection() {
    return connection;
  }

  @NonNull
  public State state() {
    return state;
  }

  public void attachConnection(@NonNull ScreenConnection connection) {
    ScreenConnection previous = this.connection;
    if (previous != null && previous != connection) {
      previous.releaseLayout();
      previous.close();
    }
    connectionEpoch.incrementAndGet();
    this.connection = connection;
    updateState(State.CONNECTING);
    this.connectionRequiresReplacement = false;
    connection.setListener(new ScreenConnection.Listener() {
      @Override
      public void onScreenMessage(@NonNull byte[] payload) {
        if (TerminalSessionRuntime.this.connection != connection) return;
        handleScreenMessage(connectionEpoch.get(), connection, payload);
      }

      @Override
      public void onConnected() {
        if (TerminalSessionRuntime.this.connection != connection) return;
        // 同一连接阶段只允许启动一次初始同步。重复 ws-connected 不能重复 Hello；
        // 合法重连会先经 onDisconnected() 进入 RECONNECTING。
        State currentState = state;
        if (currentState != State.CONNECTING && currentState != State.RECONNECTING) return;
        long epoch = connectionEpoch.get();
        updateState(State.SYNCING);
        modelExecutor.execute(() -> beginSynchronization(connection, epoch));
      }

      @Override
      public void onDisconnected(@Nullable String reason) {
        if (TerminalSessionRuntime.this.connection != connection) return;
        // 必须在投递 modelExecutor 清理任务前同步作废旧代际。否则旧 beginSync 可能
        // 先执行，并通过已经重连的同一 logical channel 发出第二个 Hello。
        connectionEpoch.incrementAndGet();
        // 断线后 Go 侧会释放租约；本地同步失效，避免 resize 丢进死通道，
        // 重连拿到新租约后 handleLayoutLease 会用 lastRequested* 补发最新尺寸。
        layoutLeaseCoordinator.invalidate();
        connectionRequiresReplacement = false;
        // 取消在途 timeout、清理 mailbox 和 pending history：状态机归 modelExecutor 所有。
        modelExecutor.execute(() -> {
          bumpSyncGeneration();
          resetResyncRecovery();
        });
        updateState(State.RECONNECTING);
      }

      @Override
      public void onInputDeliveryUncertain(@NonNull String message) {
        if (TerminalSessionRuntime.this.connection != connection) return;
        callbackExecutor.execute(() -> {
          notifyListeners("input_delivery_uncertain",
              listener -> listener.onInputDeliveryUncertain(message));
        });
      }

      @Override
      public void onInputSendResult(@NonNull String result) {
        // 输入最终结果必须始终记账，即使 connection 已被替换/清空；
        // 否则 close 或重连后在途回调会破坏 localAccepted 方程。
        noteInputSendResult(result);
      }

      @Override
      public void onAuthenticationRequired(@Nullable String reason) {
        if (TerminalSessionRuntime.this.connection != connection) return;
        connectionEpoch.incrementAndGet();
        layoutLeaseCoordinator.invalidate();
        connectionRequiresReplacement = true;
        // AUTH_REQUIRED 对当前 screen channel 是终态，和传输断线一样废弃旧
        // resync timeout、mailbox 与 history request；PTY 本身仍存活，所以状态
        // 保持 RECONNECTING 并交给上层刷新凭据后重建 channel。
        modelExecutor.execute(() -> {
          bumpSyncGeneration();
          resetResyncRecovery();
        });
        updateState(State.RECONNECTING);
        if (authenticationListener != null) {
          callbackExecutor.execute(() -> {
            AuthenticationListener currentListener = authenticationListener;
            if (currentListener == null) return;
            try {
              currentListener.onAuthenticationRequired(reason);
            } catch (RuntimeException e) {
              warnUiCallbackFailure("authentication_required", e);
            }
          });
        }
      }

      @Override
      public void onClosed() {
        if (TerminalSessionRuntime.this.connection != connection) return;
        connectionEpoch.incrementAndGet();
        TerminalSessionRuntime.this.connection = null;
        connectionRequiresReplacement = false;
        layoutLeaseCoordinator.invalidate();
        updateState(State.CLOSED);
      }
    });
  }

  public boolean hasConnection() {
    return connection != null && !isClosingOrClosed() && !connectionRequiresReplacement;
  }

  private boolean isClosingOrClosed() {
    State current = state;
    return current == State.CLOSING || current == State.CLOSED;
  }

  /** HOT→WARM：关闭 screen channel，但保留完整 model 与 resume token。 */
  public void suspendConnection() {
    ScreenConnection current = connection;
    connectionEpoch.incrementAndGet();
    connection = null;
    connectionRequiresReplacement = false;
    modelExecutor.execute(() -> {
      bumpSyncGeneration();
      resetResyncRecovery();
    });
    if (current != null) {
      current.releaseLayout();
      current.close();
    }
    layoutLeaseCoordinator.invalidate();
    if (!isClosingOrClosed()) updateState(State.RECONNECTING);
  }

  /** View detach 只释放焦点与交互租约，不关闭 channel。 */
  public void detachPage() {
    ScreenConnection current = connection;
    if (current != null) {
      if (hasLayoutLease()) current.sendFocusInput(false);
    }
    layoutLeaseCoordinator.detachPage();
  }

  /** HOT reattach 不需要网络恢复，只重新申请交互租约。 */
  public void attachPage() {
    boolean wasAttached = layoutLeaseCoordinator.isPageAttached();
    layoutLeaseCoordinator.attachPage();
    if (!wasAttached && state != State.LIVE && !isClosingOrClosed()) {
      recoverUnhealthyConnectionOnPageReattach();
    }
  }

  /**
   * HOT runtime 在页面不可见期间可能停在 transport open、retry 或 screen sync。
   * 页面重新可见是明确的恢复边界：已连接会话继续零开销复用，其余状态立即换用
   * 新 logical tunnel，避免用户等待旧握手超时或指数退避。
   */
  private void recoverUnhealthyConnectionOnPageReattach() {
    ScreenConnection current = connection;
    if (current == null || isClosingOrClosed() || state == State.LIVE) return;
    connectionEpoch.incrementAndGet();
    layoutLeaseCoordinator.invalidate();
    updateState(State.RECONNECTING);
    modelExecutor.execute(() -> {
      bumpSyncGeneration();
      resetResyncRecovery();
    });
    current.requestReconnect("page reattached while terminal connection was not ready");
  }

  public void addListener(@NonNull Listener listener) {
    if (listeners.addIfAbsent(listener)) {
      callbackExecutor.execute(() -> {
        notifyListener("initial_connection_state", listener,
            current -> current.onConnectionStateChange(state));
        notifyListener("initial_layout_lease_state", listener,
            current -> current.onLayoutLeaseStateChange(hasLayoutLease()));
      });
    }
  }

  public void removeListener(@NonNull Listener listener) {
    listeners.remove(listener);
  }

  /** 明确拒绝同一 session 的第二个活跃渲染 owner，避免静默抢走 RenderUpdate。 */
  public void registerRenderConsumer(@NonNull Object consumer) {
    synchronized (renderConsumerLock) {
      Object current = renderConsumer.get();
      if (current == consumer) return;
      if (current != null) {
        throw new IllegalStateException("terminal session " + sessionId
            + " already has active render consumer " + ownerType(current)
            + "; rejected " + ownerType(consumer));
      }
      renderConsumer.set(consumer);
    }
  }

  public void unregisterRenderConsumer(@NonNull Object consumer) {
    synchronized (renderConsumerLock) {
      if (renderConsumer.get() == consumer) renderConsumer.set(null);
    }
  }

  /** 破坏性 publication 只能由当前活跃 owner 消费。 */
  @Nullable
  public RenderUpdate consumeRenderUpdate(@NonNull Object consumer) {
    synchronized (renderConsumerLock) {
      if (renderConsumer.get() != consumer) {
        throw new IllegalStateException("render consumer is not active for terminal session "
            + sessionId + ": " + ownerType(consumer));
      }
      RenderUpdate update = model.consumeRenderUpdate(renderPublicationAuthority);
      if (update != null) {
        pipelineMetrics.onRenderConsumed(update.publicationVersion, update.snapshot.screenRevision);
      }
      return update;
    }
  }

  /** Controller 完成对该 publication 的处理（成功绘制或 state-only）。 */
  public void onRenderPublicationHandled(long publicationVersion, long screenRevision,
                                         boolean rendered) {
    pipelineMetrics.onRenderPublicationHandled(publicationVersion, screenRevision, rendered);
  }

  /** view.render() 正常返回后由 Controller 回调，原子推进 handled 与 rendered 水位。 */
  public void onRenderFrameSucceeded(long publicationVersion, long screenRevision,
                                     long drawDurationNanos) {
    pipelineMetrics.onRenderFrameSucceeded(publicationVersion, screenRevision, drawDurationNanos);
  }

  /**
   * @deprecated 使用 {@link #onRenderFrameSucceeded}。
   */
  @Deprecated
  public void onRenderFrameRendered(long publicationVersion, long screenRevision,
                                    long drawDurationNanos) {
    onRenderFrameSucceeded(publicationVersion, screenRevision, drawDurationNanos);
  }

  /**
   * view.render() 抛异常：推进 failed 水位；不推进 handled/rendered。
   * 只记录异常类型，不记录异常正文。
   */
  public void onRenderFrameFailed(long publicationVersion, long screenRevision,
                                  long drawDurationNanos, @NonNull RuntimeException error) {
    pipelineMetrics.onRenderFrameFailed(publicationVersion, screenRevision, drawDurationNanos);
    Diagnostics.warn("terminal_runtime", "render_frame_failed", diagnosticFields(
        "failureKind", error.getClass().getSimpleName(),
        "publicationVersion", publicationVersion,
        "screenRevision", screenRevision));
  }

  /** 若模型操作推进了 publicationVersion，则同步 published 水位。 */
  private void recordPublicationAdvance(long versionBefore) {
    long versionAfter = model.lastPublicationVersion();
    if (versionAfter > versionBefore) {
      pipelineMetrics.onPublicationCreated(versionAfter, model.screenRevision);
    }
  }

  private static String ownerType(@Nullable Object owner) {
    return owner == null ? "none" : owner.getClass().getName();
  }

  public void setAuthenticationListener(@Nullable AuthenticationListener listener) {
    authenticationListener = listener;
  }

  public void setEffectSink(@Nullable EffectSink sink) {
    effectSink = sink;
  }

  public void sendTextInput(@NonNull String text) {
    sendWhenLive(Math.max(1, text.length()), c -> c.sendTextInput(text));
  }

  public void sendPasteInput(@NonNull String text) {
    sendWhenLive(Math.max(1, text.length()), c -> c.sendPasteInput(text));
  }

  public void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                           boolean meta, boolean pressed) {
    sendWhenLive(Math.max(1, key.length()),
        c -> c.sendKeyInput(key, shift, alt, ctrl, meta, pressed));
  }

  public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                             boolean shift, boolean alt, boolean ctrl, boolean meta,
                             boolean pressed) {
    sendWhenLive(1, c -> c.sendMouseInput(
        row, col, button, wheelDelta, shift, alt, ctrl, meta, pressed));
  }

  public void sendFocusInput(boolean focused) {
    if (state != State.LIVE || projectionContinuity != ProjectionContinuityState.CONTINUOUS
        || !hasLayoutLease()) return;
    ScreenConnection c = connection;
    if (c != null) c.sendFocusInput(focused);
  }

  private interface LiveInput {
    boolean send(@NonNull ScreenConnection connection);
  }

  private void sendWhenLive(int units, @NonNull LiveInput input) {
    inputAttemptCount.incrementAndGet();
    inputUnitsAttempted.addAndGet(Math.max(1, units));
    if (state != State.LIVE || projectionContinuity != ProjectionContinuityState.CONTINUOUS) {
      inputRejectedNotLiveCount.incrementAndGet();
      notifyInputDeliveryUncertain("INPUT_NOT_LIVE");
      return;
    }
    if (!hasLayoutLease()) {
      inputRejectedNoLeaseCount.incrementAndGet();
      notifyInputDeliveryUncertain("INPUT_NO_LEASE");
      return;
    }
    ScreenConnection c = connection;
    if (c == null) {
      inputRejectedNotLiveCount.incrementAndGet();
      notifyInputDeliveryUncertain("INPUT_NOT_LIVE");
      return;
    }
    if (input.send(c)) {
      inputLocalQueueAcceptedCount.incrementAndGet();
      inputUnitsLocallyAccepted.addAndGet(Math.max(1, units));
      inputPendingFinalResultCount.incrementAndGet();
    }
  }

  void noteInputSendResult(@NonNull String result) {
    if (result == null) return;
    switch (result) {
      case "WEBSOCKET_ENQUEUED":
        settlePendingFinalResult();
        inputWebSocketEnqueuedCount.incrementAndGet();
        break;
      case "LOCAL_QUEUE_FULL":
        // 本地队列未接受，sendWhenLive 未递增 pending；只计数。
        inputQueueFullCount.incrementAndGet();
        break;
      case "TRANSPORT_REJECTED":
        settlePendingFinalResult();
        inputTransportRejectedCount.incrementAndGet();
        break;
      case "CHANNEL_NOT_OPEN":
        settlePendingFinalResult();
        inputChannelNotOpenCount.incrementAndGet();
        break;
      case "CONNECTION_STOPPED":
        settlePendingFinalResult();
        inputConnectionStoppedCount.incrementAndGet();
        break;
      default:
        inputUnknownResultCount.incrementAndGet();
        break;
    }
  }

  /** 将 pending 减一并钳制到 ≥0（并发结算时可能短暂下溢）。 */
  private void settlePendingFinalResult() {
    inputPendingFinalResultCount.getAndUpdate(v -> Math.max(0L, v - 1L));
  }

  /**
   * 诊断导出：输入投递计数（不含输入正文）。
   * <p>
   * Focus 输入走 {@link #sendFocusInput} 独立路径，不计入本快照。
   * 关闭快照必须满足：
   * {@code localAccepted = enqueued + channelNotOpen + transportRejected
   * + connectionStopped + abandonedAtClose}。
   * {@code LOCAL_QUEUE_FULL} / {@code UNKNOWN} 不参与该方程（前者未计入 localAccepted）。
   */
  @NonNull
  public Map<String, Long> inputDeliverySnapshot() {
    Map<String, Long> out = new LinkedHashMap<>();
    out.put("inputAttemptCount", inputAttemptCount.get());
    out.put("inputRejectedNotLiveCount", inputRejectedNotLiveCount.get());
    out.put("inputRejectedNoLeaseCount", inputRejectedNoLeaseCount.get());
    out.put("inputLocalQueueAcceptedCount", inputLocalQueueAcceptedCount.get());
    out.put("inputWebSocketEnqueuedCount", inputWebSocketEnqueuedCount.get());
    out.put("inputQueueFullCount", inputQueueFullCount.get());
    out.put("inputTransportRejectedCount", inputTransportRejectedCount.get());
    out.put("inputChannelNotOpenCount", inputChannelNotOpenCount.get());
    out.put("inputConnectionStoppedCount", inputConnectionStoppedCount.get());
    out.put("inputUnknownResultCount", inputUnknownResultCount.get());
    out.put("inputPendingFinalResultCount", inputPendingFinalResultCount.get());
    out.put("inputAbandonedAtCloseCount", inputAbandonedAtCloseCount.get());
    out.put("inputUnitsAttempted", inputUnitsAttempted.get());
    out.put("inputUnitsLocallyAccepted", inputUnitsLocallyAccepted.get());
    return out;
  }

  private void notifyInputDeliveryUncertain(@NonNull String message) {
    callbackExecutor.execute(() ->
        notifyListeners("input_delivery_uncertain",
            listener -> listener.onInputDeliveryUncertain(message)));
  }

  public void requestResize(int cols, int rows) {
    if (cols <= 0 || rows <= 0) return;
    layoutLeaseCoordinator.requestResize(cols, rows);
  }

  /** 注入历史 Range Source（Direct/Relay HTTP）。 */
  public void setHistoryRangeSource(@Nullable HistoryRangeSource source) {
    modelExecutor.execute(() -> {
      HistoryRangeSource previous = historyRangeSource;
      historyRangeSource = source;
      historyLoader.resetLifecycle();
      if (previous != null) previous.close();
      pumpHistoryRanges();
    });
  }

  /** View/Controller 上报最新可见历史需求；快速滚动只覆盖 latestDemand。 */
  public void onVisibleHistoryDemand(long visibleFromSeq, long visibleToSeq, long anchorSeq,
                                     int direction, int visibleRowCount) {
    modelExecutor.execute(() -> {
      if (state != State.LIVE) {
        historyLoader.clearDemand();
        refreshEvictionPins();
        emitHistoryRangeInfo("history_range_demand_cleared", "reason", "not_live");
        return;
      }
      if (visibleFromSeq <= 0 || visibleToSeq < visibleFromSeq) {
        refreshEvictionPins();
        return;
      }
      historyLoader.setDemand(new HistoryRangeLoader.Demand(
          visibleFromSeq, visibleToSeq, anchorSeq, direction));
      emitHistoryRangeInfo("history_range_demand_updated",
          "fromSeq", visibleFromSeq,
          "toSeq", visibleToSeq,
          "anchorSeq", anchorSeq,
          "direction", direction);
      refreshEvictionPins();
      pumpHistoryRanges();
    });
  }

  public void onVisibleHistoryDemandCleared() {
    modelExecutor.execute(() -> {
      historyLoader.clearDemand();
      refreshEvictionPins();
      emitHistoryRangeInfo("history_range_demand_cleared", "reason", "detach");
    });
  }

  private void pumpHistoryRanges() {
    if (state != State.LIVE || historyLoader.closed()) return;
    if (historyLoader.activeRequest() != null) {
      return;
    }
    HistoryRangeSource source = historyRangeSource;
    if (source == null || historyLoader.latestDemand() == null) return;
    RemoteTerminalModel.ProjectionReadView projection = model.projectionReadView();
    if (!projection.projectionComplete || projection.historyGeneration < 1) return;
    RemoteTerminalModel.RenderSnapshot snap = model.peekRenderSnapshot();
    if (snap == null
        || !(snap.history instanceof com.webterm.terminal.model.PagedTerminalHistorySnapshot)) return;
    com.webterm.terminal.model.PagedTerminalHistorySnapshot history =
        (com.webterm.terminal.model.PagedTerminalHistorySnapshot) snap.history;
    refreshEvictionPins();
    HistoryRangeLoader.Range target = historyLoader.firstMissingRange(
        projection.instanceId, projection.layoutEpoch, projection.historyGeneration,
        projection.mainHistoryExtent, history);
    if (target == null) return;
    final HistoryRangeSource.RequestHandle[] handleSlot = new HistoryRangeSource.RequestHandle[1];
    HistoryRangeSource.RequestHandle cancelProxy = () -> {
      HistoryRangeSource.RequestHandle inner = handleSlot[0];
      if (inner != null) inner.cancel();
    };
    if (!historyLoader.begin(target, cancelProxy)) {
      return;
    }
    refreshEvictionPins();
    HistoryRangeLoader.ActiveRequest active = historyLoader.activeRequest();
    emitHistoryRangeInfo("history_range_requested",
        "requestId", active.callId, "historyGeneration", target.generation,
        "fromSeq", target.fromSeq, "toSeq", target.toSeq);
    HistoryRangeSource.RequestHandle handle = source.fetch(target,
        new HistoryRangeSource.Callback() {
          @Override
          public void onResult(@NonNull HistoryRangeSource.Result result) {
            modelExecutor.execute(() -> {
              if (active == null || !historyLoader.isActive(active)) return;
              long durationMs = historyRangeDurationMs(active);
              historyLoader.complete(active);
              try {
                boolean applyChanged = applyDecodedHistoryRange(active, result);
                emitHistoryRangeInfo("history_range_completed",
                    "requestId", active.callId, "historyGeneration", result.historyGeneration,
                    "fromSeq", active.range.fromSeq, "toSeq", active.range.toSeq,
                    "responseFirstSeq", result.currentExtent.firstSeq,
                    "responseLastSeq", result.currentExtent.lastSeq,
                    "returnedLineCount", result.lines.size(), "durationMs", durationMs,
                    "applyChanged", applyChanged);
              } finally {
                refreshEvictionPins();
                pumpHistoryRanges();
              }
            });
          }

          @Override
          public void onFailure(@NonNull HistoryRangeSource.Failure failure) {
            modelExecutor.execute(() -> {
              if (active == null || !historyLoader.isActive(active)) return;
              long durationMs = historyRangeDurationMs(active);
              historyLoader.complete(active);
              Diagnostics.warn("history_range", "history_range_failed", historyRangeFields(
                  "requestId", active.callId,
                  "historyGeneration", active.range.generation,
                  "fromSeq", active.range.fromSeq, "toSeq", active.range.toSeq,
                  "durationMs", durationMs, "failureKind", failure.kind.name()));
              refreshEvictionPins();
              if (handleRangeFailure(failure)) return;
              scheduleRangeRetry(failure.retryAfterMs);
            });
          }
        });
    handleSlot[0] = handle;
  }

  /** 根据最新 demand / active Range 刷新模型驱逐 pins。 */
  private void refreshEvictionPins() {
    HistoryRangeLoader.Demand demand = historyLoader.latestDemand();
    HistoryRangeLoader.ActiveRequest active = historyLoader.activeRequest();
    EvictionPins.LongRange visible = null;
    EvictionPins.LongRange anchor = null;
    java.util.List<EvictionPins.LongRange> inFlight = new java.util.ArrayList<>();
    if (demand != null && demand.visibleFromSeq > 0
        && demand.visibleToSeq >= demand.visibleFromSeq) {
      visible = new EvictionPins.LongRange(demand.visibleFromSeq, demand.visibleToSeq);
      long anchorSeq = demand.anchorSeq > 0 ? demand.anchorSeq : demand.visibleFromSeq;
      anchor = new EvictionPins.LongRange(anchorSeq, anchorSeq);
    }
    if (active != null) {
      inFlight.add(new EvictionPins.LongRange(active.range.fromSeq, active.range.toSeq));
    }
    model.setEvictionPins(new EvictionPins(visible, anchor, inFlight, null, null));
  }

  private boolean applyDecodedHistoryRange(
      @NonNull HistoryRangeLoader.ActiveRequest active,
      @NonNull HistoryRangeSource.Result decoded) {
    RemoteTerminalModel.ProjectionReadView projection = model.projectionReadView();
    if (!decoded.instanceId.equals(projection.instanceId)
        || decoded.layoutEpoch != projection.layoutEpoch
        || decoded.historyGeneration != projection.historyGeneration) {
      Diagnostics.info("history_range", "history_range_discarded_stale", historyRangeFields(
          "requestId", active.callId,
          "historyGeneration", decoded.historyGeneration,
          "currentGeneration", projection.historyGeneration));
      return false;
    }
    historyLoader.observeServerExtent(
        decoded.instanceId, decoded.layoutEpoch, decoded.historyGeneration,
        decoded.currentExtent);
    HistoryRangeResult range = new HistoryRangeResult(
        "range-" + active.callId,
        decoded.instanceId,
        decoded.layoutEpoch,
        decoded.historyGeneration,
        HistoryRangeResult.Status.OK,
        decoded.currentExtent,
        decoded.lines,
        0);
    long publicationBefore = model.lastPublicationVersion();
    long revisionBefore = model.screenRevision;
    boolean changed;
    try {
      changed = model.applyHistoryRange(
          range, active.range.fromSeq, active.range.fromSeq, active.range.toSeq);
    } catch (IllegalArgumentException protocolError) {
      Diagnostics.warn("history_range", "history_range_protocol_conflict",
          historyRangeFields("requestId", active.callId,
              "failureKind", protocolError.getClass().getSimpleName()));
      historyLoader.clearDemand();
      sendResync("history_range_protocol_conflict");
      return false;
    } catch (RuntimeException runtimeError) {
      Diagnostics.warn("history_range", "history_range_protocol_conflict",
          historyRangeFields("requestId", active.callId,
              "failureKind", runtimeError.getClass().getSimpleName()));
      historyLoader.clearDemand();
      sendResync("history_range_protocol_conflict");
      return false;
    }
    if (changed) {
      recordCapturedModelState(false);
      // 历史段通常不推进 screenRevision，但会推进 publicationVersion。
      recordPublicationAdvance(publicationBefore);
      if (model.screenRevision != revisionBefore) {
        pipelineMetrics.onModelApplied(model.screenRevision);
      }
      dispatchRenderNeeded();
    }
    return changed;
  }

  private boolean handleRangeFailure(@NonNull HistoryRangeSource.Failure failure) {
    if (failure.kind == HistoryRangeSource.FailureKind.AUTH_REQUIRED) {
      historyLoader.clearDemand();
      emitHistoryRangeInfo("history_range_demand_cleared", "reason", "auth");
      AuthenticationListener listener = authenticationListener;
      if (listener != null) {
        callbackExecutor.execute(() -> listener.onAuthenticationRequired("history_range_auth"));
      }
      return true;
    }
    if (failure.kind == HistoryRangeSource.FailureKind.STALE_PROJECTION
        || failure.kind == HistoryRangeSource.FailureKind.SESSION_GONE) {
      historyLoader.clearDemand();
      emitHistoryRangeInfo("history_range_demand_cleared", "reason",
          failure.kind == HistoryRangeSource.FailureKind.STALE_PROJECTION
              ? "stale" : "session_gone");
      if (failure.kind == HistoryRangeSource.FailureKind.STALE_PROJECTION) {
        sendResync("history_range_stale");
      }
      return true;
    }
    return false;
  }

  private static long historyRangeDurationMs(@NonNull HistoryRangeLoader.ActiveRequest active) {
    return Math.max(0L, (System.nanoTime() - active.startedAtNanos) / 1_000_000L);
  }

  private void emitHistoryRangeInfo(@NonNull String event, Object... pairs) {
    Diagnostics.info("history_range", event, historyRangeFields(pairs));
  }

  private Map<String, Object> historyRangeFields(Object... pairs) {
    Map<String, Object> fields = diagnosticFields(pairs);
    RemoteTerminalModel.ProjectionReadView projection = model.projectionReadView();
    fields.put("historyGeneration", projection.historyGeneration);
    fields.put("currentFirstSeq", projection.mainHistoryExtent.firstSeq);
    fields.put("currentLastSeq", projection.mainHistoryExtent.lastSeq);
    return fields;
  }

  /** 测试用：跳过完整 Snapshot 同步，直接进入 LIVE。 */
  void enterLiveForTest() {
    modelExecutor.execute(() -> {
      if (isClosingOrClosed()) return;
      updateState(State.LIVE);
      projectionContinuity = ProjectionContinuityState.CONTINUOUS;
    });
  }

  /** 测试用：注入 layout lease，使 {@link #sendWhenLive} 可通过租约检查。 */
  void grantLayoutLeaseForTest(@NonNull String leaseId) {
    layoutLeaseCoordinator.forceHeldForTest(leaseId);
  }

  private void shutdownHistoryLoading() {
    historyLoader.close();
    HistoryRangeSource source = historyRangeSource;
    historyRangeSource = null;
    if (source != null) source.close();
    model.setEvictionPins(EvictionPins.NONE);
  }

  private void scheduleRangeRetry(long serverDelayMs) {
    long delay = Math.max(HISTORY_RETRY_MIN_MS,
        Math.min(HISTORY_RETRY_MAX_MS, serverDelayMs > 0 ? serverDelayMs : HISTORY_RETRY_MIN_MS));
    long epoch = historyLoader.lifecycleEpoch();
    timeoutScheduler.schedule(
        () -> modelExecutor.execute(() -> {
          if (historyLoader.closed() || historyLoader.lifecycleEpoch() != epoch) return;
          if (historyLoader.latestDemand() == null) return;
          pumpHistoryRanges();
        }),
        delay);
  }

  @NonNull
  public ProjectionContinuityState projectionContinuityState() {
    return projectionContinuity;
  }

  public void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data) {
    ScreenConnection c = connection;
    if (c != null) {
      c.sendClipboardResponse(requestId, allowed, timeout, data);
    }
  }

  public void close() {
    if (!closeRequested.compareAndSet(false, true)) {
      return;
    }

    connectionEpoch.incrementAndGet();
    renderWakeDispatcher.cancel();
    updateState(State.CLOSING);

    long closeRequestedAtEpochMs = System.currentTimeMillis();
    int mailboxMessagesAtCloseRequest = screenMailbox.pendingMessages();
    long mailboxBytesAtCloseRequest = screenMailbox.pendingBytes();

    ScreenConnection current = connection;
    connection = null;
    connectionRequiresReplacement = false;
    if (current != null) {
      current.releaseLayout();
      current.close();
    }
    layoutLeaseCoordinator.detachPage();

    modelExecutor.execute(() -> finishCloseOnModelExecutor(
        closeRequestedAtEpochMs,
        mailboxMessagesAtCloseRequest,
        mailboxBytesAtCloseRequest));
  }


  /** model executor 上推进 syncGeneration，并发布给诊断快照读取。 */
  private void bumpSyncGeneration() {
    syncGeneration++;
    publishedSyncGeneration = syncGeneration;
  }

  private long nextSyncGeneration() {
    long generation = ++syncGeneration;
    publishedSyncGeneration = syncGeneration;
    return generation;
  }

  /** modelExecutor 上完成最终清理后再写入 recentClosed，保证快照反映清理后状态。 */
  private void finishCloseOnModelExecutor(long closeRequestedAtEpochMs,
                                          int mailboxMessagesAtCloseRequest,
                                          long mailboxBytesAtCloseRequest) {
    bumpSyncGeneration();
    resetResyncRecovery();
    shutdownHistoryLoading();
    screenMailbox.reset();
    long abandoned = Math.max(0L, inputPendingFinalResultCount.getAndSet(0L));
    if (abandoned > 0L) {
      inputAbandonedAtCloseCount.addAndGet(abandoned);
    }
    updateState(State.CLOSED);
    TerminalPipelineDiagnosticsRegistry.unregister(
        this,
        diagnosticsSnapshotForClose(
            closeRequestedAtEpochMs,
            mailboxMessagesAtCloseRequest,
            mailboxBytesAtCloseRequest));
  }

  // ---- transport/screen 同步状态机（modelExecutor 唯一推进） ----

  private void beginSynchronization(@NonNull ScreenConnection expectedConnection,
                                    long expectedEpoch) {
    if (connection != expectedConnection
        || connectionEpoch.get() != expectedEpoch
        || state != State.SYNCING) return;
    projectionContinuity = ProjectionContinuityState.SYNCING;
    long generation = nextSyncGeneration();
    TerminalScreenV2Proto.ResumeToken resume = null;
    RemoteTerminalModel.RenderSnapshot snapshot = model.renderSnapshot();
    if (model.projectionReadView().projectionComplete && snapshot.screen != null
        && snapshot.screen.length > 0) {
      TerminalScreenV2Proto.ResumeToken.Builder builder =
          TerminalScreenV2Proto.ResumeToken.newBuilder()
              .setInstanceId(snapshot.instanceId)
              .setLayoutEpoch(snapshot.layoutEpoch)
              .setScreenRevision(snapshot.screenRevision)
              .setDictionaryGeneration(model.dictionaryGeneration())
              .setHistoryGeneration(model.historyGeneration())
              .setActiveBuffer(snapshot.activeBuffer == TerminalBufferKind.ALTERNATE
                  ? TerminalScreenV2Proto.BufferKind.BUFFER_KIND_ALTERNATE
                  : TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN);
      for (TerminalLine line : snapshot.screen) {
        builder.addActiveRows(TerminalScreenV2Proto.ResumeScreenLine.newBuilder()
            .setLineId(line.id).setLineVersion(line.version));
      }
      resume = builder.build();
    }
    boolean helloSent = expectedConnection.beginSync(resume, forceBaselineOnNextSync);
    forceBaselineOnNextSync = false;
    if (!helloSent) {
      // logical channel 已报告 connected，但 Hello 没有真正写入物理 Mux。继续等待
      // Snapshot 只会让页面永久闪烁；立即换 channel，并作废当前代际的迟到帧。
      connectionEpoch.incrementAndGet();
      layoutLeaseCoordinator.invalidate();
      updateState(State.RECONNECTING);
      expectedConnection.requestHelloSendFailedReconnect();
      return;
    }
    timeoutScheduler.schedule(
        () -> modelExecutor.execute(() -> onSynchronizationTimeout(generation)),
        RESYNC_SNAPSHOT_TIMEOUT_MS);
  }

  private void onSynchronizationTimeout(long generation) {
    if (generation != syncGeneration || state != State.SYNCING) return;
    TerminalResumeMetrics.syncTimeout();
    startResyncRecovery("initial synchronization timeout");
  }

  private void completeSynchronization() {
    if (state != State.SYNCING) return;
    bumpSyncGeneration();
    updateState(State.LIVE);
    projectionContinuity = ProjectionContinuityState.CONTINUOUS;
    layoutLeaseCoordinator.onSynchronizationComplete();
  }

  private void handleScreenMessage(long messageEpoch,
                                   @NonNull ScreenConnection sourceConnection,
                                   @NonNull byte[] payload) {
    boolean frameSizeValid = payload.length > 0 && payload.length <= 2 * 1024 * 1024;
    if (isClosingOrClosed()) return;
    ScreenMailbox.MessageKind kind = classifyScreenMessage(payload);
    pipelineMetrics.onFrameReceived(kind.name(), payload.length);
    if (!frameSizeValid) {
      pipelineMetrics.incrementInvalidFrameSizeRejected();
    }
    if (kind == ScreenMailbox.MessageKind.UNKNOWN) {
      pipelineMetrics.incrementUnknownEnvelopeCount();
    }
    // 在消息进入 Mailbox 之前记录接收字节；Mailbox 溢出或后续丢弃不影响已通过网络接收的事实。
    TerminalRenderMetrics.inboundScreenFrame(toScreenTrafficKind(kind), payload.length);
    // 捕获点 A：原始 screen protocol bytes 旁路记录（入队前）。不重复 parse。先做一次廉价的
    // isRecording() 判断，未记录时不构造身份对象；记录时携带流身份供控制器做会话级隔离。
    if (com.webterm.terminal.model.capture.TerminalCapture.isRecording()) {
      com.webterm.terminal.model.capture.TerminalCapture.recordWireFrame(
          captureStreamIdentity(), messageEpoch, System.currentTimeMillis(), kind.name(), payload);
    }
    ScreenMailbox.Offer offer = screenMailbox.offer(
        messageEpoch, sourceConnection, payload, frameSizeValid, kind);
    TerminalResumeMetrics.screenMailboxHighWater(offer.pendingBytes);
    if (offer.droppedBackgroundMessages > 0) {
      pipelineMetrics.incrementBackgroundDropped(offer.droppedBackgroundMessages);
      Diagnostics.info("screen_protocol", "screen_mailbox_background_dropped", diagnosticFields(
          "droppedMessages", offer.droppedBackgroundMessages,
          "droppedBytes", offer.droppedBackgroundBytes,
          "pendingMessages", screenMailbox.pendingMessages(),
          "pendingBytes", screenMailbox.pendingBytes()));
    }
    if (offer.scheduleDrain) modelExecutor.execute(this::drainScreenMailbox);
  }

  private void drainScreenMailbox() {
    try {
      long deadlineNanos = System.nanoTime() + MAX_DRAIN_NANOS_PER_SLICE;
      int processed = 0;
      while (processed < MAX_DRAIN_MESSAGES_PER_SLICE && System.nanoTime() < deadlineNanos) {
        ScreenMailbox.Drain drain = screenMailbox.poll();
        if (drain == null) return;
        processed++;
        try {
          if (drain.fence != null) {
            incrementPipelineFenceCounters(drain.fence);
            if (drain.fence.rebuildChannel) {
              onMailboxFatalControlOverflow(
                  drain.fence.reason, drain.fence.discardedBytes,
                  drain.fence.discardedMessages, drain.fence.overflowCount);
            } else {
              onMailboxOverflow(drain.fence.reason, drain.fence.discardedBytes,
                  drain.fence.discardedMessages, drain.fence.overflowCount);
            }
            continue;
          }
          ScreenMailbox.Message message = drain.message;
          // 旧物理连接已经到达本地但尚未处理的 Snapshot/Patch/Lease 不得跨代际生效。
          if (message.connectionEpoch != connectionEpoch.get()) {
            pipelineMetrics.incrementStaleConnectionEpochDropped();
            continue;
          }
          if (ScreenMailbox.isProjectionMessage(message.kind)
              && message.mailboxGeneration != screenMailbox.generation()) {
            pipelineMetrics.incrementStaleMailboxGenerationDropped();
            continue;
          }
          if (message.sourceConnection != connection) {
            pipelineMetrics.incrementWrongSourceConnectionDropped();
            continue;
          }
          TerminalRenderMetrics.mailboxResidenceDuration(System.nanoTime() - message.enqueuedAtNanos);
          processScreenMessage(message);
        } catch (RuntimeException e) {
          // Fence handling, epoch checks and message processing all share this safety net. An
          // isolated failure must never strand drainScheduled or stop subsequent frames.
          Diagnostics.warn("screen_protocol", "screen_frame_processing_failed", diagnosticFields(
              "failureKind", e.getClass().getSimpleName(),
              "localRevision", model.screenRevision));
          startResyncRecovery(
              e.getMessage() != null ? e.getMessage() : "unexpected screen frame processing failure");
        }
      }
    } finally {
      if (screenMailbox.finishDrain()) {
        try {
          modelExecutor.execute(this::drainScreenMailbox);
        } catch (RuntimeException e) {
          // A rejected continuation must not leave the mailbox permanently armed. A later offer
          // can safely restart it once the executor becomes available again.
          screenMailbox.abandonDrain();
          Diagnostics.warn("screen_protocol", "screen_mailbox_drain_reschedule_failed",
              diagnosticFields("failureKind", e.getClass().getSimpleName()));
        }
      }
    }
  }

  private static TerminalRenderMetrics.ScreenTrafficKind toScreenTrafficKind(
      ScreenMailbox.MessageKind kind) {
    switch (kind) {
      case BASELINE:
        return TerminalRenderMetrics.ScreenTrafficKind.BASELINE;
      case TERMINAL_COMMIT:
        return TerminalRenderMetrics.ScreenTrafficKind.COMMIT;
      default:
        return TerminalRenderMetrics.ScreenTrafficKind.OTHER;
    }
  }

  /** Reads only envelope tags; it intentionally does not alter the protobuf-only wire payload. */
  @NonNull
  static ScreenMailbox.MessageKind classifyScreenMessage(@NonNull byte[] payload) {
    try {
      CodedInputStream input = CodedInputStream.newInstance(payload);
      while (!input.isAtEnd()) {
        int tag = input.readTag();
        if (tag == 0) break;
        int field = WireFormat.getTagFieldNumber(tag);
        // ScreenEnvelope oneof fields in terminal_screen_v2.proto.
        // 字段 6/7 曾为 HistoryRange，已 reserved。
        if (field == 3) return ScreenMailbox.MessageKind.BASELINE;
        if (field == 11) return ScreenMailbox.MessageKind.LAYOUT_LEASE;
        if (field == 16) return classifyEffectMessage(input, tag);
        if (field == 19) return ScreenMailbox.MessageKind.EXIT;
        if (field == 21) return ScreenMailbox.MessageKind.PONG;
        if (field == 22) return ScreenMailbox.MessageKind.TERMINAL_COMMIT;
        if (field == 23) return ScreenMailbox.MessageKind.RESUME_ACCEPTED;
        if (!input.skipField(tag)) break;
      }
    } catch (IOException | RuntimeException ignored) {
      // Full parse and validation retain responsibility for reporting malformed envelopes.
    }
    return ScreenMailbox.MessageKind.UNKNOWN;
  }

  @NonNull
  private static ScreenMailbox.MessageKind classifyEffectMessage(
      @NonNull CodedInputStream input, int envelopeTag) throws IOException {
    if (WireFormat.getTagWireType(envelopeTag) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
      return ScreenMailbox.MessageKind.UNKNOWN;
    }
    int length = input.readRawVarint32();
    if (length < 0) return ScreenMailbox.MessageKind.UNKNOWN;
    int oldLimit = input.pushLimit(length);
    try {
      while (!input.isAtEnd()) {
        int tag = input.readTag();
        if (tag == 0) break;
        int field = WireFormat.getTagFieldNumber(tag);
        // TerminalEffect oneof: clipboard_read=13, clipboard_write=14.
        if (field == 13 || field == 14) {
          return ScreenMailbox.MessageKind.CLIPBOARD_EFFECT;
        }
        if (!input.skipField(tag)) break;
      }
      return ScreenMailbox.MessageKind.EFFECT;
    } finally {
      input.popLimit(oldLimit);
    }
  }

  // ---- resync 恢复状态机（以下方法只能在 modelExecutor 上调用） ----

  private void startResyncRecovery(@NonNull String reason) {
    if (resyncCoordinator.start(reason)) TerminalResumeMetrics.resync(reason);
  }

  private void onMailboxOverflow(@NonNull String reason,
                                 long discardedBytes,
                                 long discardedMessages,
                                 long overflowCount) {
    TerminalResumeMetrics.screenMailboxOverflow(reason, discardedBytes, overflowCount);
    projectionContinuity = ProjectionContinuityState.LOST;
    boolean wasRecovering = resyncCoordinator.isRecovering();
    // 先推进状态机再读诊断字段，suppressedOverflowCount 才包含本次 overflow。
    resyncCoordinator.onMailboxOverflow(reason);
    Diagnostics.warn("screen_protocol", "screen_mailbox_overflow", diagnosticFields(
        "reason", reason,
        "discardedBytes", discardedBytes,
        "discardedMessages", discardedMessages,
        "overflowCount", overflowCount,
        "pendingMessages", screenMailbox.pendingMessages(),
        "pendingBytes", screenMailbox.pendingBytes(),
        "recoveringState", resyncCoordinator.stateName(),
        "suppressedOverflowCount", resyncCoordinator.suppressedOverflowCount()));
    if (!wasRecovering) TerminalResumeMetrics.resync(reason);
  }

  private void onMailboxFatalControlOverflow(@NonNull String reason,
                                             long discardedBytes,
                                             long discardedMessages,
                                             long overflowCount) {
    TerminalResumeMetrics.screenMailboxOverflow(reason, discardedBytes, overflowCount);
    projectionContinuity = ProjectionContinuityState.LOST;
    Diagnostics.warn("screen_protocol", "screen_mailbox_channel_rebuild", diagnosticFields(
        "reason", reason,
        "discardedBytes", discardedBytes,
        "discardedMessages", discardedMessages,
        "overflowCount", overflowCount,
        "pendingMessages", screenMailbox.pendingMessages(),
        "pendingBytes", screenMailbox.pendingBytes()));
    rebuildScreenChannel(reason);
  }

  private void onInvalidSnapshot(@NonNull String reason) {
    boolean wasRecovering = resyncCoordinator.isRecovering();
    resyncCoordinator.onInvalidSnapshot(reason);
    if (!wasRecovering) TerminalResumeMetrics.resync(reason);
  }

  private void onAuthoritativeSnapshot() {
    if (resyncCoordinator.reason().startsWith("screen mailbox")) {
      TerminalResumeMetrics.screenMailboxRecovered("snapshot");
    }
    resyncCoordinator.onAuthoritativeSnapshot();
    historyLoader.resetLifecycle();
    pumpHistoryRanges();
  }

  private void resetResyncRecovery() {
    resyncCoordinator.reset();
    historyLoader.resetLifecycle();
    screenMailbox.reset();
  }

  private void startForceBaselineRecovery(@NonNull String reason) {
    forceBaselineOnNextSync = true;
    rebuildScreenChannel(reason);
  }

  private static boolean requiresForceBaselineRecovery(@NonNull Throwable error) {
    if (!(error instanceof CommitValidationException)) return false;
    switch (((CommitValidationException) error).failure) {
      case DICTIONARY_ID_REDEFINED:
      case DICTIONARY_GENERATION_MISMATCH:
      case UNKNOWN_STYLE_ID:
      case UNKNOWN_LINK_ID:
      case IDENTITY_MISMATCH:
      case INVALID_LINE_DATA:
        return true;
      default:
        return false;
    }
  }

  private void sendResync(@NonNull String reason) {
    projectionContinuity = ProjectionContinuityState.LOST;
    rebuildScreenChannel(reason);
  }

  private void processScreenMessage(@NonNull ScreenMailbox.Message message) {
    TerminalScreenV2Proto.ScreenEnvelope envelope;
    try {
      long parseStartedNanos = System.nanoTime();
      envelope = TerminalScreenV2Proto.ScreenEnvelope.parseFrom(message.payload);
      TerminalRenderMetrics.protobufParseDuration(System.nanoTime() - parseStartedNanos);
      if (envelope.getProtocolVersion() != 2) {
        throw new IllegalArgumentException("unsupported screen protocol version");
      }
    } catch (Exception e) {
      Diagnostics.warn("screen_protocol", "screen_frame_decode_failed", diagnosticFields(
          "failureKind", e.getClass().getSimpleName(),
          "localRevision", model.screenRevision));
      startResyncRecovery("invalid screen.v2 message");
      return;
    }
    if (message.connectionEpoch != connectionEpoch.get()
        || message.sourceConnection != connection) return;

    long applyStartedNanos = System.nanoTime();
    boolean renderChanged = false;
    String payloadCase = envelope.getPayloadCase().name();
    String failureReason = "UNKNOWN_APPLY_FAILURE";
    long commitBaseRevision = 0;
    long commitTargetRevision = 0;
    long publicationBefore = model.lastPublicationVersion();
    long revisionBefore = model.screenRevision;
    try {
      switch (envelope.getPayloadCase()) {
        case BASELINE: {
          TerminalScreenV2Proto.Baseline wire = envelope.getBaseline();
          failureReason = "INVALID_BASELINE";
          ScreenMessageV2Validator.validateBaseline(wire);
          long mapStartedNanos = System.nanoTime();
          ScreenBaseline baseline;
          try {
            baseline = ScreenMessageV2Mapper.mapBaseline(wire);
          } finally {
            TerminalRenderMetrics.mapperDuration(System.nanoTime() - mapStartedNanos);
          }
          pipelineMetrics.onDecodedScreenRevision(baseline.screenRevision);
          if (!model.applyBaseline(baseline)) {
            failureReason = "STALE_BASELINE";
            throw new IllegalArgumentException("model rejected Baseline");
          }
          com.webterm.terminal.model.capture.TerminalCapture.recordMappedSnapshot(
              captureStreamIdentity(), baseline);
          recordCapturedModelState(true);
          onAuthoritativeSnapshot();
          completeSynchronization();
          pumpHistoryRanges();
          renderChanged = true;
          break;
        }
        case RESUME_ACCEPTED: {
          TerminalScreenV2Proto.ResumeAccepted accepted = envelope.getResumeAccepted();
          pipelineMetrics.onDecodedScreenRevision(accepted.getScreenRevision());
          if (!accepted.getInstanceId().equals(model.instanceId)
              || accepted.getLayoutEpoch() != model.layoutEpoch
              || accepted.getScreenRevision() != model.screenRevision
              || accepted.getDictionaryGeneration() != model.dictionaryGeneration()
              || accepted.getHistoryGeneration() != model.historyGeneration()
              || !accepted.hasHistoryExtent()
              || !historyExtent(accepted.getHistoryExtent()).equals(model.displayExtent())) {
            throw new CommitValidationException(CommitFailure.IDENTITY_MISMATCH);
          }
          resyncCoordinator.onAuthoritativeSnapshot();
          completeSynchronization();
          break;
        }
        case TERMINAL_COMMIT: {
          TerminalScreenV2Proto.TerminalCommit wire = envelope.getTerminalCommit();
          commitBaseRevision = wire.getBaseRevision();
          commitTargetRevision = wire.getRevision();
          pipelineMetrics.onDecodedScreenRevision(wire.getRevision());
          failureReason = CommitFailure.INVALID_LINE_DATA.name();
          ScreenMessageV2Validator.validateTerminalCommit(wire, model.rows);
          long mapStartedNanos = System.nanoTime();
          TerminalCommit commit;
          try {
            try {
              commit = ScreenMessageV2Mapper.mapTerminalCommit(
                  wire, model.rows, model.columns);
            } catch (RuntimeException invalidLineData) {
              throw new CommitValidationException(
                  CommitFailure.INVALID_LINE_DATA, invalidLineData);
            }
          } finally {
            TerminalRenderMetrics.mapperDuration(System.nanoTime() - mapStartedNanos);
          }
          // history promotion 也在 stagedCommit.commit() 内走 publishPendingRenderUpdate。
          RemoteTerminalModel.StagedCommit stagedCommit = model.stageCommit(commit);
          long commitApplyStartedNanos = System.nanoTime();
          try {
            renderChanged = stagedCommit.commit();
          } finally {
            TerminalRenderMetrics.terminalCommitApplyDuration(
                System.nanoTime() - commitApplyStartedNanos);
          }
          com.webterm.terminal.model.capture.TerminalCapture.recordMappedCommit(
              captureStreamIdentity(), commit);
          if (renderChanged) recordCapturedModelState(false);
          completeSynchronization();
          pumpHistoryRanges();
          break;
        }
        case LAYOUT_LEASE:
          layoutLeaseCoordinator.handleV2(envelope.getLayoutLease());
          break;
        case EFFECT:
          if (model.instanceId != null
              && model.instanceId.equals(envelope.getEffect().getInstanceId())) {
            handleEffectV2(envelope.getEffect());
          }
          break;
        case EXIT:
          updateState(State.CLOSED);
          shutdownHistoryLoading();
          break;
        default:
          break;
      }
    } catch (RemoteTerminalModel.RevisionGapException e) {
      Diagnostics.warn("screen_protocol", "revision_gap", diagnosticFields(
          "payloadCase", payloadCase,
          "failureReason", e instanceof CommitValidationException
              ? ((CommitValidationException) e).failure.name() : CommitFailure.REVISION_GAP.name(),
          "baseRevision", commitBaseRevision,
          "targetRevision", commitTargetRevision,
          "localRevision", model.screenRevision,
          "rows", model.rows,
          "columns", model.columns,
          "payloadBytes", message.payload.length,
          "mailboxMessages", screenMailbox.pendingMessages(),
          "mailboxBytes", screenMailbox.pendingBytes()));
      startResyncRecovery("TerminalCommit revision gap");
      return;
    } catch (Exception e) {
      Diagnostics.warn("screen_protocol", "screen_v2_apply_failed", diagnosticFields(
          "failureKind", e.getClass().getSimpleName(),
          "payloadCase", payloadCase,
          "failureReason", failureReason,
          "projectionContinuity", projectionContinuity.name(),
          "localRevision", model.screenRevision,
          "connectionEpoch", connectionEpoch.get(),
          "mailboxGeneration", message.mailboxGeneration));
      if (requiresForceBaselineRecovery(e)) {
        startForceBaselineRecovery("screen.v2 dictionary recovery: " + e.getClass().getSimpleName());
      } else {
        startResyncRecovery("screen.v2 apply failed");
      }
      return;
    }
    TerminalRenderMetrics.modelApplyDuration(System.nanoTime() - applyStartedNanos);
    updatePipelineAfterApply(envelope.getPayloadCase(), publicationBefore, revisionBefore);
    if (renderChanged) dispatchRenderNeeded();
  }

  private void updatePipelineAfterApply(
      @NonNull TerminalScreenV2Proto.ScreenEnvelope.PayloadCase payloadCase,
      long publicationBefore,
      long revisionBefore) {
    switch (payloadCase) {
      case BASELINE:
      case TERMINAL_COMMIT:
        // 用 lastPublicationVersion 计数器而非 peek：UI 可能在 apply 与水位更新之间已消费 pending。
        recordPublicationAdvance(publicationBefore);
        if (model.screenRevision != revisionBefore) {
          pipelineMetrics.onModelApplied(model.screenRevision);
        }
        break;
      default:
        break;
    }
  }

  private void incrementPipelineFenceCounters(@NonNull ScreenMailbox.Fence fence) {
    String reason = fence.reason;
    long count = fence.discardedMessages;
    // oversized 已在 handleScreenMessage 按帧计一次 invalidFrameSizeRejected，此处不再累加。
    if (reason.contains("oversized")) {
      return;
    }
    if (reason.contains("urgent")) {
      pipelineMetrics.incrementUrgentOverflowDiscarded(count);
    } else if (reason.contains("reliable")) {
      pipelineMetrics.incrementReliableOverflowDiscarded(count);
    } else if (reason.contains("exceeded")) {
      pipelineMetrics.incrementProjectionOverflowDiscarded(count);
    }
  }

  private void rebuildScreenChannel(@NonNull String reason) {
    ScreenConnection current = connection;
    if (current == null || isClosingOrClosed()) return;
    connectionEpoch.incrementAndGet();
    layoutLeaseCoordinator.invalidate();
    updateState(State.RECONNECTING);
    current.requestReconnect(reason);
  }

  private static HistoryExtent historyExtent(TerminalScreenV2Proto.HistoryExtent extent) {
    return new HistoryExtent(extent.getFirstSeq(), extent.getLastSeq());
  }


  /** 构造当前事件流身份，供捕获做会话级隔离。仅在录制时调用。 */
  com.webterm.terminal.model.capture.CaptureStreamIdentity captureStreamIdentity() {
    String terminalInstanceId = model.projectionReadView().instanceId;
    return new com.webterm.terminal.model.capture.CaptureStreamIdentity(
        sessionId, terminalInstanceId, "");
  }

  private void recordCapturedModelState(boolean afterBaseline) {
    if (!com.webterm.terminal.model.capture.TerminalCapture.isRecording()) return;
    com.webterm.terminal.model.capture.TerminalCapture.recordModelState(
        captureStreamIdentity(),
        new com.webterm.terminal.model.capture.CapturedModelState(
            System.currentTimeMillis(),
            model.instanceId,
            model.layoutEpoch,
            model.screenRevision,
            model.rows,
            model.columns,
            model.activeBuffer == TerminalBufferKind.ALTERNATE ? 1 : 0,
            model.projectionHealth().complete,
            afterBaseline,
            model.displayExtent(),
            model.remoteAvailableExtent(),
            afterBaseline));
  }

  private Map<String, Object> diagnosticFields(Object... pairs) {
    Map<String, Object> fields = new HashMap<>();
    fields.put("sessionId", sessionId);
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      fields.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return fields;
  }

  @NonNull
  public String layoutLeaseId() {
    return layoutLeaseCoordinator.leaseId();
  }

  public boolean hasLayoutLease() {
    return layoutLeaseCoordinator.hasLease();
  }

  private void notifyLayoutLeaseState(boolean ready) {
    callbackExecutor.execute(() -> {
      notifyListeners("layout_lease_state", listener -> listener.onLayoutLeaseStateChange(ready));
    });
  }

  private void handleEffectV2(TerminalScreenV2Proto.TerminalEffect effect) {
    TerminalScreenEffect screenEffect = null;
    switch (effect.getEffectCase()) {
      case BELL:
        screenEffect = TerminalScreenEffect.bell();
        break;
      case CLIPBOARD_READ:
        screenEffect = TerminalScreenEffect.clipboardRead(
            effect.getClipboardRead().getRequestId(),
            effect.getClipboardRead().getClipboard());
        break;
      case CLIPBOARD_WRITE:
        screenEffect = TerminalScreenEffect.clipboardWrite(
            effect.getClipboardWrite().getRequestId(),
            effect.getClipboardWrite().getClipboard(),
            effect.getClipboardWrite().getData().toByteArray());
        break;
      case NOTIFICATION:
        screenEffect = TerminalScreenEffect.notification(
            effect.getNotification().getTitle(),
            effect.getNotification().getBody());
        break;
      default:
        break;
    }
    if (screenEffect != null) dispatchEffect(screenEffect);
  }

  private void dispatchEffect(@NonNull TerminalScreenEffect effect) {
    callbackExecutor.execute(() -> {
      EffectSink sink = effectSink;
      if (sink != null) {
        try {
          sink.onEffect(this, effect, !listeners.isEmpty());
        } catch (RuntimeException e) {
          warnUiCallbackFailure("effect_sink", e);
        }
      }
      notifyListeners("effect", listener -> listener.onEffect(effect));
    });
  }

  /** 请求一次最新模型绘制，供页面 attach、恢复和重新绑定使用。 */
  public void requestModelRender() {
    if (isClosingOrClosed()) return;
    if (!fullRenderRequestScheduled.compareAndSet(false, true)) return;
    modelExecutor.execute(() -> {
      fullRenderRequestScheduled.set(false);
      if (isClosingOrClosed()) return;
      long publicationBefore = model.lastPublicationVersion();
      model.requestFullRender();
      recordPublicationAdvance(publicationBefore);
      dispatchRenderNeeded();
    });
  }

  public void requestRender() {
    requestModelRender();
  }

  private void dispatchRenderNeeded() {
    // No page is observing this session. The model remains current and a future attach explicitly
    // requests one fresh render, so posting a main-thread no-op would only create background load.
    if (listeners.isEmpty()) {
      TerminalRenderMetrics.modelChange();
      return;
    }
    renderWakeDispatcher.dispatch();
  }

  private void updateState(@NonNull State newState) {
    State previous = state;
    state = newState;
    if (previous == State.LIVE && newState != State.LIVE) {
      modelExecutor.execute(() -> {
        if (!historyLoader.closed()) {
          historyLoader.clearDemand();
          refreshEvictionPins();
        }
      });
    }
    callbackExecutor.execute(() -> {
      notifyListeners("connection_state", listener -> listener.onConnectionStateChange(newState));
    });
  }

  private void notifyListeners(@NonNull String callback, @NonNull ListenerInvocation invocation) {
    for (Listener listener : listeners) notifyListener(callback, listener, invocation);
  }

  private void notifyListener(@NonNull String callback, @NonNull Listener listener,
                              @NonNull ListenerInvocation invocation) {
    try {
      invocation.invoke(listener);
    } catch (RuntimeException e) {
      warnUiCallbackFailure(callback, e);
    }
  }

  private void warnUiCallbackFailure(@NonNull String callback, @NonNull RuntimeException error) {
    // UI callbacks observe an already-published model. They cannot introduce a protocol gap, so
    // logging is sufficient; triggering a screen resync here would amplify output under failure.
    Diagnostics.warn("terminal_runtime", "ui_callback_failed", diagnosticFields(
        "callback", callback,
        "failureKind", error.getClass().getSimpleName()));
  }
}
