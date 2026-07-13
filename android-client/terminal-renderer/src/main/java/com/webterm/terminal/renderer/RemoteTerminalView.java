package com.webterm.terminal.renderer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
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
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.interaction.GestureAndScaleRecognizer;

import java.util.Map;
import java.util.NavigableMap;

/**
 * 远程终端自定义 View。负责 Android View 生命周期、IME、触摸滚动、选择和触发渲染。
 */
public final class RemoteTerminalView extends View {

  private static final String INPUT_TRACE_TAG = "WebTermInputTrace";

  public interface Host {
    void onTextInput(@NonNull String text);
    void onPasteInput(@NonNull String text);
    void onKeyEvent(@NonNull KeyEvent event);
    void onRequestResize(int cols, int rows);
    void onRequestShowKeyboard();
    /** @param maxScrollOffsetPixels inclusive top bound for this rendered content. */
    void onScrollPixels(int deltaPixels, int maxScrollOffsetPixels);
    void onRequestHistoryPage();
    void onFocusChanged(boolean focused);
    void onMouse(int row, int col, @NonNull String button, int wheelDelta,
                 boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed);
    void onAlternateScreenScroll(int rowsDown);
  }

  private final RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
  private final GestureAndScaleRecognizer gestureRecognizer;
  private final Scroller scroller;
  private final Paint selectionHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private RemoteTerminalModel model;
  private TerminalViewportState viewport = new TerminalViewportState();
  private Host host;
  private float lastFlingY;
  private boolean selecting;
  @Nullable private ActionMode selectionActionMode;
  private int draggingHandle; // 0 = none, 1 = start, 2 = end
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
    this.host = host;
  }

  @Override
  protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    if (host != null) {
      host.onFocusChanged(gainFocus);
    }
  }

  public void setModel(@Nullable RemoteTerminalModel model, @Nullable TerminalViewportState viewport) {
    this.model = model;
    if (viewport != null) {
      this.viewport = viewport;
    }
    requestLayoutIfSizeChanged();
    invalidate();
  }

  public void updateModel(@NonNull RemoteTerminalModel model) {
    this.model = model;
    requestLayoutIfSizeChanged();
    invalidate();
  }

  public void preserveViewportForAppendedLines(int lineCount) {
    if (lineCount > 0 && !viewport.followTail) {
      viewport.scrollOffsetPixels += Math.round(lineCount * lineHeight());
    }
  }

  public void setTextSize(int sizeSp) {
    this.userTextSizeSp = sizeSp;
    requestLayoutIfSizeChanged();
    invalidate();
  }

  public void setTypeface(@Nullable Typeface typeface) {
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
  protected void onDetachedFromWindow() {
    stopSelection();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (model == null) return;
    computeScrollOffset();
    renderer.render(canvas, model.renderSnapshot(), viewport);
    drawSelectionHandles(canvas);
    if (viewport.followTail && model.cursor().visible && model.cursor().blink) {
      postInvalidateDelayed(500L);
    }
  }

  @Override
  public boolean onCheckIsTextEditor() {
    return true;
  }

  @Override
  public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
    outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;
    return new RemoteTerminalInputConnection(this, host);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      return super.onKeyDown(keyCode, event);
    }
    if (host != null) {
      traceInput("view-key", eventSummary(event));
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
    if (scroller.computeScrollOffset()) {
      int delta = (int) (scroller.getCurrY() - lastFlingY);
      lastFlingY = scroller.getCurrY();
      applyScrollDelta(delta);
      invalidate();
    }
  }

  private void computeScrollOffset() {
    if (scroller.computeScrollOffset()) {
      int delta = (int) (scroller.getCurrY() - lastFlingY);
      lastFlingY = scroller.getCurrY();
      applyScrollDelta(delta);
    }
  }

  /**
   * 统一约定：deltaPixels 为正表示视口向历史（上方）移动，为负表示向底部（最新输出）移动。
   * 主缓冲区的 scrollOffsetPixels 语义与此一致；alternate buffer 与鼠标追踪路径在此换算符号。
   */
  private void applyScrollDelta(int deltaPixels) {
    if (host == null || selecting || deltaPixels == 0) return;
    if (isMouseTracking()) {
      // wheel_delta 协议约定：正值向上、负值向下，与「正值向历史」一致。
      host.onMouse(pointerRow(), pointerColumn(), "wheel",
          deltaPixels > 0 ? 1 : -1, false, false, false, false, true);
    } else if (isAlternateBuffer()) {
      // rowsDown 为正发送 ArrowDown（向最新输出），与「正值向历史」相反，取负。
      host.onAlternateScreenScroll(-Math.round(deltaPixels / lineHeight()));
    } else {
      host.onScrollPixels(deltaPixels, maxScrollOffsetPixels());
      requestOlderHistoryAtHardTop(deltaPixels);
    }
  }

  private boolean isMouseTracking() {
    return model != null && model.modes().mouseTracking != TerminalModes.MouseTracking.NONE;
  }

  private boolean isAlternateBuffer() {
    return model != null && model.activeBuffer == ScreenSnapshot.BufferKind.ALTERNATE;
  }

  private int pointerRow() {
    return Math.max(0, Math.min(model != null ? Math.max(0, model.rows - 1) : 0,
        (int) (lastPointerY / lineHeight())));
  }

  private int pointerColumn() {
    return Math.max(0, Math.min(model != null ? Math.max(0, model.columns - 1) : 0,
        (int) (lastPointerX / cellWidth())));
  }

  private void sendMouse(MotionEvent event, @NonNull String button, int wheelDelta, boolean pressed) {
    if (host == null || model == null) return;
    lastPointerX = event.getX();
    lastPointerY = event.getY();
    int col = Math.max(0, Math.min(model.columns - 1, (int) (event.getX() / cellWidth())));
    int row = Math.max(0, Math.min(model.rows - 1, (int) (event.getY() / lineHeight())));
    int meta = event.getMetaState();
    host.onMouse(row, col, button, wheelDelta,
        (meta & KeyEvent.META_SHIFT_ON) != 0,
        (meta & KeyEvent.META_ALT_ON) != 0,
        (meta & KeyEvent.META_CTRL_ON) != 0,
        (meta & KeyEvent.META_META_ON) != 0,
        pressed);
  }

  private boolean handleMouseEvent(MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_SCROLL && isMouseTracking()) {
      float wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
      if (wheel != 0f) sendMouse(event, "wheel", wheel > 0 ? 1 : -1, true);
      return true;
    }
    if (!isMouseTracking()) return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        if (event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) sendMouse(event, "left", 0, true);
        return true;
      case MotionEvent.ACTION_UP:
        if (event.getButtonState() == 0) sendMouse(event, "left", 0, false);
        return true;
      case MotionEvent.ACTION_MOVE:
        if (event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) sendMouse(event, "left", 0, true);
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
        invalidate();
      }
      return true;
    }
    return super.onGenericMotionEvent(event);
  }

  /**
   * Loads an older page only after the user actually reaches the hard history
   * boundary while moving further upward. Requesting on ACTION_UP while merely
   * near the top made a returned page preserve its anchor and undo several
   * reverse (toward-tail) swipes.
   */
  private void requestOlderHistoryAtHardTop(int deltaPixels) {
    if (host == null) return;
    int maxOffset = maxScrollOffsetPixels();
    if (shouldRequestOlderHistory(deltaPixels, viewport.scrollOffsetPixels, maxOffset,
        viewport.followTail)) {
      host.onRequestHistoryPage();
    }
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
  private int maxScrollOffsetPixels() {
    if (model == null || isAlternateBuffer()) return 0;
    TerminalLine[] screen = model.screen();
    int screenRows = screen != null ? screen.length : 0;
    int totalRows = model.historySize() + screenRows;
    float contentHeight = totalRows * lineHeight();
    float usableHeight = Math.max(0f, getHeight() - renderer.getTopInset());
    if (contentHeight <= usableHeight) return 0;
    // 与 RemoteTerminalRenderer.contentTopY 上界一致：滚到顶时首条历史行完整
    // 贴在 topInset 处。ceil 保证能到达该锚点，渲染侧的钳制吸收 <1px 过冲。
    return Math.max(0, (int) Math.ceil(model.historySize() * lineHeight()));
  }

  private float lineHeight() {
    float h = renderer.getLineHeight();
    return h > 0 ? h : 1;
  }

  private float cellWidth() {
    float w = renderer.getCellWidth();
    return w > 0 ? w : 1;
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
      host.onRequestResize(cols, rows);
    }
    invalidate();
  }

  private void requestLayoutIfSizeChanged() {
    if (getWidth() > 0 && getHeight() > 0) {
      updateSize(getWidth(), getHeight());
    }
  }

  private void startSelectionAt(float x, float y) {
    TerminalSelection.Anchor anchor = pointToAnchor(x, y);
    if (anchor == null) return;
    selecting = true;
    selectionStart = anchor;
    selectionEnd = anchor;
    expandSelectionToWord();
    updateViewportSelection();
    startSelectionActionMode();
    invalidate();
  }

  private void extendSelectionTo(float x, float y) {
    if (!selecting) return;
    TerminalSelection.Anchor anchor = pointToAnchor(x, y);
    if (anchor == null) return;
    selectionEnd = anchor;
    updateViewportSelection();
    invalidate();
  }

  private void clearSelection() {
    selecting = false;
    draggingHandle = 0;
    selectionStart = null;
    selectionEnd = null;
    updateViewportSelection();
    invalidate();
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
    TerminalLine line = lineAt(selectionStart);
    if (line == null) return;
    int startCol = selectionStart.col;
    int endCol = selectionStart.col + 1;
    while (startCol > 0 && !isWordBoundary(line.at(startCol - 1))) startCol--;
    while (endCol < line.length() && !isWordBoundary(line.at(endCol))) endCol++;
    selectionStart = new TerminalSelection.Anchor(selectionStart.historyLineId, selectionStart.screenRow, startCol);
    selectionEnd = new TerminalSelection.Anchor(selectionStart.historyLineId, selectionStart.screenRow, endCol);
  }

  private void updateViewportSelection() {
    if (selectionStart == null || selectionEnd == null) {
      viewport.selection = null;
    } else {
      viewport.selection = new TerminalSelection(selectionStart, selectionEnd);
    }
  }

  private TerminalSelection.Anchor pointToAnchor(float x, float y) {
    if (model == null) return null;
    int cols = model.columns;
    if (cols <= 0) return null;
    float cellW = cellWidth();
    float lineH = lineHeight();
    if (cellW <= 0 || lineH <= 0) return null;
    int col = Math.max(0, Math.min(cols - 1, (int) (x / cellW)));

    TerminalLine[] screen = model.screen();
    NavigableMap<Long, TerminalLine> history = isAlternateBuffer()
        ? new java.util.TreeMap<>() : model.historyCache();
    int screenRows = screen != null ? screen.length : 0;
    int historyRows = history.size();
    float scrollOffset = viewport.followTail ? 0 : viewport.scrollOffsetPixels;
    float contentTopY = RemoteTerminalRenderer.contentTopY(getHeight(), historyRows, screenRows,
        lineH, renderer.getTopInset(), scrollOffset);
    float screenTopY = RemoteTerminalRenderer.screenTopY(getHeight(), historyRows, screenRows,
        lineH, renderer.getTopInset(), scrollOffset);

    if (y >= screenTopY) {
      int row = (int) ((y - screenTopY) / lineH);
      row = Math.max(0, Math.min(screenRows - 1, row));
      return new TerminalSelection.Anchor(0, row, normalizeSelectionColumn(screen[row], col));
    }

    int historyIndex = (int) ((y - contentTopY) / lineH);
    historyIndex = Math.max(0, Math.min(historyRows - 1, historyIndex));
    int index = 0;
    long lineId = 0;
    for (Map.Entry<Long, TerminalLine> entry : history.entrySet()) {
      if (index == historyIndex) {
        lineId = entry.getKey();
        break;
      }
      index++;
    }
    return new TerminalSelection.Anchor(lineId, -1,
        normalizeSelectionColumn(history.get(lineId), col));
  }

  private int normalizeSelectionColumn(@Nullable TerminalLine line, int col) {
    if (line == null || col <= 0 || col >= line.length()) return col;
    TerminalCell cell = line.at(col);
    if (cell != null && cell.isSpacer()) {
      TerminalCell previous = line.at(col - 1);
      if (previous != null && previous.isWideStart()) return col - 1;
    }
    return col;
  }

  private void selectWordAt(float x, float y) {
    TerminalSelection.Anchor anchor = pointToAnchor(x, y);
    if (anchor == null) return;
    TerminalLine line = lineAt(anchor);
    if (line == null) return;
    int startCol = anchor.col;
    int endCol = anchor.col + 1;
    // 简单规则：向左右扩展直到空格或边界
    while (startCol > 0 && !isWordBoundary(line.at(startCol - 1))) startCol--;
    while (endCol < line.length() && !isWordBoundary(line.at(endCol))) endCol++;
    selecting = true;
    selectionStart = new TerminalSelection.Anchor(anchor.historyLineId, anchor.screenRow, startCol);
    selectionEnd = new TerminalSelection.Anchor(anchor.historyLineId, anchor.screenRow, endCol);
    updateViewportSelection();
    startSelectionActionMode();
    invalidate();
  }

  private boolean isWordBoundary(TerminalCell cell) {
    if (cell == null) return true;
    String text = cell.text;
    return text == null || text.isEmpty() || text.equals(" ") || text.equals("\t");
  }

  private TerminalLine lineAt(TerminalSelection.Anchor anchor) {
    if (model == null) return null;
    if (anchor.historyLineId != 0) {
      return model.historyCache().get(anchor.historyLineId);
    }
    TerminalLine[] screen = model.screen();
    if (screen != null && anchor.screenRow >= 0 && anchor.screenRow < screen.length) {
      return screen[anchor.screenRow];
    }
    return null;
  }

  private String selectedText() {
    if (selectionStart == null || selectionEnd == null || model == null) return "";
    TerminalSelection normalized = new TerminalSelection(selectionStart, selectionEnd).normalized();
    StringBuilder sb = new StringBuilder();
    TerminalSelection.Anchor start = normalized.start;
    TerminalSelection.Anchor end = normalized.end;

    if (start.historyLineId != 0) {
      appendHistoryRange(sb, start, end);
    } else if (end.historyLineId == 0 && start.screenRow == end.screenRow) {
      appendScreenRow(sb, start.screenRow, start.col, end.col);
    } else {
      appendScreenRange(sb, start, end);
    }
    return sb.toString();
  }

  private void appendHistoryRange(StringBuilder sb, TerminalSelection.Anchor start,
                                  TerminalSelection.Anchor end) {
    NavigableMap<Long, TerminalLine> history = model.historyCache();
    boolean first = true;
    for (Map.Entry<Long, TerminalLine> entry : history.entrySet()) {
      long lineId = entry.getKey();
      if (lineId < start.historyLineId) continue;
      if (lineId > end.historyLineId) break;
      if (!first) sb.append('\n');
      first = false;
      TerminalLine line = entry.getValue();
      int c0 = lineId == start.historyLineId ? start.col : 0;
      int c1 = lineId == end.historyLineId ? end.col : line.length();
      appendLineText(sb, line, c0, c1);
    }
  }

  private void appendScreenRange(StringBuilder sb, TerminalSelection.Anchor start,
                                 TerminalSelection.Anchor end) {
    TerminalLine[] screen = model.screen();
    if (screen == null) return;
    boolean first = true;
    for (int row = start.screenRow; row <= end.screenRow && row < screen.length; row++) {
      if (!first) sb.append('\n');
      first = false;
      int c0 = row == start.screenRow ? start.col : 0;
      int c1 = row == end.screenRow ? end.col : (row < screen.length ? screen[row].length() : 0);
      appendScreenRow(sb, row, c0, c1);
    }
  }

  private void appendScreenRow(StringBuilder sb, int row, int colStart, int colEnd) {
    TerminalLine[] screen = model.screen();
    if (screen == null || row < 0 || row >= screen.length) return;
    appendLineText(sb, screen[row], colStart, colEnd);
  }

  private void appendLineText(StringBuilder sb, TerminalLine line, int colStart, int colEnd) {
    if (line == null) return;
    int start = Math.max(0, Math.min(line.length(), colStart));
    int end = Math.max(0, Math.min(line.length(), colEnd));
    for (int i = start; i < end; i++) {
      TerminalCell cell = line.at(i);
      if (cell == null || cell.isSpacer()) continue;
      String text = cell.text;
      sb.append(text == null || text.isEmpty() ? " " : text);
    }
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
            if (text != null) host.onPasteInput(text.toString());
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
      float[] start = anchorToPoint(selectionStart, false);
      float[] end = anchorToPoint(selectionEnd, true);
      float radius = handleRadius();
      if (isNear(event, start, radius)) {
        draggingHandle = 1;
        return true;
      }
      if (isNear(event, end, radius)) {
        draggingHandle = 2;
        return true;
      }
      return false;
    }
    if (draggingHandle == 0) return false;
    if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
      TerminalSelection.Anchor anchor = pointToAnchor(event.getX(), event.getY());
      if (anchor != null) {
        if (draggingHandle == 1) selectionStart = anchor; else selectionEnd = anchor;
        updateViewportSelection();
        if (selectionActionMode != null) selectionActionMode.invalidate();
        invalidate();
      }
    } else if (event.getActionMasked() == MotionEvent.ACTION_UP
        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      draggingHandle = 0;
    }
    return true;
  }

  private boolean isNear(MotionEvent event, @Nullable float[] point, float radius) {
    if (point == null) return false;
    float dx = event.getX() - point[0];
    float dy = event.getY() - point[1];
    return dx * dx + dy * dy <= radius * radius * 2;
  }

  private void drawSelectionHandles(Canvas canvas) {
    if (!selecting) return;
    selectionHandlePaint.setColor(0xFF3B82F6);
    float radius = handleRadius();
    float[] start = anchorToPoint(selectionStart, false);
    float[] end = anchorToPoint(selectionEnd, true);
    if (start != null) canvas.drawCircle(start[0], start[1] + lineHeight(), radius, selectionHandlePaint);
    if (end != null) canvas.drawCircle(end[0], end[1] + lineHeight(), radius, selectionHandlePaint);
  }

  private float handleRadius() {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 9, getResources().getDisplayMetrics());
  }

  @Nullable
  private float[] anchorToPoint(@Nullable TerminalSelection.Anchor anchor, boolean end) {
    if (anchor == null || model == null) return null;
    int col = anchor.col;
    float contentTop = contentTopY();
    NavigableMap<Long, TerminalLine> history = isAlternateBuffer()
        ? new java.util.TreeMap<>() : model.historyCache();
    if (anchor.historyLineId != 0) {
      int index = 0;
      for (Long id : history.navigableKeySet()) {
        if (id == anchor.historyLineId) return new float[] {col * cellWidth(), contentTop + index * lineHeight()};
        index++;
      }
      return null;
    }
    return new float[] {col * cellWidth(), contentTop + (history.size() + anchor.screenRow) * lineHeight()};
  }

  private float contentTopY() {
    if (model == null) return 0;
    int screenRows = model.screen() != null ? model.screen().length : 0;
    int historyRows = isAlternateBuffer() ? 0 : model.historyCache().size();
    int totalRows = historyRows + screenRows;
    return RemoteTerminalRenderer.contentTopY(getHeight(), historyRows, screenRows,
        lineHeight(), renderer.getTopInset(), viewport.followTail ? 0 : viewport.scrollOffsetPixels);
  }

  /**
   * 键盘避让用的"保护行"底边在 View 坐标系中的 y 坐标：光标行与最后一个非空内容行中
   * 靠下那一行的底部。IME 弹出时据此只平移必要距离，而不是固定平移整个键盘高度。
   */
  public float getKeyboardProtectedBottomY() {
    if (model == null) return 0f;
    TerminalLine[] screen = model.screen();
    if (screen == null || screen.length == 0) return 0f;
    int screenRows = screen.length;
    int lastContentRow = 0;
    for (int row = screenRows - 1; row >= 0; row--) {
      if (!isLineBlank(screen[row])) {
        lastContentRow = row;
        break;
      }
    }
    TerminalCursor cursor = model.cursor();
    int cursorRow = cursor.visible ? cursor.row : 0;
    int protectedRow = Math.max(0, Math.min(screenRows - 1, Math.max(cursorRow, lastContentRow)));
    float lineH = lineHeight();
    int historyRows = isAlternateBuffer() ? 0 : model.historySize();
    float contentTop = RemoteTerminalRenderer.contentTopY(getHeight(), historyRows, screenRows,
        lineH, renderer.getTopInset(), viewport.followTail ? 0 : viewport.scrollOffsetPixels);
    return contentTop + (historyRows + protectedRow + 1) * lineH;
  }

  private boolean isLineBlank(@Nullable TerminalLine line) {
    if (line == null) return true;
    for (int i = 0; i < line.length(); i++) {
      TerminalCell cell = line.at(i);
      if (cell == null || cell.isSpacer()) continue;
      String text = cell.text;
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
      } else {
        scrolledWithFinger = true;
        // GestureDetector 的 distanceY 在手指上滑时为正（内容应跟随手指向底部移动），
        // 与 applyScrollDelta「正值向历史」的约定相反，取负。
        applyScrollDelta(-(int) distanceY);
      }
      invalidate();
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

  private static final class RemoteTerminalInputConnection extends BaseInputConnection {

    private final Host host;

    RemoteTerminalInputConnection(View targetView, Host host) {
      super(targetView, true);
      this.host = host;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
      super.commitText(text, newCursorPosition);
      sendTextToTerminal();
      return true;
    }

    @Override
    public boolean finishComposingText() {
      super.finishComposingText();
      sendTextToTerminal();
      return true;
    }

    @Override
    public boolean deleteSurroundingText(int leftLength, int rightLength) {
      traceInput("ime-delete", "left=" + leftLength + " right=" + rightLength);
      if (host != null) {
        KeyEvent deleteKey = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);
        for (int i = 0; i < leftLength; i++) {
          host.onKeyEvent(deleteKey);
        }
      }
      return super.deleteSurroundingText(leftLength, rightLength);
    }

    private void sendTextToTerminal() {
      android.text.Editable content = getEditable();
      if (host != null && content != null && content.length() > 0) {
        String text = content.toString();
        traceInput("ime-send", "len=" + text.length() + " text=" + text);
        host.onTextInput(text);
        content.clear();
      }
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
      if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
        return super.sendKeyEvent(event);
      }
      // IMEs commonly emit both commitText() and a synthetic Unicode KeyEvent
      // for the same composing result. Text belongs exclusively to
      // commitText(); forwarding both produces a second PTY write (Claude then
      // displays a plain, unstyled echo beneath the submitted prompt).
      // Hardware keyboards bypass this InputConnection and still use the View
      // key callbacks above.
      boolean suppressText = isTextKeyFromIme(event);
      traceInput("ime-key", eventSummary(event) + " suppressText=" + suppressText);
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

  private static void traceInput(String stage, String detail) {
    if (Log.isLoggable(INPUT_TRACE_TAG, Log.DEBUG)) {
      Log.d(INPUT_TRACE_TAG, stage + " " + detail);
    }
  }

  private static String eventSummary(KeyEvent event) {
    if (event == null) return "null";
    return "action=" + event.getAction() + " keyCode=" + event.getKeyCode()
        + " unicode=" + event.getUnicodeChar() + " device=" + event.getDeviceId();
  }
}
