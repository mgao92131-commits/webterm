package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link TerminalHistory} 的只读快照。Renderer/View 每帧固定读取本视图，
 * 不访问可变 Model 集合。
 */
public final class TerminalHistorySnapshot {

  private static final TerminalHistorySnapshot EMPTY =
      new TerminalHistorySnapshot(Collections.emptyList(), new int[0], 0, -1, -1);

  private final List<TerminalHistory.HistoryChunk> chunks;
  private final int[] chunkStartIndices;
  private final int size;
  private final long firstLineId;
  private final long lastLineId;

  TerminalHistorySnapshot(List<TerminalHistory.HistoryChunk> chunks, int[] chunkStartIndices, int size,
                          long firstLineId, long lastLineId) {
    this.chunks = Collections.unmodifiableList(new ArrayList<>(chunks));
    this.chunkStartIndices = chunkStartIndices.clone();
    this.size = size;
    this.firstLineId = firstLineId;
    this.lastLineId = lastLineId;
  }

  public static TerminalHistorySnapshot empty() {
    return EMPTY;
  }

  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  public long firstLineId() {
    return firstLineId;
  }

  public long lastLineId() {
    return lastLineId;
  }

  /** 按历史索引（0=最旧）O(log chunk count) 取行。 */
  public TerminalLine lineAt(int index) {
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException("index=" + index + " size=" + size);
    }
    int chunkIndex = chunkIndexForHistoryIndex(index);
    return chunks.get(chunkIndex).lineAt(index - chunkStartIndices[chunkIndex]);
  }

  /**
   * 按 LineID 定位在历史中的索引。O(log chunks + log chunk size)。
   *
   * @return 索引（0=最旧），不存在返回 -1。
   */
  public int findLineIndex(long lineId) {
    int lo = 0;
    int hi = chunks.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      TerminalHistory.HistoryChunk chunk = chunks.get(mid);
      if (lineId < chunk.firstId()) {
        hi = mid - 1;
      } else if (lineId > chunk.lastId()) {
        lo = mid + 1;
      } else {
        int local = chunk.findLocalIndex(lineId);
        return local >= 0 ? local + chunkStartIndices[mid] : -1;
      }
    }
    return -1;
  }

  /** 测试/过渡用：按 id 升序导出为 map。 */
  java.util.NavigableMap<Long, TerminalLine> asMap() {
    java.util.NavigableMap<Long, TerminalLine> map = new java.util.TreeMap<>();
    for (TerminalHistory.HistoryChunk chunk : chunks) {
      for (int i = 0; i < chunk.size; i++) {
        TerminalLine line = chunk.lineAt(i);
        map.put(line.historyOrder(), line);
      }
    }
    return map;
  }

  private int chunkIndexForHistoryIndex(int index) {
    int lo = 0;
    int hi = chunkStartIndices.length - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int start = chunkStartIndices[mid];
      int end = start + chunks.get(mid).size;
      if (index < start) {
        hi = mid - 1;
      } else if (index >= end) {
        lo = mid + 1;
      } else {
        return mid;
      }
    }
    throw new AssertionError("missing chunk for history index " + index);
  }
}
