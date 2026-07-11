package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

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

import java.util.Map;
import java.util.NavigableMap;

/**
 * 纯绘制器。读取 terminal-model 渲染为 Canvas，支持屏幕和历史滚动、选择高亮。
 */
public final class RemoteTerminalRenderer {

  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bgPaint = new Paint();
  private final Paint selectionPaint = new Paint();
  private final Paint cursorPaint = new Paint();

  private float cellWidth;
  private float lineHeight;
  private float baselineOffset;
  private int textSizeSp = 14;
  @Nullable private Typeface typeface = Typeface.MONOSPACE;

  public RemoteTerminalRenderer() {
    selectionPaint.setColor(0xFF336699);
    selectionPaint.setAlpha(128);
    cursorPaint.setStyle(Paint.Style.STROKE);
    cursorPaint.setStrokeWidth(2);
    applyFont();
  }

  public void setFontMetrics(float cellWidth, float lineHeight, float baselineOffset) {
    this.cellWidth = cellWidth;
    this.lineHeight = lineHeight;
    this.baselineOffset = baselineOffset;
  }

  public float getCellWidth() {
    return cellWidth;
  }

  public float getLineHeight() {
    return lineHeight;
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
    if (screen == null || lineHeight <= 0) return;

    int screenRows = screen.length;
    NavigableMap<Long, TerminalLine> history = model.historyCache();
    int historyRows = history.size();
    int totalRows = historyRows + screenRows;
    float contentHeight = totalRows * lineHeight;
    float viewHeight = canvas.getHeight();

    float scrollOffset = viewport.followTail
        ? 0
        : Math.max(0, Math.min(viewport.scrollOffsetPixels, contentHeight - viewHeight));
    float bottomY = contentHeight - scrollOffset;

    TerminalPalette palette = model.palette();
    Map<Integer, TerminalStyle> styles = model.styles();
    canvas.drawColor(resolveColor(palette.defaultBg));

    TerminalSelection selection = viewport.selection;

    // 从底部向上绘制：先屏幕，再历史
    float screenTopY = bottomY - screenRows * lineHeight;
    for (int row = 0; row < screenRows; row++) {
      float y = screenTopY + row * lineHeight;
      if (y + lineHeight < 0 || y > viewHeight) continue;
      drawLine(canvas, model, palette, styles, screen[row], y, 0, row, selection);
    }

    int historyIndex = 0;
    for (Map.Entry<Long, TerminalLine> entry : history.entrySet()) {
      float y = screenTopY - (historyRows - historyIndex) * lineHeight;
      if (y + lineHeight < 0 || y > viewHeight) {
        historyIndex++;
        continue;
      }
      drawLine(canvas, model, palette, styles, entry.getValue(), y, entry.getKey(), -1, selection);
      historyIndex++;
    }

    TerminalCursor cursor = model.cursor();
    if (!viewport.followTail || !cursor.visible) return;
    if (cursor.row >= 0 && cursor.row < screenRows) {
      drawCursor(canvas, cursor, palette, screenTopY + cursor.row * lineHeight);
    }
  }

  private void drawLine(Canvas canvas, RemoteTerminalModel model, TerminalPalette palette,
                        Map<Integer, TerminalStyle> styles, TerminalLine line,
                        float y, long historyLineId, int screenRow,
                        TerminalSelection selection) {
    if (line == null) return;
    int cols = model.columns;
    for (int col = 0; col < line.length() && col < cols; col++) {
      TerminalCell cell = line.at(col);
      if (cell == null || cell.isSpacer()) continue;
      boolean selected = isCellSelected(selection, historyLineId, screenRow, col);
      drawCell(canvas, palette, styles, cell, col, y, selected);
    }
  }

