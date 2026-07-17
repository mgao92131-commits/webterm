package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalHistorySnapshot;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.TerminalStyle;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.model.ScreenSnapshot;

import java.util.Map;
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

  /** Font-metric space above the first terminal cell, matching Termux. */
  public float getTopInset() {
    return Math.max(0f, lineHeight - baselineOffset);
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
                     @NonNull TerminalViewportState viewport) {
    long renderStartedNanos = System.nanoTime();
    try {
    TerminalLine[] screen = model.screen;
    if (screen == null || lineHeight <= 0 || cellWidth <= 0) return;

    // Full-screen TUIs run on the alternate buffer. Its main-buffer scrollback
    // is not part of the current canvas and must never be composited underneath.
    TerminalHistorySnapshot history = model.activeBuffer == ScreenSnapshot.BufferKind.ALTERNATE
        ? TerminalHistorySnapshot.empty() : model.history;
    int screenRows = screen.length;
    int historyRows = history.size();
    float scrollOffset = viewport.followTail ? 0 : viewport.scrollOffsetPixels;
    float screenTopY = screenTopY(canvas.getHeight(), historyRows, screenRows, lineHeight,
        getTopInset(), scrollOffset);

    TerminalPalette palette = model.palette;
    Map<Integer, TerminalStyle> styles = model.styles;
    int canvasBackground = resolveColor(palette,
        palette.reverseVideo ? palette.defaultFg : palette.defaultBg);
    canvas.drawColor(canvasBackground);

    TerminalSelection selection = viewport.selection;
    TerminalSelection normalizedSelection = selection != null ? selection.normalized() : null;
    TerminalCursor cursor = model.cursor;
    boolean cursorVisible = viewport.followTail && cursor.visible
        && (!cursor.blink || ((SystemClock.uptimeMillis() / 500L) & 1L) == 0L);

    Rect clip = new Rect();
    if (!canvas.getClipBounds(clip)) clip.set(0, 0, canvas.getWidth(), canvas.getHeight());
    int[] screenRange = rowRangeIntersecting(clip.top, clip.bottom, screenTopY, lineHeight,
        screenRows);
    for (int row = screenRange[0]; row < screenRange[1]; row++) {
      float y = screenTopY + row * lineHeight;
      drawLine(canvas, model.columns, palette, styles, screen[row], y, 0, row, normalizedSelection,
          cursor, cursorVisible, canvasBackground);
    }

    float historyTopY = screenTopY - historyRows * lineHeight;
    int[] historyRange = rowRangeIntersecting(clip.top, clip.bottom, historyTopY, lineHeight,
        historyRows);
    for (int historyIndex = historyRange[0]; historyIndex < historyRange[1]; historyIndex++) {
      TerminalLine line = history.lineAt(historyIndex);
      float y = historyTopY + historyIndex * lineHeight;
      drawLine(canvas, model.columns, palette, styles, line, y, line.id, -1,
          normalizedSelection, cursor, false, canvasBackground);
    }
    } finally {
      TerminalRenderMetrics.renderDuration(System.nanoTime() - renderStartedNanos);
    }
  }

  /** Half-open row range whose cells can affect a Canvas clip, including one anti-aliasing guard. */
  static int[] rowRangeIntersecting(int clipTop, int clipBottom, float rowsTop,
                                    float rowHeight, int rowCount) {
    if (rowCount <= 0 || rowHeight <= 0f || clipBottom <= clipTop) return new int[] {0, 0};
    double firstRaw = Math.floor((clipTop - rowsTop) / rowHeight);
    double lastRaw = Math.ceil((clipBottom - rowsTop) / rowHeight);
    int first = clampRow(firstRaw, rowCount);
    int last = clampRow(lastRaw, rowCount);
    first = Math.max(0, first - 1);
    last = Math.min(rowCount, last + 1);
    return new int[] {Math.min(first, last), Math.max(first, last)};
  }

  private static int clampRow(double value, int rowCount) {
    if (value <= 0d) return 0;
    if (value >= rowCount) return rowCount;
    return (int) value;
  }

  private void drawLine(Canvas canvas, int columns, TerminalPalette palette,
                        Map<Integer, TerminalStyle> styles, TerminalLine line, float y,
                        long historyLineId, int screenRow, TerminalSelection selection,
                        TerminalCursor cursor, boolean cursorVisible, int canvasBackground) {
    if (line == null) return;
    int lineLength = Math.min(line.length(), columns);
    for (int col = 0; col < lineLength; ) {
      TerminalCell cell = line.at(col);
      if (cell == null || cell.isSpacer()) {
        col++;
        continue;
      }

      // Keep Unicode/wide cells on the canonical path, but batch contiguous ASCII cells with
      // the same style. Selection and cursor boundaries deliberately split runs.
      if (startsBatchableAsciiRun(line, lineLength, selection, historyLineId, screenRow, col,
          cursor, cursorVisible)) {
        int runStart = col;
        int runStyleId = cell.styleId;
        plainAsciiRun.setLength(0);
        do {
          plainAsciiRun.append(line.at(col).text.charAt(0));
          col++;
        } while (col < lineLength && line.at(col).styleId == runStyleId
            && canBatchAscii(line.at(col), selection, historyLineId, screenRow, col, cursor,
                cursorVisible));
        if (drawAsciiRun(canvas, palette, styles.get(runStyleId), plainAsciiRun, runStart, y,
            canvasBackground)) {
          continue;
        }
        // A scaling requirement discovered after measuring the complete run uses the canonical
        // per-cell path so glyph hinting and placement remain pixel-identical.
        col = runStart;
      }
      int columnWidth = cell.isWideStart() ? 2 : 1;
      boolean selected = isCellSelected(selection, historyLineId, screenRow, col, columnWidth);
      boolean insideCursor = cursorVisible && screenRow == cursor.row
          && (cursor.col == col || (columnWidth == 2 && cursor.col == col + 1));
      int codePoint = cell.text == null || cell.text.isEmpty() ? ' ' : cell.text.codePointAt(0);
      boolean preserveAspect = TerminalVisualRules.shouldPreserveGlyphAspect(codePoint, columnWidth,
          hasRightPadding(line, col, columnWidth, cell.styleId));
      drawCell(canvas, palette, styles, cell, col, y, selected, insideCursor, cursor,
          preserveAspect, canvasBackground);
      col++;
    }
  }

  private static boolean canBatchAscii(TerminalCell cell, TerminalSelection selection,
                                       long historyLineId, int screenRow, int col,
                                       TerminalCursor cursor, boolean cursorVisible) {
    if (cell == null || cell.isSpacer() || cell.isWideStart()
        || cell.text == null || cell.text.length() != 1) return false;
    char c = cell.text.charAt(0);
    if (c < ' ' || c > '~') return false;
    if (isCellSelected(selection, historyLineId, screenRow, col, 1)) return false;
    return !cursorVisible || screenRow != cursor.row || cursor.col != col;
  }

  private static boolean startsBatchableAsciiRun(TerminalLine line, int lineLength,
                                                 TerminalSelection selection, long historyLineId,
                                                 int screenRow, int col, TerminalCursor cursor,
                                                 boolean cursorVisible) {
    if (col + 2 >= lineLength) return false;
    int styleId = line.at(col).styleId;
    for (int candidate = col; candidate < col + 3; candidate++) {
      TerminalCell cell = line.at(candidate);
      if (cell == null || cell.styleId != styleId
          || !canBatchAscii(cell, selection, historyLineId, screenRow,
          candidate, cursor, cursorVisible)) return false;
    }
    return true;
  }

  /** @return true if the run was drawn; false when glyph scaling requires the per-cell path. */
  private boolean drawAsciiRun(Canvas canvas, TerminalPalette palette,
                               @Nullable TerminalStyle style, CharSequence text, int startCol,
                               float rowY, int canvasBackground) {
    TerminalColor fgColor = style != null ? style.fg : palette.defaultFg;
    TerminalColor bgColor = style != null ? style.bg : palette.defaultBg;
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
      textPaint.setColor(style.underlineColor != null
          ? resolveColor(palette, style.underlineColor) : fg);
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

  private void drawCell(Canvas canvas, TerminalPalette palette, Map<Integer, TerminalStyle> styles,
                        TerminalCell cell, int col, float rowY, boolean selected,
                        boolean insideCursor, TerminalCursor cursor, boolean preserveAspect,
                        int canvasBackground) {
    TerminalStyle style = styles.get(cell.styleId);
    TerminalColor fgColor = style != null ? style.fg : palette.defaultFg;
    TerminalColor bgColor = style != null ? style.bg : palette.defaultBg;
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

    String text = cell.text;
    if (text == null || text.isEmpty()) text = " ";
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
      textPaint.setColor(style.underlineColor != null
          ? resolveColor(palette, style.underlineColor) : fg);
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

  private boolean hasRightPadding(TerminalLine line, int col, int width, int styleId) {
    int nextCol = col + width;
    if (nextCol >= line.length()) return false;
    TerminalCell next = line.at(nextCol);
    return next != null && !next.isSpacer() && next.styleId == styleId
        && (next.text == null || next.text.isEmpty() || " ".equals(next.text));
  }

  private static boolean isCellSelected(TerminalSelection selection, long historyLineId, int screenRow,
                                        int col, int columnWidth) {
    if (selection == null) return false;
    return compareSelectionPosition(historyLineId, screenRow, col, selection.end) < 0
        && compareSelectionPosition(historyLineId, screenRow, col + Math.max(1, columnWidth),
            selection.start) > 0;
  }

  private static int compareSelectionPosition(long historyLineId, int screenRow, int col,
                                              TerminalSelection.Anchor other) {
    if (historyLineId != 0 && other.historyLineId != 0) {
      int cmp = Long.compare(historyLineId, other.historyLineId);
      return cmp != 0 ? cmp : Integer.compare(col, other.col);
    }
    if (historyLineId != 0) return -1;
    if (other.historyLineId != 0) return 1;
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
