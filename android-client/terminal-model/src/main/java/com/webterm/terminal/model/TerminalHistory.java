package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.ToLongFunction;

/**
 * 分段不可变历史缓存。内部按 {@link #CHUNK_SIZE} 分块，每块内容创建后不可变；
 * 整体支持尾部追加、头部 prepend 和按预算驱逐，避免每次发布 RenderSnapshot 时
 * 复制全部历史行引用。
 */
public final class TerminalHistory {

  public static final int CHUNK_SIZE = 128;

  private final List<HistoryChunk> chunks;
  private final ToLongFunction<TerminalLine> lineByteEstimator;
  private int size;
  private long estimatedBytes;

  public TerminalHistory(ToLongFunction<TerminalLine> lineByteEstimator) {
    this.lineByteEstimator = lineByteEstimator;
    this.chunks = new ArrayList<>();
  }

  /** 返回当前缓存行数。 */
  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  /** 按构造时传入的估算器统计的近似字节数。 */
  public long estimatedBytes() {
    return estimatedBytes;
  }

  /** 缓存最旧行 id；空缓存返回 -1。 */
  public long firstLineId() {
    if (chunks.isEmpty()) return -1;
    return chunks.get(0).firstId();
  }

  /** 缓存最新行 id；空缓存返回 -1。 */
  public long lastLineId() {
    if (chunks.isEmpty()) return -1;
    return chunks.get(chunks.size() - 1).lastId();
  }

