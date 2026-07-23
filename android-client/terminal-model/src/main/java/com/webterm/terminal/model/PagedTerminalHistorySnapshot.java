package com.webterm.terminal.model;

import java.util.Collections;
import java.util.Map;

/** Renderer 可无锁读取的分页历史快照。 */
public final class PagedTerminalHistorySnapshot implements TerminalHistoryView {
  private final HistoryExtent extent;
  private final Map<Long, PagedTerminalHistory.HistoryPageChunk> pages;
  private final long loadedLineCount;
  private final long estimatedByteCount;

  PagedTerminalHistorySnapshot(
      HistoryExtent extent,
      Map<Long, PagedTerminalHistory.HistoryPageChunk> pages,
      long loadedLineCount,
      long estimatedByteCount) {
    this.extent = extent;
    this.pages = Collections.unmodifiableMap(pages);
    this.loadedLineCount = loadedLineCount;
    this.estimatedByteCount = estimatedByteCount;
  }

  public HistoryExtent extent() {
    return extent;
  }

  public long firstSeq() {
    return extent.firstSeq;
  }

  public long lastSeq() {
    return extent.lastSeq;
  }

  public long logicalSize() {
    return extent.logicalSize();
  }

  @Override
  public int size() {
    return (int) Math.min(Integer.MAX_VALUE, logicalSize());
  }

  public long loadedLineCount() {
    return loadedLineCount;
  }

  public long estimatedByteCount() {
    return estimatedByteCount;
  }

  /** 最旧的已加载 HistorySeq；当前只有占位槽时返回 -1。 */
  public long firstLoadedSeq() {
    long first = Long.MAX_VALUE;
    for (Map.Entry<Long, PagedTerminalHistory.HistoryPageChunk> entry : pages.entrySet()) {
      long pageFirst = entry.getKey() * PagedTerminalHistory.PAGE_SIZE + 1;
      PagedTerminalHistory.HistoryPageChunk page = entry.getValue();
      for (int offset = 0; offset < PagedTerminalHistory.PAGE_SIZE; offset++) {
        if (page.slots[offset] == null) continue;
        long seq = pageFirst + offset;
        if (extent.contains(seq) && seq < first) first = seq;
      }
    }
    return first == Long.MAX_VALUE ? -1 : first;
  }

  /**
   * 返回与可见区相交的首个 UNLOADED 页闭区间；没有可请求槽位时返回 null。
   * UNAVAILABLE 槽位不会再次请求，避免服务端已裁剪区间形成热循环。
   */
  public long[] firstRequestablePage(long visibleFromSeq, long visibleToSeq) {
    if (extent.isEmpty()) return null;
    long from = Math.max(extent.firstSeq, visibleFromSeq);
    long to = Math.min(extent.lastSeq, visibleToSeq);
    if (from > to) return null;
    for (long seq = from; seq <= to; seq++) {
      long index = seq - extent.firstSeq;
      if (slotStateAt(index) == SlotState.UNLOADED) {
        long pageFirst = PagedTerminalHistory.pageNumber(seq)
            * PagedTerminalHistory.PAGE_SIZE + 1;
        return new long[] {
            Math.max(extent.firstSeq, pageFirst),
            Math.min(extent.lastSeq, pageFirst + PagedTerminalHistory.PAGE_SIZE - 1)
        };
      }
      if (seq == Long.MAX_VALUE) break;
    }
    return null;
  }

  public TerminalLine lineAt(long logicalIndex) {
    long seq = seqAt(logicalIndex);
    PagedTerminalHistory.HistoryPageChunk page = pages.get(PagedTerminalHistory.pageNumber(seq));
    return page == null ? null : page.slots[PagedTerminalHistory.pageOffset(seq)];
  }

  @Override
  public TerminalLine lineAt(int logicalIndex) {
    return lineAt((long) logicalIndex);
  }

  @Override
  public int findSeqIndex(long seq) {
    if (!extent.contains(seq)) return -1;
    long index = seq - extent.firstSeq;
    return index > Integer.MAX_VALUE ? -1 : (int) index;
  }

  public TerminalLine lineBySeq(long seq) {
    if (!extent.contains(seq)) return null;
    PagedTerminalHistory.HistoryPageChunk page = pages.get(PagedTerminalHistory.pageNumber(seq));
    return page == null ? null : page.slots[PagedTerminalHistory.pageOffset(seq)];
  }

  public SlotState slotStateAt(long logicalIndex) {
    long seq = seqAt(logicalIndex);
    PagedTerminalHistory.HistoryPageChunk page = pages.get(PagedTerminalHistory.pageNumber(seq));
    if (page == null) return SlotState.UNLOADED;
    int offset = PagedTerminalHistory.pageOffset(seq);
    if (page.slots[offset] != null) return SlotState.LOADED;
    return page.unavailable[offset] ? SlotState.UNAVAILABLE : SlotState.UNLOADED;
  }

  private long seqAt(long logicalIndex) {
    if (logicalIndex < 0 || logicalIndex >= logicalSize()) {
      throw new IndexOutOfBoundsException(
          "logicalIndex=" + logicalIndex + " size=" + logicalSize());
    }
    return extent.firstSeq + logicalIndex;
  }
}
