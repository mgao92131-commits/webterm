package com.webterm.terminal.model;

import java.util.BitSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个尚未交给 Canvas 的累计渲染脏区。
 *
 * <p>实例只在 {@link RemoteTerminalModel} 的同步边界内写入；经
 * {@link RemoteTerminalModel#consumeRenderUpdate()} 交换出去后不再修改，因而可安全地
 * 由主线程读取。BitSet 采用不可变约定，避免每个 Patch 为脏行额外分配集合。</p>
 *
 * <p>屏幕损伤三级：精确行 → Screen 区域（{@link #screenRegionInvalidate}）→ 整 View
 * （{@link #fullInvalidate}）。滚动采用坐标变换合并：正数表示内容向上移动，已累计脏行随
 * 内容平移，新 Commit 脏行已位于最终屏幕坐标直接合并。普通滚动与行写入混合不再升成
 * {@link #fullInvalidate}。</p>
 */
public final class RenderDirtyState {
  /** 超过此数量后退化为一次完整 history rebuild，避免脏区元数据无界增长。 */
  public static final int MAX_HISTORY_DIRTY_RANGES = 16;
  public boolean fullInvalidate;
  /**
   * 无法继续建立精确屏幕行映射时，只刷新实时 Screen 区域，不升到整 View。
   * 与 {@link #fullInvalidate} 互斥：结构变化优先 full；区域退化时 full 必须为 false。
   */
  public boolean screenRegionInvalidate;
  public final BitSet changedScreenRows = new BitSet();
  /** 屏幕整体滚动行数；正数向上，负数向下，0 表示无整体滚动。 */
  public int screenScrollRows;
  /** 滚动后新暴露、需要重新录制的实时屏幕行（逻辑行号）。 */
  public final BitSet exposedScreenRows = new BitSet();
  public boolean historyChanged;
  /** 空范围使用 from=1,to=0；historyChanged 但无范围时 View 必须安全退化 FULL。 */
  public long changedHistoryFromSeq = 1;
  public long changedHistoryToSeq = 0;
  /** extent/geometry 改变了历史逻辑位置，不能按单一 seq 范围局部失效。 */
  public boolean historyStructureChanged;
  /** 离散历史脏区过多时为 true；ContentAxis 应安全退化为 full rebuild。 */
  public boolean historyRangesOverflow;
  private final List<HistorySeqRange> changedHistoryRanges = new ArrayList<>();
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
    return !fullInvalidate && !screenRegionInvalidate && changedScreenRows.isEmpty()
        && screenScrollRows == 0 && exposedScreenRows.isEmpty() && !historyChanged
        && !geometryChanged && !cursorChanged && !paletteChanged && !stylesChanged
        && !linksChanged && !modesChanged && !activeBufferChanged;
  }

  /** 返回已合并的离散历史脏区；交换 publication 后该列表不再修改。 */
  public List<HistorySeqRange> historyRanges() {
    return Collections.unmodifiableList(changedHistoryRanges);
  }

  void merge(boolean fullInvalidate, BitSet changedRows, int screenScrollRows, BitSet exposedRows,
             int rowCount, boolean historyChanged, boolean geometryChanged, boolean cursorChanged,
             int previousCursorRow, int currentCursorRow, boolean paletteChanged,
             boolean stylesChanged, boolean linksChanged, boolean modesChanged,
             boolean activeBufferChanged) {
    boolean structuralChange = this.fullInvalidate || fullInvalidate
        || this.geometryChanged || geometryChanged
        || this.activeBufferChanged || activeBufferChanged;

    if (structuralChange) {
      // 全局结构变化：整 View 重绘，清空滚动与区域退化语义。
      this.fullInvalidate = true;
      this.screenRegionInvalidate = false;
      this.screenScrollRows = 0;
      this.exposedScreenRows.clear();
      if (changedRows != null) this.changedScreenRows.or(changedRows);
    } else if (this.screenRegionInvalidate) {
      // 已处于 Screen 区域退化：继续吸收后续损伤，不再尝试精确行映射。
    } else if (screenScrollRows != 0) {
      composeScroll(screenScrollRows, changedRows, exposedRows, rowCount);
    } else {
      // 净滚动可为 0，但上一份 Dirty 经滚动变换后留下的 exposedRows 仍有效；
      // mergeFrom 时只要传入 exposedRows 就必须吸收。
      if (changedRows != null) this.changedScreenRows.or(changedRows);
      if (exposedRows != null) this.exposedScreenRows.or(exposedRows);
    }

    this.historyChanged |= historyChanged;
    this.geometryChanged |= geometryChanged;
    this.cursorChanged |= cursorChanged;
    // 第一处旧光标和最后一处新光标都必须被重画；光标使用各 Commit 自身屏幕坐标，
    // 不随后续内容滚动平移。
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
   * 将新滚动量合并进累计损伤。正数滚动表示内容向上移动：已记录脏行随内容上移，
   * 新 Commit 脏行已位于最终坐标，直接 OR。净滚动可为零或反向，仍保留变换后的脏行。
   */
  private void composeScroll(
      int delta, BitSet changedRows, BitSet exposedRows, int rowCount) {
    if (rowCount <= 0) {
      markScreenRegionInvalidate();
      return;
    }
    long newScrollLong = (long) this.screenScrollRows + delta;
    if (newScrollLong > Integer.MAX_VALUE || newScrollLong < Integer.MIN_VALUE) {
      markScreenRegionInvalidate();
      return;
    }
    int newScroll = (int) newScrollLong;
    if (Math.abs((long) newScroll) >= rowCount) {
      markScreenRegionInvalidate();
      return;
    }
    shiftRows(-delta, rowCount);
    this.screenScrollRows = newScroll;
    if (exposedRows != null) this.exposedScreenRows.or(exposedRows);
    if (changedRows != null) this.changedScreenRows.or(changedRows);
  }

  /** Screen 区域安全退化：不清成 fullInvalidate，只刷新实时 Screen。 */
  private void markScreenRegionInvalidate() {
    this.fullInvalidate = false;
    this.screenRegionInvalidate = true;
    this.screenScrollRows = 0;
    this.changedScreenRows.clear();
    this.exposedScreenRows.clear();
  }

  void mergeHistoryRange(long fromSeq, long toSeq, boolean structureChanged) {
    historyChanged = true;
    historyStructureChanged |= structureChanged;
    if (fromSeq > toSeq) return;
    if (changedHistoryFromSeq > changedHistoryToSeq) {
      changedHistoryFromSeq = fromSeq;
      changedHistoryToSeq = toSeq;
    } else {
      changedHistoryFromSeq = Math.min(changedHistoryFromSeq, fromSeq);
      changedHistoryToSeq = Math.max(changedHistoryToSeq, toSeq);
    }
    if (historyRangesOverflow) return;
    long mergedFrom = fromSeq;
    long mergedTo = toSeq;
    for (int i = changedHistoryRanges.size() - 1; i >= 0; i--) {
      HistorySeqRange existing = changedHistoryRanges.get(i);
      if (strictlyBefore(existing.toSeq(), mergedFrom)
          || strictlyBefore(mergedTo, existing.fromSeq())) {
        continue;
      }
      mergedFrom = Math.min(mergedFrom, existing.fromSeq());
      mergedTo = Math.max(mergedTo, existing.toSeq());
      changedHistoryRanges.remove(i);
    }
    changedHistoryRanges.add(new HistorySeqRange(mergedFrom, mergedTo));
    changedHistoryRanges.sort((left, right) -> Long.compare(left.fromSeq(), right.fromSeq()));
    if (changedHistoryRanges.size() > MAX_HISTORY_DIRTY_RANGES) {
      changedHistoryRanges.clear();
      historyRangesOverflow = true;
    }
  }

  private static boolean strictlyBefore(long leftTo, long rightFrom) {
    return leftTo < rightFrom
        && (leftTo == Long.MAX_VALUE || leftTo + 1 < rightFrom);
  }

  void mergeFrom(RenderDirtyState other, int rowCount) {
    if (other == null) return;
    merge(other.fullInvalidate, other.changedScreenRows, other.screenScrollRows,
        other.exposedScreenRows, rowCount, other.historyChanged, other.geometryChanged,
        other.cursorChanged, other.previousCursorRow, other.currentCursorRow,
        other.paletteChanged, other.stylesChanged, other.linksChanged, other.modesChanged,
        other.activeBufferChanged);
    if (other.screenRegionInvalidate && !this.fullInvalidate) {
      markScreenRegionInvalidate();
    }
    if (other.historyChanged) {
      if (other.historyRangesOverflow) {
        historyRangesOverflow = true;
      }
      if (!other.changedHistoryRanges.isEmpty()) {
        for (HistorySeqRange range : other.changedHistoryRanges) {
          mergeHistoryRange(range.fromSeq(), range.toSeq(), other.historyStructureChanged);
        }
      } else {
        mergeHistoryRange(other.changedHistoryFromSeq, other.changedHistoryToSeq,
            other.historyStructureChanged);
      }
    }
    historyRangesOverflow |= other.historyRangesOverflow;
  }

  /**
   * 将已累计的 {@link #changedScreenRows} 与 {@link #exposedScreenRows} 按 delta 平移。
   * delta 为正表示向更大行号移动（屏幕向下滚动后，旧内容落到更下方）。
   * 光标行不平移：previous/current 分别保留首帧旧光标与末帧新光标的屏幕坐标。
   */
  private void shiftRows(int delta, int rowCount) {
    if (rowCount <= 0 || delta == 0) return;
    BitSet shiftedChanged = shiftBitSet(changedScreenRows, delta, rowCount);
    BitSet shiftedExposed = shiftBitSet(exposedScreenRows, delta, rowCount);
    changedScreenRows.clear();
    changedScreenRows.or(shiftedChanged);
    exposedScreenRows.clear();
    exposedScreenRows.or(shiftedExposed);
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
