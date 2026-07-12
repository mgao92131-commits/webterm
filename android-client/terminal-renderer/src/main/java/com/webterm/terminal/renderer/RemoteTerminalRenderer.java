package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.TerminalStyle;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.model.ScreenSnapshot;

import java.util.Map;
import java.util.NavigableMap;

/**
 * Go 权威屏幕投影的 Canvas renderer。
 * 视觉规则与旧 com.termux.view.TerminalRenderer 对齐，状态则只来自 RemoteTerminalModel。
 */
public final class RemoteTerminalRenderer {

  static final int SELECTION_OVERLAY = 0x665B92F3;

  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bgPaint = new Paint();
  private final Paint selectionPaint = new Paint();

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

  public void render(@NonNull Canvas canvas, @NonNull RemoteTerminalModel model,
                     @NonNull TerminalViewportState viewport) {
    TerminalLine[] screen = model.screen();
    if (screen == null || lineHeight <= 0 || cellWidth <= 0) return;

    // Full-screen TUIs run on the alternate buffer. Its main-buffer scrollback
    // is not part of the current canvas and must never be composited underneath.
    NavigableMap<Long, TerminalLine> history = model.activeBuffer == ScreenSnapshot.BufferKind.ALTERNATE
        ? new java.util.TreeMap<>() : model.historyCache();
    int screenRows = screen.length;
    int historyRows = history.size();
    float scrollOffset = viewport.followTail ? 0 : viewport.scrollOffsetPixels;
    float screenTopY = screenTopY(canvas.getHeight(), historyRows, screenRows, lineHeight,
        getTopInset(), scrollOffset);

    TerminalPalette palette = model.palette();
    Map<Integer, TerminalStyle> styles = model.styles();
    int canvasBackground = resolveColor(palette.reverseVideo ? palette.defaultFg : palette.defaultBg);
    canvas.drawColor(canvasBackground);

    TerminalSelection selection = viewport.selection;
    TerminalCursor cursor = model.cursor();
    boolean cursorVisible = viewport.followTail && cursor.visible
        && (!cursor.blink || ((SystemClock.uptimeMillis() / 500L) & 1L) == 0L);

    for (int row = 0; row < screenRows; row++) {
      float y = screenTopY + row * lineHeight;
      if (y + lineHeight < 0 || y > canvas.getHeight()) continue;
      drawLine(canvas, model.columns, palette, styles, screen[row], y, 0, row, selection,
          cursor, cursorVisible, canvasBackground);
    }

    int historyIndex = 0;
    for (Map.Entry<Long, TerminalLine> entry : history.entrySet()) {
      float y = screenTopY - (historyRows - historyIndex) * lineHeight;
      if (y + lineHeight >= 0 && y <= canvas.getHeight()) {
        drawLine(canvas, model.columns, palette, styles, entry.getValue(), y, entry.getKey(), -1,
            selection, cursor, false, canvasBackground);
      }
      historyIndex++;
    }
  }

