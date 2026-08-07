package com.webterm.feature.terminal.domain;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.TerminalViewportState;

/**
 * 页面级屏幕控制器。持有 View / Renderer / Viewport，负责 attach/detach 和用户输入。
 * 不拥有模型；模型归 TerminalSessionRuntime 所有。
 */
public final class TerminalScreenController implements TerminalSessionRuntime.Listener {

  public interface View {
    /** 绑定会话模型仅供输入/选择等交互使用；Canvas 不从它读取绘制快照。 */
    void bindModel(@NonNull RemoteTerminalModel model);
    void render(@NonNull RenderUpdate update, @NonNull TerminalViewportState viewport);
    void onCursorChanged();
    void requestInvalidate();
    default int liveScreenExitOffsetPixels() { return Integer.MAX_VALUE; }
    default void onConnectionStateChanged(@NonNull TerminalSessionRuntime.State state) {}
    default void onLayoutLeaseStateChanged(boolean ready) {}
    default void onInputDeliveryUncertain(@NonNull String message) {}
  }

  public interface EffectListener {
    void onEffect(@NonNull TerminalScreenEffect effect);
  }

  private static final long RESIZE_DEBOUNCE_MS = 100L;
  private final TerminalSessionRuntime runtime;
  private final TerminalViewportState viewport;
  private final LifecycleEventObserver lifecycleObserver;
  private final FrameScheduler frameScheduler;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Runnable sendResizeRunnable = this::sendResizeNow;

  private int pendingCols;
  private int pendingRows;
  private EffectListener effectListener;
  private View view;
  private boolean renderScheduled;
  private boolean renderConsumerActive;
  @Nullable private Runnable scheduledRenderCallback;
  private long renderGeneration;
  private boolean historyDemandScheduled;
  private long pendingDemandFrom;
  private long pendingDemandTo;
  private long pendingDemandAnchor;
  private int pendingDemandDirection;
  private final Runnable flushHistoryDemandRunnable = this::flushPendingHistoryDemand;

  public TerminalScreenController(@NonNull TerminalSessionRuntime runtime) {
    this(runtime, new TerminalViewportState(), new ChoreographerFrameScheduler());
  }

  /** Registry 注入 session-scoped viewport，使普通 View 重建不丢失滚动锚点。 */
  public TerminalScreenController(@NonNull TerminalSessionRuntime runtime,
                                  @NonNull TerminalViewportState viewport) {
    this(runtime, viewport, new ChoreographerFrameScheduler());
  }

  TerminalScreenController(@NonNull TerminalSessionRuntime runtime,
                           @NonNull TerminalViewportState viewport,
                           @NonNull FrameScheduler frameScheduler) {
    this.runtime = runtime;
    this.viewport = viewport;
    this.frameScheduler = frameScheduler;
    this.lifecycleObserver = (source, event) -> {
      if (event == Lifecycle.Event.ON_RESUME) {
        activateRenderConsumer();
      } else if (event == Lifecycle.Event.ON_PAUSE) {
        deactivateRenderConsumer();
      }
    };
  }

  public void attach(@NonNull LifecycleOwner owner, @NonNull View view) {
    this.view = view;
    view.bindModel(runtime.model());
    owner.getLifecycle().addObserver(lifecycleObserver);
    // addObserver 通常会同步补发当前状态；测试/fake Lifecycle 未补发时显式覆盖
    // 已经 RESUMED 的 attach，且 activate 自身幂等。
    Lifecycle.State state = owner.getLifecycle().getCurrentState();
    if (state != null && state.isAtLeast(Lifecycle.State.RESUMED)) activateRenderConsumer();
  }

  public void detach(@NonNull LifecycleOwner owner) {
    deactivateRenderConsumer();
    owner.getLifecycle().removeObserver(lifecycleObserver);
    cancelPendingHistoryDemand();
    runtime.onVisibleHistoryDemandCleared();
    view = null;
  }

  private void activateRenderConsumer() {
    if (renderConsumerActive) return;
    runtime.registerRenderConsumer(this);
    renderConsumerActive = true;
    runtime.addListener(this);
    runtime.requestRender();
  }

