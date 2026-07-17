package com.webterm.terminal.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 模型变更摘要。用于 renderer 局部 invalidate。
 *
 * <p>历史增长按方向区分：{@link #tailAppendedLines} 是实时输出滚入历史（追加在底部），
 * {@link #historyPrependedLines} 是顶部历史分页（插入在已有行上方）。两者对视口补偿的
 * 语义不同，不能混用：非 follow-tail 时只有 tail append 需要增加 offset 钉住可见内容；
 * prepend 在底部锚定几何下同时推高 historyRows 与旧行索引，旧行 Y 坐标天然不变。</p>
 */
public final class ModelChange {
  public final boolean fullInvalidate;
  public final Set<Integer> changedScreenRows;
  public final boolean historyChanged;
  public final boolean cursorChanged;
  public final boolean modesChanged;
  public final boolean titleChanged;
  /** 实时输出滚入历史的新行数（追加在尾部）。 */
  public final int tailAppendedLines;
  /** 顶部历史分页插入的新行数。 */
  public final int historyPrependedLines;
  /** True when a snapshot changes the terminal instance or grid geometry. */
  public final boolean geometryChanged;
  /** Palette changes affect every cell, including cells whose text did not change. */
  public final boolean paletteChanged;
  /** A newly published style can affect any changed cell that references it. */
  public final boolean stylesChanged;
  /** Hyperlink dictionary changes are kept conservative until link rendering is isolated. */
  public final boolean linksChanged;
  /** Main/alternate changes always replace the visible terminal composition. */
  public final boolean activeBufferChanged;
  /** CWD is not painted by the terminal Canvas, but listeners must retain its change. */
  public final boolean workingDirectoryChanged;
  /** Cursor dirty rows allow a safe partial redraw of both the old and new cursor cell. */
  public final int previousCursorRow;
  public final int currentCursorRow;

  public ModelChange(boolean fullInvalidate, Set<Integer> changedScreenRows,
                     boolean historyChanged, boolean cursorChanged,
                     boolean modesChanged, boolean titleChanged) {
    this(fullInvalidate, changedScreenRows, historyChanged, cursorChanged,
        modesChanged, titleChanged, 0, 0);
  }

  public ModelChange(boolean fullInvalidate, Set<Integer> changedScreenRows,
                     boolean historyChanged, boolean cursorChanged,
                     boolean modesChanged, boolean titleChanged,
                     int tailAppendedLines, int historyPrependedLines) {
    this(fullInvalidate, changedScreenRows, historyChanged, cursorChanged,
        modesChanged, titleChanged, tailAppendedLines, historyPrependedLines, false);
  }

  public ModelChange(boolean fullInvalidate, Set<Integer> changedScreenRows,
                     boolean historyChanged, boolean cursorChanged,
                     boolean modesChanged, boolean titleChanged,
                     int tailAppendedLines, int historyPrependedLines,
                     boolean geometryChanged) {
    this(fullInvalidate, changedScreenRows, historyChanged, cursorChanged, modesChanged,
        titleChanged, tailAppendedLines, historyPrependedLines, geometryChanged,
        false, false, false, false, false, -1, -1);
  }

  public ModelChange(boolean fullInvalidate, Set<Integer> changedScreenRows,
                     boolean historyChanged, boolean cursorChanged,
                     boolean modesChanged, boolean titleChanged,
                     int tailAppendedLines, int historyPrependedLines,
                     boolean geometryChanged, boolean paletteChanged,
                     boolean stylesChanged, boolean linksChanged, boolean activeBufferChanged,
                     boolean workingDirectoryChanged, int previousCursorRow,
                     int currentCursorRow) {
    this.fullInvalidate = fullInvalidate;
    this.changedScreenRows = changedScreenRows != null
        ? Collections.unmodifiableSet(changedScreenRows)
        : Collections.emptySet();
    this.historyChanged = historyChanged;
    this.cursorChanged = cursorChanged;
    this.modesChanged = modesChanged;
    this.titleChanged = titleChanged;
    this.tailAppendedLines = Math.max(0, tailAppendedLines);
    this.historyPrependedLines = Math.max(0, historyPrependedLines);
    this.geometryChanged = geometryChanged;
    this.paletteChanged = paletteChanged;
    this.stylesChanged = stylesChanged;
    this.linksChanged = linksChanged;
    this.activeBufferChanged = activeBufferChanged;
    this.workingDirectoryChanged = workingDirectoryChanged;
    this.previousCursorRow = previousCursorRow;
    this.currentCursorRow = currentCursorRow;
  }

  public static ModelChange full(boolean geometryChanged) {
    return new ModelChange(true, null, true, true, true, true, 0, 0, geometryChanged,
        true, true, true, true, true, -1, -1);
  }

  public static ModelChange none() {
    return new ModelChange(false, null, false, false, false, false);
  }

  /**
   * Combines UI-observable model changes without retaining an intermediate model snapshot.
   * The model itself has already applied every protocol patch in revision order.
   */
  public ModelChange merge(ModelChange newer) {
    if (newer == null) return this;
    Set<Integer> rows = new HashSet<>(changedScreenRows);
    rows.addAll(newer.changedScreenRows);
    int previousRow = previousCursorRow >= 0 ? previousCursorRow : newer.previousCursorRow;
    int currentRow = newer.currentCursorRow >= 0 ? newer.currentCursorRow : currentCursorRow;
    return new ModelChange(
        fullInvalidate || newer.fullInvalidate,
        rows,
        historyChanged || newer.historyChanged,
        cursorChanged || newer.cursorChanged,
        modesChanged || newer.modesChanged,
        titleChanged || newer.titleChanged,
        saturatingAdd(tailAppendedLines, newer.tailAppendedLines),
        saturatingAdd(historyPrependedLines, newer.historyPrependedLines),
        geometryChanged || newer.geometryChanged,
        paletteChanged || newer.paletteChanged,
        stylesChanged || newer.stylesChanged,
        linksChanged || newer.linksChanged,
        activeBufferChanged || newer.activeBufferChanged,
        workingDirectoryChanged || newer.workingDirectoryChanged,
        previousRow,
        currentRow);
  }

  private static int saturatingAdd(int first, int second) {
    long result = (long) first + second;
    return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
  }
}
