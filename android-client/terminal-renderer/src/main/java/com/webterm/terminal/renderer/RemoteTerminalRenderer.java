package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalHistorySnapshot;
import com.webterm.terminal.model.TerminalHistoryView;
import com.webterm.terminal.model.HistoryRenderView;
import com.webterm.terminal.model.SlotState;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.UnifiedContentAxis;

import java.util.List;

/**
 * Go 权威屏幕投影的 Canvas renderer。
 * 视觉规则与应用既有终端体验对齐，状态只来自 RemoteTerminalModel。
 */
public final class RemoteTerminalRenderer {

  static final int SELECTION_OVERLAY = 0x665B92F3;

  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bgPaint = new Paint();
  private final Paint selectionPaint = new Paint();
  private final Paint placeholderPaint = new Paint();
  private final Rect clipBounds = new Rect();
  /** Reused on the UI thread for the common, plain-ASCII output path. */
  private final StringBuilder plainAsciiRun = new StringBuilder();

  private float cellWidth;
  private float lineHeight;
  private float baselineOffset;
  private int textSizeSp = 14;
  @Nullable private Typeface typeface = Typeface.MONOSPACE;

  public RemoteTerminalRenderer() {
    applyFont();
  }

  public void updateFont(float textSizePx, @Nullable Typeface tf) {
    typeface = tf;
    textPaint.setTextSize(textSizePx);
    textPaint.setTypeface(tf != null ? tf : Typeface.MONOSPACE);
    // ceil 保证行高落在整数像素；与像素对齐的 topInset 一起，使相邻行
    // RenderNode 的上下边界都落在整数 Y，避免硬件合成时出现 1px 暗缝。
    lineHeight = (float) Math.ceil(textPaint.getFontSpacing());
    // rowY is the top of a terminal cell. Match Termux TerminalRenderer:
    // its baseline is the cell top minus Paint.ascent(), not the full line
    // spacing. Using lineHeight here lowered glyphs within their cells and
    // made a full-cell block cursor appear visibly too high.
    baselineOffset = -textPaint.ascent();
    cellWidth = textPaint.measureText("X");
    // 预热 fallback chain，避免首个 emoji 采用错误的测量宽度。
    textPaint.measureText("😀");
  }

  public void setFontMetrics(float cellWidth, float lineHeight, float baselineOffset) {
    this.cellWidth = cellWidth;
    this.lineHeight = lineHeight;
    this.baselineOffset = baselineOffset;
  }

  public float getCellWidth() { return cellWidth; }
  public float getLineHeight() { return lineHeight; }

  /**
   * Font-metric space above the first terminal cell, matching Termux.
   *
   * <p>对原始 {@code lineHeight - baselineOffset} 做一次整像素量化。基线来自
   * {@link Paint#ascent()}，常为小数；若不量化，{@code contentTopY + row * lineHeight}
   * 整列落在亚像素 Y 上，硬件加速下相邻行级 RenderNode 边界会透出底层背景，
   * 形成周期性约 1px 暗线。绘制、命中测试、选择手柄与脏区一律走本值，
   * 保证坐标系一致。</p>
   */
  public float getTopInset() {
    return (float) Math.round(Math.max(0f, lineHeight - baselineOffset));
  }

  /** 基线相对行顶的偏移（仅供诊断只读快照使用）。 */
  public float getBaselineOffset() { return baselineOffset; }

  static int liveScreenExitOffsetPixels(int viewportHeight, float topInset) {
    return Math.max(
        0, (int) Math.ceil(Math.max(0f, viewportHeight - topInset)));
  }

  public void setTextSize(int textSizeSp) {
    this.textSizeSp = textSizeSp;
    applyFont();
  }

  public void setTypeface(@Nullable Typeface typeface) {
    this.typeface = typeface;
    applyFont();
  }

  private void applyFont() {
    textPaint.setTextSize(textSizeSp);
    textPaint.setTypeface(typeface != null ? typeface : Typeface.MONOSPACE);
  }

  public void render(@NonNull Canvas canvas, @NonNull RemoteTerminalModel.RenderSnapshot model,
                     @NonNull TerminalViewportState viewport, boolean cursorBlinkOn) {
    render(canvas, model, viewport, cursorBlinkOn, null);
  }

