package com.webterm.feature.terminal.domain;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.webterm.terminal.model.ModelChange;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.TerminalViewportState;

/**
 * 页面级屏幕控制器。持有 View / Renderer / Viewport，负责 attach/detach 和用户输入。
 * 不拥有模型；模型归 TerminalSessionRuntime 所有。
 */
public final class TerminalScreenController implements TerminalSessionRuntime.Listener {

  public interface View {
    void render(@NonNull RemoteTerminalModel model, @NonNull TerminalViewportState viewport);
    void onCursorChanged();
    void onTitleChanged(@Nullable String title);
    void requestInvalidate();
    /** Only invoked for tail appends while the user is not following the tail. */
    default void onHistoryAppended(int lineCount) {}
    default void onConnectionStateChanged(@NonNull TerminalSessionRuntime.State state) {}
  }

  public interface EffectListener {
    void onEffect(@NonNull TerminalScreenEffect effect);
  }

  private static final long RESIZE_DEBOUNCE_MS = 100L;
  // A terminal can receive many PTY chunks before Android draws the next frame.
  // Rendering the latest published model once per frame window prevents stale
  // callback work from accumulating on the main thread.
  private static final long RENDER_FRAME_WINDOW_MS = 16L;

  private final TerminalSessionRuntime runtime;
  private final TerminalViewportState viewport = new TerminalViewportState();
  private final LifecycleEventObserver lifecycleObserver;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Runnable sendResizeRunnable = this::sendResizeNow;
  private final Runnable renderRunnable = this::renderNow;

  private int pendingCols;
  private int pendingRows;
  private int sentCols = -1;
  private int sentRows = -1;
  private EffectListener effectListener;
  private View view;
  private boolean renderScheduled;
  /** 上一次成功排队的历史分页边界；用于保证 beforeId 严格向旧方向推进。 */
  private long lastRequestedHistoryBeforeId = -1;

  public TerminalScreenController(@NonNull TerminalSessionRuntime runtime) {
    this.runtime = runtime;
    this.lifecycleObserver = (source, event) -> {
      if (event == Lifecycle.Event.ON_RESUME) {
        runtime.addListener(this);
        requestRender();
      } else if (event == Lifecycle.Event.ON_PAUSE) {
        runtime.removeListener(this);
      }
    };
  }

  public void attach(@NonNull LifecycleOwner owner, @NonNull View view) {
    this.view = view;
    owner.getLifecycle().addObserver(lifecycleObserver);
    runtime.addListener(this);
    requestRender();
  }

  public void detach(@NonNull LifecycleOwner owner) {
    runtime.removeListener(this);
    owner.getLifecycle().removeObserver(lifecycleObserver);
    view = null;
    mainHandler.removeCallbacks(renderRunnable);
    renderScheduled = false;
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

  public void onScrollPixels(int deltaPixels, int maxScrollOffsetPixels) {
    if (deltaPixels == 0) return;
    viewport.scrollBy(deltaPixels, maxScrollOffsetPixels);
    requestRender();
  }

  public void requestOlderHistoryPage() {
    if (viewport.loadingOlderHistory) return;
    RemoteTerminalModel model = runtime.model();
    long firstCachedId = model.firstCachedHistoryId();
    if (firstCachedId < 0 && !model.hasMoreHistoryBefore()) return;
    long beforeLineId = firstCachedId < 0 ? Long.MAX_VALUE : firstCachedId;
    if (firstCachedId >= 0 && firstCachedId <= model.firstAvailableHistoryId()) return;
    // 同一边界只请求一次：若上一页未能推进本地窗口（例如被预算驱逐或返回空页），
    // 重复请求同一页只会形成热循环。模型侧驱逐保证新页存活，正常路径下
    // firstCachedId 每次分页后严格变小。
    if (beforeLineId == lastRequestedHistoryBeforeId) return;
    lastRequestedHistoryBeforeId = beforeLineId;
    viewport.loadingOlderHistory = true;
    runtime.requestHistoryPage(beforeLineId, 250);
  }

  private void sendResizeNow() {
    if (pendingCols <= 0 || pendingRows <= 0) return;
    if (pendingCols == sentCols && pendingRows == sentRows) return;
    runtime.requestResize(pendingCols, pendingRows);
    sentCols = pendingCols;
    sentRows = pendingRows;
  }

  @Override
  public void onModelChange(@NonNull ModelChange change) {
    // A resize/new-instance snapshot replaces physical screen rows and history
    // anchors. Keep a user's viewport during same-geometry full snapshots, but
    // reset it when the authoritative terminal geometry changes.
    if (change.geometryChanged) {
      viewport.resetForSnapshot();
      lastRequestedHistoryBeforeId = -1;
    }
    // Only tail appends (live output scrolling into history below the visible
    // window) compensate the offset to pin the current content. A prepended
    // history page lands above the cached rows; in the bottom-anchored geometry
    // it shifts historyRows and old row indices together, so old lines keep
    // their screen Y and the offset must stay untouched — otherwise a returned
    // page would undo the user's reverse swipes.
    if (!viewport.followTail && change.tailAppendedLines > 0 && view != null) {
      view.onHistoryAppended(change.tailAppendedLines);
    }
    if (change.historyChanged) viewport.loadingOlderHistory = false;
    requestRender();
  }

  @Override
  public void onEffect(@NonNull TerminalScreenEffect effect) {
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
    requestRender();
  }

  private void requestRender() {
    if (renderScheduled) return;
    renderScheduled = true;
    mainHandler.postDelayed(renderRunnable, RENDER_FRAME_WINDOW_MS);
  }

  private void renderNow() {
    renderScheduled = false;
    View v = view;
    if (v != null) {
      v.render(runtime.model(), viewport);
    }
  }
}
