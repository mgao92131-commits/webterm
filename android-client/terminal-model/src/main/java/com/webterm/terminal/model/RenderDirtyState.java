package com.webterm.terminal.model;

import java.util.BitSet;

/**
 * 一个尚未交给 Canvas 的累计渲染脏区。
 *
 * <p>实例只在 {@link RemoteTerminalModel} 的同步边界内写入；经
 * {@link RemoteTerminalModel#consumeRenderUpdate()} 交换出去后不再修改，因而可安全地
 * 由主线程读取。BitSet 采用不可变约定，避免每个 Patch 为脏行额外分配集合。</p>
 *
 * <p>新增屏幕整体滚动语义：{@link #screenScrollRows} 表示实时屏幕相对上一帧的位移
 * （正数向上滚动），{@link #exposedScreenRows} 记录滚动后新暴露、必须重新录制的行。
 * 单纯位置移动的旧行不再全部加入 {@link #changedScreenRows}，以便 View 层复用
 * RenderNode 行缓存。</p>
 */
public final class RenderDirtyState {
  public boolean fullInvalidate;
  public final BitSet changedScreenRows = new BitSet();
  /** 屏幕整体滚动行数；正数向上，负数向下，0 表示无整体滚动。 */
  public int screenScrollRows;
  /** 滚动后新暴露、需要重新录制的实时屏幕行（逻辑行号）。 */
  public final BitSet exposedScreenRows = new BitSet();
  public boolean historyChanged;
  public boolean geometryChanged;
  public boolean cursorChanged;
  public int previousCursorRow = -1;
  public int currentCursorRow = -1;
  public boolean paletteChanged;
  public boolean stylesChanged;
  public boolean linksChanged;
  public boolean modesChanged;
  public boolean activeBufferChanged;

  public boolean isEmpty() {
    return !fullInvalidate && changedScreenRows.isEmpty() && screenScrollRows == 0
        && exposedScreenRows.isEmpty() && !historyChanged && !geometryChanged
        && !cursorChanged && !paletteChanged && !stylesChanged && !linksChanged && !modesChanged
        && !activeBufferChanged;
  }

  void merge(boolean fullInvalidate, BitSet changedRows, int screenScrollRows, BitSet exposedRows,
             int rowCount, boolean historyChanged, boolean geometryChanged, boolean cursorChanged,
             int previousCursorRow, int currentCursorRow, boolean paletteChanged,
             boolean stylesChanged, boolean linksChanged, boolean modesChanged,
             boolean activeBufferChanged) {
    boolean structuralChange = this.fullInvalidate || fullInvalidate
        || this.geometryChanged || geometryChanged
        || this.activeBufferChanged || activeBufferChanged;
    boolean oppositeScroll = !structuralChange && this.screenScrollRows != 0 && screenScrollRows != 0
        && (this.screenScrollRows > 0) != (screenScrollRows > 0);
    boolean contentAfterScroll = !structuralChange && this.screenScrollRows != 0 && screenScrollRows == 0
        && (changedRows != null && !changedRows.isEmpty());
    boolean scrollAfterContent = !structuralChange && this.screenScrollRows == 0 && screenScrollRows != 0
        && !this.changedScreenRows.isEmpty();

    if (structuralChange || oppositeScroll || contentAfterScroll || scrollAfterContent) {
      // 无法安全合并滚动或位置缓存已失效：退化到整屏重绘，清空滚动语义。
      this.fullInvalidate = true;
      this.screenScrollRows = 0;
      this.exposedScreenRows.clear();
      if (changedRows != null) this.changedScreenRows.or(changedRows);
    } else if (screenScrollRows != 0) {
      int newScroll = this.screenScrollRows + screenScrollRows;
      if (newScroll == 0) {
        // 往返滚动导致缓存位置无法对应，退化到整屏重绘。
        this.fullInvalidate = true;
        this.screenScrollRows = 0;
        this.exposedScreenRows.clear();
        if (changedRows != null) this.changedScreenRows.or(changedRows);
      } else {
        // 同一方向连续滚动：把已累计的脏行按新滚动平移后，再合并本次暴露行。
        shiftRows(-screenScrollRows, rowCount);
        this.screenScrollRows = newScroll;
        if (exposedRows != null) this.exposedScreenRows.or(exposedRows);
        if (changedRows != null) this.changedScreenRows.or(changedRows);
      }
    } else {
      if (changedRows != null) this.changedScreenRows.or(changedRows);
    }

    this.historyChanged |= historyChanged;
    this.geometryChanged |= geometryChanged;
    this.cursorChanged |= cursorChanged;
    // 第一处旧光标和最后一处新光标都必须被重画。
    if (this.previousCursorRow < 0 && previousCursorRow >= 0) {
      this.previousCursorRow = previousCursorRow;
    }
    if (currentCursorRow >= 0) this.currentCursorRow = currentCursorRow;
    this.paletteChanged |= paletteChanged;
    this.stylesChanged |= stylesChanged;
    this.linksChanged |= linksChanged;
    this.modesChanged |= modesChanged;
    this.activeBufferChanged |= activeBufferChanged;
  }

  /**
   * 将已累计的 {@link #changedScreenRows} 与 {@link #exposedScreenRows} 按 delta 平移。
   * delta 为正表示向更大行号移动（屏幕向下滚动后，旧内容落到更下方）。
   * 光标行号随内容一起平移并钳制在有效范围内，保持与最终屏幕 layout 一致。
   */
  private void shiftRows(int delta, int rowCount) {
    if (rowCount <= 0 || delta == 0) return;
    BitSet shiftedChanged = shiftBitSet(changedScreenRows, delta, rowCount);
    BitSet shiftedExposed = shiftBitSet(exposedScreenRows, delta, rowCount);
    changedScreenRows.clear();
    changedScreenRows.or(shiftedChanged);
    exposedScreenRows.clear();
    exposedScreenRows.or(shiftedExposed);
    previousCursorRow = shiftCursorRow(previousCursorRow, delta, rowCount);
    currentCursorRow = shiftCursorRow(currentCursorRow, delta, rowCount);
  }

  /** 平移光标行号；越界时钳制到屏幕边缘（保留一次边缘重画，绝不丢失）。 */
  private static int shiftCursorRow(int row, int delta, int rowCount) {
    if (row < 0) return row;
    int shifted = row + delta;
    if (shifted < 0) return 0;
    if (shifted >= rowCount) return rowCount - 1;
    return shifted;
  }

  private static BitSet shiftBitSet(BitSet source, int delta, int rowCount) {
    BitSet result = new BitSet(rowCount);
    if (delta > 0) {
      for (int i = source.nextSetBit(0); i >= 0; i = source.nextSetBit(i + 1)) {
        int ni = i + delta;
        if (ni >= 0 && ni < rowCount) result.set(ni);
      }
    } else {
      int d = -delta;
      for (int i = source.nextSetBit(d); i >= 0; i = source.nextSetBit(i + 1)) {
        int ni = i - d;
        if (ni >= 0 && ni < rowCount) result.set(ni);
      }
    }
    return result;
  }
}