  /**
   * 绘制完整终端帧。screen/history 行优先通过 {@link TerminalLineRenderNodeCache}
   * 绘制；缓存不可用时回退到直接 {@link #drawLine}。光标和选择作为动态覆盖层
   * 在静态正文之后绘制，避免光标闪烁或选择变化触发整行重录。
   */
  public void render(@NonNull Canvas canvas, @NonNull RemoteTerminalModel.RenderSnapshot model,
                     @NonNull TerminalViewportState viewport, boolean cursorBlinkOn,
                     @Nullable TerminalLineRenderNodeCache lineCache) {
    long renderStartedNanos = System.nanoTime();
    try {
    RenderLine[] screen = model.screenView.copyLines();
    if (screen == null || lineHeight <= 0 || cellWidth <= 0) return;

    UnifiedContentAxis axis = model.contentAxis;
    // The content axis is the only vertical coordinate space. History and
    // ActiveRows remain semantic item kinds, not independent layout systems.
    HistoryRenderView history = model.activeBuffer == TerminalBufferKind.ALTERNATE
        ? TerminalHistorySnapshot.empty() : model.history;
    int screenRows = screen.length;
    long historyRowsLong = axis.historyRowCount();
    int historyRows = (int) Math.min(Integer.MAX_VALUE, historyRowsLong);
    int maxScrollOffset = Math.round(historyRows * lineHeight);
    float scrollOffset = viewport.derivedScrollOffsetPixels(
        model, lineHeight, maxScrollOffset);
    float contentTopY = contentTopY(canvas.getHeight(), historyRows, screenRows, lineHeight,
        getTopInset(), scrollOffset);
    float screenTopY = contentTopY + historyRows * lineHeight;

    TerminalPalette palette = model.palette;
    int canvasBackground = resolveColor(palette,
        palette.reverseVideo ? palette.defaultFg : palette.defaultBg);
    canvas.drawColor(canvasBackground);

    TerminalSelection selection = viewport.selection;
    TerminalSelection normalizedSelection = selection != null ? selection.normalized() : null;
    TerminalCursor cursor = model.cursor;
    boolean cursorVisible = shouldDrawCursor(
        viewport, model.activeBuffer, cursor, cursorBlinkOn);

    Rect clip = clipBounds;
    if (!canvas.getClipBounds(clip)) clip.set(0, 0, canvas.getWidth(), canvas.getHeight());

    boolean useCache = canvas.isHardwareAccelerated() && lineCache != null;
    float topInset = getTopInset();
    int visibleHistoryRows = 0;
    long axisRows = axis.rowCount();
    long firstAxisRow = Math.max(0L,
        (long) Math.floor((clip.top - contentTopY) / lineHeight) - 1L);
    long lastAxisRow = Math.min(axisRows,
        (long) Math.ceil((clip.bottom - contentTopY) / lineHeight) + 1L);
    for (long axisRow = firstAxisRow; axisRow < lastAxisRow; axisRow++) {
      UnifiedContentAxis.Item item = axis.itemAtRow(axisRow);
      float y = contentTopY + axisRow * lineHeight;
      boolean active = item.kind == UnifiedContentAxis.Kind.ACTIVE_LINE;
      if (!active && (y + lineHeight <= topInset + 0.001f || y >= screenTopY)) continue;

      if (item.kind == UnifiedContentAxis.Kind.MISSING_HISTORY_RANGE) {
        long historySeq = item.fromHistorySeq + (axisRow - item.startRow);
        long historyIndex = historySeq - history.firstSeq();
        if (historyIndex >= 0 && historyIndex <= Integer.MAX_VALUE) {
          canvas.save();
          canvas.clipRect(0f, topInset, canvas.getWidth(), screenTopY);
          drawHistoryPlaceholder(canvas, model.columns, history, (int) historyIndex, y,
              canvasBackground);
          canvas.restore();
        }
        continue;
      }

      RenderLine line = item.line;
      int screenRow = active ? (int) (axisRow - historyRowsLong) : -1;
      long historySeq = active ? 0 : item.fromHistorySeq;
      if (!active) {
        visibleHistoryRows++;
        canvas.save();
        canvas.clipRect(0f, topInset, canvas.getWidth(), screenTopY);
      }
      TerminalLineRenderNodeCache.LineDrawResult cacheResult = useCache
          ? lineCache.drawOrRecord(canvas, line, y, !active)
          : TerminalLineRenderNodeCache.LineDrawResult.UNAVAILABLE;
      if (cacheResult == TerminalLineRenderNodeCache.LineDrawResult.UNAVAILABLE) {
        if (useCache) {
          drawTerminalLineContent(canvas, model.columns, palette, line, y, canvasBackground);
        } else {
          drawLine(canvas, model.columns, palette, line, y, historySeq, screenRow,
              normalizedSelection, cursor, active && cursorVisible, canvasBackground);
        }
      }
      if (useCache) {
        drawSelectionOverlayForRow(canvas, model.columns, palette, line, y,
            historySeq, screenRow, normalizedSelection, canvasBackground);
        if (active && cursorVisible && cursor.row == screenRow) {
          drawCursorOverlayForRow(canvas, model.columns, palette, line, y,
              screenRow, cursor, canvasBackground);
        }
      }
      if (!active) canvas.restore();
    }
    TerminalRenderMetrics.visibleHistoryRowsDrawn(visibleHistoryRows);
    } finally {
      TerminalRenderMetrics.renderDuration(System.nanoTime() - renderStartedNanos);
    }
  }

