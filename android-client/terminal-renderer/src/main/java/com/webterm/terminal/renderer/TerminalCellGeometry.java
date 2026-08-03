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
  private int rowNodeLeftBleedPx = 2;
  private int rowNodeRightBleedPx = 2;
  private int rowNodeTopBleedPx = 1;
  private int rowNodeBottomBleedPx = 1;

  void update(float cellWidth, float lineHeight, float baselineOffset) {
    this.cellWidth = Math.max(0f, cellWidth);
    this.lineHeightPx = Math.max(0, Math.round(lineHeight));
    this.baselineOffset = baselineOffset;
    this.topInsetPx = Math.max(0, Math.round(lineHeight - baselineOffset));
    this.rowNodeLeftBleedPx = 2;
    this.rowNodeRightBleedPx = 2;
    this.rowNodeTopBleedPx = 1;
    this.rowNodeBottomBleedPx = 1;
  }

  void updateRowNodeBleed(PaintMetrics metrics) {
    if (metrics == null) return;
    float skewBleed = Math.abs(metrics.textSkewX) * metrics.textSizePx;
    rowNodeLeftBleedPx = clampBleed(2 + (int) Math.ceil(skewBleed * 0.5f));
    rowNodeRightBleedPx = clampBleed(2 + (int) Math.ceil(skewBleed));
    float topOverhang = Math.max(0f, -(baselineOffset + metrics.fontTop));
    float bottomOverhang = Math.max(0f,
        baselineOffset + metrics.fontBottom - lineHeightPx);
    rowNodeTopBleedPx = clampBleed(1 + (int) Math.ceil(topOverhang));
    rowNodeBottomBleedPx = clampBleed(1 + (int) Math.ceil(bottomOverhang));
  }

  private static int clampBleed(int value) {
    return Math.max(1, Math.min(8, value));
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

  int rowNodeLeftBleedPx() { return rowNodeLeftBleedPx; }
  int rowNodeRightBleedPx() { return rowNodeRightBleedPx; }
  int rowNodeTopBleedPx() { return rowNodeTopBleedPx; }
  int rowNodeBottomBleedPx() { return rowNodeBottomBleedPx; }

  /** Paint metrics are kept as a tiny value object so geometry remains independent of Paint. */
  static final class PaintMetrics {
    final float fontTop;
    final float fontBottom;
    final float textSkewX;
    final float textSizePx;

    PaintMetrics(float fontTop, float fontBottom, float textSkewX, float textSizePx) {
      this.fontTop = fontTop;
      this.fontBottom = fontBottom;
      this.textSkewX = textSkewX;
      this.textSizePx = textSizePx;
    }
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
