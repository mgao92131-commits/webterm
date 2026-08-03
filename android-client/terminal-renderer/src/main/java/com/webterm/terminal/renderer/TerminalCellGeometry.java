package com.webterm.terminal.renderer;

/**
 * 终端 cell 的共享几何入口。
 *
 * <p>文字 origin 保留浮点坐标以维持现有字体 hinting；所有 cell 矩形边界统一通过
 * {@link #columnEdgePx(int)} 取整，避免相邻 cell 分别计算宽度后出现 1px 缝隙或越界。</p>
 */
final class TerminalCellGeometry {
  private float cellWidth;
  private int lineHeightPx;
  private float baselineOffset;
  private int topInsetPx;

  void update(float cellWidth, float lineHeight, float baselineOffset) {
    this.cellWidth = Math.max(0f, cellWidth);
    this.lineHeightPx = Math.max(0, Math.round(lineHeight));
    this.baselineOffset = baselineOffset;
    this.topInsetPx = Math.max(0, Math.round(lineHeight - baselineOffset));
  }

  float cellWidth() {
    return cellWidth;
  }

  float textOriginX(int column) {
    return column * cellWidth;
  }

  int columnEdgePx(int column) {
    return Math.round(column * cellWidth);
  }

  int cellLeftPx(int column) {
    return columnEdgePx(column);
  }

  int cellRightPx(int column, int columnCount) {
    if (columnCount <= 0) return 0;
    int clampedColumn = Math.max(0, Math.min(column, columnCount - 1));
    return columnEdgePx(clampedColumn + 1);
  }

  int cellWidthPx(int column, int columnCount) {
    return cellRightPx(column, columnCount) - cellLeftPx(column);
  }

  int spanRightPx(int column, int columnSpan, int columnCount) {
    if (columnCount <= 0) return 0;
    int rightColumn = Math.max(0, Math.min(column + Math.max(1, columnSpan), columnCount));
    return columnEdgePx(rightColumn);
  }

  int contentWidthPx(int columnCount) {
    return Math.max(0, columnEdgePx(Math.max(0, columnCount)));
  }

  /** Returns the physical column at x; an exact edge belongs to the column on its right. */
  int columnAt(float x, int columns) {
    if (columns <= 0 || cellWidth <= 0f || x <= 0f) return 0;
    int contentWidth = contentWidthPx(columns);
    if (x >= contentWidth) return columns - 1;

    int low = 0;
    int high = columns;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (columnEdgePx(middle) <= x) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return Math.max(0, Math.min(columns - 1, low - 1));
  }

  int columnsThatFit(int availableWidth) {
    if (availableWidth <= 0 || cellWidth <= 0f) return 0;
    int high = Math.max(1, (int) Math.ceil(availableWidth / cellWidth) + 1);
    int low = 0;
    while (low < high) {
      int middle = (low + high + 1) >>> 1;
      if (columnEdgePx(middle) <= availableWidth) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return low;
  }

  int lineHeightPx() {
    return lineHeightPx;
  }

  int topInsetPx() {
    return topInsetPx;
  }

  float baselineOffset() {
    return baselineOffset;
  }

  int rowsThatFit(int availableHeight) {
    if (availableHeight <= topInsetPx || lineHeightPx <= 0) return 0;
    return (availableHeight - topInsetPx) / lineHeightPx;
  }

  /** Row zero starts at topInset; coordinates above it stay on row zero. */
  int rowAt(float y, int rows) {
    if (rows <= 0 || lineHeightPx <= 0 || y < topInsetPx) return 0;
    int row = (int) Math.floor((y - topInsetPx) / lineHeightPx);
    return Math.max(0, Math.min(rows - 1, row));
  }

  int rowAt(float y, float rowTop, int rows) {
    if (rows <= 0 || lineHeightPx <= 0 || y < rowTop) return 0;
    int row = (int) Math.floor((y - rowTop) / lineHeightPx);
    return Math.max(0, Math.min(rows - 1, row));
  }
}