  private void drawCell(Canvas canvas, TerminalPalette palette,
                        Map<Integer, TerminalStyle> styles, TerminalCell cell,
                        int col, float rowY, boolean selected) {
    float x = col * cellWidth;
    TerminalStyle style = styles.get(cell.styleId);
    TerminalColor fgColor = style != null ? style.fg : palette.defaultFg;
    TerminalColor bgColor = style != null ? style.bg : palette.defaultBg;
    if (palette.reverseVideo ^ (style != null && style.reverse())) {
      TerminalColor swap = fgColor; fgColor = bgColor; bgColor = swap;
    }
    int fg = resolveColor(fgColor);
    int bg = resolveColor(bgColor);

    bgPaint.setColor(bg);
    float width = cell.isWideStart() ? cellWidth * 2 : cellWidth;
    canvas.drawRect(x, rowY, x + width, rowY + lineHeight, bgPaint);

    if (selected) {
      canvas.drawRect(x, rowY, x + width, rowY + lineHeight, selectionPaint);
    }

    textPaint.setColor(fg);
    textPaint.setFakeBoldText(style != null && style.bold());
    textPaint.setTextSkewX(style != null && style.italic() ? -0.25f : 0f);
    textPaint.setAlpha(style != null && style.dim() ? 160 : 255);
    String text = cell.text;
    if (text == null || text.isEmpty()) text = " ";
    if (style == null || !style.hidden()) {
      canvas.drawText(text, x, rowY + baselineOffset, textPaint);
    }
    textPaint.setAlpha(255);
    if (style != null && (style.underline() || style.doubleUnderline())) {
      textPaint.setColor(style.underlineColor != null ? resolveColor(style.underlineColor) : fg);
      float underlineY = rowY + lineHeight - 2;
      canvas.drawLine(x, underlineY, x + width, underlineY, textPaint);
      if (style.doubleUnderline()) canvas.drawLine(x, underlineY - 3, x + width, underlineY - 3, textPaint);
    }
    if (style != null && style.strike()) {
      canvas.drawLine(x, rowY + lineHeight * 0.52f, x + width, rowY + lineHeight * 0.52f, textPaint);
    }
  }

  private boolean isCellSelected(TerminalSelection selection, long historyLineId, int screenRow, int col) {
    if (selection == null) return false;
    TerminalSelection normalized = selection.normalized();
    TerminalSelection.Anchor start = normalized.start;
    TerminalSelection.Anchor end = normalized.end;
    TerminalSelection.Anchor cellStart = new TerminalSelection.Anchor(historyLineId, screenRow, col);
    TerminalSelection.Anchor cellEnd = new TerminalSelection.Anchor(historyLineId, screenRow, col + 1);
    return cellStart.compareTo(end) < 0 && cellEnd.compareTo(start) > 0;
  }

  private void drawCursor(Canvas canvas, TerminalCursor cursor, TerminalPalette palette, float rowY) {
    float x = cursor.col * cellWidth;
    cursorPaint.setColor(resolveColor(palette.cursorColor));
    switch (cursor.shape) {
      case BAR:
        canvas.drawLine(x + 1, rowY, x + 1, rowY + lineHeight, cursorPaint);
        break;
      case UNDERLINE:
        canvas.drawLine(x, rowY + lineHeight - 1, x + cellWidth, rowY + lineHeight - 1, cursorPaint);
        break;
      case BLOCK:
      default:
        canvas.drawRect(x, rowY, x + cellWidth, rowY + lineHeight, cursorPaint);
    }
  }

  static int resolveColor(TerminalColor color) {
    if (color == null) return 0xFF000000;
    switch (color.kind) {
      case RGB: return 0xFF000000 | color.rgb;
      case DEFAULT_FG: return 0xFFFFFFFF;
      case DEFAULT_BG: return 0xFF000000;
      case CURSOR: return 0xFFFFFFFF;
      case INDEXED: return ansiColor(color.index);
      default: return 0xFF000000;
    }
  }

  private static int ansiColor(int index) {
    final int[] base = {0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF};
    int i = Math.max(0, Math.min(255, index));
    if (i < 16) return 0xFF000000 | base[i];
    if (i < 232) {
      int n = i - 16;
      int[] level = {0, 95, 135, 175, 215, 255};
      return 0xFF000000 | (level[n / 36] << 16) | (level[(n / 6) % 6] << 8) | level[n % 6];
    }
    int gray = 8 + (i - 232) * 10;
    return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
  }
}
