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
}
