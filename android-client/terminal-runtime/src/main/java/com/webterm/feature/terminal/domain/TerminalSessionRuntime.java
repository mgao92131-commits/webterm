package com.webterm.feature.terminal.domain;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ResumeToken;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.model.ScreenPatch;
import com.webterm.core.contract.diagnostics.DiagnosticLevel;
import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.protocol.ScreenMessageMapper;
import com.webterm.terminal.protocol.ScreenMessageValidator;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.HashMap;
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

  /** 可注入的延迟调度器；回调内部必须重新投递到 modelExecutor 并校验 generation。 */
  public interface TimeoutScheduler {
    void schedule(@NonNull Runnable task, long delayMs);
  }

  /** 屏幕协议连接抽象。 */
  public interface ScreenConnection {
    void setListener(@NonNull Listener listener);
    /** transport 建立后由 model executor 提供原子 resume token 并开始 Hello。 */
    default boolean beginSync(@NonNull ResumeToken resumeToken) { return false; }
    void setLayoutLeaseId(@NonNull String leaseId);
    void sendTextInput(@NonNull String text);
    void sendPasteInput(@NonNull String text);
    void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed);
    void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                        boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed);
    void sendFocusInput(boolean focused);
    void requestResize(int cols, int rows);
    /** 返回 true 表示请求已成功排队发送；false 表示当前无可用通道，调用方不得留下 pending 状态。 */
    boolean requestHistoryPage(@NonNull String requestId, long beforeLineId, int limit);
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
  private long patchSummaryGeneration;
  private long patchSummaryFirstBaseRevision = -1;
  private long patchSummaryLastScreenRevision = -1;
  private String patchSummaryInstanceId;
  private long patchSummaryLayoutEpoch = -1;
  private int patchSummaryCount;
  private long patchSummaryBytes;
  private int patchSummaryChangedRows;
  private int patchSummaryHistoryAppend;

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
        // TODO: 上报非致命错误
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
    if (state != State.CONNECTED || !hasLayoutLease()) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendTextInput(text);
    }
  }

  public void sendPasteInput(@NonNull String text) {
    if (state != State.CONNECTED || !hasLayoutLease()) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendPasteInput(text);
    }
  }

  public void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                           boolean meta, boolean pressed) {
    if (state != State.CONNECTED || !hasLayoutLease()) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendKeyInput(key, shift, alt, ctrl, meta, pressed);
    }
  }

  public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                             boolean shift, boolean alt, boolean ctrl, boolean meta,
                             boolean pressed) {
    if (state != State.CONNECTED || !hasLayoutLease()) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendMouseInput(row, col, button, wheelDelta, shift, alt, ctrl, meta, pressed);
    }
  }

  public void sendFocusInput(boolean focused) {
    if (state != State.CONNECTED || !hasLayoutLease()) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendFocusInput(focused);
    }
  }

  public void requestResize(int cols, int rows) {
    layoutLeaseCoordinator.requestResize(cols, rows);
  }

  /**
   * 只有连接可用且请求成功排队后才记录 pending 请求 id 并返回 true；
   * 否则返回 false，调用方必须清除 loading 状态并允许之后重试。
   */
  public boolean requestHistoryPage(long beforeLineId, int limit) {
    if (state != State.CONNECTED) return false;
    ScreenConnection c = connection;
    if (c == null) return false;
    String requestId = historyRequests.nextRequestId();
    if (!c.requestHistoryPage(requestId, beforeLineId, limit)) return false;
    historyRequests.markPending(requestId);
    return true;
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
    ResumeToken token = !resyncCoordinator.isRecovering()
        ? model.resumeToken()
        : ResumeToken.cold(RemoteTerminalModel.SCHEMA_GENERATION);
    updateState(State.SYNCING);
    long generation = ++syncGeneration;
    if (!expectedConnection.beginSync(token)) {
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
    ScreenMessageValidator.ValidationResult frameSize =
        ScreenMessageValidator.validateEnvelopeSize(payload);
    if (state == State.CLOSED) return;
    ScreenMailbox.MessageKind kind = classifyScreenMessage(payload);
    ScreenMailbox.Offer offer = screenMailbox.offer(
        messageEpoch, sourceConnection, payload, frameSize.ok, kind);
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
                drain.fence.overflowCount);
            continue;
          }
          ScreenMailbox.Message message = drain.message;
          // 旧物理连接已经到达本地但尚未处理的 Snapshot/Patch/Lease 不得跨代际生效。
          if (message.connectionEpoch != connectionEpoch.get()
              || message.mailboxGeneration != screenMailbox.generation()
              || message.sourceConnection != connection) continue;
          TerminalRenderMetrics.inboundScreenFrame(message.kind.ordinal(), message.payload.length);
          TerminalRenderMetrics.mailboxResidenceDuration(System.nanoTime() - message.enqueuedAtNanos);
          // A recovery fence only accepts the authority frame that can release it. Dropping
          // patches here avoids protobuf parsing and allocation while a snapshot is in flight.
          if (message.kind == ScreenMailbox.MessageKind.PATCH && resyncCoordinator.isRecovering()) {
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

  /** Reads only envelope tags; it intentionally does not alter the protobuf-only wire payload. */
  @NonNull
  private static ScreenMailbox.MessageKind classifyScreenMessage(@NonNull byte[] payload) {
    try {
      CodedInputStream input = CodedInputStream.newInstance(payload);
      while (!input.isAtEnd()) {
        int tag = input.readTag();
        if (tag == 0) break;
        int field = WireFormat.getTagFieldNumber(tag);
        // ScreenEnvelope oneof fields in terminal_screen.proto.
        if (field == 11) return ScreenMailbox.MessageKind.SNAPSHOT;
        if (field == 12) return ScreenMailbox.MessageKind.PATCH;
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
                                 long overflowCount) {
    TerminalResumeMetrics.screenMailboxOverflow(reason, discardedBytes, overflowCount);
    boolean wasRecovering = resyncCoordinator.isRecovering();
    resyncCoordinator.onMailboxOverflow(reason);
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
    historyRequests.clear();
  }

  private void resetResyncRecovery() {
    resyncCoordinator.reset();
    historyRequests.clear();
    screenMailbox.reset();
    resetPatchSummary();
  }

  private void sendResync(@NonNull String reason) {
    ScreenConnection c = connection;
    if (c != null) {
      Diagnostics.warn("screen_protocol", "resync_requested", diagnosticFields(
          "layoutEpoch", model.layoutEpoch,
          "screenRevision", model.screenRevision,
          "reason", reason));
      c.requestResync(model.layoutEpoch, model.screenRevision, reason);
    }
  }

  private void processScreenMessage(@NonNull ScreenMailbox.Message message) {
      TerminalScreenProto.ScreenEnvelope envelope;
      try {
        long parseStartedNanos = System.nanoTime();
        envelope = TerminalScreenProto.ScreenEnvelope.parseFrom(message.payload);
        TerminalRenderMetrics.protobufParseDuration(System.nanoTime() - parseStartedNanos);
        if (envelope.getProtocolVersion() != 1) {
          throw new IllegalArgumentException("unsupported protocol version");
        }
      } catch (Exception e) {
        Diagnostics.warn("screen_protocol", "screen_frame_decode_failed", diagnosticFields(
            "failureKind", e.getClass().getSimpleName(),
            "localRevision", model.screenRevision));
        startResyncRecovery(e.getMessage() != null ? e.getMessage() : "invalid screen message");
        return;
      }

      // 旧物理连接已经到达本地但尚未处理的帧不得跨代际生效。
      if (message.connectionEpoch != connectionEpoch.get()
          || message.sourceConnection != connection) return;

      // InputAck 与 instance identity 同样消费这一次 typed parse；TerminalChannel
      // 不再在 DeviceConnection event loop 上重复解析原始 protobuf。
      try {
        if (ScreenEnvelopeDispatcher.dispatchReliableInput(
            envelope, message.sourceConnection.reliableInputTracker())) return;
      } catch (RuntimeException e) {
        Diagnostics.warn("screen_protocol", "input_ack_processing_failed", diagnosticFields(
            "failureKind", e.getClass().getSimpleName(),
            "localRevision", model.screenRevision));
        ReliableInputTracker tracker = message.sourceConnection.reliableInputTracker();
        if (tracker != null) tracker.clear();
        return;
      }

      long modelApplyStartedNanos = System.nanoTime();
      switch (envelope.getPayloadCase()) {
        case SNAPSHOT: {
          Diagnostics.info("screen_protocol", "snapshot_received", diagnosticFields(
              "instanceId", envelope.getSnapshot().getInstanceId(),
              "layoutEpoch", envelope.getSnapshot().getLayoutEpoch(),
              "screenRevision", envelope.getSnapshot().getScreenRevision(),
              "payloadBytes", message.payload.length));
          ScreenMessageValidator.ValidationResult validation =
              ScreenMessageValidator.validateSnapshot(envelope.getSnapshot());
          if (!validation.ok) {
            Diagnostics.warn("screen_protocol", "snapshot_rejected", diagnosticFields(
                "failureKind", "INVALID_SNAPSHOT"));
            // 无效 snapshot 不能解除围栏：等待期间按退避重发 resync，
            // 空闲时启动一次恢复。不能静默丢弃，否则永远等不到权威帧。
            onInvalidSnapshot(validation.reason != null ? validation.reason : "invalid snapshot");
            return;
          }
          ScreenSnapshot snapshot;
          boolean geometryChanged;
          try {
            snapshot = ScreenMessageMapper.mapSnapshot(envelope.getSnapshot());
            geometryChanged = model.instanceId == null
                || !model.instanceId.equals(snapshot.instanceId)
                || model.layoutEpoch != snapshot.layoutEpoch
                || model.rows != snapshot.rows || model.columns != snapshot.cols;
            model.applySnapshot(snapshot);
          } catch (Exception e) {
            Diagnostics.warn("screen_protocol", "snapshot_apply_failed", diagnosticFields(
                "failureKind", e.getClass().getSimpleName(),
                "localRevision", model.screenRevision));
            startResyncRecovery(e.getMessage() != null ? e.getMessage() : "snapshot apply failed");
            return;
          }
          resetPatchSummary();
          Diagnostics.info("screen_protocol", "snapshot_applied", diagnosticFields(
              "instanceId", snapshot.instanceId,
              "layoutEpoch", snapshot.layoutEpoch,
              "screenRevision", snapshot.screenRevision,
              "changedRows", snapshot.screen.size(),
              "historyAppend", snapshot.history.lines.size(),
              "payloadBytes", message.payload.length,
              "applyDurationMs", elapsedMillis(modelApplyStartedNanos)));
          if (geometryChanged) {
            Diagnostics.info("screen_protocol", "layout_epoch_changed", diagnosticFields(
                "instanceId", snapshot.instanceId,
                "layoutEpoch", snapshot.layoutEpoch,
                "screenRevision", snapshot.screenRevision,
                "newCols", snapshot.cols,
                "newRows", snapshot.rows));
          }
          TerminalResumeMetrics.snapshot(envelope.getSnapshot().getScreenRevision());
          // A snapshot atomically replaces the local projection and is the
          // only frame that may release a revision-gap recovery fence.
          onAuthoritativeSnapshot();
          completeSynchronization();
          break;
        }
        case PATCH:
          // A patch is relative to the state that failed validation. Applying
          // queued patches while waiting for a snapshot only creates repeated
          // revision gaps and a resync storm on slow links. The envelope is
          // already parsed here on modelExecutor; drop without validating
          // or touching the local revision.
          if (resyncCoordinator.isRecovering()) return;
          // rows 取当前模型 geometry：patch 自身不携带 geometry，
          // 行索引上界只能相对本地投影校验（计划 §10.1）。
          ScreenMessageValidator.ValidationResult patchValidation =
              ScreenMessageValidator.validatePatch(envelope.getPatch(), model.rows);
          if (!patchValidation.ok) {
            Diagnostics.warn("screen_protocol", "patch_rejected", diagnosticFields(
                "failureKind", "INVALID_PATCH",
                "localRevision", model.screenRevision));
            startResyncRecovery(patchValidation.reason != null ? patchValidation.reason : "invalid patch");
            return;
          }
          try {
            boolean resumePatch = state == State.SYNCING || state == State.TRANSPORT_CONNECTED;
            long patchBase = envelope.getPatch().getBaseRevision();
            ScreenPatch patch = ScreenMessageMapper.mapPatch(envelope.getPatch(), model.columns);
            if (model.instanceId == null || !model.instanceId.equals(patch.instanceId)) {
              Diagnostics.warn("screen_protocol", "instance_mismatch", diagnosticFields(
                  "instanceId", patch.instanceId, "localRevision", model.screenRevision,
                  "baseRevision", patch.baseRevision));
            } else if (model.layoutEpoch != patch.layoutEpoch) {
              Diagnostics.warn("screen_protocol", "layout_epoch_mismatch", diagnosticFields(
                  "layoutEpoch", patch.layoutEpoch, "localRevision", model.screenRevision,
                  "baseRevision", patch.baseRevision));
            } else if (model.screenRevision != patch.baseRevision) {
              Diagnostics.warn("screen_protocol", "revision_gap", diagnosticFields(
                  "baseRevision", patch.baseRevision, "localRevision", model.screenRevision,
                  "screenRevision", patch.screenRevision));
            }
            model.applyPatch(patch);
            recordPatchSummary(message.payload.length, countScreenLineUpdates(patch), patch);
            if (resumePatch) {
              TerminalResumeMetrics.cumulativePatch(patchBase,
                  envelope.getPatch().getScreenRevision(),
                  envelope.getPatch().getLineUpdatesCount(),
                  envelope.getPatch().getHistoryAppendIdsCount());
            }
          } catch (Exception e) {
            Diagnostics.warn("screen_protocol", "patch_apply_failed", diagnosticFields(
                "failureKind", e.getClass().getSimpleName(),
                "localRevision", model.screenRevision));
            startResyncRecovery(e.getMessage() != null ? e.getMessage() : "patch apply failed");
            return;
          }
          completeSynchronization();
          break;
        case HISTORY_PAGE: {
          // A page is anchored to the cache window that requested it. A late
          // response after reconnect/snapshot must not prepend rows into a
          // different viewport.
          if (!historyRequests.accept(envelope.getHistoryPage().getRequestId())) return;
          try {
            requireValid(ScreenMessageValidator.validateHistoryPage(envelope.getHistoryPage()));
            model.prependHistoryPage(
                ScreenMessageMapper.mapHistoryPage(envelope.getHistoryPage(), model.columns));
            historyRequests.complete(envelope.getHistoryPage().getRequestId());
          } catch (Exception e) {
            Diagnostics.warn("screen_protocol", "history_page_rejected", diagnosticFields(
                "failureKind", e.getClass().getSimpleName(),
                "localRevision", model.screenRevision));
            historyRequests.complete(envelope.getHistoryPage().getRequestId());
            return;
          }
          break;
        }
        case HISTORY_TRIM:
          model.trimHistory(envelope.getHistoryTrim().getLayoutEpoch(),
              envelope.getHistoryTrim().getFirstAvailableLineId());
          break;
        case LAYOUT_LEASE:
          layoutLeaseCoordinator.handle(envelope.getLayoutLease());
          break;
        case EFFECT:
          if (model.instanceId != null && model.instanceId.equals(envelope.getEffect().getInstanceId())) {
            handleEffect(envelope.getEffect());
          }
          break;
        case EXIT:
          updateState(State.CLOSED);
          break;
        case RESUME_ACK:
          long ackBase = model.screenRevision;
          try {
            model.applyResumeAck(
                envelope.getResumeAck().getInstanceId(),
                envelope.getResumeAck().getLayoutEpoch(),
                envelope.getResumeAck().getScreenRevision());
          } catch (RemoteTerminalModel.RevisionGapException e) {
            Diagnostics.warn("screen_protocol", "resume_ack_rejected", diagnosticFields(
                "failureKind", e.getClass().getSimpleName(),
                "localRevision", model.screenRevision));
            startResyncRecovery(e.getMessage() != null ? e.getMessage() : "resume ack rejected");
            return;
          }
          TerminalResumeMetrics.exactResume(ackBase,
              envelope.getResumeAck().getScreenRevision());
          completeSynchronization();
          break;
        default:
          break;
      }
      TerminalRenderMetrics.modelApplyDuration(System.nanoTime() - modelApplyStartedNanos);
      if (envelope.getPayloadCase() == TerminalScreenProto.ScreenEnvelope.PayloadCase.SNAPSHOT
          || envelope.getPayloadCase() == TerminalScreenProto.ScreenEnvelope.PayloadCase.PATCH
          || envelope.getPayloadCase() == TerminalScreenProto.ScreenEnvelope.PayloadCase.HISTORY_PAGE
          || envelope.getPayloadCase() == TerminalScreenProto.ScreenEnvelope.PayloadCase.HISTORY_TRIM) {
        dispatchRenderNeeded();
      }
  }

  private static void requireValid(ScreenMessageValidator.ValidationResult result) {
    if (!result.ok) throw new IllegalArgumentException(result.reason);
  }

  private Map<String, Object> diagnosticFields(Object... pairs) {
    Map<String, Object> fields = new HashMap<>();
    fields.put("sessionId", sessionId);
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      fields.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return fields;
  }

  private void recordPatchSummary(int payloadBytes, int changedRows, @NonNull ScreenPatch patch) {
    if (!Diagnostics.isEnabled(DiagnosticLevel.INFO)) return;
    patchSummaryCount++;
    patchSummaryBytes += payloadBytes;
    patchSummaryChangedRows += changedRows;
    patchSummaryHistoryAppend += patch.historyAppendIds.size();
    if (patchSummaryFirstBaseRevision < 0) patchSummaryFirstBaseRevision = patch.baseRevision;
    patchSummaryLastScreenRevision = patch.screenRevision;
    if (patchSummaryCount == 1) {
      patchSummaryInstanceId = patch.instanceId;
      patchSummaryLayoutEpoch = patch.layoutEpoch;
      final long generation = patchSummaryGeneration;
      timeoutScheduler.schedule(
          () -> modelExecutor.execute(() -> flushPatchSummary(generation)), 1000L);
    }
  }

  /** History promotion carries an ID but is not a changed visible screen row. */
  private static int countScreenLineUpdates(@NonNull ScreenPatch patch) {
    if (patch.lineUpdates.isEmpty()) return 0;
    java.util.HashSet<Long> historyIds = new java.util.HashSet<>(patch.historyAppendIds);
    int count = 0;
    for (com.webterm.terminal.model.TerminalLine line : patch.lineUpdates) {
      if (!historyIds.contains(line.id)) count++;
    }
    return count;
  }

  private void flushPatchSummary(long generation) {
    if (generation != patchSummaryGeneration || patchSummaryCount == 0) return;
    Diagnostics.info("screen_protocol", "patch_applied_summary", diagnosticFields(
        "instanceId", patchSummaryInstanceId,
        "layoutEpoch", patchSummaryLayoutEpoch,
        "firstBaseRevision", patchSummaryFirstBaseRevision,
        "lastScreenRevision", patchSummaryLastScreenRevision,
        "patchCount", patchSummaryCount,
        "payloadBytes", patchSummaryBytes,
        "changedRows", patchSummaryChangedRows,
        "historyAppend", patchSummaryHistoryAppend));
    resetPatchSummary();
  }

  private void resetPatchSummary() {
    patchSummaryGeneration++;
    patchSummaryFirstBaseRevision = -1;
    patchSummaryLastScreenRevision = -1;
    patchSummaryInstanceId = null;
    patchSummaryLayoutEpoch = -1;
    patchSummaryCount = 0;
    patchSummaryBytes = 0;
    patchSummaryChangedRows = 0;
    patchSummaryHistoryAppend = 0;
  }

  private static long elapsedMillis(long startedNanos) {
    return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
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

  private void handleEffect(TerminalScreenProto.TerminalEffect effect) {
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
    if (screenEffect != null) {
      dispatchEffect(screenEffect);
    }
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
