package com.webterm.terminal.renderer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Scroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.capture.CapturedScreenshot;
import com.webterm.terminal.model.capture.CapturedViewState;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalHistorySnapshot;
import com.webterm.terminal.model.TerminalHistoryView;
import com.webterm.terminal.model.HistoryRenderView;
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.UnifiedContentAxis;
import com.webterm.terminal.interaction.GestureAndScaleRecognizer;

import java.util.Map;

/**
 * 远程终端自定义 View。负责 Android View 生命周期、IME、触摸滚动、选择和触发渲染。
 */
public final class RemoteTerminalView extends View {
  private static final class InvalidationPlan {
    InvalidationResult result = InvalidationResult.NONE;
    final Rect[] rects = new Rect[MAX_PARTIAL_DIRTY_RECTS];
    int rectCount;
    int dirtyRowCount;

    InvalidationPlan() {
      for (int i = 0; i < rects.length; i++) rects[i] = new Rect();
    }

    void reset() {
      result = InvalidationResult.NONE;
      rectCount = 0;
      dirtyRowCount = 0;
    }
  }

  private static final int HANDLE_NONE = 0;
  private static final int HANDLE_START = 1;
  private static final int HANDLE_END = 2;
  private static final float AUTO_SCROLL_EDGE_DP = 48f;
  private static final float AUTO_SCROLL_MIN_LINES_PER_SECOND = 3f;
  private static final float AUTO_SCROLL_MAX_LINES_PER_SECOND = 12f;
  private static final int MAX_PARTIAL_DIRTY_RECTS = 8;
  private static final float MAX_PARTIAL_DIRTY_AREA_RATIO = 0.40f;

  /**
   * 使用系统普通文本输入类型，保留用户当前输入法及中英文状态；只关闭联想、
   * 自动改正与自动大写。不能使用 VISIBLE_PASSWORD，部分 OEM 输入法会因此
   * 强制切换到英文安全键盘。
   */
  static final int TERMINAL_INPUT_TYPE = android.text.InputType.TYPE_CLASS_TEXT
      | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;

  public interface Host {
    void onTextInput(@NonNull String text);
    void onPasteInput(@NonNull String text);
    void onKeyEvent(@NonNull KeyEvent event);
    void onRequestResize(int cols, int rows);
    default void onRequestResize(int cols, int rows, int viewWidth, int viewHeight,
                                 float cellWidth, float lineHeight, boolean keyboardVisible) {
      onRequestResize(cols, rows);
    }
    void onRequestShowKeyboard();
    /** @param maxScrollOffsetPixels inclusive top bound for this rendered content. */
    void onScrollPixels(
        int deltaPixels, int maxScrollOffsetPixels, int liveScreenExitOffsetPixels);
    /** View 上报可见 HistorySeq 闭区间与滚动方向（-1 更旧，+1 更新，0 未知）。 */
    default void onVisibleHistoryDemand(long fromSeq, long toSeq, long anchorSeq) {
      onVisibleHistoryDemand(fromSeq, toSeq, anchorSeq, 0);
    }
    default void onVisibleHistoryDemand(long fromSeq, long toSeq, long anchorSeq, int direction) {}
    void onFocusChanged(boolean focused);
    void onMouse(int row, int col, @NonNull String button, int wheelDelta,
                 boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed);
    void onAlternateScreenScroll(int rowsDown);
  }

  private final RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
  private final TerminalLineRenderNodeCache lineCache = new TerminalLineRenderNodeCache();
  private final GestureAndScaleRecognizer gestureRecognizer;
  private final Scroller scroller;
  private final Paint selectionHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final InvalidationPlan invalidationPlan = new InvalidationPlan();
  private final Rect oldSelectionDamage = new Rect();
  private final Rect newSelectionDamage = new Rect();
  private final Rect oldHandleDamage = new Rect();
  private final Rect newHandleDamage = new Rect();
  // 用于 RenderNode 行缓存的全局视觉 generation；任何影响正文字体的变化都会递增。
  private int fontGeneration;
  private int paletteGeneration;
  private int styleGeneration;

  private RemoteTerminalModel model;
  /** Canvas 本帧唯一允许使用的不可变快照；绝不在 onDraw 再向模型取新快照。 */
  @Nullable private RemoteTerminalModel.RenderSnapshot renderedSnapshot;
  /** 最近一次应用的脏区，仅供现场捕获只读快照使用。 */
  @Nullable private RenderDirtyState lastAppliedDirty;
  private TerminalViewportState viewport = new TerminalViewportState();
  private Host host;
  /** -1 向更旧历史，+1 向更新输出，0 未知；供 Range 方向预取。 */
  private int historyDemandDirection;
  private float lastFlingY;
  private int flingFramesScheduledForTest;
  private boolean selecting;
  @Nullable private ActionMode selectionActionMode;
  private int draggingHandle; // HANDLE_NONE / HANDLE_START / HANDLE_END
  private TerminalSelection.Anchor selectionStart;
  private TerminalSelection.Anchor selectionEnd;
  private int userTextSizeSp;
  private Typeface userTypeface;
  private int appliedGeometryWidth = -1;
  private int appliedGeometryHeight = -1;
  private int appliedTextSizeSp = -1;
  @Nullable private Typeface appliedTypeface;
  private boolean scrolledWithFinger;
  private float lastPointerX;
  private float lastPointerY;
  private boolean mouseMoveScheduled;
  private int pendingMouseRow;
  private int pendingMouseCol;
  private int pendingMouseMeta;
  private final Runnable mouseMoveRunnable = this::flushPendingMouseMove;
  private float selectionPointerX;
  private float selectionPointerY;
  private float handleTouchOffsetX;
  private float handleTouchOffsetY;
  private float autoScrollRemainderPixels;
  private long autoScrollLastFrameNanos;
  private boolean autoScrollScheduled;
  private final Runnable selectionAutoScrollRunnable = this::runSelectionAutoScrollFrame;
  private boolean cursorBlinkOn = true;
  private int previousCursorRow = -1;
  private int currentCursorRow = -1;
  private boolean cursorBlinkScheduled;
  private final Runnable cursorBlinkRunnable = new Runnable() {
    @Override public void run() {
      if (!shouldBlinkCursor()) {
        stopCursorBlinking();
        return;
      }
      cursorBlinkOn = !cursorBlinkOn;
      invalidateCursorRows(previousCursorRow, currentCursorRow);
      previousCursorRow = currentCursorRow;
      postOnAnimationDelayed(this, 500L);
    }
  };

  public RemoteTerminalView(Context context) {
    this(context, null);
  }