  private void deactivateRenderConsumer() {
    cancelPendingRender();
    runtime.removeListener(this);
    if (!renderConsumerActive) return;
    renderConsumerActive = false;
    runtime.unregisterRenderConsumer(this);
  }

  public void sendText(@NonNull String text) {
    prepareLiveInput();
    runtime.sendTextInput(text);
  }

  public void sendPaste(@NonNull String text) {
    prepareLiveInput();
    runtime.sendPasteInput(text);
  }

  public void sendKey(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                      boolean meta, boolean pressed) {
    prepareLiveInput();
    runtime.sendKeyInput(key, shift, alt, ctrl, meta, pressed);
  }

  public void sendMouse(int row, int col, @NonNull String button, int wheelDelta,
                        boolean shift, boolean alt, boolean ctrl, boolean meta,
                        boolean pressed) {
    prepareLiveInput();
    runtime.sendMouseInput(row, col, button, wheelDelta, shift, alt, ctrl, meta, pressed);
  }

  public void sendFocus(boolean focused) {
    runtime.sendFocusInput(focused);
  }

  public void setEffectListener(@Nullable EffectListener listener) {
    this.effectListener = listener;
  }

  public void requestResize(int cols, int rows) {
    requestResize(cols, rows, 0, 0, 0f, 0f, false);
  }

  public void requestResize(int cols, int rows, int viewWidth, int viewHeight,
                            float cellWidth, float lineHeight, boolean keyboardVisible) {
    if (cols <= 0 || rows <= 0) return;
    pendingCols = cols;
    pendingRows = rows;
    mainHandler.removeCallbacks(sendResizeRunnable);
    mainHandler.postDelayed(sendResizeRunnable, RESIZE_DEBOUNCE_MS);
  }

  @androidx.annotation.VisibleForTesting
  TerminalViewportState viewport() {
    return viewport;
  }

  @androidx.annotation.VisibleForTesting
  boolean renderScheduled() {
    return renderScheduled;
  }

  @androidx.annotation.VisibleForTesting
  LifecycleEventObserver lifecycleObserver() {
    return lifecycleObserver;
  }

  public void onScrollPixels(
      int deltaPixels, int maxScrollOffsetPixels, int liveScreenExitOffsetPixels) {
    // RemoteTerminalView 已使用同一个 immutable RenderSnapshot 建立 LineAnchor。
    // Controller 不保存像素 offset，也不触发任何网络状态变化。
  }

  /** View 上报可见历史需求；同一帧内合并为最新 demand。 */
  public void onVisibleHistoryDemand(long fromSeq, long toSeq, long anchorSeq) {
    onVisibleHistoryDemand(fromSeq, toSeq, anchorSeq, 0);
  }

  public void onVisibleHistoryDemand(long fromSeq, long toSeq, long anchorSeq, int direction) {
    boolean frameCoalesced = historyDemandScheduled;
    HistoryDemandMetrics.viewportProduced(frameCoalesced);
    pendingDemandFrom = fromSeq;
    pendingDemandTo = toSeq;
    pendingDemandAnchor = anchorSeq;
    pendingDemandDirection = direction;
    if (historyDemandScheduled) return;
    historyDemandScheduled = true;
    frameScheduler.postFrame(flushHistoryDemandRunnable);
  }

  public void onVisibleHistoryDemandCleared() {
    cancelPendingHistoryDemand();
    runtime.onVisibleHistoryDemandCleared();
  }

  private void flushPendingHistoryDemand() {
    historyDemandScheduled = false;
    long from = pendingDemandFrom;
    long to = pendingDemandTo;
    long anchor = pendingDemandAnchor;
    int direction = pendingDemandDirection;
    if (from <= 0 || to < from) {
      // 无效区间不主动 clearDemand；detach 仍走 onVisibleHistoryDemandCleared。
      return;
    }
    runtime.onVisibleHistoryDemand(from, to, anchor, direction,
        (int) Math.max(1, to - from + 1));
  }

  private void cancelPendingHistoryDemand() {
    if (!historyDemandScheduled) return;
    historyDemandScheduled = false;
    frameScheduler.cancelFrame(flushHistoryDemandRunnable);
  }

