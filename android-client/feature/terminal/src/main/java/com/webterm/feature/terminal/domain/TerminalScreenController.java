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
    default void onHistoryAppended(int lineCount) {}
  }

  public interface EffectListener {
    void onEffect(@NonNull TerminalScreenEffect effect);
  }

  private static final long RESIZE_DEBOUNCE_MS = 100L;

  private final TerminalSessionRuntime runtime;
  private final TerminalViewportState viewport = new TerminalViewportState();
  private final LifecycleEventObserver lifecycleObserver;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Runnable sendResizeRunnable = this::sendResizeNow;

  private int pendingCols;
  private int pendingRows;
  private int sentCols = -1;
  private int sentRows = -1;
  private EffectListener effectListener;
  private View view;

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

  public void onScrollPixels(int deltaPixels) {
    if (deltaPixels == 0) return;
    viewport.followTail = false;
    viewport.scrollOffsetPixels = Math.max(0, viewport.scrollOffsetPixels + deltaPixels);
    requestRender();
  }

  public void followTail() {
    viewport.followTail = true;
    viewport.scrollOffsetPixels = 0;
    viewport.unreadLineCount = 0;
    requestRender();
  }

  public void requestOlderHistoryPage() {
    if (viewport.loadingOlderHistory) return;
    RemoteTerminalModel model = runtime.model();
    if (model.historyCache().isEmpty() && !model.hasMoreHistoryBefore()) return;
    long firstCachedId = model.historyCache().isEmpty()
        ? Long.MAX_VALUE
        : model.historyCache().firstKey();
    if (firstCachedId <= model.firstAvailableHistoryId()) return;
    long beforeLineId = firstCachedId;
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
    if (!viewport.followTail && change.historyChanged) {
      viewport.unreadLineCount++;
      if (change.appendedHistoryLines > 0 && view != null) {
        view.onHistoryAppended(change.appendedHistoryLines);
      }
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
    requestRender();
  }

  private void requestRender() {
    View v = view;
    if (v != null) {
      v.render(runtime.model(), viewport);
    }
  }
}
