package com.webterm.terminal.model;

import java.util.BitSet;

/**
 * 一个尚未交给 Canvas 的累计渲染脏区。
 *
 * <p>实例只在 {@link RemoteTerminalModel} 的同步边界内写入；经
 * {@link RemoteTerminalModel#consumeRenderUpdate()} 交换出去后不再修改，因而可安全地
 * 由主线程读取。BitSet 采用不可变约定，避免每个 Patch 为脏行额外分配集合。</p>
 */
public final class RenderDirtyState {
  public boolean fullInvalidate;
  public final BitSet changedScreenRows = new BitSet();
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
    return !fullInvalidate && changedScreenRows.isEmpty() && !historyChanged && !geometryChanged
        && !cursorChanged && !paletteChanged && !stylesChanged && !linksChanged && !modesChanged
        && !activeBufferChanged;
  }

  void merge(boolean fullInvalidate, BitSet changedRows, boolean historyChanged,
             boolean geometryChanged, boolean cursorChanged, int previousCursorRow,
             int currentCursorRow, boolean paletteChanged, boolean stylesChanged,
             boolean linksChanged, boolean modesChanged, boolean activeBufferChanged) {
    this.fullInvalidate |= fullInvalidate;
    if (changedRows != null) this.changedScreenRows.or(changedRows);
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
}