  private void sendResizeNow() {
    if (pendingCols <= 0 || pendingRows <= 0) return;
    // LayoutLeaseCoordinator owns dedupe, lease/connection gating, and the
    // last-successfully-sent geometry. The controller only debounces measurement.
    runtime.requestResize(pendingCols, pendingRows);
  }

  private void applyTerminalState(@NonNull RenderUpdate update) {
    // LineAnchor 由 publication 内同一个 RenderSnapshot 解析。Baseline、分页和
    // Promotion 都不能在 Controller 中读取更晚模型或重置 viewport。
    if (update.state.historyChanged) viewport.loadingOlderHistory = false;
  }

  private void prepareLiveInput() {
    // Viewport position never controls projection continuity or input delivery.
  }

  @Override
  public void onRenderNeeded() {
    requestRender();
  }

  @Override
  public void onEffect(@NonNull TerminalScreenEffect effect) {
    dispatchEffect(effect);
  }

  private void dispatchEffect(@NonNull TerminalScreenEffect effect) {
    View v = view;
    if (v != null) {
      switch (effect.type()) {
        case BELL:
          // TODO: 播放铃声
          break;
        default:
          break;
      }
    }
    if (effectListener != null) {
      effectListener.onEffect(effect);
    }
  }

  @Override
  public void onConnectionStateChange(@NonNull TerminalSessionRuntime.State state) {
    View v = view;
    if (state == TerminalSessionRuntime.State.CLOSING
        || state == TerminalSessionRuntime.State.CLOSED) {
      // 关闭路径只更新视图状态，禁止再 requestRender（避免 close 后唤醒 model 绘制）。
      if (v != null) v.onConnectionStateChanged(state);
      return;
    }
    if (v != null) v.onConnectionStateChanged(state);
    runtime.requestRender();
  }

  @Override
  public void onLayoutLeaseStateChange(boolean ready) {
    View v = view;
    if (v != null) v.onLayoutLeaseStateChanged(ready);
  }

  @Override
  public void onInputDeliveryUncertain(@NonNull String message) {
    View v = view;
    if (v != null) v.onInputDeliveryUncertain(message);
  }

  private void requestRender() {
    if (renderScheduled) return;
    renderScheduled = true;
    long generation = ++renderGeneration;
    Runnable callback = () -> renderOnFrame(generation);
    scheduledRenderCallback = callback;
    frameScheduler.postFrame(callback);
    com.webterm.terminal.model.TerminalRenderMetrics.renderRequested();
  }

  private void renderOnFrame(long callbackGeneration) {
    if (!renderScheduled || callbackGeneration != renderGeneration) return;
    renderScheduled = false;
    scheduledRenderCallback = null;
    View v = view;
    RenderUpdate update = runtime.consumeRenderUpdate(this);
    if (update == null) return;
    applyTerminalState(update);
    if (update.dirty.isEmpty()) {
      runtime.onRenderPublicationHandled(
          update.publicationVersion, update.snapshot.screenRevision, false);
      return;
    }
    if (v == null) return;
    com.webterm.terminal.model.TerminalRenderMetrics.vsyncRender();
    long started = System.nanoTime();
    try {
      v.render(update, viewport);
      long duration = System.nanoTime() - started;
      com.webterm.terminal.model.TerminalRenderMetrics.vsyncDrawDuration(duration);
      runtime.onRenderFrameSucceeded(
          update.publicationVersion, update.snapshot.screenRevision, duration);
    } catch (RuntimeException error) {
      long duration = System.nanoTime() - started;
      com.webterm.terminal.model.TerminalRenderMetrics.vsyncDrawDuration(duration);
      runtime.onRenderFrameFailed(
          update.publicationVersion, update.snapshot.screenRevision, duration, error);
      throw error;
    }
  }

  private void cancelPendingRender() {
    renderGeneration++;
    Runnable callback = scheduledRenderCallback;
    if (callback != null) frameScheduler.cancelFrame(callback);
    scheduledRenderCallback = null;
    renderScheduled = false;
  }
}
