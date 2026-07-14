package com.webterm.feature.terminal.domain;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.ModelChange;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ResumeToken;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.protocol.ScreenMessageMapper;
import com.webterm.terminal.protocol.ScreenMessageValidator;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import java.util.ArrayDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

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
  /** resync 最多重发次数；耗尽后升级为 channel 重建。 */
  private static final int MAX_RESYNC_RETRIES = 3;
  /** 发送 resync 后等待权威 snapshot 的时间，超时按有界退避重发。 */
  private static final long RESYNC_SNAPSHOT_TIMEOUT_MS = 2000L;
  /** 第 1/2/3 次重发 resync 前的退避延迟。 */
  private static final long[] RETRY_BACKOFF_MS = {1000L, 2000L, 4000L};

  public interface Listener {
    void onModelChange(@NonNull ModelChange change);
    void onEffect(@NonNull TerminalScreenEffect effect);
    void onConnectionStateChange(@NonNull State state);
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
    void releaseLayout();
    void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data);
    void close();

    interface Listener {
      void onScreenMessage(@NonNull byte[] payload);
      void onConnected();
      void onDisconnected(@Nullable String reason);
      default void onAuthenticationRequired(@Nullable String reason) {}
      void onClosed();
    }
  }

  private final String sessionId;
  private final RemoteTerminalModel model;
  private final Executor modelExecutor;
  private final Executor callbackExecutor;
  private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
  private final Object screenMailboxLock = new Object();
  private final ArrayDeque<byte[]> pendingScreenMessages = new ArrayDeque<>();
  private boolean screenDrainScheduled;

  private volatile State state = State.CONNECTING;
  private volatile String layoutLeaseId = "";
  private volatile boolean layoutLeaseGranted;
  private volatile int lastRequestedCols;
  private volatile int lastRequestedRows;

  /** resync 恢复状态机；modelExecutor 是唯一状态推进线程，字段无需 volatile。 */
  private enum ResyncState {
    IDLE,
    WAITING_SNAPSHOT,
    RETRY_SCHEDULED,
    RECONNECT_REQUIRED
  }

  private ResyncState resyncState = ResyncState.IDLE;
  private int resyncAttempt;
  /** 每次推进状态都递增，同时充当 timeout token：回调携带当时的 generation，过期即丢弃。 */
  private long resyncGeneration;
  private String resyncReason = "";
  private final AtomicLong nextHistoryRequestId = new AtomicLong();
  @Nullable private volatile String pendingHistoryRequestId;
  private volatile ScreenConnection connection;
  private volatile boolean connectionRequiresReplacement;
  @Nullable private volatile AuthenticationListener authenticationListener;
  @Nullable private volatile EffectSink effectSink;
  private final TimeoutScheduler timeoutScheduler;
  private long syncGeneration;

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
    this.sessionId = sessionId;
    this.model = model;
    this.modelExecutor = modelExecutor;
    this.callbackExecutor = callbackExecutor;
    this.timeoutScheduler = timeoutScheduler;
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
    this.connection = connection;
    this.connectionRequiresReplacement = false;
    connection.setListener(new ScreenConnection.Listener() {
      @Override
      public void onScreenMessage(@NonNull byte[] payload) {
        if (TerminalSessionRuntime.this.connection != connection) return;
        handleScreenMessage(payload);
      }

      @Override
      public void onConnected() {
        if (TerminalSessionRuntime.this.connection != connection) return;
        updateState(State.TRANSPORT_CONNECTED);
        modelExecutor.execute(() -> beginSynchronization(connection));
      }

      @Override
      public void onDisconnected(@Nullable String reason) {
        if (TerminalSessionRuntime.this.connection != connection) return;
        // 断线后 Go 侧会释放租约；本地同步失效，避免 resize 丢进死通道，
        // 重连拿到新租约后 handleLayoutLease 会用 lastRequested* 补发最新尺寸。
        layoutLeaseGranted = false;
        layoutLeaseId = "";
        connectionRequiresReplacement = false;
        // 取消在途 timeout、清理 mailbox 和 pending history：状态机归 modelExecutor 所有。
        modelExecutor.execute(() -> {
          syncGeneration++;
          resetResyncRecovery();
        });
        updateState(State.RECONNECTING);
      }

      @Override
      public void onAuthenticationRequired(@Nullable String reason) {
        if (TerminalSessionRuntime.this.connection != connection) return;
        layoutLeaseGranted = false;
        layoutLeaseId = "";
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
            if (currentListener != null) currentListener.onAuthenticationRequired(reason);
          });
        }
      }

      @Override
      public void onClosed() {
        if (TerminalSessionRuntime.this.connection != connection) return;
        TerminalSessionRuntime.this.connection = null;
        connectionRequiresReplacement = false;
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
    connection = null;
    connectionRequiresReplacement = false;
    layoutLeaseGranted = false;
    layoutLeaseId = "";
    modelExecutor.execute(() -> {
      syncGeneration++;
      resetResyncRecovery();
    });
    if (current != null) {
      current.releaseLayout();
      current.close();
    }
    if (state != State.CLOSED) updateState(State.RECONNECTING);
  }

  /** View detach 只释放焦点与交互租约，不关闭 channel。 */
  public void detachPage() {
    ScreenConnection current = connection;
    if (current != null) {
      current.sendFocusInput(false);
      current.releaseLayout();
    }
    layoutLeaseGranted = false;
    layoutLeaseId = "";
  }

  /** HOT reattach 不需要网络恢复，只重新申请交互租约。 */
  public void attachPage() {
    ScreenConnection current = connection;
    if (current != null && state == State.CONNECTED) current.acquireLayout(true);
  }

  public void addListener(@NonNull Listener listener) {
    if (listeners.addIfAbsent(listener)) {
      callbackExecutor.execute(() -> listener.onConnectionStateChange(state));
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
    if (state != State.CONNECTED || !layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendTextInput(text);
    }
  }

  public void sendPasteInput(@NonNull String text) {
    if (state != State.CONNECTED || !layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendPasteInput(text);
    }
  }

  public void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                           boolean meta, boolean pressed) {
    if (state != State.CONNECTED || !layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendKeyInput(key, shift, alt, ctrl, meta, pressed);
    }
  }

  public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                             boolean shift, boolean alt, boolean ctrl, boolean meta,
                             boolean pressed) {
    if (state != State.CONNECTED || !layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendMouseInput(row, col, button, wheelDelta, shift, alt, ctrl, meta, pressed);
    }
  }

  public void sendFocusInput(boolean focused) {
    if (state != State.CONNECTED || !layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendFocusInput(focused);
    }
  }

  public void requestResize(int cols, int rows) {
    if (cols <= 0 || rows <= 0) return;
    // 无论租约是否在手都先记下最新尺寸；租约授予时统一补发，
    // 否则首次连接（租约往返慢于 View 首帧）或断线重连期间的尺寸请求会静默丢失。
    lastRequestedCols = cols;
    lastRequestedRows = rows;
    if (state != State.CONNECTED || !layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.requestResize(cols, rows);
    }
  }

  /**
   * 只有连接可用且请求成功排队后才记录 pending 请求 id 并返回 true；
   * 否则返回 false，调用方必须清除 loading 状态并允许之后重试。
   */
  public boolean requestHistoryPage(long beforeLineId, int limit) {
    if (state != State.CONNECTED) return false;
    ScreenConnection c = connection;
    if (c == null) return false;
    String requestId = "h-" + nextHistoryRequestId.incrementAndGet();
    if (!c.requestHistoryPage(requestId, beforeLineId, limit)) return false;
    pendingHistoryRequestId = requestId;
    return true;
  }

  public void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data) {
    ScreenConnection c = connection;
    if (c != null) {
      c.sendClipboardResponse(requestId, allowed, timeout, data);
    }
  }

  public void close() {
    updateState(State.CLOSED);
    synchronized (screenMailboxLock) {
      pendingScreenMessages.clear();
    }
    // 取消在途 timeout 并复位恢复状态机（递增 generation 作废旧回调）。
    modelExecutor.execute(this::resetResyncRecovery);
    ScreenConnection c = connection;
    if (c != null) {
      c.releaseLayout();
      c.close();
    }
    layoutLeaseId = "";
    layoutLeaseGranted = false;
    modelExecutor.execute(() -> syncGeneration++);
  }

  // ---- transport/screen 同步状态机（modelExecutor 唯一推进） ----

  private void beginSynchronization(@NonNull ScreenConnection expectedConnection) {
    if (state == State.CLOSED || connection != expectedConnection) return;
    ResumeToken token = resyncState == ResyncState.IDLE
        ? model.resumeToken()
        : ResumeToken.cold(RemoteTerminalModel.SCHEMA_GENERATION);
    updateState(State.SYNCING);
    long generation = ++syncGeneration;
    if (expectedConnection.beginSync(token)) {
      timeoutScheduler.schedule(
          () -> modelExecutor.execute(() -> onSynchronizationTimeout(generation)),
          RESYNC_SNAPSHOT_TIMEOUT_MS);
    }
  }

  private void onSynchronizationTimeout(long generation) {
    if (generation != syncGeneration || state != State.SYNCING) return;
    startResyncRecovery("initial synchronization timeout");
  }

  private void completeSynchronization() {
    if (state != State.SYNCING && state != State.TRANSPORT_CONNECTED) return;
    syncGeneration++;
    updateState(State.CONNECTED);
    ScreenConnection c = connection;
    if (c != null) c.acquireLayout(true);
  }

  private void handleScreenMessage(byte[] payload) {
    boolean scheduleDrain = false;
    boolean overflow = false;
    synchronized (screenMailboxLock) {
      if (state == State.CLOSED) return;
      if (pendingScreenMessages.size() >= MAX_PENDING_SCREEN_MESSAGES) {
        // Patches are sequential and cannot be safely coalesced. Drop the
        // stale backlog and converge through one authoritative snapshot
        // instead of retaining an unbounded queue of byte arrays. The resync
        // state machine is owned by modelExecutor, so escalate there even
        // when a fence is already up: the just-cleared queue may have held
        // the in-flight snapshot, and the old wait must not go silent.
        pendingScreenMessages.clear();
        overflow = true;
      }
      pendingScreenMessages.addLast(payload);
      if (!screenDrainScheduled) {
        screenDrainScheduled = true;
        scheduleDrain = true;
      }
    }
    if (overflow) modelExecutor.execute(this::onMailboxOverflow);
    if (scheduleDrain) modelExecutor.execute(this::drainScreenMailbox);
  }

  private void drainScreenMailbox() {
    while (true) {
      byte[] payload;
      synchronized (screenMailboxLock) {
        payload = pendingScreenMessages.pollFirst();
        if (payload == null) {
          screenDrainScheduled = false;
          return;
        }
      }
      processScreenMessage(payload);
    }
  }

  // ---- resync 恢复状态机（以下方法只能在 modelExecutor 上调用） ----

  private void startResyncRecovery(@NonNull String reason) {
    if (resyncState != ResyncState.IDLE) return;
    resyncAttempt = 0;
    resyncReason = reason;
    resyncState = ResyncState.WAITING_SNAPSHOT;
    resyncGeneration++;
    sendResync(reason);
    armResyncWaitTimeout();
  }

  private void onMailboxOverflow() {
    String reason = "screen model backlog exceeded " + MAX_PENDING_SCREEN_MESSAGES + " frames";
    if (resyncState == ResyncState.IDLE) {
      startResyncRecovery(reason);
      return;
    }
    if (resyncState == ResyncState.RECONNECT_REQUIRED) return;
    // 等待 resync 期间再次溢出：被清出队列的可能正是在途 SNAPSHOT。
    // 推进 generation 作废旧 timeout，重新发送 resync 并重新计时，
    // 不能因为旧的等待状态而永久静默。
    resyncReason = reason;
    resyncState = ResyncState.WAITING_SNAPSHOT;
    resyncGeneration++;
    sendResync(reason);
    armResyncWaitTimeout();
  }

  private void onInvalidSnapshot(@NonNull String reason) {
    if (resyncState == ResyncState.IDLE) {
      startResyncRecovery(reason);
    } else if (resyncState == ResyncState.WAITING_SNAPSHOT) {
      // 等到的是无效 snapshot：不再空等，按退避重发 resync。
      scheduleResyncRetry(reason);
    }
    // RETRY_SCHEDULED / RECONNECT_REQUIRED：已有待执行的恢复动作，直接丢弃。
  }

  private void onAuthoritativeSnapshot() {
    resyncState = ResyncState.IDLE;
    resyncAttempt = 0;
    resyncGeneration++;
    pendingHistoryRequestId = null;
  }

  private void scheduleResyncRetry(@NonNull String reason) {
    if (resyncState != ResyncState.WAITING_SNAPSHOT) return;
    resyncReason = reason;
    if (resyncAttempt >= MAX_RESYNC_RETRIES) {
      // 重试耗尽：进入 RECONNECT_REQUIRED，只触发一次 channel 重建。
      resyncState = ResyncState.RECONNECT_REQUIRED;
      resyncGeneration++;
      ScreenConnection c = connection;
      if (c != null) {
        c.requestReconnect("resync retries exhausted after " + MAX_RESYNC_RETRIES
            + " attempts: " + reason);
      }
      return;
    }
    resyncAttempt++;
    resyncState = ResyncState.RETRY_SCHEDULED;
    final long gen = ++resyncGeneration;
    timeoutScheduler.schedule(
        () -> modelExecutor.execute(() -> onResyncRetryFire(gen)),
        RETRY_BACKOFF_MS[resyncAttempt - 1]);
  }

  private void armResyncWaitTimeout() {
    final long gen = resyncGeneration;
    timeoutScheduler.schedule(
        () -> modelExecutor.execute(() -> onResyncWaitTimeout(gen)),
        RESYNC_SNAPSHOT_TIMEOUT_MS);
  }

  private void onResyncWaitTimeout(long gen) {
    if (gen != resyncGeneration || resyncState != ResyncState.WAITING_SNAPSHOT) return;
    scheduleResyncRetry("snapshot timeout after resync: " + resyncReason);
  }

  private void onResyncRetryFire(long gen) {
    if (gen != resyncGeneration || resyncState != ResyncState.RETRY_SCHEDULED) return;
    resyncState = ResyncState.WAITING_SNAPSHOT;
    resyncGeneration++;
    sendResync(resyncReason);
    armResyncWaitTimeout();
  }

  private void resetResyncRecovery() {
    resyncState = ResyncState.IDLE;
    resyncAttempt = 0;
    resyncGeneration++;
    pendingHistoryRequestId = null;
    synchronized (screenMailboxLock) {
      pendingScreenMessages.clear();
    }
  }

  private void sendResync(@NonNull String reason) {
    ScreenConnection c = connection;
    if (c != null) {
      c.requestResync(model.layoutEpoch, model.screenRevision, reason);
    }
  }

  private void processScreenMessage(byte[] payload) {
      try {
        // envelope 2 MiB 上限（proto 头部契约 / 计划 §10.1）必须在解码前校验，
        // 避免超大帧直接触发内存分配。
        ScreenMessageValidator.ValidationResult size = ScreenMessageValidator.validateEnvelopeSize(payload);
        if (!size.ok) throw new IllegalArgumentException(size.reason);
        TerminalScreenProto.ScreenEnvelope envelope =
            TerminalScreenProto.ScreenEnvelope.parseFrom(payload);
        if (envelope.getProtocolVersion() != 1) {
          throw new IllegalArgumentException("unsupported protocol version");
        }
        ModelChange change;
        switch (envelope.getPayloadCase()) {
          case SNAPSHOT: {
            ScreenMessageValidator.ValidationResult validation =
                ScreenMessageValidator.validateSnapshot(envelope.getSnapshot());
            if (!validation.ok) {
              // 无效 snapshot 不能解除围栏：等待期间按退避重发 resync，
              // 空闲时启动一次恢复。不能静默丢弃，否则永远等不到权威帧。
              onInvalidSnapshot(validation.reason != null ? validation.reason : "invalid snapshot");
              return;
            }
            change = model.applySnapshot(ScreenMessageMapper.mapSnapshot(envelope.getSnapshot()));
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
            if (resyncState != ResyncState.IDLE) return;
            // rows 取当前模型 geometry：patch 自身不携带 geometry，
            // 行索引上界只能相对本地投影校验（计划 §10.1）。
            requireValid(ScreenMessageValidator.validatePatch(envelope.getPatch(), model.rows));
            change = model.applyPatch(ScreenMessageMapper.mapPatch(envelope.getPatch()));
            completeSynchronization();
            break;
          case HISTORY_PAGE:
            // A page is anchored to the cache window that requested it. A late
            // response after reconnect/snapshot must not prepend rows into a
            // different viewport.
            if (!envelope.getHistoryPage().getRequestId().equals(pendingHistoryRequestId)) return;
            requireValid(ScreenMessageValidator.validateHistoryPage(envelope.getHistoryPage()));
            change = model.prependHistoryPage(ScreenMessageMapper.mapHistoryPage(envelope.getHistoryPage()));
            pendingHistoryRequestId = null;
            break;
          case HISTORY_TRIM:
            change = model.trimHistory(envelope.getHistoryTrim().getLayoutEpoch(),
                envelope.getHistoryTrim().getFirstAvailableLineId());
            break;
          case LAYOUT_LEASE:
            handleLayoutLease(envelope.getLayoutLease());
            change = ModelChange.none();
            break;
          case EFFECT:
            change = ModelChange.none();
            if (model.instanceId != null && model.instanceId.equals(envelope.getEffect().getInstanceId())) {
              handleEffect(envelope.getEffect());
            }
            break;
          case EXIT:
            change = ModelChange.none();
            updateState(State.CLOSED);
            break;
          case RESUME_ACK:
            model.applyResumeAck(
                envelope.getResumeAck().getInstanceId(),
                envelope.getResumeAck().getLayoutEpoch(),
                envelope.getResumeAck().getScreenRevision());
            change = ModelChange.none();
            completeSynchronization();
            break;
          default:
            change = ModelChange.none();
            break;
        }
        dispatchModelChange(change);
      } catch (Exception e) {
        // revision gap、校验失败、解析失败：空闲时启动一次恢复；
        // 等待/重试期间的消息失败由等待超时兜底，这里直接丢弃。
        startResyncRecovery(e.getMessage() != null ? e.getMessage() : "invalid screen message");
      }
  }

  private static void requireValid(ScreenMessageValidator.ValidationResult result) {
    if (!result.ok) throw new IllegalArgumentException(result.reason);
  }

  @NonNull
  public String layoutLeaseId() {
    return layoutLeaseId == null ? "" : layoutLeaseId;
  }

  public boolean hasLayoutLease() {
    return layoutLeaseGranted;
  }

  private void handleLayoutLease(TerminalScreenProto.LayoutLease lease) {
    if (state != State.CONNECTED) return;
    if (lease.getGranted()) {
      layoutLeaseId = lease.getLeaseId();
      layoutLeaseGranted = true;
    } else {
      layoutLeaseId = "";
      layoutLeaseGranted = false;
    }
    ScreenConnection c = connection;
    if (c != null) {
      c.setLayoutLeaseId(layoutLeaseId);
      // 租约到手后补发一次最新尺寸：覆盖连接时的占位 hello 尺寸，
      // 以及租约往返期间/断线期间被缓存下来的 resize 请求。
      if (layoutLeaseGranted && lastRequestedCols > 0 && lastRequestedRows > 0) {
        c.requestResize(lastRequestedCols, lastRequestedRows);
      }
    }
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
      if (sink != null) sink.onEffect(this, effect, !listeners.isEmpty());
      for (Listener listener : listeners) listener.onEffect(effect);
    });
  }

  private void dispatchModelChange(@NonNull ModelChange change) {
    callbackExecutor.execute(() -> {
      for (Listener listener : listeners) listener.onModelChange(change);
    });
  }

  private void updateState(@NonNull State newState) {
    state = newState;
    callbackExecutor.execute(() -> {
      for (Listener listener : listeners) listener.onConnectionStateChange(newState);
    });
  }
}