  static boolean shouldDrawCursor(
      @NonNull TerminalViewportState viewport,
      @NonNull TerminalCursor cursor,
      boolean cursorBlinkOn) {
    return shouldDrawCursor(
        viewport, TerminalBufferKind.MAIN, cursor, cursorBlinkOn);
  }

  static boolean shouldDrawCursor(
      @NonNull TerminalViewportState viewport,
      @NonNull TerminalBufferKind buffer,
      @NonNull TerminalCursor cursor,
      boolean cursorBlinkOn) {
    return viewport.isFollowTail(buffer) && cursor.visible
        && (!cursor.blink || cursorBlinkOn);
  }

  private void drawHistoryPlaceholder(Canvas canvas, int columns, HistoryRenderView history,
                                      int historyIndex, float y, int canvasBackground) {
    SlotState state = history.slotStateAt(historyIndex);
    int alpha = state == SlotState.UNAVAILABLE ? 18 : 10;
    placeholderPaint.setColor((canvasBackground & 0x00ffffff) | (alpha << 24));
    canvas.drawRect(0f, y, columns * cellWidth, y + lineHeight, placeholderPaint);
  }

  /** Half-open row range whose cells can affect a Canvas clip, including one anti-aliasing guard. */
  static int[] rowRangeIntersecting(int clipTop, int clipBottom, float rowsTop,
                                    float rowHeight, int rowCount) {
    long packed = rowRangeIntersectingPacked(
        clipTop, clipBottom, rowsTop, rowHeight, rowCount);
    return new int[] {(int) (packed >> 32), (int) packed};
  }

  /** 热路径用一个 long 携带两个非负 int，避免每帧创建范围数组。 */
  static long rowRangeIntersectingPacked(int clipTop, int clipBottom, float rowsTop,
                                         float rowHeight, int rowCount) {
    if (rowCount <= 0 || rowHeight <= 0f || clipBottom <= clipTop) return 0L;
    double firstRaw = Math.floor((clipTop - rowsTop) / rowHeight);
    double lastRaw = Math.ceil((clipBottom - rowsTop) / rowHeight);
    int first = clampRow(firstRaw, rowCount);
    int last = clampRow(lastRaw, rowCount);
    first = Math.max(0, first - 1);
    last = Math.min(rowCount, last + 1);
    int rangeFirst = Math.min(first, last);
    int rangeLast = Math.max(first, last);
    return ((long) rangeFirst << 32) | (rangeLast & 0xffffffffL);
  }

  private static int clampRow(double value, int rowCount) {
    if (value <= 0d) return 0;
    if (value >= rowCount) return rowCount;
    return (int) value;
  }