  public RemoteTerminalView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public RemoteTerminalView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    setFocusableInTouchMode(true);
    setFocusable(true);
    this.scroller = new Scroller(context);
    this.gestureRecognizer = new GestureAndScaleRecognizer(context, new GestureListener());
  }

  public void setHost(@Nullable Host host) {
    clearPendingMouseMove();
    if (host == null) stopSelectionAutoScroll();
    this.host = host;
  }

  /** 仅绑定交互所需模型；绘制数据必须通过 {@link #applyRenderUpdate} 进入。 */
  public void bindModel(@Nullable RemoteTerminalModel model) {
    this.model = model;
  }

  @Override
  protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    if (host != null) {
      host.onFocusChanged(gainFocus);
    }
  }

  public void setModel(@Nullable RemoteTerminalModel model, @Nullable TerminalViewportState viewport) {
    bindModel(model);
    if (viewport != null) {
      this.viewport = viewport;
    }
    updateRenderedSnapshot(model != null ? model.renderSnapshot() : null);
    requestLayoutIfSizeChanged();
    updateCursorBlinkSchedule();
    // 旧测试/嵌入调用的兼容入口不再参与正式脏区链路，保守全量重画。
    TerminalRenderMetrics.fullInvalidate();
    invalidate();
  }

  public void updateModel(@NonNull RemoteTerminalModel model) {
    setModel(model, viewport);
  }

  /**
   * 接收 Controller 在 VSync 原子消费出的绘制批次。脏区判断与真正 Canvas 绘制均使用
   * 同一个 snapshot，后续 Patch 只能通过下一次 RenderUpdate 改变它。
   */
  public void applyRenderUpdate(@NonNull RenderUpdate update,
                                @NonNull TerminalViewportState viewport) {
    RemoteTerminalModel.RenderSnapshot previousSnapshot = renderedSnapshot;
    this.viewport = viewport;
    if ((selectionStart == null || selectionEnd == null) && viewport.selection != null) {
      selectionStart = viewport.selection.start;
      selectionEnd = viewport.selection.end;
    }
    if (previousSnapshot != null && selectionStart != null && selectionEnd != null) {
      captureSelectionDamage(oldSelectionDamage, oldHandleDamage);
      selectionStart = reconcileSelectionAnchor(
          selectionStart, previousSnapshot, update.snapshot);
      selectionEnd = reconcileSelectionAnchor(
          selectionEnd, previousSnapshot, update.snapshot);
      if (selectionStart == null || selectionEnd == null) {
        selectionStart = null;
        selectionEnd = null;
      }
      updateViewportSelection();
    }
    updateRenderedSnapshot(update.snapshot);
    this.lastAppliedDirty = update.dirty; // 现场捕获只读快照用（不消费状态）
    boolean geometryChanged = requestLayoutIfSizeChanged();
    updateCursorBlinkSchedule();
    updateGenerationCounters(update.dirty);
    InvalidationPlan plan = buildInvalidationPlan(
        update.dirty, update.snapshot, viewport, geometryChanged, invalidationPlan);
    switch (plan.result) {
      case NONE:
        TerminalRenderMetrics.historyOnlyNoDraw();
        break;
      case PARTIAL:
        for (int i = 0; i < plan.rectCount; i++) invalidate(plan.rects[i]);
        TerminalRenderMetrics.partialRowInvalidate(plan.dirtyRowCount);
        break;
      case SCREEN_REGION:
        if (plan.rectCount > 0) invalidate(plan.rects[0]);
        TerminalRenderMetrics.screenRegionInvalidate();
        if (update.dirty.screenScrollRows != 0) {
          TerminalRenderMetrics.screenScrollEvent(Math.abs(update.dirty.screenScrollRows));
        }
        break;
      case FULL:
        TerminalRenderMetrics.fullInvalidate();
        invalidate();
        break;
    }
    reportVisibleHistoryDemand();
  }

  /**
   * Selection still exposes history/screen coordinates to text extraction, but publication
   * transitions must preserve its semantic identity. Resolve the old coordinate to a LineID,
   * then locate that same line on the new unified axis (including ActiveRows -> HistoryIndex
   * promotion) before publishing the new coordinate.
   */
  @Nullable
  @androidx.annotation.VisibleForTesting
  static TerminalSelection.Anchor reconcileSelectionAnchor(
      @NonNull TerminalSelection.Anchor anchor,
      @NonNull RemoteTerminalModel.RenderSnapshot previousSnapshot,
      @NonNull RemoteTerminalModel.RenderSnapshot nextSnapshot) {
    if (!java.util.Objects.equals(previousSnapshot.instanceId, nextSnapshot.instanceId)
        || previousSnapshot.layoutEpoch != nextSnapshot.layoutEpoch
        || previousSnapshot.historyGeneration != nextSnapshot.historyGeneration
        || previousSnapshot.activeBuffer != nextSnapshot.activeBuffer) {
      return null;
    }
    RenderLine previousLine;
    if (anchor.historySeq != 0) {
      int index = previousSnapshot.history.findSeqIndex(anchor.historySeq);
      previousLine = index >= 0 ? previousSnapshot.history.renderLineAt(index) : null;
    } else {
      previousLine = anchor.screenRow >= 0
          && anchor.screenRow < previousSnapshot.screenView.size()
          ? previousSnapshot.screenView.lineAt(anchor.screenRow)
          : null;
    }
    if (previousLine == null) return null;

    UnifiedContentAxis axis = nextSnapshot.contentAxis;
    Long axisRow = axis.rowOfLineKey(previousLine.key());
    if (axisRow == null) return null;
    UnifiedContentAxis.Item item = axis.itemAtRow(axisRow);
    if (item.kind == UnifiedContentAxis.Kind.LOADED_LINE) {
      return new TerminalSelection.Anchor(item.fromHistorySeq, -1, anchor.col);
    }
    if (item.kind == UnifiedContentAxis.Kind.ACTIVE_LINE) {
      long activeRow = axisRow - axis.historyRowCount();
      if (activeRow < 0 || activeRow > Integer.MAX_VALUE) return null;
      return new TerminalSelection.Anchor(0, (int) activeRow, anchor.col);
    }
    return null;
  }

  @androidx.annotation.VisibleForTesting
  void updateGenerationCounters(@NonNull RenderDirtyState dirty) {
    if (dirty.geometryChanged) fontGeneration++;
    if (dirty.paletteChanged) paletteGeneration++;
    if (dirty.stylesChanged || dirty.linksChanged) styleGeneration++;
  }

  @androidx.annotation.VisibleForTesting
  int[] visualGenerationsForTest() {
    return new int[] {fontGeneration, paletteGeneration, styleGeneration};
  }

  /**
   * Tail-appended history grows content above the live screen; when the user is
   * not following the tail the offset grows by the same pixel height so the
   * visible lines stay pinned. Routed through {@link TerminalViewportState#scrollBy}
   * so the stored offset stays clamped to the rendered hard top instead of
   * accumulating invisible overscroll. History prepends never call this: their
   * rows land above the cached rows and old lines keep their Y without any
   * offset change.
   */
  public void preserveViewportForAppendedLines(int lineCount) {
    // LineAnchor 绑定稳定 LineID；history 前缀增长后 offset 由新 snapshot 自动派生。
  }

  public void setTextSize(int sizeSp) {
    if (this.userTextSizeSp != sizeSp) fontGeneration++;
    this.userTextSizeSp = sizeSp;
    requestLayoutIfSizeChanged();
    invalidate();
  }

  public void setTypeface(@Nullable Typeface typeface) {
    if (this.userTypeface != typeface) fontGeneration++;
    this.userTypeface = typeface;
    requestLayoutIfSizeChanged();
    invalidate();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    updateSize(w, h);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    updateCursorBlinkSchedule();
  }

  @Override
  protected void onDetachedFromWindow() {
    clearPendingMouseMove();
    stopCursorBlinking();
    stopSelectionAutoScroll();
    stopSelection();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    if (snapshot == null) return;
    TerminalLineRenderNodeCache cache = canvas.isHardwareAccelerated() ? lineCache : null;
    if (cache != null) {
      int canvasBackground = RemoteTerminalRenderer.resolveColor(snapshot.palette,
          snapshot.palette.reverseVideo ? snapshot.palette.defaultFg : snapshot.palette.defaultBg);
      cache.beginFrame(snapshot, renderer, snapshot.palette, canvasBackground,
          fontGeneration, paletteGeneration, styleGeneration);
    }
    try {
      renderer.render(canvas, snapshot, viewport, cursorBlinkOn, cache);
      drawSelectionHandles(canvas, snapshot);
    } finally {
      if (cache != null) cache.endFrame();
    }
  }

  @Override
  public boolean onCheckIsTextEditor() {
    return true;
  }

  @Override
  public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
    outAttrs.inputType = TERMINAL_INPUT_TYPE;
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;
    return new RemoteTerminalInputConnection(this, host);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      return super.onKeyDown(keyCode, event);
    }
    if (host != null) {
      host.onKeyEvent(event);
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      return super.onKeyUp(keyCode, event);
    }
    if (host != null) {
      host.onKeyEvent(event);
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  @Override
  public boolean onKeyPreIme(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK && selecting) {
      stopSelection();
      return true;
    }
    return super.onKeyPreIme(keyCode, event);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    lastPointerX = event.getX();
    lastPointerY = event.getY();
    if (event.getAction() == MotionEvent.ACTION_DOWN) {
      requestFocus();
      if (!scroller.isFinished()) scroller.forceFinished(true);
    }
    if (event.getActionMasked() == MotionEvent.ACTION_UP
        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      stopSelectionAutoScroll();
    }
    if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
      if (handleMouseEvent(event)) return true;
    }
    if (handleSelectionHandleTouch(event)) return true;
    gestureRecognizer.onTouchEvent(event);
    return true;
  }

  @Override
  public void computeScroll() {
    super.computeScroll();
    if (!scroller.computeScrollOffset()) return;
    continueFlingFrame(scroller.getCurrY());
  }

  private void continueFlingFrame(float currentY) {
    int delta = (int) (currentY - lastFlingY);
    lastFlingY = currentY;
    applyScrollDelta(delta);
    // Scroller 的整数位置可能连续两帧相同；只要动画仍活跃就必须继续调度下一帧。
    flingFramesScheduledForTest++;
    postInvalidateOnAnimation();
  }

  @androidx.annotation.VisibleForTesting
  int flingFramesScheduledForTest() {
    return flingFramesScheduledForTest;
  }

  /**
   * 统一约定：deltaPixels 为正表示视口向历史（上方）移动，为负表示向底部（最新输出）移动。
   * 主缓冲区的 scrollOffsetPixels 语义与此一致；alternate buffer 与鼠标追踪路径在此换算符号。
   */
  private void applyScrollDelta(int deltaPixels) {
    if (host == null || selecting || deltaPixels == 0) return;
    boolean viewportChanged = false;
    if (isMouseTracking()) {
      // wheel_delta 协议约定：正值向上、负值向下，与「正值向历史」一致。
      host.onMouse(pointerRow(), pointerColumn(), "wheel",
          deltaPixels > 0 ? 1 : -1, false, false, false, false, true);
    } else if (isAlternateBuffer()) {
      // rowsDown 为正发送 ArrowDown（向最新输出），与「正值向历史」相反，取负。
      host.onAlternateScreenScroll(-Math.round(deltaPixels / lineHeight()));
    } else {
      viewport.scrollBy(
          deltaPixels, maxScrollOffsetPixels(), renderedSnapshot, lineHeight());
      host.onScrollPixels(
          deltaPixels, maxScrollOffsetPixels(), liveScreenExitOffsetPixels());
      historyDemandDirection = deltaPixels > 0 ? -1 : 1;
      reportVisibleHistoryDemand();
      viewportChanged = true;
    }
    updateCursorBlinkSchedule();
    if (viewportChanged) {
      postInvalidateOnAnimation(0, 0, getWidth(), getHeight());
    }
  }

  /**
   * 选择期间只移动 Android 主缓冲区 viewport。备用屏滚动会转换成方向键，不能由
   * 文本选择手势触发。
   *
   */
  private void scrollSelectionViewport(int deltaPixels) {
    if (host == null || deltaPixels == 0 || isAlternateBuffer()) return;
    viewport.scrollBy(
        deltaPixels, maxScrollOffsetPixels(), renderedSnapshot, lineHeight());
    host.onScrollPixels(
        deltaPixels, maxScrollOffsetPixels(), liveScreenExitOffsetPixels());
    historyDemandDirection = deltaPixels > 0 ? -1 : 1;
    reportVisibleHistoryDemand();
    postInvalidateOnAnimation(0, 0, getWidth(), getHeight());
  }

  private void updateSelectionAutoScroll(float x, float y) {
    selectionPointerX = x;
    selectionPointerY = y;
    if (!selecting || host == null || isAlternateBuffer()
        || autoScrollVelocityPixelsPerSecond(y) == 0f) {
      stopSelectionAutoScroll();
      return;
    }
    if (!autoScrollScheduled) {
      autoScrollScheduled = true;
      autoScrollLastFrameNanos = 0L;
      autoScrollRemainderPixels = 0f;
      postOnAnimation(selectionAutoScrollRunnable);
    }
  }

  private void runSelectionAutoScrollFrame() {
    if (!autoScrollScheduled || !selecting || host == null || isAlternateBuffer()) {
      stopSelectionAutoScroll();
      return;
    }
    float velocity = autoScrollVelocityPixelsPerSecond(selectionPointerY);
    if (velocity == 0f) {
      stopSelectionAutoScroll();
      return;
    }

    long now = System.nanoTime();
    float elapsedSeconds = autoScrollLastFrameNanos == 0L
        ? 1f / 60f
        : Math.min(0.05f, (now - autoScrollLastFrameNanos) / 1_000_000_000f);
    autoScrollLastFrameNanos = now;
    float requested = velocity * elapsedSeconds + autoScrollRemainderPixels;
    int deltaPixels = requested > 0 ? (int) Math.floor(requested) : (int) Math.ceil(requested);
    autoScrollRemainderPixels = requested - deltaPixels;

    if (deltaPixels != 0) {
      scrollSelectionViewport(deltaPixels);
      TerminalSelection.Anchor anchor = selectionAnchorAtPointer(selectionPointerX, selectionPointerY);
      if (anchor != null) {
        updateSelectionEndpoint(draggingHandle == HANDLE_START ? HANDLE_START : HANDLE_END, anchor);
      }
    }
    postOnAnimation(selectionAutoScrollRunnable);
  }

  private float autoScrollVelocityPixelsPerSecond(float y) {
    float edge = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, AUTO_SCROLL_EDGE_DP,
        getResources().getDisplayMetrics());
    if (edge <= 0f || getHeight() <= 0) return 0f;
    float top = renderer.getTopInset();
    float depth;
    float direction;
    if (y < top + edge) {
      depth = (top + edge - y) / edge;
      direction = 1f;
    } else if (y > getHeight() - edge) {
      depth = (y - (getHeight() - edge)) / edge;
      direction = -1f;
    } else {
      return 0f;
    }
    depth = Math.max(0f, Math.min(1f, depth));
    float linesPerSecond = AUTO_SCROLL_MIN_LINES_PER_SECOND
        + depth * (AUTO_SCROLL_MAX_LINES_PER_SECOND - AUTO_SCROLL_MIN_LINES_PER_SECOND);
    return direction * linesPerSecond * lineHeight();
  }

  private void stopSelectionAutoScroll() {
    removeCallbacks(selectionAutoScrollRunnable);
    autoScrollScheduled = false;
    autoScrollLastFrameNanos = 0L;
    autoScrollRemainderPixels = 0f;
  }

  @Nullable
  private TerminalSelection.Anchor selectionAnchorAtPointer(float pointerX, float pointerY) {
    if (draggingHandle == HANDLE_NONE) return pointToAnchor(pointerX, pointerY);
    // 手柄圆心位于行底边，而 pointToAnchor 接收单元格内部坐标。保留按下时手指
    // 相对圆心的偏移，再以目标行中线做命中，避免一开始拖动就跳到下一行。
    float hotspotX = pointerX - handleTouchOffsetX;
    float hotspotY = pointerY - handleTouchOffsetY;
    return pointToAnchor(hotspotX, hotspotY - lineHeight() * 0.5f);
  }

  @androidx.annotation.VisibleForTesting
  boolean isMouseTracking() {
    if (renderedSnapshot == null) return false;
    return renderedSnapshot.modes.mouseTracking != TerminalModes.MouseTracking.NONE;
  }

  @androidx.annotation.VisibleForTesting
  boolean isAlternateBuffer() {
    if (renderedSnapshot == null) return false;
    return renderedSnapshot.activeBuffer == TerminalBufferKind.ALTERNATE;
  }

  @androidx.annotation.VisibleForTesting
  int pointerRow() {
    int rows = renderedSnapshot != null ? renderedSnapshot.rows : 0;
    return Math.max(0, Math.min(rows > 0 ? rows - 1 : 0,
        (int) (lastPointerY / lineHeight())));
  }

  @androidx.annotation.VisibleForTesting
  int pointerColumn() {
    int cols = renderedSnapshot != null ? renderedSnapshot.columns : 0;
    return Math.max(0, Math.min(cols > 0 ? cols - 1 : 0,
        (int) (lastPointerX / cellWidth())));
  }

  private void sendMouse(MotionEvent event, @NonNull String button, int wheelDelta, boolean pressed) {
    if (host == null || renderedSnapshot == null) return;
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    lastPointerX = event.getX();
    lastPointerY = event.getY();
    int col = Math.max(0, Math.min(snapshot.columns - 1, (int) (event.getX() / cellWidth())));
    int row = Math.max(0, Math.min(snapshot.rows - 1, (int) (event.getY() / lineHeight())));
    sendMouseAt(row, col, event.getMetaState(), button, wheelDelta, pressed);
  }

  private void sendMouseAt(int row, int col, int meta, @NonNull String button, int wheelDelta,
                           boolean pressed) {
    if (host == null) return;
    host.onMouse(row, col, button, wheelDelta,
        (meta & KeyEvent.META_SHIFT_ON) != 0,
        (meta & KeyEvent.META_ALT_ON) != 0,
        (meta & KeyEvent.META_CTRL_ON) != 0,
        (meta & KeyEvent.META_META_ON) != 0,
        pressed);
  }

  private void scheduleMouseMove(MotionEvent event) {
    if (renderedSnapshot == null) return;
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    if (snapshot.rows <= 0 || snapshot.columns <= 0) return;
    lastPointerX = event.getX();
    lastPointerY = event.getY();
    pendingMouseCol = Math.max(0,
        Math.min(snapshot.columns - 1, (int) (event.getX() / cellWidth())));
    pendingMouseRow = Math.max(0,
        Math.min(snapshot.rows - 1, (int) (event.getY() / lineHeight())));
    pendingMouseMeta = event.getMetaState();
    if (!mouseMoveScheduled) {
      mouseMoveScheduled = true;
      postOnAnimation(mouseMoveRunnable);
    }
  }

  private void flushPendingMouseMove() {
    if (!mouseMoveScheduled) return;
    removeCallbacks(mouseMoveRunnable);
    mouseMoveScheduled = false;
    if (!isMouseTracking()) return;
    sendMouseAt(pendingMouseRow, pendingMouseCol, pendingMouseMeta, "left", 0, true);
  }

  private void clearPendingMouseMove() {
    removeCallbacks(mouseMoveRunnable);
    mouseMoveScheduled = false;
  }

  private boolean handleMouseEvent(MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_SCROLL && isMouseTracking()) {
      flushPendingMouseMove();
      float wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
      if (wheel != 0f) sendMouse(event, "wheel", wheel > 0 ? 1 : -1, true);
      return true;
    }
    if (!isMouseTracking()) return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        flushPendingMouseMove();
        if (event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) sendMouse(event, "left", 0, true);
        return true;
      case MotionEvent.ACTION_UP:
        flushPendingMouseMove();
        if (event.getButtonState() == 0) sendMouse(event, "left", 0, false);
        return true;
      case MotionEvent.ACTION_MOVE:
        if (event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) scheduleMouseMove(event);
        return true;
      default:
        return true;
    }
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent event) {
    if (event.isFromSource(InputDevice.SOURCE_MOUSE) && handleMouseEvent(event)) return true;
    if (event.isFromSource(InputDevice.SOURCE_MOUSE)
        && event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
      float wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
      if (wheel != 0f) {
        applyScrollDelta(wheel > 0f ? Math.round(lineHeight() * 3) : -Math.round(lineHeight() * 3));
      }
      return true;
    }
    return super.onGenericMotionEvent(event);
  }

  /**
   * v2 display extent 含尚未驻留的占位行。View 只上报可见 HistorySeq；
   * RangeLoader 根据当前可见缺失区间发起非对齐 Range。
   */
  @androidx.annotation.VisibleForTesting
  void reportVisibleHistoryDemand() {
    if (host == null || renderedSnapshot == null || isAlternateBuffer()) {
      if (host != null) host.onVisibleHistoryDemand(0, 0, 0, 0);
      return;
    }
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    HistoryRenderView history = snapshot.history;
    if (history.isEmpty() || getHeight() <= 0 || lineHeight() <= 0f) {
      host.onVisibleHistoryDemand(0, 0, 0, 0);
      return;
    }

    int screenRows = snapshot.screenView.size();
    int historyRows = history.size();
    float scrollOffset = viewportOffset(snapshot);
    float screenTop = RemoteTerminalRenderer.screenTopY(
        getHeight(), historyRows, screenRows, lineHeight(), renderer.getTopInset(), scrollOffset);
    float historyTop = screenTop - historyRows * lineHeight();
    long visible = RemoteTerminalRenderer.rowRangeIntersectingPacked(
        Math.round(renderer.getTopInset()), getHeight(), historyTop, lineHeight(), historyRows);
    int visibleFirst = (int) (visible >> 32);
    int visibleLast = (int) visible;
    if (visibleFirst >= visibleLast) {
      host.onVisibleHistoryDemand(0, 0, 0, 0);
      return;
    }
    long visibleFrom = history.firstSeq() + visibleFirst;
    long visibleTo = history.firstSeq() + visibleLast - 1L;
    host.onVisibleHistoryDemand(visibleFrom, visibleTo, visibleFrom, historyDemandDirection);
  }

  static boolean shouldRequestOlderHistory(int deltaPixels, int scrollOffsetPixels,
                                           int maxScrollOffsetPixels, boolean followTail) {
    return deltaPixels > 0 && !followTail && maxScrollOffsetPixels > 0
        && scrollOffsetPixels >= maxScrollOffsetPixels;
  }

  /**
   * The controller owns the viewport state, while this View owns the pixel
   * geometry. Supply the exact renderer-compatible top bound on every user
   * scroll so state cannot retain invisible overscroll beyond cached history.
   */
  @androidx.annotation.VisibleForTesting
  int maxScrollOffsetPixels() {
    return maxScrollOffsetPixels(renderedSnapshot);
  }

  @androidx.annotation.VisibleForTesting
  int maxScrollOffsetPixels(@Nullable RemoteTerminalModel.RenderSnapshot snapshot) {
    if (snapshot == null || snapshot.activeBuffer == TerminalBufferKind.ALTERNATE) return 0;
    int screenRows = snapshot.screenView.size();
    int historyRows = snapshot.history.size();
    int totalRows = historyRows + screenRows;
    float contentHeight = totalRows * lineHeight();
    float usableHeight = Math.max(0f, getHeight() - renderer.getTopInset());
    if (contentHeight <= usableHeight) return 0;
    // 与 RemoteTerminalRenderer.contentTopY 上界一致：滚到顶时首条历史行完整
    // 贴在 topInset 处。ceil 保证能到达该锚点，渲染侧的钳制吸收 <1px 过冲。
    return Math.max(0, (int) Math.ceil(historyRows * lineHeight()));
  }

  @androidx.annotation.VisibleForTesting
  float lineHeight() {
    float h = renderer.getLineHeight();
    return h > 0 ? h : 1;
  }

  public int liveScreenExitOffsetPixels() {
    return RemoteTerminalRenderer.liveScreenExitOffsetPixels(
        getHeight(), renderer.getTopInset());
  }

  private float cellWidth() {
    float w = renderer.getCellWidth();
    return w > 0 ? w : 1;
  }

  /**
   * 捕获点 E：返回当前 View 的只读诊断快照（几何/字体/viewport/渲染身份/光标/选择）。
   * 仅读取字段，绝不修改 View 状态；必须在主线程调用（读取 renderedSnapshot 与 viewport）。
   */
  @NonNull
  public CapturedViewState captureDiagnostics() {
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    long renderedRevision = snapshot != null ? snapshot.screenRevision : 0L;
    long renderedEpoch = snapshot != null ? snapshot.layoutEpoch : 0L;
    String renderedInstance = snapshot != null ? snapshot.instanceId : "";
    boolean hasSelection = selecting || selectionStart != null || selectionEnd != null;
    String typefaceDescription = userTypeface != null ? String.valueOf(userTypeface) : "monospace";
    int liveScreenExitOffsetPixels = liveScreenExitOffsetPixels();
    return new CapturedViewState(
        System.currentTimeMillis(),
        getWidth(), getHeight(),
        getPaddingLeft(), getPaddingTop(), getPaddingRight(), getPaddingBottom(),
        userTextSizeSp > 0 ? userTextSizeSp : 14f,
        typefaceDescription,
        cellWidth(), lineHeight(), renderer.getBaselineOffset(),
        viewportOffset(snapshot), isFollowingTail(snapshot),
        isFollowingTail(snapshot) ? "FOLLOW_TAIL" : "LINE_ANCHOR",
        liveScreenExitOffsetPixels,
        liveScreenExitOffsetPixels > 0
            && viewportOffset(snapshot) >= liveScreenExitOffsetPixels,
        isKeyboardVisible(),
        renderedRevision, renderedEpoch, renderedInstance,
        cursorBlinkOn, hasSelection);
  }

  /** 截图像素硬上限（约 1.5MP），主线程按此下界缩放，避免整屏 bitmap OOM。 */
  private static final int CAPTURE_MAX_PIXELS = 1_500_000;

  /**
   * 捕获点 F：在主线程把当前终端 viewport 光栅化为有界 ARGB 像素（按 CAPTURE_MAX_PIXELS
   * 下界缩放），PNG 压缩交由控制器后台完成。优先捕获 viewport 而非整个 Activity。
   * 失败（尺寸为 0、OOM 等）返回 null，由 manifest 记录 screenshotAvailable=false，绝不抛出。
   */
  @Nullable
  public CapturedScreenshot captureScreenshot() {
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) return null;
    android.graphics.Bitmap bitmap = null;
    try {
      float scale = 1f;
      long pixels = (long) w * h;
      if (pixels > CAPTURE_MAX_PIXELS) {
        scale = (float) Math.sqrt((double) CAPTURE_MAX_PIXELS / pixels);
      }
      int cw = Math.max(1, Math.round(w * scale));
      int ch = Math.max(1, Math.round(h * scale));
      bitmap = android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888);
      android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
      if (scale != 1f) canvas.scale(scale, scale);
      draw(canvas);
      java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(cw * ch * 4);
      bitmap.copyPixelsToBuffer(buffer);
      return new CapturedScreenshot(buffer.array(), cw, ch, w, h, scale != 1f);
    } catch (Throwable t) {
      return null;
    } finally {
      if (bitmap != null) {
        bitmap.recycle();
      }
    }
  }

  /** 现场捕获：返回当前正在绘制的不可变 RenderSnapshot（主线程只读）。 */
  @Nullable
  public RemoteTerminalModel.RenderSnapshot currentRenderedSnapshot() {
    return renderedSnapshot;
  }

  /** 现场捕获：返回最近一次应用的脏区只读引用。 */
  @Nullable
  public RenderDirtyState lastAppliedDirty() {
    return lastAppliedDirty;
  }

  private void updateFontMetrics() {
    int sp = userTextSizeSp > 0 ? userTextSizeSp : 14;
    float textSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    renderer.updateFont(textSizePx, userTypeface);
  }

  public void updateSize(int w, int h) {
    if (w <= 0 || h <= 0) return;
    int effectiveTextSizeSp = userTextSizeSp > 0 ? userTextSizeSp : 14;
    boolean fontChanged = appliedTextSizeSp != effectiveTextSizeSp || appliedTypeface != userTypeface;
    boolean sizeChanged = appliedGeometryWidth != w || appliedGeometryHeight != h;
    if (!fontChanged && !sizeChanged) return;
    if (fontChanged) {
      updateFontMetrics();
      appliedTextSizeSp = effectiveTextSizeSp;
      appliedTypeface = userTypeface;
    }
    appliedGeometryWidth = w;
    appliedGeometryHeight = h;
    float cellW = cellWidth();
    float lineH = lineHeight();
    if (cellW <= 0 || lineH <= 0) return;
    int cols = Math.max(4, (int) (w / cellW));
    int rows = Math.max(4, (int) ((h - renderer.getTopInset()) / lineH));
    if (host != null) {
      host.onRequestResize(cols, rows, w, h, cellW, lineH, isKeyboardVisible());
    }
    invalidate();
  }

  private boolean isKeyboardVisible() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return false;
    android.view.WindowInsets insets = getRootWindowInsets();
    return insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
  }

  private boolean requestLayoutIfSizeChanged() {
    if (getWidth() > 0 && getHeight() > 0) {
      int effectiveTextSizeSp = userTextSizeSp > 0 ? userTextSizeSp : 14;
      boolean changed = appliedGeometryWidth != getWidth() || appliedGeometryHeight != getHeight()
          || appliedTextSizeSp != effectiveTextSizeSp || appliedTypeface != userTypeface;
      updateSize(getWidth(), getHeight());
      return changed;
    }
    return false;
  }

  @androidx.annotation.VisibleForTesting
  InvalidationResult resolveInvalidation(@NonNull RenderDirtyState dirty,
                                         @NonNull RemoteTerminalModel.RenderSnapshot snapshot,
                                         @NonNull TerminalViewportState viewport,
                                         boolean geometryChanged) {
    return buildInvalidationPlan(dirty, snapshot, viewport, geometryChanged,
        new InvalidationPlan()).result;
  }

  @androidx.annotation.VisibleForTesting
  int[] invalidationRectForTest(@NonNull RenderDirtyState dirty,
                                @NonNull RemoteTerminalModel.RenderSnapshot snapshot,
                                @NonNull TerminalViewportState viewport) {
    InvalidationPlan plan = buildInvalidationPlan(
        dirty, snapshot, viewport, false, new InvalidationPlan());
    if (plan.rectCount == 0) return null;
    Rect rect = plan.rects[0];
    return new int[] {rect.left, rect.top, rect.right, rect.bottom};
  }

  @NonNull
  private InvalidationPlan buildInvalidationPlan(
      @NonNull RenderDirtyState dirty,
      @NonNull RemoteTerminalModel.RenderSnapshot snapshot,
      @NonNull TerminalViewportState viewport,
      boolean geometryChanged,
      @NonNull InvalidationPlan plan) {
    plan.reset();
    if (getWidth() <= 0 || getHeight() <= 0
        || snapshot.activeBuffer == null
        || dirty.fullInvalidate || geometryChanged || dirty.geometryChanged
        || dirty.activeBufferChanged || dirty.paletteChanged || dirty.modesChanged) {
      plan.result = InvalidationResult.FULL;
      return plan;
    }

    boolean screenChanged = dirty.screenScrollRows != 0
        || !dirty.changedScreenRows.isEmpty()
        || !dirty.exposedScreenRows.isEmpty();

    float lineHeight = renderer.getLineHeight();
    TerminalHistoryView history = snapshot.activeBuffer == TerminalBufferKind.ALTERNATE
        ? TerminalHistorySnapshot.empty() : snapshot.history;
    int snapshotViewportOffset = viewport.derivedScrollOffsetPixels(
        snapshot, lineHeight, maxScrollOffsetPixels(snapshot));
    float screenTop = lineHeight > 0f ? RemoteTerminalRenderer.screenTopY(getHeight(), history.size(),
        snapshot.screenView.size(), lineHeight, renderer.getTopInset(),
        snapshotViewportOffset) : 0f;
    float screenBottom = screenTop + snapshot.screenView.size() * lineHeight;
    // 历史可见区域只可能是 [topInset, screenTop)，当 followTail 时 screenTop == topInset，
    // 该区域为空，因此 history-only 不应触发任何重绘。
    boolean visibleHistoryChanged = dirty.historyChanged
        && snapshot.activeBuffer == TerminalBufferKind.MAIN
        && !viewport.isFollowTail(snapshot.activeBuffer);

    if (!screenChanged && !visibleHistoryChanged && !dirty.cursorChanged
        && !dirty.stylesChanged && !dirty.linksChanged) {
      return plan;
    }

    if (dirty.stylesChanged || dirty.linksChanged) {
      plan.result = InvalidationResult.FULL;
      return plan;
    }
    // extent/geometry 变化，或旧调用方没有提供精确范围时，保留安全 FULL 退化。
    if (visibleHistoryChanged) {
      boolean hasRange = dirty.changedHistoryFromSeq <= dirty.changedHistoryToSeq;
      if (dirty.historyStructureChanged || !hasRange) {
        plan.result = InvalidationResult.FULL;
        return plan;
      }
      if (screenChanged || dirty.cursorChanged) {
        // 两套坐标损伤在同一帧合并时优先保证正确性；history-only 才走精确矩形。
        plan.result = InvalidationResult.FULL;
        return plan;
      }
      buildHistoryRange(plan, (HistoryRenderView) history, dirty,
          screenTop, lineHeight);
      return plan;
    }

    if (screenChanged) {
      // 实时 screen 完全滚出当前视口且没有可见历史变化时，不需要刷新。
      boolean screenVisible = screenBottom > 0f && screenTop < getHeight();
      if (!screenVisible && !visibleHistoryChanged && !dirty.cursorChanged) {
        return plan;
      }
      if (dirty.screenScrollRows != 0) {
        buildScreenRegion(plan, screenTop, screenBottom);
        return plan;
      }
      buildPartialRows(plan, dirty, snapshot.screenView.size(), screenTop, lineHeight);
      if (plan.result == InvalidationResult.FULL) {
        buildScreenRegion(plan, screenTop, screenBottom);
      }
      return plan;
    }

    if (dirty.cursorChanged) {
      buildPartialRows(plan, dirty, snapshot.screenView.size(), screenTop, lineHeight);
      return plan;
    }

    plan.result = InvalidationResult.FULL;
    return plan;
  }

  private void buildHistoryRange(@NonNull InvalidationPlan plan,
                                 @NonNull HistoryRenderView history,
                                 @NonNull RenderDirtyState dirty,
                                 float screenTop, float lineHeight) {
    long from = Math.max(dirty.changedHistoryFromSeq, history.firstSeq());
    long to = Math.min(dirty.changedHistoryToSeq, history.lastSeq());
    if (from > to || lineHeight <= 0f) return;
    long firstIndex = from - history.firstSeq();
    long lastIndex = to - history.firstSeq();
    // 与 Renderer 一致使用 int-capped history.size()，避免超大逻辑 extent 下坐标分歧。
    float historyTop = screenTop - history.size() * lineHeight;
    float rawTop = historyTop + firstIndex * lineHeight;
    float rawBottom = historyTop + (lastIndex + 1L) * lineHeight;
    float visibleTop = Math.max(renderer.getTopInset(), rawTop);
    float visibleBottom = Math.min(Math.min(screenTop, getHeight()), rawBottom);
    if (visibleBottom <= visibleTop) return;
    Rect rect = plan.rects[0];
    rect.set(0, Math.max(0, (int) Math.floor(visibleTop) - 1), getWidth(),
        Math.min(getHeight(), (int) Math.ceil(visibleBottom) + 1));
    plan.rectCount = 1;
    long rows = lastIndex - firstIndex + 1L;
    plan.dirtyRowCount = (int) Math.min(Integer.MAX_VALUE, rows);
    plan.result = InvalidationResult.PARTIAL;
  }

  private void buildPartialRows(@NonNull InvalidationPlan plan,
                                @NonNull RenderDirtyState dirty,
                                int screenRows, float screenTop, float lineHeight) {
    int runStart = -1;
    int previous = -2;
    long dirtyArea = 0L;
    for (int row = 0; row <= screenRows; row++) {
      boolean selected = row < screenRows && (dirty.changedScreenRows.get(row)
          || dirty.exposedScreenRows.get(row)
          || (dirty.cursorChanged
          && (row == dirty.previousCursorRow || row == dirty.currentCursorRow)));
      if (selected && (runStart < 0 || row == previous + 1)) {
        if (runStart < 0) runStart = row;
        previous = row;
        continue;
      }
      if (runStart >= 0) {
        float rawTop = screenTop + runStart * lineHeight;
        float rawBottom = screenTop + (previous + 1) * lineHeight;
        if (rawBottom > 0f && rawTop < getHeight()) {
          if (plan.rectCount >= MAX_PARTIAL_DIRTY_RECTS) {
            plan.result = InvalidationResult.FULL;
            return;
          }
          Rect rect = plan.rects[plan.rectCount++];
          rect.set(0, Math.max(0, (int) Math.floor(rawTop) - 1), getWidth(),
              Math.min(getHeight(), (int) Math.ceil(rawBottom) + 1));
          dirtyArea += (long) rect.width() * rect.height();
          plan.dirtyRowCount += previous - runStart + 1;
        }
        runStart = selected ? row : -1;
        previous = selected ? row : -2;
      } else if (selected) {
        runStart = previous = row;
      }
    }
    if (plan.rectCount == 0) {
      plan.result = InvalidationResult.NONE;
    } else if ((double) dirtyArea
        <= (double) getWidth() * getHeight() * MAX_PARTIAL_DIRTY_AREA_RATIO) {
      plan.result = InvalidationResult.PARTIAL;
    } else {
      plan.result = InvalidationResult.FULL;
    }
  }

  private void buildScreenRegion(@NonNull InvalidationPlan plan,
                                 float screenTop, float screenBottom) {
    plan.reset();
    int top = Math.max(0, (int) Math.floor(screenTop) - 1);
    int bottom = Math.min(getHeight(), (int) Math.ceil(screenBottom) + 1);
    if (bottom <= top) return;
    plan.rects[0].set(0, top, getWidth(), bottom);
    plan.rectCount = 1;
    plan.result = InvalidationResult.SCREEN_REGION;
  }

  private void startSelectionAt(float x, float y) {
    TerminalSelection.Anchor anchor = pointToAnchor(x, y);
    if (anchor == null) return;
    captureSelectionDamage(oldSelectionDamage, oldHandleDamage);
    selecting = true;
    selectionStart = anchor;
    selectionEnd = anchor;
    expandSelectionToWord();
    updateViewportSelection();
    startSelectionActionMode();
    invalidateSelectionTransition();
  }

  private void extendSelectionTo(float x, float y) {
    if (!selecting) return;
    TerminalSelection.Anchor anchor = pointToAnchor(x, y);
    if (anchor == null) return;
    updateSelectionEndpoint(HANDLE_END, anchor);
  }

  private void clearSelection() {
    stopSelectionAutoScroll();
    captureSelectionDamage(oldSelectionDamage, oldHandleDamage);
    selecting = false;
    draggingHandle = HANDLE_NONE;
    selectionStart = null;
    selectionEnd = null;
    updateViewportSelection();
    invalidateSelectionTransition();
  }

  private void stopSelection() {
    if (!selecting) return;
    ActionMode actionMode = selectionActionMode;
    selectionActionMode = null;
    clearSelection();
    if (actionMode != null) actionMode.finish();
  }

  private void expandSelectionToWord() {
    if (selectionStart == null) return;
    RenderLine line = lineAt(selectionStart);
    if (line == null) return;
    int startCol = selectionStart.col;
    int endCol = selectionStart.col + 1;
    while (startCol > 0 && !isWordBoundary(line.at(startCol - 1))) startCol--;
    while (endCol < line.length() && !isWordBoundary(line.at(endCol))) endCol++;
    selectionStart = new TerminalSelection.Anchor(selectionStart.historySeq, selectionStart.screenRow, startCol);
    selectionEnd = new TerminalSelection.Anchor(selectionStart.historySeq, selectionStart.screenRow, endCol);
  }

  private void updateViewportSelection() {
    if (selectionStart == null || selectionEnd == null) {
      viewport.selection = null;
    } else {
      viewport.selection = new TerminalSelection(selectionStart, selectionEnd);
    }
  }

  private void invalidateSelectionTransition() {
    captureSelectionDamage(newSelectionDamage, newHandleDamage);
    invalidateDamagePair(oldSelectionDamage, newSelectionDamage);
    invalidateDamagePair(oldHandleDamage, newHandleDamage);
    oldSelectionDamage.setEmpty();
    oldHandleDamage.setEmpty();
  }

  private void invalidateDamagePair(@NonNull Rect oldDamage, @NonNull Rect newDamage) {
    if (oldDamage.isEmpty() && newDamage.isEmpty()) return;
    if (oldDamage.isEmpty()) {
      invalidate(newDamage);
      return;
    }
    if (newDamage.isEmpty()) {
      invalidate(oldDamage);
      return;
    }
    oldDamage.union(newDamage);
    invalidate(oldDamage);
  }

  private void captureSelectionDamage(@NonNull Rect selectionDamage,
                                      @NonNull Rect handleDamage) {
    selectionDamage.setEmpty();
    handleDamage.setEmpty();
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    if (snapshot == null || selectionStart == null || selectionEnd == null) return;
    TerminalSelection normalized = new TerminalSelection(selectionStart, selectionEnd).normalized();
    float[] start = anchorToPoint(normalized.start, false, snapshot);
    float[] end = anchorToPoint(normalized.end, true, snapshot);
    if (start != null && end != null) {
      int top = Math.max(0, (int) Math.floor(Math.min(start[1], end[1])) - 1);
      int bottom = Math.min(getHeight(),
          (int) Math.ceil(Math.max(start[1], end[1]) + lineHeight()) + 1);
      if (bottom > top) {
        boolean singleRow = Math.abs(start[1] - end[1]) < 0.5f;
        int left = singleRow
            ? Math.max(0, (int) Math.floor(Math.min(start[0], end[0])) - 1) : 0;
        int right = singleRow
            ? Math.min(getWidth(), (int) Math.ceil(Math.max(start[0], end[0])) + 1)
            : getWidth();
        if (right > left) selectionDamage.set(left, top, right, bottom);
      }
    }
    if (!selecting) return;
    addHandleDamage(handleDamage, anchorToHandleCenter(selectionStart, snapshot));
    addHandleDamage(handleDamage, anchorToHandleCenter(selectionEnd, snapshot));
  }

  private void addHandleDamage(@NonNull Rect damage, @Nullable float[] center) {
    if (center == null) return;
    float radius = handleRadius() + 2f;
    int left = Math.max(0, (int) Math.floor(center[0] - radius));
    int top = Math.max(0, (int) Math.floor(center[1] - radius));
    int right = Math.min(getWidth(), (int) Math.ceil(center[0] + radius));
    int bottom = Math.min(getHeight(), (int) Math.ceil(center[1] + radius));
    if (right > left && bottom > top) damage.union(left, top, right, bottom);
  }

  private void updateSelectionEndpoint(int handle, @NonNull TerminalSelection.Anchor proposed) {
    if (selectionStart == null || selectionEnd == null) return;
    captureSelectionDamage(oldSelectionDamage, oldHandleDamage);
    if (handle == HANDLE_START) {
      selectionStart = constrainSelectionEndpoint(true, proposed, selectionStart, selectionEnd);
    } else if (handle == HANDLE_END) {
      selectionEnd = constrainSelectionEndpoint(false, proposed, selectionStart, selectionEnd);
    }
    updateViewportSelection();
    if (selectionActionMode != null) selectionActionMode.invalidate();
    invalidateSelectionTransition();
  }

  static TerminalSelection.Anchor constrainSelectionEndpoint(
      boolean startHandle, @NonNull TerminalSelection.Anchor proposed,
      @NonNull TerminalSelection.Anchor currentStart,
      @NonNull TerminalSelection.Anchor currentEnd) {
    if (startHandle && proposed.compareTo(currentEnd) > 0) return currentEnd;
    if (!startHandle && proposed.compareTo(currentStart) < 0) return currentStart;
    return proposed;
  }

  private TerminalSelection.Anchor pointToAnchor(float x, float y) {
    if (renderedSnapshot == null) return null;
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    int cols = snapshot.columns;
    if (cols <= 0) return null;
    float cellW = cellWidth();
    float lineH = lineHeight();
    if (cellW <= 0 || lineH <= 0) return null;
    int col = Math.max(0, Math.min(cols - 1, (int) (x / cellW)));

    RenderLine[] screen = snapshot.screenView.copyLines();
    HistoryRenderView history = snapshot.activeBuffer == TerminalBufferKind.ALTERNATE
        ? TerminalHistorySnapshot.empty() : snapshot.history;
    int screenRows = screen.length;
    int historyRows = history.size();
    if (screenRows == 0 && historyRows == 0) return null;
    float scrollOffset = viewportOffset(snapshot);
    float contentTopY = RemoteTerminalRenderer.contentTopY(getHeight(), historyRows, screenRows,
        lineH, renderer.getTopInset(), scrollOffset);
    float screenTopY = RemoteTerminalRenderer.screenTopY(getHeight(), historyRows, screenRows,
        lineH, renderer.getTopInset(), scrollOffset);

    if (screenRows > 0 && (y >= screenTopY || historyRows == 0)) {
      int row = (int) ((y - screenTopY) / lineH);
      row = Math.max(0, Math.min(screenRows - 1, row));
      return new TerminalSelection.Anchor(0, row, normalizeSelectionColumn(screen[row], col));
    }

    if (historyRows == 0) return null;
    int historyIndex = (int) ((y - contentTopY) / lineH);
    historyIndex = Math.max(0, Math.min(historyRows - 1, historyIndex));
    RenderLine line = history.renderLineAt(historyIndex);
    // 稀疏分页历史下，命中的逻辑行可能尚未加载（UNLOADED）或已被裁剪（UNAVAILABLE），
    // lineAt 返回 null。此时无法锚定选择，安全返回 null 让上层（selectWordAt/长按）退出。
    if (line == null) return null;
    // HistorySeq 必须取自分页槽位，不能用 LineStore 归零后的 line.historyOrder()/LineID。
    long historySeq = history.seqAt(historyIndex);
    return new TerminalSelection.Anchor(historySeq, -1, normalizeSelectionColumn(line, col));
  }

  private int normalizeSelectionColumn(@Nullable RenderLine line, int col) {
    if (line == null || col <= 0 || col >= line.length()) return col;
    CellValue cell = line.at(col);
    if (cell != null && cell.isSpacer()) {
      CellValue previous = line.at(col - 1);
      if (previous != null && previous.isWideStart()) return col - 1;
    }
    return col;
  }

  private void selectWordAt(float x, float y) {
    TerminalSelection.Anchor anchor = pointToAnchor(x, y);
    if (anchor == null) return;
    RenderLine line = lineAt(anchor);
    if (line == null) return;
    int startCol = anchor.col;
    int endCol = anchor.col + 1;
    // 简单规则：向左右扩展直到空格或边界
    while (startCol > 0 && !isWordBoundary(line.at(startCol - 1))) startCol--;
    while (endCol < line.length() && !isWordBoundary(line.at(endCol))) endCol++;
    captureSelectionDamage(oldSelectionDamage, oldHandleDamage);
    selecting = true;
    selectionStart = new TerminalSelection.Anchor(anchor.historySeq, anchor.screenRow, startCol);
    selectionEnd = new TerminalSelection.Anchor(anchor.historySeq, anchor.screenRow, endCol);
    updateViewportSelection();
    startSelectionActionMode();
    invalidateSelectionTransition();
  }

  private boolean isWordBoundary(CellValue cell) {
    if (cell == null) return true;
    String text = cell.text();
    return text == null || text.isEmpty() || text.equals(" ") || text.equals("\t");
  }

  private RenderLine lineAt(TerminalSelection.Anchor anchor) {
    if (renderedSnapshot == null) return null;
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    if (anchor.historySeq != 0) {
      int index = snapshot.history.findSeqIndex(anchor.historySeq);
      return index >= 0 ? snapshot.history.renderLineAt(index) : null;
    }
    if (anchor.screenRow >= 0 && anchor.screenRow < snapshot.screenView.size()) {
      return snapshot.screenView.lineAt(anchor.screenRow);
    }
    return null;
  }

  private String selectedText() {
    if (selectionStart == null || selectionEnd == null || renderedSnapshot == null) return "";
    TerminalSelection normalized = new TerminalSelection(selectionStart, selectionEnd).normalized();
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    HistoryRenderView history = snapshot.activeBuffer == TerminalBufferKind.ALTERNATE
        ? TerminalHistorySnapshot.empty() : snapshot.history;
    return TerminalSelectionTextExtractor.extract(
        normalized, history, snapshot.screenView.copyLines());
  }

  private void copySelectionToClipboard() {
    String text = selectedText();
    if (text.isEmpty()) return;
    ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard != null) {
      clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }
  }

  private void startSelectionActionMode() {
    if (selectionActionMode != null) {
      selectionActionMode.invalidate();
      return;
    }
    ActionMode.Callback2 callback = new ActionMode.Callback2() {
      @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        int show = MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT;
        menu.add(Menu.NONE, android.R.id.copy, Menu.NONE, android.R.string.copy).setShowAsAction(show);
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        menu.add(Menu.NONE, android.R.id.paste, Menu.NONE, android.R.string.paste)
            .setEnabled(clipboard != null && clipboard.hasPrimaryClip()).setShowAsAction(show);
        return true;
      }

      @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

      @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        if (item.getItemId() == android.R.id.copy) {
          copySelectionToClipboard();
          stopSelection();
          return true;
        }
        if (item.getItemId() == android.R.id.paste) {
          ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
          if (clipboard != null && clipboard.hasPrimaryClip() && host != null) {
            CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(getContext());
            if (text != null) {
              String paste = text.toString();
              host.onPasteInput(paste);
            }
          }
          stopSelection();
          return true;
        }
        return false;
      }

      @Override public void onDestroyActionMode(ActionMode mode) {
        if (selectionActionMode == mode) {
          selectionActionMode = null;
          clearSelection();
        }
      }

      @Override public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
        float[] start = anchorToPoint(selectionStart, false);
        float[] end = anchorToPoint(selectionEnd, true);
        if (start == null || end == null) {
          outRect.set(0, 0, getWidth(), getHeight());
          return;
        }
        outRect.set(Math.round(Math.min(start[0], end[0])), Math.round(Math.min(start[1], end[1])),
            Math.round(Math.max(start[0], end[0])), Math.round(Math.max(start[1], end[1]) + lineHeight()));
      }
    };
    selectionActionMode = startActionMode(callback, ActionMode.TYPE_FLOATING);
  }

  private boolean handleSelectionHandleTouch(MotionEvent event) {
    if (!selecting) return false;
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      float[] start = anchorToHandleCenter(selectionStart);
      float[] end = anchorToHandleCenter(selectionEnd);
      float radius = handleRadius();
      draggingHandle = nearestHandle(event, start, end, radius);
      if (draggingHandle != HANDLE_NONE) {
        float[] center = draggingHandle == HANDLE_START ? start : end;
        handleTouchOffsetX = center == null ? 0f : event.getX() - center[0];
        handleTouchOffsetY = center == null ? 0f : event.getY() - center[1];
        selectionPointerX = event.getX();
        selectionPointerY = event.getY();
        return true;
      }
      return false;
    }
    if (draggingHandle == HANDLE_NONE) return false;
    if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
      TerminalSelection.Anchor anchor = selectionAnchorAtPointer(event.getX(), event.getY());
      if (anchor != null) {
        updateSelectionEndpoint(draggingHandle, anchor);
      }
      updateSelectionAutoScroll(event.getX(), event.getY());
    } else if (event.getActionMasked() == MotionEvent.ACTION_UP
        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      draggingHandle = HANDLE_NONE;
      handleTouchOffsetX = 0f;
      handleTouchOffsetY = 0f;
      stopSelectionAutoScroll();
    }
    return true;
  }

  private int nearestHandle(MotionEvent event, @Nullable float[] start, @Nullable float[] end,
                            float radius) {
    float startDistance = distanceSquared(event, start);
    float endDistance = distanceSquared(event, end);
    float maxDistance = radius * radius * 2f;
    boolean startHit = startDistance <= maxDistance;
    boolean endHit = endDistance <= maxDistance;
    if (startHit && endHit) return startDistance <= endDistance ? HANDLE_START : HANDLE_END;
    if (startHit) return HANDLE_START;
    if (endHit) return HANDLE_END;
    return HANDLE_NONE;
  }

  private float distanceSquared(MotionEvent event, @Nullable float[] point) {
    if (point == null) return Float.POSITIVE_INFINITY;
    float dx = event.getX() - point[0];
    float dy = event.getY() - point[1];
    return dx * dx + dy * dy;
  }

  private void drawSelectionHandles(Canvas canvas,
                                    @NonNull RemoteTerminalModel.RenderSnapshot snapshot) {
    if (!selecting) return;
    selectionHandlePaint.setColor(0xFF3B82F6);
    float radius = handleRadius();
    float[] start = anchorToHandleCenter(selectionStart, snapshot);
    float[] end = anchorToHandleCenter(selectionEnd, snapshot);
    if (start != null) canvas.drawCircle(start[0], start[1], radius, selectionHandlePaint);
    if (end != null) canvas.drawCircle(end[0], end[1], radius, selectionHandlePaint);
  }

  private float handleRadius() {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 9, getResources().getDisplayMetrics());
  }

  @Nullable
  private float[] anchorToHandleCenter(@Nullable TerminalSelection.Anchor anchor) {
    if (renderedSnapshot == null) return null;
    return anchorToHandleCenter(anchor, renderedSnapshot);
  }

  @Nullable
  private float[] anchorToHandleCenter(@Nullable TerminalSelection.Anchor anchor,
                                       @NonNull RemoteTerminalModel.RenderSnapshot snapshot) {
    float[] point = anchorToPoint(anchor, false, snapshot);
    if (point == null) return null;
    point[1] += lineHeight();
    return point;
  }

  @Nullable
  private float[] anchorToPoint(@Nullable TerminalSelection.Anchor anchor, boolean end) {
    if (anchor == null || renderedSnapshot == null) return null;
    return anchorToPoint(anchor, end, renderedSnapshot);
  }

  @Nullable
  private float[] anchorToPoint(@Nullable TerminalSelection.Anchor anchor, boolean end,
                                @NonNull RemoteTerminalModel.RenderSnapshot snapshot) {
    if (anchor == null) return null;
    int col = anchor.col;
    float contentTop = contentTopY(snapshot);
    TerminalHistoryView history = snapshot.activeBuffer == TerminalBufferKind.ALTERNATE
        ? TerminalHistorySnapshot.empty() : snapshot.history;
    if (anchor.historySeq != 0) {
      int index = history.findSeqIndex(anchor.historySeq);
      if (index < 0) return null;
      return new float[] {col * cellWidth(), contentTop + index * lineHeight()};
    }
    return new float[] {col * cellWidth(), contentTop + (history.size() + anchor.screenRow) * lineHeight()};
  }

  private float contentTopY() {
    if (renderedSnapshot == null) return 0;
    return contentTopY(renderedSnapshot);
  }

  private boolean isFollowingTail(@Nullable RemoteTerminalModel.RenderSnapshot snapshot) {
    return snapshot == null || viewport.isFollowTail(snapshot.activeBuffer);
  }

  private int viewportOffset(@Nullable RemoteTerminalModel.RenderSnapshot snapshot) {
    return snapshot == null ? 0 : viewport.derivedScrollOffsetPixels(
        snapshot, lineHeight(), maxScrollOffsetPixels(snapshot));
  }

  private float contentTopY(@NonNull RemoteTerminalModel.RenderSnapshot snapshot) {
    int screenRows = snapshot.screenView.size();
    int historyRows = snapshot.activeBuffer == TerminalBufferKind.ALTERNATE
        ? 0 : snapshot.history.size();
    return RemoteTerminalRenderer.contentTopY(getHeight(), historyRows, screenRows,
        lineHeight(), renderer.getTopInset(), viewportOffset(snapshot));
  }

  private boolean shouldBlinkCursor() {
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    if (!isAttachedToWindow() || snapshot == null || !isFollowingTail(snapshot)) return false;
    return snapshot.cursor.visible && snapshot.cursor.blink;
  }

  private void updateRenderedSnapshot(
      @Nullable RemoteTerminalModel.RenderSnapshot nextSnapshot) {
    RemoteTerminalModel.RenderSnapshot previousSnapshot = renderedSnapshot;
    previousCursorRow = cursorRow(previousSnapshot);
    currentCursorRow = cursorRow(nextSnapshot);
    renderedSnapshot = nextSnapshot;
    TerminalCursor previousCursor =
        previousSnapshot != null ? previousSnapshot.cursor : null;
    TerminalCursor currentCursor =
        nextSnapshot != null ? nextSnapshot.cursor : null;
    if (!java.util.Objects.equals(previousCursor, currentCursor)) {
      removeCallbacks(cursorBlinkRunnable);
      cursorBlinkScheduled = false;
      cursorBlinkOn = true;
    }
  }

  private static int cursorRow(
      @Nullable RemoteTerminalModel.RenderSnapshot snapshot) {
    if (snapshot == null || snapshot.cursor == null || !snapshot.cursor.visible
        || snapshot.cursor.row < 0
        || snapshot.cursor.row >= snapshot.screenView.size()) {
      return -1;
    }
    return snapshot.cursor.row;
  }

  private void updateCursorBlinkSchedule() {
    boolean shouldBlink = shouldBlinkCursor();
    if (!shouldBlink) {
      stopCursorBlinking();
    } else if (!cursorBlinkScheduled) {
      cursorBlinkOn = true;
      cursorBlinkScheduled = true;
      postOnAnimationDelayed(cursorBlinkRunnable, 500L);
    }
  }

  private void stopCursorBlinking() {
    removeCallbacks(cursorBlinkRunnable);
    cursorBlinkScheduled = false;
    cursorBlinkOn = true;
  }

  private void invalidateCursorRows(int previousRow, int currentRow) {
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    if (snapshot == null
        || getWidth() <= 0 || getHeight() <= 0) {
      return;
    }
    boolean previousValid = previousRow >= 0 && previousRow < snapshot.screenView.size();
    boolean currentValid = currentRow >= 0 && currentRow < snapshot.screenView.size();
    if (!previousValid && !currentValid) return;
    TerminalHistoryView history = snapshot.activeBuffer == TerminalBufferKind.ALTERNATE
        ? TerminalHistorySnapshot.empty() : snapshot.history;
    float rowHeight = renderer.getLineHeight();
    if (rowHeight <= 0f) return;
    float screenTop = RemoteTerminalRenderer.screenTopY(
        getHeight(), history.size(), snapshot.screenView.size(), rowHeight,
        renderer.getTopInset(),
        viewportOffset(snapshot));
    int firstRow = previousValid ? previousRow : currentRow;
    int lastRow = currentValid ? currentRow : previousRow;
    if (firstRow > lastRow) {
      int swap = firstRow;
      firstRow = lastRow;
      lastRow = swap;
    }
    int rectCount = 0;
    rectCount += postCursorRowDamage(firstRow, screenTop, rowHeight) ? 1 : 0;
    if (lastRow != firstRow) {
      rectCount += postCursorRowDamage(lastRow, screenTop, rowHeight) ? 1 : 0;
    }
    if (rectCount > 0) TerminalRenderMetrics.partialInvalidate(rectCount);
  }

  private boolean postCursorRowDamage(int row, float screenTop, float rowHeight) {
    float rawTop = screenTop + row * rowHeight;
    float rawBottom = rawTop + rowHeight;
    if (rawBottom <= 0f || rawTop >= getHeight()) return false;
    postInvalidateOnAnimation(0, Math.max(0, (int) Math.floor(rawTop) - 1), getWidth(),
        Math.min(getHeight(), (int) Math.ceil(rawBottom) + 1));
    return true;
  }

  /**
   * 键盘避让用的"保护行"底边在 View 坐标系中的 y 坐标：光标行与最后一个非空内容行中
   * 靠下那一行的底部。IME 弹出时据此只平移必要距离，而不是固定平移整个键盘高度。
   */
  public float getKeyboardProtectedBottomY() {
    if (renderedSnapshot == null) return 0f;
    RemoteTerminalModel.RenderSnapshot snapshot = renderedSnapshot;
    RenderLine[] screen = snapshot.screenView.copyLines();
    if (screen.length == 0) return 0f;
    int screenRows = screen.length;
    int lastContentRow = 0;
    for (int row = screenRows - 1; row >= 0; row--) {
      if (!isLineBlank(screen[row])) {
        lastContentRow = row;
        break;
      }
    }
    TerminalCursor cursor = snapshot.cursor;
    int cursorRow = cursor.visible ? cursor.row : 0;
    int protectedRow = Math.max(0, Math.min(screenRows - 1, Math.max(cursorRow, lastContentRow)));
    float lineH = lineHeight();
    int historyRows = snapshot.activeBuffer == TerminalBufferKind.ALTERNATE
        ? 0 : snapshot.history.size();
    float contentTop = RemoteTerminalRenderer.contentTopY(getHeight(), historyRows, screenRows,
        lineH, renderer.getTopInset(), viewportOffset(snapshot));
    return contentTop + (historyRows + protectedRow + 1) * lineH;
  }

  private boolean isLineBlank(@Nullable RenderLine line) {
    if (line == null) return true;
    for (int i = 0; i < line.length(); i++) {
      CellValue cell = line.at(i);
      if (cell == null || cell.isSpacer()) continue;
      String text = cell.text();
      if (text != null && !text.isEmpty() && !text.equals(" ") && !text.equals("\t")) return false;
    }
    return true;
  }

  private final class GestureListener implements GestureAndScaleRecognizer.Listener {
    @Override
    public boolean onDown(float x, float y) {
      lastFlingY = 0;
      scrolledWithFinger = false;
      return true;
    }

    @Override
    public void onLongPress(MotionEvent e) {
      if (gestureRecognizer.isInProgress()) return;
      performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
      startSelectionAt(e.getX(), e.getY());
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
      if (selecting) {
        stopSelection();
      } else if (host != null) {
        host.onRequestShowKeyboard();
      }
      return true;
    }

    @Override
    public boolean onUp(MotionEvent event) {
      stopSelectionAutoScroll();
      if (isMouseTracking() && !selecting && !scrolledWithFinger) {
        sendMouse(event, "left", 0, true);
        sendMouse(event, "left", 0, false);
      }
      return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
      // Match the legacy TerminalView: only a long press enters text selection.
      return false;
    }

    @Override
    public boolean onScroll(MotionEvent e2, float distanceX, float distanceY) {
      if (selecting) {
        extendSelectionTo(e2.getX(), e2.getY());
        updateSelectionAutoScroll(e2.getX(), e2.getY());
      } else {
        scrolledWithFinger = true;
        // GestureDetector 的 distanceY 在手指上滑时为正（内容应跟随手指向底部移动），
        // 与 applyScrollDelta「正值向历史」的约定相反，取负。
        applyScrollDelta(-(int) distanceY);
      }
      return true;
    }

    @Override
    public boolean onFling(MotionEvent e2, float velocityX, float velocityY) {
      if (selecting) return true;
      lastFlingY = 0;
      // 手指向下甩动（velocityY > 0）应往历史方向滚动，与「正值向历史」约定一致，直接用 velocityY。
      scroller.fling(0, 0, 0, (int) velocityY, 0, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
      invalidate();
      return true;
    }

    @Override
    public boolean onScale(float focusX, float focusY, float scale) {
      if (selecting) return true;
      // Keep the gesture contract shared with TerminalView. Persisted font changes still
      // come from Settings, rather than silently changing the remote PTY geometry.
      return true;
    }
  }

  /**
   * IME 状态机。composing 文本最多发送一次：setComposingText 只更新本地
   * Editable；commitText 发送一次并清空；finishComposingText 只在本地仍留有
   * 未发送 composing 文本时补发一次。删除未发送的 composing 文本只改本地，
   * 不会向远端 PTY 发送 DEL。
   */
  private static final class RemoteTerminalInputConnection extends BaseInputConnection {

    private final Host host;
    private final Context context;

    RemoteTerminalInputConnection(View targetView, Host host) {
      super(targetView, true);
      this.host = host;
      this.context = targetView.getContext();
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
      // Composing updates stay local; nothing is sent until commit/finish.
      return super.setComposingText(text, newCursorPosition);
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
      boolean result = super.commitText(text, newCursorPosition);
      dispatchEditableToTerminal();
      return result;
    }

    @Override
    public boolean finishComposingText() {
      // If the editable still holds un-sent composing text, flush it once.
      // After commitText the buffer was already sent and cleared, so an empty
      // editable (or no composing span) must not send a duplicate.
      boolean hasPendingComposing = hasComposingText();
      boolean result = super.finishComposingText();
      if (hasPendingComposing) {
        dispatchEditableToTerminal();
      }
      return result;
    }

    @Override
    public boolean performContextMenuAction(int id) {
      if (id != android.R.id.paste && id != android.R.id.pasteAsPlainText) {
        return super.performContextMenuAction(id);
      }
      ClipboardManager clipboard = (ClipboardManager)
          context.getSystemService(Context.CLIPBOARD_SERVICE);
      if (clipboard == null || !clipboard.hasPrimaryClip()) return false;
      ClipData clip = clipboard.getPrimaryClip();
      if (clip == null || clip.getItemCount() == 0) return false;
      CharSequence value = clip.getItemAt(0).coerceToText(context);
      if (value == null) return false;

      android.text.Editable content = getEditable();
      if (content != null) content.clear();
      String paste = value.toString();
      if (!paste.isEmpty() && host != null) {
        host.onPasteInput(paste);
      }
      return true;
    }

    @Override
    public boolean deleteSurroundingText(int leftLength, int rightLength) {
      if (hasComposingText()) {
        // Deleting un-sent composing text is a local edit; the remote PTY has
        // never seen these characters and must receive zero DEL keys. This is
        // done inside the composing span directly: the framework's
        // BaseInputConnection.deleteSurroundingText expands the deletion anchor
        // to the composing span START, which makes it a no-op when the scratch
        // buffer holds only composing text.
        return deleteWithinComposing(leftLength, rightLength);
      }
      if (host != null) {
        for (int i = 0; i < leftLength; i++) {
          // Fresh DOWN/UP objects per key: sharing one KeyEvent instance lets
          // receivers mutate a recycled event and lose the action pairing.
          host.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
          host.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
        }
      }
      return super.deleteSurroundingText(leftLength, rightLength);
    }

    /**
     * Deletes inside the un-sent composing span: {@code leftLength} chars from
     * its tail, then {@code rightLength} chars forward from its (new) end.
     * Fully deleting the span removes it, after which DEL keys go remote again.
     */
    private boolean deleteWithinComposing(int leftLength, int rightLength) {
      android.text.Editable content = getEditable();
      if (content == null) return false;
      int start = BaseInputConnection.getComposingSpanStart(content);
      int end = BaseInputConnection.getComposingSpanEnd(content);
      if (start < 0 || end <= start) return false;
      int deleteBefore = Math.min(Math.max(leftLength, 0), end - start);
      if (deleteBefore > 0) {
        content.delete(end - deleteBefore, end);
      }
      int newEnd = end - deleteBefore;
      int deleteAfter = Math.min(Math.max(rightLength, 0),
          Math.max(0, content.length() - newEnd));
      if (deleteAfter > 0) {
        content.delete(newEnd, newEnd + deleteAfter);
      }
      return true;
    }

    private void dispatchEditableToTerminal() {
      android.text.Editable content = getEditable();
      if (host != null && content != null && content.length() > 0) {
        String text = content.toString();
        content.clear();
        if (containsLineBreak(text)) {
          host.onPasteInput(text);
        } else {
          host.onTextInput(text);
        }
      }
    }

    private static boolean containsLineBreak(String text) {
      return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }

    /** True when the local editable holds a non-empty, un-sent composing span. */
    private boolean hasComposingText() {
      android.text.Editable content = getEditable();
      if (content == null) return false;
      int start = BaseInputConnection.getComposingSpanStart(content);
      int end = BaseInputConnection.getComposingSpanEnd(content);
      return start >= 0 && end > start;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
      if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
        return super.sendKeyEvent(event);
      }
      if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
        // While composing, backspace edits the local un-sent text only. Do NOT
        // delegate to super.sendKeyEvent: on modern Android that re-dispatches
        // the key through the IMM back into this View, looping the DEL to the
        // remote PTY for a character it never received.
        if (hasComposingText()) {
          if (event.getAction() == KeyEvent.ACTION_DOWN) {
            deleteWithinComposing(1, 0);
          }
          return true;
        }
        if (host != null) {
          host.onKeyEvent(event);
        }
        return true;
      }
      // IMEs commonly emit both commitText() and a synthetic Unicode KeyEvent
      // for the same composing result. Text belongs exclusively to
      // commitText(); forwarding both produces a second PTY write (Claude then
      // displays a plain, unstyled echo beneath the submitted prompt).
      // Hardware keyboards bypass this InputConnection and still use the View
      // key callbacks above.
      boolean suppressText = isTextKeyFromIme(event);
      if (host != null && !suppressText) {
        host.onKeyEvent(event);
      }
      return true;
    }

    private static boolean isTextKeyFromIme(KeyEvent event) {
      if (event == null || event.getUnicodeChar() == 0) return false;
      switch (event.getKeyCode()) {
        case KeyEvent.KEYCODE_ENTER:
        case KeyEvent.KEYCODE_NUMPAD_ENTER:
        case KeyEvent.KEYCODE_DEL:
        case KeyEvent.KEYCODE_FORWARD_DEL:
        case KeyEvent.KEYCODE_TAB:
        case KeyEvent.KEYCODE_ESCAPE:
          return false;
        default:
          return true;
      }
    }
  }

}