  /** 按历史索引（0=最旧）O(chunk count) 取行。 */
  public TerminalLine lineAt(int index) {
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException("index=" + index + " size=" + size);
    }
    int remaining = index;
    for (HistoryChunk chunk : chunks) {
      if (remaining < chunk.size) return chunk.lineAt(remaining);
      remaining -= chunk.size;
    }
    throw new AssertionError();
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
      HistoryChunk chunk = chunks.get(mid);
      if (lineId < chunk.firstId()) {
        hi = mid - 1;
      } else if (lineId > chunk.lastId()) {
        lo = mid + 1;
      } else {
        int local = chunk.findLocalIndex(lineId);
        return local >= 0 ? local + sizeBefore(mid) : -1;
      }
    }
    return -1;
  }

  /** 追加单行到历史尾部。均摊 O(1)。 */
  public void append(TerminalLine line) {
    long bytes = lineByteEstimator.applyAsLong(line);
    HistoryChunk last = chunks.isEmpty() ? null : chunks.get(chunks.size() - 1);
    if (last == null || last.size == CHUNK_SIZE) {
      TerminalLine[] lines = new TerminalLine[CHUNK_SIZE];
      long[] lineBytes = new long[CHUNK_SIZE];
      lines[0] = line;
      lineBytes[0] = bytes;
      chunks.add(new HistoryChunk(lines, lineBytes, 0, 1, bytes));
    } else {
      int idx = last.offset + last.size;
      last.lines[idx] = line;
      last.lineBytes[idx] = bytes;
      last.size++;
      last.estimatedBytes += bytes;
    }
    size++;
    estimatedBytes += bytes;
  }

  /** 按顺序追加多行。O(lines)。 */
  public void appendAll(List<TerminalLine> lines) {
    for (TerminalLine line : lines) {
      append(line);
    }
  }

  /**
   * 插入或替换一行。若 line id 已存在则原地替换（id 不变），否则追加到尾部。
   * 替换只应在同 id 内容更新时使用，以保持 LineID 升序不变。
   */
  public boolean put(TerminalLine line) {
    int index = findLineIndex(line.id);
    if (index >= 0) {
      replaceAt(index, line);
      return false;
    }
    append(line);
    return true;
  }

  /** 清空全部历史。O(chunks)。 */
  public void clear() {
    chunks.clear();
    size = 0;
    estimatedBytes = 0;
  }

  /**
   * 在头部 prepend 一页。lines 必须按 lineId 升序，且全部 id 小于当前 {@link #firstLineId()}。
   * O(lines + chunks) 用于复制引用。
   */
  public void prepend(List<TerminalLine> lines) {
    if (lines.isEmpty()) return;
    List<HistoryChunk> newChunks = buildChunks(lines);
    chunks.addAll(0, newChunks);
    size += lines.size();
    for (TerminalLine line : lines) {
      estimatedBytes += lineByteEstimator.applyAsLong(line);
    }
  }

  /** 删除所有 lineId 小于 firstAvailableLineId 的行。O(chunks)。 */
  public void trimHeadUntil(long firstAvailableLineId) {
    while (!chunks.isEmpty() && chunks.get(0).lastId() < firstAvailableLineId) {
      removeFirstChunk();
    }
    if (!chunks.isEmpty() && chunks.get(0).firstId() < firstAvailableLineId) {
      HistoryChunk first = chunks.get(0);
      int local = first.findLocalIndex(firstAvailableLineId);
      if (local < 0) local = 0;
      removeFirstLines(first, local);
    }
  }

  /**
   * Tail append 后从最旧端驱逐，直到满足目标预算。
   * 至少保留一行（与旧 TreeMap 行为一致）。
   */
  public void trimHeadToBudget(int targetLines, long targetBytes) {
    while (size > 1 && (size > targetLines || estimatedBytes > targetBytes)) {
      HistoryChunk first = chunks.get(0);
      if (first.size == 1) {
        removeFirstChunk();
      } else {
        removeFirstLines(first, 1);
      }
    }
  }

  /**
   * Prepend 后从较新端驱逐，保护 anchorLineId 不被删除。
   * 驱逐顺序：先删 id > anchorLineId 的行，再删 id < anchorLineId 的行；
   * anchor 本身永不驱逐。每次只删一行，确保缓存量平滑下降到目标附近。
   */
  public void trimTailToBudget(int targetLines, long targetBytes, long anchorLineId) {
    while (size > 1 && (size > targetLines || estimatedBytes > targetBytes)) {
      HistoryChunk last = chunks.get(chunks.size() - 1);
      if (last.lastId() > anchorLineId) {
        // last chunk 仍包含比 anchor 新的行，从尾部逐行删除。
        removeLastLines(last, 1);
      } else if (canEvictHeadLine(anchorLineId)) {
        evictHeadLine(anchorLineId);
      } else {
        break;
      }
    }
  }

  /** 创建当前只读快照。O(chunks) 复制 chunk 引用列表。 */
  public TerminalHistorySnapshot snapshot() {
    if (chunks.isEmpty()) {
      return TerminalHistorySnapshot.empty();
    }
    return new TerminalHistorySnapshot(
        new ArrayList<>(chunks),
        size,
        chunks.get(0).firstId(),
        chunks.get(chunks.size() - 1).lastId());
  }

  /** 测试/过渡用：按 id 升序导出为 map。 */
  NavigableMap<Long, TerminalLine> asMap() {
    NavigableMap<Long, TerminalLine> map = new TreeMap<>();
    for (HistoryChunk chunk : chunks) {
      for (int i = 0; i < chunk.size; i++) {
        TerminalLine line = chunk.lineAt(i);
        map.put(line.id, line);
      }
    }
    return map;
  }

  private List<HistoryChunk> buildChunks(List<TerminalLine> lines) {
    if (lines.isEmpty()) return Collections.emptyList();
    List<HistoryChunk> result = new ArrayList<>();
    int index = 0;
    while (index < lines.size()) {
      int count = Math.min(CHUNK_SIZE, lines.size() - index);
      TerminalLine[] arr = new TerminalLine[CHUNK_SIZE];
      long[] bytes = new long[CHUNK_SIZE];
      long chunkBytes = 0;
      for (int i = 0; i < count; i++) {
        TerminalLine line = lines.get(index + i);
        arr[i] = line;
        long b = lineByteEstimator.applyAsLong(line);
        bytes[i] = b;
        chunkBytes += b;
      }
      result.add(new HistoryChunk(arr, bytes, 0, count, chunkBytes));
      index += count;
    }
    return result;
  }

  private void removeFirstChunk() {
    HistoryChunk removed = chunks.remove(0);
    size -= removed.size;
    estimatedBytes -= removed.estimatedBytes;
  }

  private void removeLastChunk() {
    HistoryChunk removed = chunks.remove(chunks.size() - 1);
    size -= removed.size;
    estimatedBytes -= removed.estimatedBytes;
  }

  private void removeFirstLines(HistoryChunk chunk, int count) {
    if (count <= 0 || count > chunk.size) return;
    long removedBytes = 0;
    for (int i = 0; i < count; i++) {
      removedBytes += chunk.lineBytes[chunk.offset + i];
    }
    chunk.offset += count;
    chunk.size -= count;
    chunk.estimatedBytes -= removedBytes;
    size -= count;
    estimatedBytes -= removedBytes;
    if (chunk.size == 0) {
      chunks.remove(0);
    }
  }

  private void removeLastLines(HistoryChunk chunk, int count) {
    if (count <= 0 || count > chunk.size) return;
    long removedBytes = 0;
    int start = chunk.offset + chunk.size - count;
    for (int i = 0; i < count; i++) {
      removedBytes += chunk.lineBytes[start + i];
    }
    chunk.size -= count;
    chunk.estimatedBytes -= removedBytes;
    size -= count;
    estimatedBytes -= removedBytes;
    if (chunk.size == 0) {
      chunks.remove(chunks.size() - 1);
    }
  }

  private void replaceAt(int index, TerminalLine line) {
    int remaining = index;
    for (HistoryChunk chunk : chunks) {
      if (remaining < chunk.size) {
        int local = chunk.offset + remaining;
        long oldBytes = chunk.lineBytes[local];
        long newBytes = lineByteEstimator.applyAsLong(line);
        chunk.lines[local] = line;
        chunk.lineBytes[local] = newBytes;
        chunk.estimatedBytes += newBytes - oldBytes;
        estimatedBytes += newBytes - oldBytes;
        return;
      }
      remaining -= chunk.size;
    }
    throw new AssertionError();
  }

  /** 判断是否能从头部（更旧端）删除一行而不触及 anchor。 */
  private boolean canEvictHeadLine(long anchorLineId) {
    if (size <= 1) return false;
    HistoryChunk first = chunks.get(0);
    long firstLastId = first.lastId();
    if (firstLastId < anchorLineId) return true;
    if (firstLastId == anchorLineId && first.firstId() < anchorLineId) {
      int anchorLocal = first.findLocalIndex(anchorLineId);
      return anchorLocal > 0;
    }
    return false;
  }

  /** 从头部删除一行，保证 anchor 不被删除。 */
  private void evictHeadLine(long anchorLineId) {
    HistoryChunk first = chunks.get(0);
    if (first.lastId() < anchorLineId) {
      removeFirstLines(first, 1);
    } else {
      // anchor is inside first chunk and not the first line
      removeFirstLines(first, 1);
    }
  }

  private int sizeBefore(int chunkIndex) {
    int sum = 0;
    for (int i = 0; i < chunkIndex; i++) {
      sum += chunks.get(i).size;
    }
    return sum;
  }

  /** 内部 chunk。内容创建后不变，offset/size 支持头部/尾部的局部删除。 */
  static final class HistoryChunk {
    final TerminalLine[] lines;
    final long[] lineBytes;
    int offset;
    int size;
    long estimatedBytes;

    HistoryChunk(TerminalLine[] lines, long[] lineBytes, int offset, int size, long estimatedBytes) {
      this.lines = lines;
      this.lineBytes = lineBytes;
      this.offset = offset;
      this.size = size;
      this.estimatedBytes = estimatedBytes;
    }

    long firstId() {
      return lines[offset].id;
    }

    long lastId() {
      return lines[offset + size - 1].id;
    }

    TerminalLine lineAt(int localIndex) {
      return lines[offset + localIndex];
    }

    int findLocalIndex(long lineId) {
      int lo = offset;
      int hi = offset + size - 1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        long midId = lines[mid].id;
        if (midId == lineId) return mid - offset;
        if (midId < lineId) lo = mid + 1;
        else hi = mid - 1;
      }
      return -1;
    }
  }
}