  private void drawLine(Canvas canvas, int columns, TerminalPalette palette,
                        RenderLine line, float y,
                        long historySeq, int screenRow, TerminalSelection selection,
                        TerminalCursor cursor, boolean cursorVisible, int canvasBackground) {
    if (line == null) return;
    int lineLength = Math.min(line.length(), columns);
    for (int col = 0; col < lineLength; ) {
      CellValue cell = line.at(col);
      if (cell == null || cell.isSpacer()) {
        col++;
        continue;
      }

      // Keep Unicode/wide cells on the canonical path, but batch contiguous ASCII cells with
      // the same style. Selection and cursor boundaries deliberately split runs.
      if (startsBatchableAsciiRun(line, lineLength, selection, historySeq, screenRow, col,
          cursor, cursorVisible)) {
        int runStart = col;
        StyleValue runStyle = styleOf(cell);
        plainAsciiRun.setLength(0);
        do {
          plainAsciiRun.append(line.at(col).text().charAt(0));
          col++;
        } while (col < lineLength && java.util.Objects.equals(
                styleOf(line.at(col)), runStyle)
            && canBatchAscii(line.at(col), selection, historySeq, screenRow, col, cursor,
                cursorVisible));
        if (drawAsciiRun(canvas, palette, runStyle, plainAsciiRun, runStart, y,
            canvasBackground)) {
          continue;
        }
        // A scaling requirement discovered after measuring the complete run uses the canonical
        // per-cell path so glyph hinting and placement remain pixel-identical.
        col = runStart;
      }
      int columnWidth = cell.isWideStart() ? 2 : 1;
      boolean selected = isCellSelected(selection, historySeq, screenRow, col, columnWidth);
      boolean insideCursor = cursorVisible && screenRow == cursor.row
          && (cursor.col == col || (columnWidth == 2 && cursor.col == col + 1));
      int codePoint = cell.text().isEmpty() ? ' ' : cell.text().codePointAt(0);
      boolean preserveAspect = TerminalVisualRules.shouldPreserveGlyphAspect(codePoint, columnWidth,
          hasRightPadding(line, col, columnWidth, styleOf(cell)));
      drawCell(canvas, palette, cell, col, y, selected, insideCursor, cursor,
          preserveAspect, canvasBackground);
      col++;
    }
  }

  /**
   * 仅绘制终端行的静态正文：背景、字符、样式装饰，不包含光标和选择。
   * 用于 RenderNode 行缓存录制，保证光标闪烁和选择变化不会触发整行重录。
   */
  void drawTerminalLineContent(Canvas canvas, int columns, TerminalPalette palette,
                               RenderLine line, float y, int canvasBackground) {
    if (line == null) return;
    int lineLength = Math.min(line.length(), columns);
    for (int col = 0; col < lineLength; ) {
      CellValue cell = line.at(col);
      if (cell == null || cell.isSpacer()) {
        col++;
        continue;
      }

      // 静态正文没有光标和选择边界，统一传 null / false 以复用现有批处理路径。
      if (startsBatchableAsciiRun(line, lineLength, null, 0, -1, col,
          null, false)) {
        int runStart = col;
        StyleValue runStyle = styleOf(cell);
        plainAsciiRun.setLength(0);
        do {
          plainAsciiRun.append(line.at(col).text().charAt(0));
          col++;
        } while (col < lineLength && java.util.Objects.equals(
                styleOf(line.at(col)), runStyle)
            && canBatchAscii(line.at(col), null, 0, -1, col, null,
                false));
        if (drawAsciiRun(canvas, palette, runStyle, plainAsciiRun, runStart, y,
            canvasBackground)) {
          continue;
        }
        col = runStart;
      }
      int columnWidth = cell.isWideStart() ? 2 : 1;
      int codePoint = cell.text().isEmpty() ? ' ' : cell.text().codePointAt(0);
      boolean preserveAspect = TerminalVisualRules.shouldPreserveGlyphAspect(codePoint, columnWidth,
          hasRightPadding(line, col, columnWidth, styleOf(cell)));
      drawCell(canvas, palette, cell, col, y, false, false, null,
          preserveAspect, canvasBackground);
      col++;
    }
  }

