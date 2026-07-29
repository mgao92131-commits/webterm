package com.webterm.terminal.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Renderer 可无锁读取的分页历史快照。 */
public final class PagedTerminalHistorySnapshot implements HistoryRenderView {
  public static final class LoadedEntry {
    public final long historySeq;
    public final TerminalLine line;

    LoadedEntry(long historySeq, TerminalLine line) {
      this.historySeq = historySeq;
      this.line = line;
    }
  }
  private final HistoryExtent extent;
  private final HistoryExtent availableExtent;
  private final Map<Long, PagedTerminalHistory.HistoryPageChunk> pages;
  private final long loadedLineCount;
  private final long estimatedByteCount;

  PagedTerminalHistorySnapshot(
      HistoryExtent extent,
      HistoryExtent availableExtent,
      Map<Long, PagedTerminalHistory.HistoryPageChunk> pages,
      long loadedLineCount,
      long estimatedByteCount) {
    this.extent = extent;
    this.availableExtent = availableExtent;
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

  public HistoryExtent availableExtent() {
    return availableExtent;
  }

  public long estimatedByteCount() {
    return estimatedByteCount;
  }

  /** 当前真正驻留正文的 LineID；不把逻辑 extent 的缺口伪装成已加载。 */
  public Set<Long> loadedLineIds() {
    Set<Long> result = new HashSet<>();
    for (PagedTerminalHistory.HistoryPageChunk page : pages.values()) {
      for (TerminalLine line : page.slots) {
        if (line != null) result.add(line.id);
      }
    }
    return result;
  }

  /** 仅枚举真正驻留的正文，按 HistorySeq 升序；成本与本地缓存量而非远端 extent 成正比。 */
  public List<LoadedEntry> loadedEntries() {
    List<LoadedEntry> result = new ArrayList<>();
    for (Map.Entry<Long, PagedTerminalHistory.HistoryPageChunk> pageEntry : pages.entrySet()) {
      long pageFirst = PagedTerminalHistory.pageFirstSeq(pageEntry.getKey());
      TerminalLine[] slots = pageEntry.getValue().slots;
      for (int offset = 0; offset < slots.length; offset++) {
        TerminalLine line = slots[offset];
        if (line == null) continue;
        long seq = pageFirst + offset;
        if (extent.contains(seq)) result.add(new LoadedEntry(seq, line));
      }
    }
    result.sort(Comparator.comparingLong(entry -> entry.historySeq));
    return result;
  }

  /** 最旧的已加载 HistorySeq；当前只有占位槽时返回 -1。 */
  public long firstLoadedSeq() {
    long first = Long.MAX_VALUE;
    for (Map.Entry<Long, PagedTerminalHistory.HistoryPageChunk> entry : pages.entrySet()) {
      long pageFirst = PagedTerminalHistory.pageFirstSeq(entry.getKey());
      PagedTerminalHistory.HistoryPageChunk page = entry.getValue();
      for (int offset = 0; offset < PagedTerminalHistory.PAGE_SIZE; offset++) {
        if (page.slots[offset] == null) continue;
        long seq = pageFirst > Long.MAX_VALUE - offset ? Long.MAX_VALUE : pageFirst + offset;
        if (extent.contains(seq) && seq < first) first = seq;
      }
    }
    return first == Long.MAX_VALUE ? -1 : first;
  }

  public TerminalLine lineAt(long logicalIndex) {
    long seq = seqAt(logicalIndex);
    return loadedLineBySeq(seq);
  }

  @Override
  public TerminalLine lineAt(int logicalIndex) {
    return lineAt((long) logicalIndex);
  }

  @Override
  public long seqAt(int index) {
    return seqAt((long) index);
  }

  @Override
  public int findSeqIndex(long seq) {
    if (!extent.contains(seq)) return -1;
    long index = seq - extent.firstSeq;
    return index > Integer.MAX_VALUE ? -1 : (int) index;
  }

  public TerminalLine lineBySeq(long seq) {
    return extent.contains(seq) ? loadedLineBySeq(seq) : null;
  }

  public SlotState slotStateAt(long logicalIndex) {
    long seq = seqAt(logicalIndex);
    // 本地 resident line 是已经显示过的不可变投影事实；remote available extent 只决定
    // 缺失槽位能否继续向服务端请求。服务端 trim 不得让当前 viewport 的驻留行消失。
    if (loadedLineBySeq(seq) != null) return SlotState.LOADED;
    if (!availableExtent.contains(seq)) return SlotState.UNAVAILABLE;
    return SlotState.UNLOADED;
  }

  private TerminalLine loadedLineBySeq(long seq) {
    PagedTerminalHistory.HistoryPageChunk page = pages.get(PagedTerminalHistory.pageNumber(seq));
    return page == null ? null : page.slots[PagedTerminalHistory.pageOffset(seq)];
  }

  private long seqAt(long logicalIndex) {
    if (logicalIndex < 0 || logicalIndex >= logicalSize()) {
      throw new IndexOutOfBoundsException(
          "logicalIndex=" + logicalIndex + " size=" + logicalSize());
    }
    return extent.firstSeq + logicalIndex;
  }
}
