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
import com.webterm.core.contract.diagnostics.Diagnostics;

import java.util.Map;

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
    void onTitleChanged(@Nullable String title);
    void requestInvalidate();
    /** Only invoked for tail appends while the user is not following the tail. */
    default void onHistoryAppended(int lineCount) {}
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
  private int sentCols = -1;
  private int sentRows = -1;
  private int pendingViewWidth;
  private int pendingViewHeight;
  private float pendingCellWidth;
  private float pendingLineHeight;
  private boolean pendingKeyboardVisible;
  private EffectListener effectListener;
  private View view;
  private boolean renderScheduled;
  @Nullable private Runnable scheduledRenderCallback;
  private long renderGeneration;
  /** 上一次成功排队的历史分页边界；用于保证 beforeSeq 严格向旧方向推进。 */
  private long lastRequestedHistoryBeforeSeq = -1;

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
        runtime.addListener(this);
        runtime.requestRender();
      } else if (event == Lifecycle.Event.ON_PAUSE) {
        runtime.removeListener(this);
        // 与 detach 一致地取消排队中的渲染：暂停期间不再需要绘制，
        // resume 时由 requestRender 统一请求一次最新快照。不置 view=null、
        // 不移除 observer，attach/detach 才负责完整解绑。
        cancelPendingRender();
      }
    };
  }

  public void attach(@NonNull LifecycleOwner owner, @NonNull View view) {
    this.view = view;
    view.bindModel(runtime.model());
    owner.getLifecycle().addObserver(lifecycleObserver);
    runtime.addListener(this);
    runtime.requestRender();
  }

  public void detach(@NonNull LifecycleOwner owner) {
    runtime.removeListener(this);
    owner.getLifecycle().removeObserver(lifecycleObserver);
    view = null;
    cancelPendingRender();
  }

  public void sendText(@NonNull String text) {
    runtime.sendTextInput(text);
  }

  public void sendPaste(@NonNull String text) {
    runtime.sendPasteInput(text);
  }

  public void sendKey(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                      boolean meta, boolean pressed) {
    runtime.sendKeyInput(key, shift, alt, ctrl, meta, pressed);
  }

  public void sendMouse(int row, int col, @NonNull String button, int wheelDelta,
                        boolean shift, boolean alt, boolean ctrl, boolean meta,
                        boolean pressed) {
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
    pendingViewWidth = viewWidth;
    pendingViewHeight = viewHeight;
    pendingCellWidth = cellWidth;
    pendingLineHeight = lineHeight;
    pendingKeyboardVisible = keyboardVisible;
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

  public void onScrollPixels(int deltaPixels, int maxScrollOffsetPixels) {
    if (deltaPixels == 0) return;
    viewport.scrollBy(deltaPixels, maxScrollOffsetPixels);
    runtime.requestRender();
  }

  public void requestOlderHistoryPage() {
    RemoteTerminalModel model = runtime.model();
    if (viewport.loadingOlderHistory) {
      // HistoryPage 已在模型线程应用但尚未走到下一次 VSync 时，也允许分页边界立即推进；
      // 不能让纯绘制节拍把用户的下一次翻页卡住。
      long cachedWhileLoading = model.firstCachedHistorySeq();
      if ((cachedWhileLoading >= 0 && cachedWhileLoading < lastRequestedHistoryBeforeSeq)
          || !model.hasMoreHistoryBefore()) {
        viewport.loadingOlderHistory = false;
      } else {
        return;
      }
    }
    long firstCachedSeq = model.firstCachedHistorySeq();
    if (firstCachedSeq < 0 && !model.hasMoreHistoryBefore()) return;
    long beforeHistorySeq = firstCachedSeq < 0 ? Long.MAX_VALUE : firstCachedSeq;
    if (firstCachedSeq >= 0 && firstCachedSeq <= model.firstAvailableHistorySeq()) return;
    // 同一边界只请求一次：若上一页未能推进本地窗口（例如被预算驱逐或返回空页），
    // 重复请求同一页只会形成热循环。模型侧驱逐保证新页存活，正常路径下
    // firstCachedSeq 每次分页后严格变小。
    if (beforeHistorySeq == lastRequestedHistoryBeforeSeq) return;
    // 请求失败（无连接或通道不可用）：不记录边界、不置 loading，
    // loadingOlderHistory 保持 false，允许之后重试同一边界。
    if (!runtime.requestHistoryPage(beforeHistorySeq, 250)) return;
    lastRequestedHistoryBeforeSeq = beforeHistorySeq;
    viewport.loadingOlderHistory = true;
  }

  private void sendResizeNow() {
    if (pendingCols <= 0 || pendingRows <= 0) return;
    if (pendingCols == sentCols && pendingRows == sentRows) return;
    Diagnostics.info("terminal_view", "terminal_resize_requested", Map.ofEntries(
        Map.entry("sessionId", runtime.sessionId()),
        Map.entry("oldCols", sentCols),
        Map.entry("oldRows", sentRows),
        Map.entry("newCols", pendingCols),
        Map.entry("newRows", pendingRows),
        Map.entry("viewWidth", pendingViewWidth),
        Map.entry("viewHeight", pendingViewHeight),
        Map.entry("cellWidth", pendingCellWidth),
        Map.entry("lineHeight", pendingLineHeight),
        Map.entry("keyboardVisible", pendingKeyboardVisible),
        Map.entry("layoutEpoch", runtime.model().layoutEpoch)));
    runtime.requestResize(pendingCols, pendingRows);
    sentCols = pendingCols;
    sentRows = pendingRows;
  }

  private void applyTerminalState(@NonNull RenderUpdate update) {
    // A resize/new-instance snapshot replaces physical screen rows and history
    // anchors. Keep a user's viewport during same-geometry full snapshots, but
    // reset it when the authoritative terminal geometry changes.
    if (update.state.geometryChanged) {
      viewport.resetForSnapshot();
      lastRequestedHistoryBeforeSeq = -1;
    }
    // Only tail appends (live output scrolling into history below the visible
    // window) compensate the offset to pin the current content. A prepended
    // history page lands above the cached rows; in the bottom-anchored geometry
    // it shifts historyRows and old row indices together, so old lines keep
    // their screen Y and the offset must stay untouched — otherwise a returned
    // page would undo the user's reverse swipes.
    if (!viewport.followTail && update.state.tailAppendedLines > 0 && view != null) {
      view.onHistoryAppended(update.state.tailAppendedLines);
    }
    if (update.state.historyChanged) viewport.loadingOlderHistory = false;

    // Snapshot/Patch 中的元数据与实时 Effect 共用同一条页面分发路径。这样重连恢复
    // 不会遗漏 title/cwd；页面层负责基于值去重，避免随后到达的实时 Effect 重复通知。
    if (update.state.titleChanged) {
      dispatchEffect(TerminalScreenEffect.title(update.snapshot.title));
    }
    if (update.state.workingDirectoryChanged) {
      dispatchEffect(TerminalScreenEffect.workingDirectory(update.snapshot.workingDirectory));
    }
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
        case TITLE:
          v.onTitleChanged(effect.asTitle());
          break;
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
    RenderUpdate update = runtime.model().consumeRenderUpdate();
    if (update != null) applyTerminalState(update);
    if (v != null && update != null && !update.dirty.isEmpty()) {
      com.webterm.terminal.model.TerminalRenderMetrics.vsyncRender();
      v.render(update, viewport);
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
