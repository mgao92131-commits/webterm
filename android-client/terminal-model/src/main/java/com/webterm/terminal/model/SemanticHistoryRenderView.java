package com.webterm.terminal.model;

/**
 * 从权威位置目录与唯一正文缓存构造的不可变历史渲染视图。
 *
 * <p>该对象不复制正文，也不维护第二套反向索引。</p>
 */
public final class SemanticHistoryRenderView implements HistoryRenderView {
  private final HistoryCatalog catalog;
  private final BodyCache cache;

  public SemanticHistoryRenderView(HistoryCatalog catalog, BodyCache cache) {
    if (catalog == null || cache == null) {
      throw new IllegalArgumentException("history render state is incomplete");
    }
    this.catalog = catalog;
    this.cache = cache;
  }

  @Override
  public int size() {
    return (int) Math.min(Integer.MAX_VALUE, logicalSize());
  }

  @Override
  public RenderLine renderLineAt(int index) {
    long seq = seqAt(index);
    LineKey key = catalog.key(seq);
    if (key == null || cache.historyResidency().key(seq) == null) return null;
    LineBody body = cache.body(key);
    return body == null ? null : new RenderLine(key, body);
  }

  @Override
  public int findSeqIndex(long seq) {
    if (!extent().contains(seq)) return -1;
    long index = seq - firstSeq();
    return index > Integer.MAX_VALUE ? -1 : (int) index;
  }

  @Override
  public long firstSeq() {
    return extent().firstSeq;
  }

  @Override
  public long lastSeq() {
    return extent().lastSeq;
  }

  @Override
  public long seqAt(int index) {
    if (index < 0 || index >= size()) {
      throw new IndexOutOfBoundsException("index=" + index + " size=" + size());
    }
    return firstSeq() + index;
  }

  @Override
  public HistoryExtent extent() {
    return catalog.extent();
  }

  @Override
  public HistoryExtent availableExtent() {
    return cache.historyResidency().availableExtent();
  }

  @Override
  public long logicalSize() {
    return extent().logicalSize();
  }

  @Override
  public long loadedLineCount() {
    return cache.loadedHistoryCount();
  }

  @Override
  public long estimatedByteCount() {
    return cache.estimatedHistoryBytes();
  }

  @Override
  public long firstLoadedSeq() {
    long first = Long.MAX_VALUE;
    for (HistoryResidencyIndex.ResidentEntry entry
        : cache.historyResidency().residentEntries()) {
      if (extent().contains(entry.historySeq()) && entry.historySeq() < first) {
        first = entry.historySeq();
      }
    }
    return first == Long.MAX_VALUE ? -1 : first;
  }

  @Override
  public SlotState slotStateAt(long logicalIndex) {
    if (logicalIndex < 0 || logicalIndex >= logicalSize()) {
      throw new IndexOutOfBoundsException(
          "logicalIndex=" + logicalIndex + " size=" + logicalSize());
    }
    return cache.historyResidency().slotState(firstSeq() + logicalIndex);
  }
}