  private void drawLine(Canvas canvas, int columns, TerminalPalette palette,
                        Map<Integer, TerminalStyle> styles, TerminalLine line, float y,
                        long historyLineId, int screenRow, TerminalSelection selection,
                        TerminalCursor cursor, boolean cursorVisible, int canvasBackground) {
    if (line == null) return;
    for (int col = 0; col < line.length() && col < columns; col++) {
      TerminalCell cell = line.at(col);
      if (cell == null || cell.isSpacer()) continue;
      int columnWidth = cell.isWideStart() ? 2 : 1;
      boolean selected = isCellSelected(selection, historyLineId, screenRow, col, columnWidth);
      boolean insideCursor = cursorVisible && screenRow == cursor.row
          && (cursor.col == col || (columnWidth == 2 && cursor.col == col + 1));
      int codePoint = cell.text == null || cell.text.isEmpty() ? ' ' : cell.text.codePointAt(0);
      boolean preserveAspect = TerminalVisualRules.shouldPreserveGlyphAspect(codePoint, columnWidth,
          hasRightPadding(line, col, columnWidth, cell.styleId));
      drawCell(canvas, palette, styles, cell, col, y, selected, insideCursor, cursor,
          preserveAspect, canvasBackground);
    }
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

    int fg = resolveColor(fgColor);
    boolean bold = style != null && (style.bold() || style.blinkSlow() || style.blinkFast());
    if (bold && fgColor.kind == TerminalColor.Kind.INDEXED
        && fgColor.index >= 0 && fgColor.index < 8) {
      fg = TerminalVisualRules.ansiColor(fgColor.index + 8);
    }
    if (style != null && style.dim()) fg = TerminalVisualRules.dim(fg);
    int bg = resolveColor(bgColor);

    float x = col * cellWidth;
    float width = cell.isWideStart() ? cellWidth * 2 : cellWidth;
    if (bg != canvasBackground) {
      bgPaint.setColor(bg);
      canvas.drawRect(x, rowY, x + width, rowY + lineHeight, bgPaint);
    }

    if (insideCursor) {
      bgPaint.setColor(resolveColor(palette.cursorColor));
      if (cursor.shape == TerminalCursor.Shape.BAR) {
        canvas.drawRect(x, rowY, x + width / 4f, rowY + lineHeight, bgPaint);
      } else if (cursor.shape == TerminalCursor.Shape.UNDERLINE) {
        canvas.drawRect(x, rowY + lineHeight * 3f / 4f, x + width, rowY + lineHeight, bgPaint);
      } else {
        canvas.drawRect(x, rowY, x + width, rowY + lineHeight, bgPaint);
      }
    }

    textPaint.setColor(fg);
    textPaint.setFakeBoldText(bold);
    textPaint.setTextSkewX(style != null && style.italic() ? -0.35f : 0f);
    String text = cell.text;
    if (text == null || text.isEmpty()) text = " ";

    float expectedWidth = (cell.isWideStart() ? 2 : 1) * cellWidth;
    float measuredWidth = textPaint.measureText(text);
    boolean scaleGlyph = !preserveAspect && !text.equals(" ")
        && measuredWidth > 0 && Math.abs(measuredWidth - expectedWidth) > 0.01f;
    boolean savedMatrix = false;
    float drawX = x;
    if (scaleGlyph) {
      canvas.save();
      float scaleX = expectedWidth / measuredWidth;
      canvas.scale(scaleX, 1f);
      drawX = x / scaleX;
      savedMatrix = true;
    }
    if (style == null || !style.hidden()) {
      canvas.drawText(text, drawX, rowY + baselineOffset, textPaint);
    }
    if (savedMatrix) canvas.restore();

    if (style != null && (style.underline() || style.doubleUnderline())) {
      textPaint.setColor(style.underlineColor != null ? resolveColor(style.underlineColor) : fg);
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

  private boolean isCellSelected(TerminalSelection selection, long historyLineId, int screenRow,
                                 int col, int columnWidth) {
    if (selection == null) return false;
    TerminalSelection normalized = selection.normalized();
    TerminalSelection.Anchor cellStart = new TerminalSelection.Anchor(historyLineId, screenRow, col);
    TerminalSelection.Anchor cellEnd = new TerminalSelection.Anchor(historyLineId, screenRow,
        col + Math.max(1, columnWidth));
    return cellStart.compareTo(normalized.end) < 0 && cellEnd.compareTo(normalized.start) > 0;
  }

  static int resolveColor(TerminalColor color) {
    if (color == null) return 0xFF000000;
    switch (color.kind) {
      case RGB: return 0xFF000000 | color.rgb;
      case DEFAULT_FG: return 0xFFFFFFFF;
      case DEFAULT_BG: return 0xFF000000;
      case CURSOR: return 0xFFFFFFFF;
      case INDEXED: return TerminalVisualRules.ansiColor(color.index);
      default: return 0xFF000000;
    }
  }

  /** Shared geometry for drawing, hit-testing and selection handles. */
  static float contentTopY(int viewportHeight, int historyRows, int screenRows,
                           float lineHeight, float topInset, float scrollOffsetPixels) {
    float usableHeight = Math.max(0, viewportHeight - topInset);
    float contentHeight = (historyRows + screenRows) * lineHeight;
    float maxOffset = Math.max(0, contentHeight - usableHeight);
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
