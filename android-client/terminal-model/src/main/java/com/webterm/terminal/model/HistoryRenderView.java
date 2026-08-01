package com.webterm.terminal.model;

/**
 * Renderer 与 Range Loader 共享的只读历史投影视图。
 *
 * <p>接口只描述位置轴和正文驻留状态，不暴露分页实现、正文缓存或编辑器。</p>
 */
public interface HistoryRenderView {
  int size();
  default boolean isEmpty() { return size() == 0; }
  int findSeqIndex(long seq);
  long firstSeq();
  long lastSeq();
  long seqAt(int index);
  HistoryExtent extent();
  HistoryExtent availableExtent();
  long logicalSize();
  long loadedLineCount();
  long estimatedByteCount();
  long firstLoadedSeq();
  SlotState slotStateAt(long logicalIndex);
  RenderLine renderLineAt(int logicalIndex);

  /** 在指定方向定位最近的 UNLOADED 历史槽位；实现可利用页级驻留索引跳过热页。 */
  default long nearestUnloadedSeq(long fromSeq, long toSeq, int direction) {
    long from = Math.max(firstSeq(), fromSeq);
    long to = Math.min(lastSeq(), toSeq);
    if (from > to) return -1;
    if (direction <= 0) {
      for (long seq = to; seq >= from; seq--) {
        if (slotStateAt(seq - firstSeq()) == SlotState.UNLOADED) return seq;
      }
    } else {
      for (long seq = from; seq <= to; seq++) {
        if (slotStateAt(seq - firstSeq()) == SlotState.UNLOADED) return seq;
      }
    }
    return -1;
  }
}