  /** 在已绘制的行上追加选择高亮覆盖层。 */
  private void drawSelectionOverlayForRow(Canvas canvas, int columns, TerminalPalette palette,
                                          RenderLine line, float y, long historySeq, int screenRow,
                                          TerminalSelection selection, int canvasBackground) {
    if (line == null || selection == null) return;
    TerminalSelection normalized = selection.normalized();
    int lineLength = Math.min(line.length(), columns);
    for (int col = 0; col < lineLength; ) {
      CellValue cell = line.at(col);
      if (cell == null || cell.isSpacer()) {
        col++;
        continue;
      }
      int columnWidth = cell.isWideStart() ? 2 : 1;
      boolean selected = isCellSelected(normalized, historySeq, screenRow, col, columnWidth);
      if (selected) {
        selectionPaint.setColor(SELECTION_OVERLAY);
        float x = col * cellWidth;
        float width = columnWidth * cellWidth;
        canvas.drawRect(x, y, x + width, y + lineHeight, selectionPaint);
      }
      col += columnWidth;
    }
  }

  /** 在已绘制的行上追加光标覆盖层。 */
  private void drawCursorOverlayForRow(Canvas canvas, int columns, TerminalPalette palette,
                                       RenderLine line, float y, int screenRow,
                                       TerminalCursor cursor, int canvasBackground) {
    if (line == null || cursor == null || !cursor.visible || screenRow != cursor.row
        || cursor.col < 0 || cursor.col >= columns) {
      return;
    }
    int col = cursor.col;
    CellValue cell = col < line.length() ? line.at(col) : null;
    // 光标落在宽字符右半（spacer 列）时归一到宽字符起始格：整格 2 列高亮，
    // 与 drawCell 旧路径 `cursor.col == col + 1` 的块光标行为一致。
    if (cell != null && cell.isSpacer() && col > 0) {
      CellValue left = line.at(col - 1);
      if (left != null && left.isWideStart()) {
        col--;
        cell = left;
      }
    }
    int columnWidth = cell != null && cell.isWideStart() ? 2 : 1;
    int cursorColor = resolveColor(palette, palette.cursorColor);
    float x = col * cellWidth;
    float width = columnWidth * cellWidth;
    bgPaint.setColor(cursorColor);
    if (cursor.shape == TerminalCursor.Shape.BAR) {
      canvas.drawRect(x, y, x + width / 4f, y + lineHeight, bgPaint);
    } else if (cursor.shape == TerminalCursor.Shape.UNDERLINE) {
      canvas.drawRect(x, y + lineHeight * 3f / 4f, x + width, y + lineHeight, bgPaint);
    } else if (cell == null || cell.isSpacer()) {
      // 空 cell（或左侧无宽字符起始格的异常 spacer）没有字形可重绘，只画光标矩形。
      canvas.drawRect(x, y, x + width, y + lineHeight, bgPaint);
    } else {
      // BLOCK 光标：复用 drawCell 的 insideCursor 路径——先画反色背景与不透明光标矩形，
      // 再以反色前景重绘字形，保证块光标下的字符可见（对齐旧 drawLine 路径）。
      int codePoint = cell.text().isEmpty() ? ' ' : cell.text().codePointAt(0);
      boolean preserveAspect = TerminalVisualRules.shouldPreserveGlyphAspect(codePoint,
          columnWidth, hasRightPadding(line, col, columnWidth, styleOf(cell)));
      drawCell(canvas, palette, cell, col, y, false, true, cursor, preserveAspect,
          canvasBackground);
    }
  }

  private static boolean canBatchAscii(CellValue cell, TerminalSelection selection,
                                       long historySeq, int screenRow, int col,
                                       TerminalCursor cursor, boolean cursorVisible) {
    if (cell == null || cell.isSpacer() || cell.isWideStart()
        || cell.text().length() != 1) return false;
    char c = cell.text().charAt(0);
    if (c < ' ' || c > '~') return false;
    if (isCellSelected(selection, historySeq, screenRow, col, 1)) return false;
    return !cursorVisible || screenRow != cursor.row || cursor.col != col;
  }

