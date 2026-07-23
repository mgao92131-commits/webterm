package com.webterm.feature.terminal.domain;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenPatchV2;
import com.webterm.terminal.model.HistoryDelta;
import com.webterm.terminal.model.HistoryRangeResult;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.protocol.ScreenMessageV2Mapper;
import com.webterm.terminal.protocol.ScreenMessageV2Validator;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.HashMap;
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
    CONNECTING,
    TRANSPORT_CONNECTED,
    SYNCING,
    CONNECTED,
    RECONNECTING,
    CLOSED
  }

  /** 投影流与 transport 状态正交；FROZEN 期间只接收 TailStatus 和按需历史。 */
  public enum StreamState {
    LIVE,
    FROZEN,
    RESYNCING
  }

  /** 可注入的延迟调度器；回调内部必须重新投递到 modelExecutor 并校验 generation。 */
  public interface TimeoutScheduler {
    void schedule(@NonNull Runnable task, long delayMs);
  }

  /** 屏幕协议连接抽象。 */
  public interface ScreenConnection {
    void setListener(@NonNull Listener listener);
    default boolean beginSync(long streamGeneration,
                              @NonNull TerminalScreenV2Proto.ScreenStreamMode desiredMode,
                              @Nullable String instanceId, long layoutEpoch,
                              boolean hasFrozenProjection) {
      return false;
    }
    default boolean setStreamMode(long streamGeneration,
                                  @NonNull TerminalScreenV2Proto.ScreenStreamMode mode) {
      return false;
    }
    void setLayoutLeaseId(@NonNull String leaseId);
    void sendTextInput(@NonNull String text);
    void sendPasteInput(@NonNull String text);
    void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed);
    void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                        boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed);
    void sendFocusInput(boolean focused);
    /** 返回 true 表示 resize 已被本地发送队列接受；false 表示当前无可用通道，调用方不得记录"已发送"。 */
    boolean requestResize(int cols, int rows);
    /** 返回 true 表示请求已成功排队发送；false 表示当前无可用通道，调用方不得留下 pending 状态。 */
    default boolean requestHistoryRange(@NonNull String requestId, @NonNull String instanceId,
                                        long layoutEpoch, long fromSeq, long toSeq) {
      return false;
    }
    default void requestResync(long layoutEpoch, long screenRevision, @NonNull String reason) {}
    /** resync 重试耗尽后的最终恢复：重建 channel，依赖服务端 hello 触发新 snapshot。 */
    default void requestReconnect(@NonNull String reason) {}
    void acquireLayout(boolean interactive);
    default void acquireLayout(@NonNull String requestId, boolean interactive) {
      acquireLayout(interactive);
    }
    void releaseLayout();
    void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data);
    void close();

    /** 可靠输入账本由 dispatcher 直接消费；transport adapter 不接收 typed envelope。 */
    @Nullable default ReliableInputTracker reliableInputTracker() { return null; }

    interface Listener {
      void onScreenMessage(@NonNull byte[] payload);
      void onConnected();
      void onDisconnected(@Nullable String reason);
      default void onAuthenticationRequired(@Nullable String reason) {}
      default void onInputDeliveryUncertain(@NonNull String message) {}
      default void onInputDeliveryEvent(@NonNull ReliableInputTracker.Event event) {}
      void onClosed();
    }
  }

  private final String sessionId;
  private final RemoteTerminalModel model;
  private final Executor modelExecutor;
  private final Executor callbackExecutor;
  private final RenderWakeDispatcher renderWakeDispatcher;
  private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
  private final TerminalConnectionPolicy connectionPolicy = new TerminalConnectionPolicy();
  private final ScreenMailbox screenMailbox =
      new ScreenMailbox(MAX_PENDING_SCREEN_MESSAGES, MAX_PENDING_SCREEN_BYTES);

  private volatile State state = State.CONNECTING;
  private final LayoutLeaseCoordinator layoutLeaseCoordinator;
  /**
   * Screen 连接代际。网络回调可以早于 modelExecutor 中的任务完成；任何断线、替换或
   * 关闭都必须同步推进代际，让旧连接排队的 Hello/同步任务在执行时自行失效。
   */
  private final AtomicLong connectionEpoch = new AtomicLong();

  private final ResyncCoordinator resyncCoordinator;
  private final HistoryRequestCoordinator historyRequests = new HistoryRequestCoordinator();
  private volatile ScreenConnection connection;
  private volatile boolean connectionRequiresReplacement;
  @Nullable private volatile AuthenticationListener authenticationListener;
  @Nullable private volatile EffectSink effectSink;
  private final TimeoutScheduler timeoutScheduler;
  private final TimeoutScheduler leaseScheduler;
  private long syncGeneration;
  private long streamGeneration = 1L;
  private volatile StreamState streamState = StreamState.LIVE;
  private volatile boolean freezeRequested;
  private final ArrayDeque<Runnable> pendingLiveInputs = new ArrayDeque<>();
  private int pendingLiveInputUnits;
  private final Object pendingResizeLock = new Object();
  private int pendingBaselineResizeCols;
  private int pendingBaselineResizeRows;
  private static final int MAX_PENDING_LIVE_INPUTS = 64;
  private static final int MAX_PENDING_LIVE_INPUT_UNITS = 1 << 20;
  private static final long HISTORY_REQUEST_TIMEOUT_MS = 5_000L;
  private static final long HISTORY_RETRY_MIN_MS = 200L;
  private static final long HISTORY_RETRY_MAX_MS = 5_000L;

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
          @Override public boolean isTerminalConnected() { return state == State.CONNECTED; }
          @Override public ScreenConnection connection() {
            return TerminalSessionRuntime.this.connection;
          }
          @Override public void onInputReadyChanged(boolean ready) {
            notifyLayoutLeaseState(ready);
          }
        }, LEASE_RETRY_BACKOFF_MS, LEASE_REQUEST_TIMEOUT_MS,
        LEASE_FALLBACK_RENEW_MS, LEASE_MIN_RENEW_DELAY_MS);
  }

  @NonNull
  public String sessionId() {
    return sessionId;
  }

  @NonNull
  public RemoteTerminalModel model() {
    return model;
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
        updateState(State.TRANSPORT_CONNECTED);
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
          syncGeneration++;
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
      public void onInputDeliveryEvent(@NonNull ReliableInputTracker.Event event) {
        if (TerminalSessionRuntime.this.connection != connection) return;
        if (connectionPolicy.onInputDelivery(event)
            == TerminalConnectionPolicy.Decision.REBUILD_SCREEN_CHANNEL) {
          modelExecutor.execute(() -> {
            if (TerminalSessionRuntime.this.connection != connection) return;
            connectionEpoch.incrementAndGet();
            layoutLeaseCoordinator.invalidate();
            updateState(State.RECONNECTING);
            connection.requestReconnect("input delivery: " + event.type.name());
          });
        }
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
          syncGeneration++;
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
    return connection != null && state != State.CLOSED && !connectionRequiresReplacement;
  }

  /** HOT→WARM：关闭 screen channel，但保留完整 model 与 resume token。 */
  public void suspendConnection() {
    ScreenConnection current = connection;
    connectionEpoch.incrementAndGet();
    connection = null;
    connectionRequiresReplacement = false;
    modelExecutor.execute(() -> {
      syncGeneration++;
      resetResyncRecovery();
    });
    if (current != null) {
      current.releaseLayout();
      current.close();
    }
    layoutLeaseCoordinator.invalidate();
    if (state != State.CLOSED) updateState(State.RECONNECTING);
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
    if (!wasAttached && state != State.CONNECTED && state != State.CLOSED) {
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
    if (current == null || state == State.CLOSED || state == State.CONNECTED) return;
    connectionEpoch.incrementAndGet();
    layoutLeaseCoordinator.invalidate();
    updateState(State.RECONNECTING);
    modelExecutor.execute(() -> {
      syncGeneration++;
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
    // Focus reporting is terminal metadata, not an explicit user command. A window focus
    // callback while browsing frozen history must neither queue input nor force the stream LIVE.
    if (state != State.CONNECTED || streamState != StreamState.LIVE
        || freezeRequested || !hasLayoutLease()) return;
    ScreenConnection c = connection;
    if (c != null) c.sendFocusInput(focused);
  }

  private interface LiveInput {
    void send(@NonNull ScreenConnection connection);
  }

  private void sendWhenLive(int units, @NonNull LiveInput input) {
    if (state != State.CONNECTED || !hasLayoutLease()) return;
    if (streamState == StreamState.LIVE && !freezeRequested) {
      ScreenConnection c = connection;
      if (c != null) input.send(c);
      return;
    }
    int boundedUnits = Math.max(1, units);
    synchronized (pendingLiveInputs) {
      if (pendingLiveInputs.size() >= MAX_PENDING_LIVE_INPUTS
          || pendingLiveInputUnits + boundedUnits > MAX_PENDING_LIVE_INPUT_UNITS) {
        notifyInputDeliveryUncertain("等待实时 Baseline 的输入队列已满");
        return;
      }
      pendingLiveInputs.addLast(() -> {
        ScreenConnection c = connection;
        if (c != null && state == State.CONNECTED && hasLayoutLease()) input.send(c);
      });
      pendingLiveInputUnits += boundedUnits;
    }
    resumeLiveStream();
  }

  private void flushPendingLiveInputs() {
    ArrayDeque<Runnable> actions;
    synchronized (pendingLiveInputs) {
      actions = new ArrayDeque<>(pendingLiveInputs);
      pendingLiveInputs.clear();
      pendingLiveInputUnits = 0;
    }
    for (Runnable action : actions) action.run();
  }

  private void clearPendingLiveInputs() {
    synchronized (pendingLiveInputs) {
      pendingLiveInputs.clear();
      pendingLiveInputUnits = 0;
    }
  }

  private void notifyInputDeliveryUncertain(@NonNull String message) {
    callbackExecutor.execute(() ->
        notifyListeners("input_delivery_uncertain",
            listener -> listener.onInputDeliveryUncertain(message)));
  }

  public void requestResize(int cols, int rows) {
    if (cols <= 0 || rows <= 0) return;
    if (state == State.CONNECTED && streamState == StreamState.LIVE && !freezeRequested) {
      layoutLeaseCoordinator.requestResize(cols, rows);
      return;
    }
    // FROZEN/RESYNCING/SYNCING 下只保留最新尺寸。必须等同 generation 的 Baseline
    // 成功提交后再交给 lease 协调器，避免 resize 改 epoch 后让在途 Baseline 立即失效。
    synchronized (pendingResizeLock) {
      pendingBaselineResizeCols = cols;
      pendingBaselineResizeRows = rows;
    }
  }

  private void flushResizeAfterBaseline() {
    int cols;
    int rows;
    synchronized (pendingResizeLock) {
      cols = pendingBaselineResizeCols;
      rows = pendingBaselineResizeRows;
      pendingBaselineResizeCols = 0;
      pendingBaselineResizeRows = 0;
    }
    if (cols > 0 && rows > 0) layoutLeaseCoordinator.requestResize(cols, rows);
  }

  /** v2 按可见固定页请求历史；相同闭区间在途时幂等忽略。 */
  public boolean requestHistoryRange(long fromSeq, long toSeq, long anchorSeq) {
    return requestHistoryRange(fromSeq, toSeq, anchorSeq, 0);
  }

  private boolean requestHistoryRange(
      long fromSeq, long toSeq, long anchorSeq, int retryAttempt) {
    if (state != State.CONNECTED) return false;
    ScreenConnection c = connection;
    if (c == null || model.instanceId == null || model.layoutEpoch == 0) return false;
    HistoryExtent extent = model.displayExtent();
    long boundedFrom = Math.max(extent.firstSeq, fromSeq);
    long boundedTo = Math.min(extent.lastSeq, toSeq);
    if (boundedFrom <= 0 || boundedTo < boundedFrom || boundedTo - boundedFrom >= 256) {
      return false;
    }
    if (historyRequests.isRangePending(boundedFrom, boundedTo)) return true;
    String requestId = historyRequests.nextRequestId();
    if (!c.requestHistoryRange(
        requestId, model.instanceId, model.layoutEpoch, boundedFrom, boundedTo)) return false;
    historyRequests.markPending(requestId, boundedFrom, boundedTo, anchorSeq,
        model.instanceId, model.layoutEpoch, retryAttempt);
    scheduleHistoryRequestTimeout(requestId);
    return true;
  }

  private void scheduleHistoryRequestTimeout(@NonNull String requestId) {
    timeoutScheduler.schedule(
        () -> modelExecutor.execute(() -> {
          HistoryRequestCoordinator.Pending expired = historyRequests.expire(requestId);
          if (expired != null) scheduleHistoryRetry(expired, 0);
        }),
        HISTORY_REQUEST_TIMEOUT_MS);
  }

  private void scheduleHistoryRetry(
      @NonNull HistoryRequestCoordinator.Pending pending, long serverDelayMs) {
    int nextAttempt = Math.min(8, pending.retryAttempt + 1);
    long exponential = Math.min(
        HISTORY_RETRY_MAX_MS, HISTORY_RETRY_MIN_MS << Math.min(5, pending.retryAttempt));
    long delay = Math.max(exponential, Math.min(HISTORY_RETRY_MAX_MS, serverDelayMs));
    timeoutScheduler.schedule(
        () -> modelExecutor.execute(() -> {
          if (state != State.CONNECTED
              || !pending.instanceId.equals(model.instanceId)
              || pending.layoutEpoch != model.layoutEpoch) return;
          requestHistoryRange(
              pending.fromSeq, pending.toSeq, pending.anchorSeq, nextAttempt);
        }),
        delay);
  }

  /** 用户离开尾部时冻结屏幕流；本地投影和 viewport 不被远端输出推进。 */
  public void freezeStream() {
    freezeRequested = true;
    modelExecutor.execute(() -> {
      if (!freezeRequested || state != State.CONNECTED
          || streamState == StreamState.FROZEN) return;
      ScreenConnection c = connection;
      if (c == null) return;
      long generation = ++streamGeneration;
      if (c.setStreamMode(
          generation, TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_FROZEN)) {
        streamState = StreamState.FROZEN;
      }
    });
  }

  /** 回到底部或输入前切回 LIVE；新的 Baseline 是唯一解冻提交点。 */
  public void resumeLiveStream() {
    freezeRequested = false;
    if (streamState == StreamState.LIVE) return;
    modelExecutor.execute(() -> {
      if (!freezeRequested && streamState != StreamState.LIVE) {
        requestFreshBaseline("return to live");
      }
    });
  }

  @NonNull
  public StreamState streamState() {
    return streamState;
  }

  public void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data) {
    ScreenConnection c = connection;
    if (c != null) {
      c.sendClipboardResponse(requestId, allowed, timeout, data);
    }
  }

  public void close() {
    connectionEpoch.incrementAndGet();
    renderWakeDispatcher.cancel();
    updateState(State.CLOSED);
    screenMailbox.reset();
    clearPendingLiveInputs();
    // 取消在途 timeout 并复位恢复状态机（递增 generation 作废旧回调）。
    modelExecutor.execute(this::resetResyncRecovery);
    ScreenConnection c = connection;
    if (c != null) {
      c.releaseLayout();
      c.close();
    }
    layoutLeaseCoordinator.detachPage();
    modelExecutor.execute(() -> syncGeneration++);
  }

  // ---- transport/screen 同步状态机（modelExecutor 唯一推进） ----

  private void beginSynchronization(@NonNull ScreenConnection expectedConnection,
                                    long expectedEpoch) {
    if (connection != expectedConnection
        || connectionEpoch.get() != expectedEpoch
        || state != State.TRANSPORT_CONNECTED) return;
    updateState(State.SYNCING);
    long generation = ++syncGeneration;
    boolean wantsFrozen = freezeRequested || streamState == StreamState.FROZEN;
    TerminalScreenV2Proto.ScreenStreamMode desiredMode =
        wantsFrozen
            ? TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_FROZEN
            : TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_LIVE;
    boolean hasFrozenProjection = wantsFrozen
        && model.instanceId != null && !model.instanceId.isEmpty();
    boolean helloSent = expectedConnection.beginSync(
        streamGeneration, desiredMode, model.instanceId, model.layoutEpoch,
        hasFrozenProjection);
    if (!helloSent) {
      // logical channel 已报告 connected，但 Hello 没有真正写入物理 Mux。继续等待
      // Snapshot 只会让页面永久闪烁；立即换 channel，并作废当前代际的迟到帧。
      connectionEpoch.incrementAndGet();
      layoutLeaseCoordinator.invalidate();
      updateState(State.RECONNECTING);
      expectedConnection.requestReconnect("screen Hello send failed");
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
    if (state != State.SYNCING && state != State.TRANSPORT_CONNECTED) return;
    syncGeneration++;
    updateState(State.CONNECTED);
    layoutLeaseCoordinator.onSynchronizationComplete();
  }

  private void handleScreenMessage(long messageEpoch,
                                   @NonNull ScreenConnection sourceConnection,
                                   @NonNull byte[] payload) {
    boolean frameSizeValid = payload.length > 0 && payload.length <= 2 * 1024 * 1024;
    if (state == State.CLOSED) return;
    ScreenMailbox.MessageKind kind = classifyScreenMessage(payload);
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
            onMailboxOverflow(drain.fence.reason, drain.fence.discardedBytes,
                drain.fence.discardedMessages, drain.fence.overflowCount);
            continue;
          }
          ScreenMailbox.Message message = drain.message;
          // 旧物理连接已经到达本地但尚未处理的 Snapshot/Patch/Lease 不得跨代际生效。
          if (message.connectionEpoch != connectionEpoch.get()
              || message.mailboxGeneration != screenMailbox.generation()
              || message.sourceConnection != connection) continue;
          TerminalRenderMetrics.mailboxResidenceDuration(System.nanoTime() - message.enqueuedAtNanos);
          // A recovery fence only accepts the authority frame that can release it. Dropping
          // patches here avoids protobuf parsing and allocation while a snapshot is in flight.
          if (message.kind == ScreenMailbox.MessageKind.SCREEN_PATCH
              && streamState == StreamState.RESYNCING) {
            continue;
          }
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
      case SCREEN_PATCH:
        return TerminalRenderMetrics.ScreenTrafficKind.PATCH;
      case HISTORY_RANGE:
        return TerminalRenderMetrics.ScreenTrafficKind.HISTORY_RANGE;
      case HISTORY_DELTA:
        return TerminalRenderMetrics.ScreenTrafficKind.HISTORY_DELTA;
      default:
        return TerminalRenderMetrics.ScreenTrafficKind.OTHER;
    }
  }

  /** Reads only envelope tags; it intentionally does not alter the protobuf-only wire payload. */
  @NonNull
  private static ScreenMailbox.MessageKind classifyScreenMessage(@NonNull byte[] payload) {
    try {
      CodedInputStream input = CodedInputStream.newInstance(payload);
      while (!input.isAtEnd()) {
        int tag = input.readTag();
        if (tag == 0) break;
        int field = WireFormat.getTagFieldNumber(tag);
        // ScreenEnvelope oneof fields in terminal_screen_v2.proto.
        if (field == 3) return ScreenMailbox.MessageKind.BASELINE;
        if (field == 4) return ScreenMailbox.MessageKind.SCREEN_PATCH;
        if (field == 5) return ScreenMailbox.MessageKind.HISTORY_DELTA;
        if (field == 7) return ScreenMailbox.MessageKind.HISTORY_RANGE;
        if (!input.skipField(tag)) break;
      }
    } catch (IOException | RuntimeException ignored) {
      // Full parse and validation retain responsibility for reporting malformed envelopes.
    }
    return ScreenMailbox.MessageKind.UNKNOWN;
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
    historyRequests.retainCompatible(model.instanceId, model.layoutEpoch);
  }

  private void resetResyncRecovery() {
    resyncCoordinator.reset();
    historyRequests.clear();
    screenMailbox.reset();
  }

  private void sendResync(@NonNull String reason) {
    requestFreshBaseline(reason);
  }

  private void requestFreshBaseline(@NonNull String reason) {
    ScreenConnection c = connection;
    if (c == null || state == State.CLOSED) return;
    long generation = ++streamGeneration;
    streamState = StreamState.RESYNCING;
    Diagnostics.warn("screen_protocol", "baseline_requested", diagnosticFields(
        "layoutEpoch", model.layoutEpoch,
        "screenRevision", model.screenRevision,
        "streamGeneration", generation,
        "reason", reason));
    if (!c.setStreamMode(
        generation, TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_LIVE)) {
      c.requestReconnect("screen Baseline request failed");
    }
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

    ReliableInputTracker tracker = message.sourceConnection.reliableInputTracker();
    try {
      switch (envelope.getPayloadCase()) {
        case INPUT_ACK:
          if (tracker != null) tracker.handleInputAck(envelope.getInputAck());
          return;
        case INFO:
          if (tracker != null) tracker.observeTerminalInstance(envelope.getInfo().getInstanceId());
          break;
        case BASELINE:
          if (tracker != null) {
            tracker.observeTerminalInstance(envelope.getBaseline().getInstanceId());
          }
          break;
        case SCREEN_PATCH:
          if (tracker != null) {
            tracker.observeTerminalInstance(envelope.getScreenPatch().getInstanceId());
          }
          break;
        default:
          break;
      }
    } catch (RuntimeException e) {
      if (tracker != null) tracker.clear();
      Diagnostics.warn("screen_protocol", "input_ack_processing_failed", diagnosticFields(
          "failureKind", e.getClass().getSimpleName()));
      return;
    }

    long applyStartedNanos = System.nanoTime();
    boolean renderChanged = false;
    try {
      switch (envelope.getPayloadCase()) {
        case BASELINE: {
          TerminalScreenV2Proto.Baseline wire = envelope.getBaseline();
          requireCurrentStreamGeneration(wire.getStreamGeneration());
          ScreenMessageV2Validator.validateBaseline(wire);
          ScreenBaseline baseline = ScreenMessageV2Mapper.mapBaseline(wire);
          String previousInstanceId = model.instanceId;
          if (!model.applyBaseline(baseline)) {
            throw new IllegalArgumentException("stale Baseline");
          }
          com.webterm.terminal.model.capture.TerminalCapture.recordMappedSnapshot(
              captureStreamIdentity(), baseline);
          recordCapturedModelState(true);
          streamGeneration = wire.getStreamGeneration();
          streamState = StreamState.LIVE;
          onAuthoritativeSnapshot();
          completeSynchronization();
          flushResizeAfterBaseline();
          if (previousInstanceId != null
              && !previousInstanceId.equals(baseline.instanceId)) {
            clearPendingLiveInputs();
            notifyInputDeliveryUncertain("终端实例已变更，等待实时恢复的输入未发送");
          } else {
            flushPendingLiveInputs();
          }
          renderChanged = true;
          break;
        }
        case SCREEN_PATCH: {
          if (streamState != StreamState.LIVE) return;
          TerminalScreenV2Proto.ScreenPatch wire = envelope.getScreenPatch();
          requireCurrentStreamGeneration(wire.getStreamGeneration());
          ScreenMessageV2Validator.validatePatch(wire);
          if (wire.getScreenLineUpdatesCount() > model.rows) {
            throw new IllegalArgumentException("screen patch exceeds row limit");
          }
          ScreenPatchV2 patch = ScreenMessageV2Mapper.mapPatch(wire, model.columns);
          model.applyScreenPatch(patch);
          com.webterm.terminal.model.capture.TerminalCapture.recordMappedPatch(
              captureStreamIdentity(), patch);
          recordCapturedModelState(false);
          completeSynchronization();
          renderChanged = true;
          break;
        }
        case HISTORY_DELTA: {
          if (streamState != StreamState.LIVE) return;
          TerminalScreenV2Proto.HistoryDelta wire = envelope.getHistoryDelta();
          requireCurrentStreamGeneration(wire.getStreamGeneration());
          ScreenMessageV2Validator.validateHistoryDelta(wire);
          HistoryDelta delta = ScreenMessageV2Mapper.mapHistoryDelta(wire, model.columns);
          renderChanged = model.applyHistoryDelta(delta);
          if (renderChanged) recordCapturedModelState(false);
          break;
        }
        case HISTORY_RANGE_RESPONSE: {
          TerminalScreenV2Proto.HistoryRangeResponse wire =
              envelope.getHistoryRangeResponse();
          ScreenMessageV2Validator.validateHistoryRange(wire);
          HistoryRequestCoordinator.Pending pending =
              historyRequests.complete(wire.getRequestId());
          if (pending == null) return;
          HistoryRangeResult range =
              ScreenMessageV2Mapper.mapHistoryRange(wire, model.columns);
          renderChanged = model.applyHistoryRange(
              range, pending.anchorSeq, pending.fromSeq, pending.toSeq);
          if (renderChanged) recordCapturedModelState(false);
          if (range.status == HistoryRangeResult.Status.STALE_PROJECTION) {
            Diagnostics.info("screen_protocol", "frozen_projection_stale", diagnosticFields(
                "instanceId", wire.getInstanceId(),
                "layoutEpoch", wire.getLayoutEpoch()));
            requestFreshBaseline("history projection stale");
          } else if (range.status == HistoryRangeResult.Status.RETRYABLE) {
            scheduleHistoryRetry(pending, range.retryAfterMs);
          }
          break;
        }
        case TAIL_STATUS: {
          TerminalScreenV2Proto.TailStatus status = envelope.getTailStatus();
          requireCurrentStreamGeneration(status.getStreamGeneration());
          boolean accepted = model.observeTailStatus(
              status.getInstanceId(),
              status.getLayoutEpoch(),
              status.getLatestScreenRevision(),
              historyExtent(status.getLatestHistoryExtent()));
          if (!accepted) return;
          recordCapturedModelState(false);
          if (streamState == StreamState.FROZEN) completeSynchronization();
          if (status.getExited()) updateState(State.CLOSED);
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
          break;
        default:
          break;
      }
    } catch (RemoteTerminalModel.RevisionGapException e) {
      Diagnostics.warn("screen_protocol", "revision_gap", diagnosticFields(
          "localRevision", model.screenRevision,
          "streamGeneration", streamGeneration));
      startResyncRecovery("screen.v2 revision gap");
      return;
    } catch (Exception e) {
      Diagnostics.warn("screen_protocol", "screen_v2_apply_failed", diagnosticFields(
          "failureKind", e.getClass().getSimpleName(),
          "localRevision", model.screenRevision));
      startResyncRecovery("screen.v2 apply failed");
      return;
    }
    TerminalRenderMetrics.modelApplyDuration(System.nanoTime() - applyStartedNanos);
    if (renderChanged) dispatchRenderNeeded();
  }

  private void requireCurrentStreamGeneration(long generation) {
    if (generation != streamGeneration) {
      throw new IllegalArgumentException("stale stream generation");
    }
  }

  private static HistoryExtent historyExtent(TerminalScreenV2Proto.HistoryExtent extent) {
    return new HistoryExtent(extent.getFirstSeq(), extent.getLastSeq());
  }


  /**
   * 构造当前事件流身份（sessionId/terminalInstanceId/clientInstanceId），供捕获做会话级隔离。
   * 仅在 isRecording() 为真时调用，避免热路径在无捕获时的对象分配。
   */
  com.webterm.terminal.model.capture.CaptureStreamIdentity captureStreamIdentity() {
    String terminalInstanceId = model.instanceId == null ? "" : model.instanceId;
    String clientInstanceId = "";
    ScreenConnection c = connection;
    if (c != null && c.reliableInputTracker() != null) {
      clientInstanceId = c.reliableInputTracker().clientInstanceId();
    }
    return new com.webterm.terminal.model.capture.CaptureStreamIdentity(
        sessionId, terminalInstanceId, clientInstanceId);
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
            model.remoteScreenRevision(),
            model.rows,
            model.columns,
            model.activeBuffer == TerminalBufferKind.ALTERNATE ? 1 : 0,
            model.projectionHealth().complete,
            afterBaseline,
            model.displayExtent(),
            model.remoteAvailableExtent()));
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
      case TITLE:
        screenEffect = TerminalScreenEffect.title(effect.getTitle().getTitle());
        break;
      case CWD:
        screenEffect = TerminalScreenEffect.workingDirectory(effect.getCwd().getPath());
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

  /** 请求一次最新模型绘制，供页面 attach、恢复和本地滚动使用。 */
  public void requestRender() {
    model.requestFullRender();
    dispatchRenderNeeded();
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
    state = newState;
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
