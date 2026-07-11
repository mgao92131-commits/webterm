package com.webterm.terminal.renderer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.KeyEvent;
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
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.TerminalViewportState;

import java.util.Map;
import java.util.NavigableMap;

/**
 * 远程终端自定义 View。负责 Android View 生命周期、IME、触摸滚动、选择和触发渲染。
 */
public final class RemoteTerminalView extends View {

  public interface Host {
    void onTextInput(@NonNull String text);
    void onPasteInput(@NonNull String text);
    void onKeyEvent(@NonNull KeyEvent event);
    void onRequestResize(int cols, int rows);
    void onRequestShowKeyboard();
    void onScrollPixels(int deltaPixels);
    void onRequestHistoryPage();
    void onFollowTail();
    void onFocusChanged(boolean focused);
  }

  private final RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
  private final GestureDetector gestureDetector;
  private final Scroller scroller;

  private RemoteTerminalModel model;
  private TerminalViewportState viewport = new TerminalViewportState();
  private Host host;
  private float lastFlingY;
  private boolean selecting;
  private TerminalSelection.Anchor selectionStart;
  private TerminalSelection.Anchor selectionEnd;
  private int userTextSizeSp;
  private Typeface userTypeface;

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
    this.gestureDetector = new GestureDetector(context, new GestureListener());
    gestureDetector.setIsLongpressEnabled(true);
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
    if (typeface != null) {
      renderer.setTypeface(typeface);
    }
    requestLayoutIfSizeChanged();
    invalidate();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    updateFontMetrics(w);
    notifyResize(w, h);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (model == null) return;
    computeScrollOffset();
    renderer.render(canvas, model, viewport);
  }

  @Override
  public boolean onCheckIsTextEditor() {
    return true;
  }

  @Override
  public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
    outAttrs.inputType = EditorInfo.TYPE_NULL;
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;
    return new RemoteTerminalInputConnection(this, host);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (host != null) {
      host.onKeyEvent(event);
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (host != null) {
      host.onKeyEvent(event);
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (event.getAction() == MotionEvent.ACTION_DOWN) {
      requestFocus();
      if (!scroller.isFinished()) scroller.forceFinished(true);
    }
    boolean handled = gestureDetector.onTouchEvent(event);
    if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
      if (!viewport.followTail && host != null && isNearTop()) {
        host.onRequestHistoryPage();
      }
    }
    return handled || super.onTouchEvent(event);
  }

  @Override
  public void computeScroll() {
    super.computeScroll();
    if (scroller.computeScrollOffset()) {
      int delta = (int) (scroller.getCurrY() - lastFlingY);
      lastFlingY = scroller.getCurrY();
      applyScrollDelta(delta);
      invalidate();
      if (scroller.isFinished() && !viewport.followTail && host != null && isNearTop()) {
        host.onRequestHistoryPage();
      }
    }
  }

  private void computeScrollOffset() {
    if (scroller.computeScrollOffset()) {
      int delta = (int) (scroller.getCurrY() - lastFlingY);
      lastFlingY = scroller.getCurrY();
      applyScrollDelta(delta);
      if (scroller.isFinished() && !viewport.followTail && host != null && isNearTop()) {
        host.onRequestHistoryPage();
      }
    }
  }

  private void applyScrollDelta(int deltaPixels) {
    if (host != null && !selecting) {
      host.onScrollPixels(deltaPixels);
    }
  }

  private boolean isNearTop() {
    if (model == null) return false;
    int historyRows = model.historyCache().size();
    int totalRows = historyRows + (model.screen() != null ? model.screen().length : 0);
    float contentHeight = totalRows * lineHeight();
    return viewport.scrollOffsetPixels >= contentHeight - getHeight() - lineHeight() * 50;
  }

  private float lineHeight() {
    float h = renderer.getLineHeight();
    return h > 0 ? h : 1;
  }

  private float cellWidth() {
    float w = renderer.getCellWidth();
    return w > 0 ? w : 1;
  }

  private void updateFontMetrics(int width) {
    if (model == null || model.columns <= 0) return;
    float baseCellWidth = width / (float) model.columns;
    float baseLineHeight = baseCellWidth * 1.8f;
    float baseTextSizePx = baseLineHeight * 0.75f;
    float scale = 1.0f;
    if (userTextSizeSp > 0) {
      float userTextSizePx = TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_SP, userTextSizeSp, getResources().getDisplayMetrics());
      scale = userTextSizePx / baseTextSizePx;
    }
    float cellWidth = baseCellWidth * scale;
    float lineHeight = baseLineHeight * scale;
    renderer.setFontMetrics(cellWidth, lineHeight, lineHeight * 0.75f);
    if (userTypeface != null) {
      renderer.setTypeface(userTypeface);
    }
  }

  private void notifyResize(int w, int h) {
    if (host == null || model == null || model.columns <= 0) return;
    float cellW = cellWidth();
    float lineH = lineHeight();
    if (cellW <= 0 || lineH <= 0) return;
    int cols = Math.max(1, (int) (w / cellW));
    int rows = Math.max(1, (int) (h / lineH));
    host.onRequestResize(cols, rows);
  }

  private void requestLayoutIfSizeChanged() {
    if (model != null && model.columns > 0 && getWidth() > 0) {
      updateFontMetrics(getWidth());
    }
  }

  private void startSelectionAt(float x, float y) {
    TerminalSelection.Anchor anchor = pointToAnchor(x, y);
    if (anchor == null) return;
    selecting = true;
    selectionStart = anchor;
    selectionEnd = anchor;
    updateViewportSelection();
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
    selectionStart = null;
    selectionEnd = null;
    updateViewportSelection();
    invalidate();
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
    NavigableMap<Long, TerminalLine> history = model.historyCache();
    int screenRows = screen != null ? screen.length : 0;
    int historyRows = history.size();
    int totalRows = historyRows + screenRows;
    float contentHeight = totalRows * lineH;
    float scrollOffset = viewport.followTail ? 0 : viewport.scrollOffsetPixels;
    float bottomY = contentHeight - scrollOffset;
    float screenTopY = bottomY - screenRows * lineH;

    if (y >= screenTopY) {
      int row = (int) ((y - screenTopY) / lineH);
      row = Math.max(0, Math.min(screenRows - 1, row));
      return new TerminalSelection.Anchor(0, row, col);
    }

    int historyOffset = (int) ((screenTopY - y) / lineH);
    historyOffset = Math.max(0, Math.min(historyRows - 1, historyOffset));
    int index = 0;
    long lineId = 0;
    for (Map.Entry<Long, TerminalLine> entry : history.descendingMap().entrySet()) {
      if (index == historyOffset) {
        lineId = entry.getKey();
        break;
      }
      index++;
    }
    return new TerminalSelection.Anchor(lineId, -1, col);
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
    invalidate();
    copySelectionToClipboard();
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

  private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
    @Override
    public boolean onDown(MotionEvent e) {
      lastFlingY = 0;
      return true;
    }

    @Override
    public void onLongPress(MotionEvent e) {
      startSelectionAt(e.getX(), e.getY());
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
      if (selecting) {
        copySelectionToClipboard();
        clearSelection();
      } else if (host != null) {
        host.onRequestShowKeyboard();
      }
      return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
      selectWordAt(e.getX(), e.getY());
      return true;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
      if (selecting) {
        extendSelectionTo(e2.getX(), e2.getY());
      } else {
        applyScrollDelta((int) distanceY);
      }
      invalidate();
      return true;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
      if (selecting) return true;
      lastFlingY = 0;
      scroller.fling(0, 0, 0, (int) -velocityY, 0, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
      invalidate();
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
      if (host != null && text != null) {
        host.onTextInput(text.toString());
      }
      return true;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
      if (host != null) {
        host.onKeyEvent(event);
      }
      return true;
    }
  }
}