  private static boolean startsBatchableAsciiRun(RenderLine line, int lineLength,
                                                 TerminalSelection selection, long historySeq,
                                                 int screenRow, int col, TerminalCursor cursor,
                                                 boolean cursorVisible) {
    if (col + 2 >= lineLength) return false;
    StyleValue style = styleOf(line.at(col));
    for (int candidate = col; candidate < col + 3; candidate++) {
      CellValue cell = line.at(candidate);
      if (cell == null || !java.util.Objects.equals(
              styleOf(cell), style)
          || !canBatchAscii(cell, selection, historySeq, screenRow,
          candidate, cursor, cursorVisible)) return false;
    }
    return true;
  }

  /** @return true if the run was drawn; false when glyph scaling requires the per-cell path. */
  private boolean drawAsciiRun(Canvas canvas, TerminalPalette palette,
                               @Nullable StyleValue style, CharSequence text, int startCol,
                               float rowY, int canvasBackground) {
    TerminalColor fgColor = style != null ? style.fg() : palette.defaultFg;
    TerminalColor bgColor = style != null ? style.bg() : palette.defaultBg;
    if (palette.reverseVideo ^ (style != null && style.reverse())) {
      TerminalColor swap = fgColor;
      fgColor = bgColor;
      bgColor = swap;
    }

    int fg = resolveColor(palette, fgColor);
    boolean bold = style != null && (style.bold() || style.blinkSlow() || style.blinkFast());
    if (bold && fgColor.kind == TerminalColor.Kind.INDEXED
        && fgColor.index >= 0 && fgColor.index < 8) {
      fg = resolveIndexedColor(palette, fgColor.index + 8);
    }
    if (style != null && style.dim()) fg = TerminalVisualRules.dim(fg);

    textPaint.setColor(fg);
    textPaint.setFakeBoldText(bold);
    textPaint.setTextSkewX(style != null && style.italic() ? -0.35f : 0f);

    float x = startCol * cellWidth;
    float width = text.length() * cellWidth;
    // The canonical cell path scales each glyph independently. Scaling a whole run changes
    // hinting/anti-aliasing and can visibly shift glyphs, so only batch naturally cell-wide text.
    if (style == null || !style.hidden()) {
      float measuredWidth = textPaint.measureText(text, 0, text.length());
      // Robolectric's legacy Paint shadow reports zero; keep the benchmark capable of observing
      // draw batching while real Android Canvas uses the strict no-scaling check below.
      if (measuredWidth > 0 && Math.abs(measuredWidth - width) > 0.01f) return false;
    }
    int bg = resolveColor(palette, bgColor);
    if (bg != canvasBackground) {
      bgPaint.setColor(bg);
      canvas.drawRect(x, rowY, x + width, rowY + lineHeight, bgPaint);
    }

    if (style == null || !style.hidden()) {
      canvas.drawText(text, 0, text.length(), x, rowY + baselineOffset, textPaint);
    }

    if (style != null && (style.underline() || style.doubleUnderline())) {
      textPaint.setColor(style.underlineColor() != null
          ? resolveColor(palette, style.underlineColor()) : fg);
      float underlineY = rowY + lineHeight - 2;
      canvas.drawLine(x, underlineY, x + width, underlineY, textPaint);
      if (style.doubleUnderline()) {
        canvas.drawLine(x, underlineY - 3, x + width, underlineY - 3, textPaint);
      }
    }
    if (style != null && style.strike()) {
      textPaint.setColor(fg);
      canvas.drawLine(x, rowY + lineHeight * 0.52f, x + width,
          rowY + lineHeight * 0.52f, textPaint);
    }
    return true;
  }

  private void drawCell(Canvas canvas, TerminalPalette palette,
                        CellValue cell, int col, float rowY, boolean selected,
                        boolean insideCursor, TerminalCursor cursor, boolean preserveAspect,
                        int canvasBackground) {
    StyleValue style = styleOf(cell);
    TerminalColor fgColor = style != null ? style.fg() : palette.defaultFg;
    TerminalColor bgColor = style != null ? style.bg() : palette.defaultBg;
    boolean blockCursor = insideCursor && cursor.shape == TerminalCursor.Shape.BLOCK;
    // Text selection is a view-layer highlight, not an ANSI inverse-video mode.
    // Reversing every individual cell makes CJK/emoji and styled TUI runs lose
    // contrast or look clipped when their glyph width differs from a cell.
    boolean reverse = palette.reverseVideo ^ (style != null && style.reverse()) ^ blockCursor;
    if (reverse) {
      TerminalColor swap = fgColor;
      fgColor = bgColor;
      bgColor = swap;
    }

    int fg = resolveColor(palette, fgColor);
    boolean bold = style != null && (style.bold() || style.blinkSlow() || style.blinkFast());
    if (bold && fgColor.kind == TerminalColor.Kind.INDEXED
        && fgColor.index >= 0 && fgColor.index < 8) {
      fg = resolveIndexedColor(palette, fgColor.index + 8);
    }
    if (style != null && style.dim()) fg = TerminalVisualRules.dim(fg);
    int bg = resolveColor(palette, bgColor);

    float x = col * cellWidth;
    float width = cell.isWideStart() ? cellWidth * 2 : cellWidth;
    if (bg != canvasBackground) {
      bgPaint.setColor(bg);
      canvas.drawRect(x, rowY, x + width, rowY + lineHeight, bgPaint);
    }

    if (insideCursor) {
      bgPaint.setColor(resolveColor(palette, palette.cursorColor));
      if (cursor.shape == TerminalCursor.Shape.BAR) {
        canvas.drawRect(x, rowY, x + width / 4f, rowY + lineHeight, bgPaint);
      } else if (cursor.shape == TerminalCursor.Shape.UNDERLINE) {
        canvas.drawRect(x, rowY + lineHeight * 3f / 4f, x + width, rowY + lineHeight, bgPaint);
      } else {
        canvas.drawRect(x, rowY, x + width, rowY + lineHeight, bgPaint);
      }
    }

    String text = cell.text();
    if (text.isEmpty()) text = " ";
    // A terminal's common case is an unstyled blank cell. Its background was
    // already handled above, so measuring and drawing a space per cell only
    // burns UI-thread time without changing pixels.
    boolean drawGlyph = !" ".equals(text) && (style == null || !style.hidden());
    if (drawGlyph) {
      textPaint.setColor(fg);
      textPaint.setFakeBoldText(bold);
      textPaint.setTextSkewX(style != null && style.italic() ? -0.35f : 0f);
      float expectedWidth = (cell.isWideStart() ? 2 : 1) * cellWidth;
      float measuredWidth = textPaint.measureText(text);
      boolean scaleGlyph = !preserveAspect && measuredWidth > 0
          && Math.abs(measuredWidth - expectedWidth) > 0.01f;
      boolean savedMatrix = false;
      float drawX = x;
      if (scaleGlyph) {
        canvas.save();
        float scaleX = expectedWidth / measuredWidth;
        canvas.scale(scaleX, 1f);
        drawX = x / scaleX;
        savedMatrix = true;
      }
      canvas.drawText(text, drawX, rowY + baselineOffset, textPaint);
      if (savedMatrix) canvas.restore();
    }

    if (style != null && (style.underline() || style.doubleUnderline())) {
      textPaint.setColor(style.underlineColor() != null
          ? resolveColor(palette, style.underlineColor()) : fg);
      float underlineY = rowY + lineHeight - 2;
      canvas.drawLine(x, underlineY, x + width, underlineY, textPaint);
      if (style.doubleUnderline()) {
        canvas.drawLine(x, underlineY - 3, x + width, underlineY - 3, textPaint);
      }
    }
    if (style != null && style.strike()) {
      canvas.drawLine(x, rowY + lineHeight * 0.52f, x + width, rowY + lineHeight * 0.52f, textPaint);
    }

    if (selected) {
      // Draw after the complete glyph run so selection never replaces text with
      // an opaque, cell-sized reverse background.
      selectionPaint.setColor(SELECTION_OVERLAY);
      canvas.drawRect(x, rowY, x + width, rowY + lineHeight, selectionPaint);
    }
  }

  private boolean hasRightPadding(
      RenderLine line, int col, int width, StyleValue style) {
    int nextCol = col + width;
    if (nextCol >= line.length()) return false;
    CellValue next = line.at(nextCol);
    return next != null && !next.isSpacer()
        && java.util.Objects.equals(styleOf(next), style)
        && (next.text().isEmpty() || " ".equals(next.text()));
  }

  private static StyleValue styleOf(CellValue cell) {
    return cell == null ? null : cell.style();
  }

  private static boolean isCellSelected(TerminalSelection selection, long historySeq, int screenRow,
                                        int col, int columnWidth) {
    if (selection == null) return false;
    return compareSelectionPosition(historySeq, screenRow, col, selection.end) < 0
        && compareSelectionPosition(historySeq, screenRow, col + Math.max(1, columnWidth),
            selection.start) > 0;
  }

  private static int compareSelectionPosition(long historySeq, int screenRow, int col,
                                              TerminalSelection.Anchor other) {
    if (historySeq != 0 && other.historySeq != 0) {
      int cmp = Long.compare(historySeq, other.historySeq);
      return cmp != 0 ? cmp : Integer.compare(col, other.col);
    }
    if (historySeq != 0) return -1;
    if (other.historySeq != 0) return 1;
    int cmp = Integer.compare(screenRow, other.screenRow);
    return cmp != 0 ? cmp : Integer.compare(col, other.col);
  }

  static int resolveColor(TerminalColor color) {
    return resolveColor(TerminalPalette.defaults(), color);
  }

  static int resolveColor(TerminalPalette palette, TerminalColor color) {
    if (color == null) return 0xFF000000;
    switch (color.kind) {
      case RGB: return 0xFF000000 | color.rgb;
      case DEFAULT_FG:
        return palette.defaultFg != null && palette.defaultFg.kind != TerminalColor.Kind.DEFAULT_FG
            ? resolveColor(palette, palette.defaultFg) : 0xFFFFFFFF;
      case DEFAULT_BG:
        return palette.defaultBg != null && palette.defaultBg.kind != TerminalColor.Kind.DEFAULT_BG
            ? resolveColor(palette, palette.defaultBg) : 0xFF000000;
      case CURSOR:
        return palette.cursorColor != null && palette.cursorColor.kind != TerminalColor.Kind.CURSOR
            ? resolveColor(palette, palette.cursorColor) : 0xFFFFFFFF;
      case INDEXED: return resolveIndexedColor(palette, color.index);
      default: return 0xFF000000;
    }
  }

  private static int resolveIndexedColor(TerminalPalette palette, int index) {
    Integer override = palette.indexedColors.get(index);
    return override != null ? 0xFF000000 | override : TerminalVisualRules.ansiColor(index);
  }

  /** Shared geometry for drawing, hit-testing and selection handles. */
  static float contentTopY(int viewportHeight, int historyRows, int screenRows,
                           float lineHeight, float topInset, float scrollOffsetPixels) {
    float usableHeight = Math.max(0, viewportHeight - topInset);
    float contentHeight = (historyRows + screenRows) * lineHeight;
    // 上界按"首条历史行贴顶"锚定：滚到顶时 contentTopY == topInset，首行完整可见。
    // 旧公式 contentHeight - usableHeight 是底部锚定，行数 floor 取整的余数会让
    // 首行停在视口顶边之外，被裁掉半行。内容不足一屏时不允许滚动。
    float maxOffset = contentHeight > usableHeight ? historyRows * lineHeight : 0f;
    float offset = Math.max(0, Math.min(scrollOffsetPixels, maxOffset));
    return topInset + offset - historyRows * lineHeight;
  }

  static float screenTopY(int viewportHeight, int historyRows, int screenRows,
                          float lineHeight, float topInset, float scrollOffsetPixels) {
    return contentTopY(viewportHeight, historyRows, screenRows, lineHeight, topInset, scrollOffsetPixels)
        + historyRows * lineHeight;
  }

  static float contentTopY(int viewportHeight, int historyRows, int screenRows,
                           float lineHeight, float scrollOffsetPixels) {
    return contentTopY(viewportHeight, historyRows, screenRows, lineHeight, 0f, scrollOffsetPixels);
  }

  static float screenTopY(int viewportHeight, int historyRows, int screenRows,
                          float lineHeight, float scrollOffsetPixels) {
    return screenTopY(viewportHeight, historyRows, screenRows, lineHeight, 0f, scrollOffsetPixels);
  }
}
