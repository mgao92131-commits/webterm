package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RenderSnapshot 的统一纵向内容轴：历史前缀（已加载行或缺失范围）后接 ActiveRows。
 * 历史部分按 {@link HistoryResidencyIndex#PAGE_SIZE} 分页结构共享；普通正文加载只重建
 * dirty 覆盖的页，未变页保持对象身份。
 */
public final class UnifiedContentAxis {
  public enum Kind {
    LOADED_LINE,
    MISSING_HISTORY_RANGE,
    ACTIVE_LINE
  }

  public static final class Item {
    public final Kind kind;
    public final long startRow;
    public final long rowCount;
    public final long fromHistorySeq;
    public final long toHistorySeq;
    public final long lineId;
    public final RenderLine line;

    private Item(Kind kind, long startRow, long rowCount,
                 long fromHistorySeq, long toHistorySeq,
                 long lineId, RenderLine line) {
      this.kind = kind;
      this.startRow = startRow;
      this.rowCount = rowCount;
      this.fromHistorySeq = fromHistorySeq;
      this.toHistorySeq = toHistorySeq;
      this.lineId = lineId;
      this.line = line;
    }

    public long endRowExclusive() {
      return startRow + rowCount;
    }

    static Item loaded(long startRow, long historySeq, RenderLine line) {
      return new Item(Kind.LOADED_LINE, startRow, 1, historySeq, historySeq,
          line.key().lineId(), line);
    }

    static Item missing(long startRow, long fromSeq, long toSeq) {
      return new Item(Kind.MISSING_HISTORY_RANGE, startRow, toSeq - fromSeq + 1,
          fromSeq, toSeq, 0, null);
    }

    static Item active(long startRow, RenderLine line) {
      return new Item(Kind.ACTIVE_LINE, startRow, 1, 0, 0, line.key().lineId(), line);
    }
  }

  private final List<Item> items;
  private final List<Item> activeItems;
  private final HistoryPart history;
  private final HistoryCatalog historyCatalog;
  private final Map<Long, Long> activeRowByLineId;
  private final Map<LineKey, Long> activeRowByLineKey;
  private final long rowCount;
  private final long historyRowCount;

  private UnifiedContentAxis(
      HistoryPart history, HistoryCatalog historyCatalog, List<Item> activeItems,
      Map<Long, Long> activeRowByLineId, Map<LineKey, Long> activeRowByLineKey,
      long rowCount) {
    this.history = history;
    this.historyCatalog = historyCatalog != null ? historyCatalog : new HistoryCatalog();
    this.activeItems = activeItems;
    this.items = new CombinedItems(history.segmentIndex, activeItems);
    this.activeRowByLineId = Collections.unmodifiableMap(activeRowByLineId);
    this.activeRowByLineKey = Collections.unmodifiableMap(activeRowByLineKey);
    this.rowCount = rowCount;
    this.historyRowCount = history.rowCount;
  }

  public static UnifiedContentAxis empty() {
    return new UnifiedContentAxis(
        HistoryPart.EMPTY, new HistoryCatalog(), Collections.emptyList(),
        Collections.emptyMap(), Collections.emptyMap(), 0);
  }

  static UnifiedContentAxis build(TerminalSurfaceState surface) {
    RenderLine[] rows = new RenderLine[surface.activeRows.size()];
    for (int row = 0; row < rows.length; row++) {
      LineKey key = surface.activeRows.keyAt(row);
      LineBody body = surface.bodyCache.body(key);
      if (body == null) {
        throw new IllegalStateException("ActiveRows references missing LineKey " + key);
      }
      rows[row] = new RenderLine(key, body);
    }
    return update(surface, ScreenRenderView.takeOwnership(rows), null, null, true);
  }

  /** @deprecated 保留旧签名；新路径请使用 {@link #update}. */
  @Deprecated
  static UnifiedContentAxis build(
      TerminalSurfaceState surface,
      ScreenRenderView screen,
      UnifiedContentAxis previous,
      boolean rebuildHistory) {
    return update(surface, screen, previous, null, rebuildHistory);
  }

  static UnifiedContentAxis update(
      TerminalSurfaceState surface,
      ScreenRenderView screen,
      UnifiedContentAxis previous,
      RenderDirtyState dirty) {
    boolean rebuildHistory = dirty == null || dirty.historyChanged || dirty.activeBufferChanged;
    return update(surface, screen, previous, dirty, rebuildHistory);
  }

  /**
   * 兼容 Baseline 的历史拓扑快速路径。HistoryCatalog 已确认相同，且正文是
   * immutable LineBody；因此只重建当前屏幕项，直接复用旧 HistoryPart/page index。
   */
  static UnifiedContentAxis reuseHistoryTopology(
      TerminalSurfaceState surface, ScreenRenderView screen, UnifiedContentAxis previous) {
    if (previous == null) return update(surface, screen, null, null, true);
    List<Item> activeItems = new ArrayList<>(screen.size());
    Map<Long, Long> activeRowsById = new HashMap<>();
    Map<LineKey, Long> activeRowsByKey = new HashMap<>();
    long row = previous.history.rowCount;
    for (int activeRow = 0; activeRow < screen.size(); activeRow++) {
      RenderLine line = screen.lineAt(activeRow);
      activeItems.add(Item.active(row, line));
      activeRowsById.put(line.key().lineId(), row);
      activeRowsByKey.put(line.key(), row);
      row++;
    }
    TerminalRenderMetrics.historyAxisUpdate(0, 0, 0, 0, false);
    return new UnifiedContentAxis(
        previous.history, surface.historyCatalog, Collections.unmodifiableList(activeItems),
        activeRowsById, activeRowsByKey, row);
  }

  private static UnifiedContentAxis update(
      TerminalSurfaceState surface,
      ScreenRenderView screen,
      UnifiedContentAxis previous,
      RenderDirtyState dirty,
      boolean historyTouched) {
    long started = System.nanoTime();
    HistoryPart history;
    if (!historyTouched && previous != null) {
      history = previous.history;
    } else {
      history = updateHistory(surface, previous == null ? null : previous.history, dirty);
    }
    List<Item> activeItems = new ArrayList<>(screen.size());
    Map<Long, Long> activeRowsById = new HashMap<>();
    Map<LineKey, Long> activeRowsByKey = new HashMap<>();
    long row = history.rowCount;
    for (int activeRow = 0; activeRow < screen.size(); activeRow++) {
      RenderLine line = screen.lineAt(activeRow);
      LineKey key = line.key();
      activeItems.add(Item.active(row, line));
      activeRowsById.put(key.lineId(), row);
      activeRowsByKey.put(key, row);
      row++;
    }
    TerminalRenderMetrics.historyAxisUpdate(
        System.nanoTime() - started, history.pagesRebuilt, history.pagesReused,
        history.rowsScanned, history.fullRebuild);
    return new UnifiedContentAxis(
        history, surface.historyCatalog, Collections.unmodifiableList(activeItems),
        activeRowsById, activeRowsByKey, row);
  }

  private static HistoryPart updateHistory(
      TerminalSurfaceState surface, HistoryPart previous, RenderDirtyState dirty) {
    HistoryExtent extent = surface.historyCatalog.extent();
    if (extent.isEmpty()) return HistoryPart.EMPTY;

    boolean canIncremental = previous != null
        && previous.extent != null
        && previous.extent.equals(extent)
        && dirty != null
        && dirty.historyChanged
        && !dirty.historyStructureChanged
        && dirty.changedHistoryFromSeq <= dirty.changedHistoryToSeq;

    if (!canIncremental) {
      return buildHistoryFull(surface, "structure_or_unknown");
    }

    long fromSeq = Math.max(extent.firstSeq, dirty.changedHistoryFromSeq);
    long toSeq = Math.min(extent.lastSeq, dirty.changedHistoryToSeq);
    if (fromSeq > toSeq) {
      return previous;
    }

    long firstPage = HistoryResidencyIndex.pageNumber(fromSeq);
    long lastPage = HistoryResidencyIndex.pageNumber(toSeq);
    Map<Long, HistoryAxisPage> pages = new HashMap<>(previous.pages);
    int rebuilt = 0;
    int reused = previous.pages.size();
    int scanned = 0;
    for (long pageNumber = firstPage; pageNumber <= lastPage; pageNumber++) {
      HistoryAxisPage page = buildPage(surface, extent, pageNumber);
      scanned += HistoryResidencyIndex.PAGE_SIZE;
      rebuilt++;
      reused--;
      if (page.isEmpty()) {
        pages.remove(pageNumber);
      } else {
        pages.put(pageNumber, page);
      }
    }
    SegmentIndex segments = updateSegmentIndex(
        extent, pages, previous.segmentIndex, firstPage, lastPage, false);
    return new HistoryPart(
        extent, Collections.unmodifiableMap(pages), segments,
        extent.logicalSize(), rebuilt, Math.max(0, reused), scanned, false);
  }

  private static HistoryPart buildHistoryFull(TerminalSurfaceState surface, String reason) {
    HistoryExtent extent = surface.historyCatalog.extent();
    if (extent.isEmpty()) return HistoryPart.EMPTY;
    Map<Long, HistoryAxisPage> pages = new HashMap<>();
    int scanned = 0;
    long firstPage = HistoryResidencyIndex.pageNumber(extent.firstSeq);
    long lastPage = HistoryResidencyIndex.pageNumber(extent.lastSeq);
    for (long pageNumber = firstPage; pageNumber <= lastPage; pageNumber++) {
      HistoryAxisPage page = buildPage(surface, extent, pageNumber);
      scanned += HistoryResidencyIndex.PAGE_SIZE;
      if (!page.isEmpty()) pages.put(pageNumber, page);
    }
    SegmentIndex segments = updateSegmentIndex(
        extent, pages, null, firstPage, lastPage, true);
    TerminalRenderMetrics.historyAxisFullRebuild(reason);
    return new HistoryPart(
        extent, Collections.unmodifiableMap(pages), segments,
        extent.logicalSize(), pages.size(), 0, scanned, true);
  }

  private static HistoryAxisPage buildPage(
      TerminalSurfaceState surface, HistoryExtent extent, long pageNumber) {
    long pageFirst = HistoryResidencyIndex.pageFirstSeq(pageNumber);
    long pageLast = HistoryResidencyIndex.pageLastSeq(pageNumber);
    long from = Math.max(extent.firstSeq, pageFirst);
    long to = Math.min(extent.lastSeq, pageLast);
    if (from > to) return new HistoryAxisPage(pageNumber, List.of(), 0);

    List<Item> items = new ArrayList<>();
    int loaded = 0;
    long missingFrom = -1;
    for (long seq = from; seq <= to; seq++) {
      long axisRow = seq - extent.firstSeq;
      LineKey expected = surface.historyCatalog.key(seq);
      LineKey resident = surface.bodyCache.historyResidency().key(seq);
      LineBody body = expected != null && expected.equals(resident)
          ? surface.bodyCache.body(expected) : null;
      if (body != null) {
        if (missingFrom >= 0) {
          items.add(Item.missing(missingFrom - extent.firstSeq, missingFrom, seq - 1));
          missingFrom = -1;
        }
        items.add(Item.loaded(axisRow, seq, new RenderLine(expected, body)));
        loaded++;
      } else if (missingFrom < 0) {
        missingFrom = seq;
      }
    }
    if (missingFrom >= 0) {
      items.add(Item.missing(missingFrom - extent.firstSeq, missingFrom, to));
    }
    if (loaded == 0) return new HistoryAxisPage(pageNumber, List.of(), 0);
    return new HistoryAxisPage(pageNumber, items, loaded);
  }

  /**
   * 更新页原生 segment block。页目录可以重建索引，但只有 dirty 页会创建新的 Item；
   * 未变页直接复用旧 block，不再把全部历史摊平成一份新 List。
   */
  private static SegmentIndex updateSegmentIndex(
      HistoryExtent extent, Map<Long, HistoryAxisPage> pages,
      SegmentIndex previous, long dirtyFirstPage, long dirtyLastPage, boolean fullRebuild) {
    long started = System.nanoTime();
    Map<Long, SegmentBlock> blocks = new HashMap<>();
    if (previous != null) blocks.putAll(previous.blocksByPage);
    long firstPage = fullRebuild ? dirtyFirstPage : previous.firstPage;
    long lastPage = fullRebuild ? dirtyLastPage : previous.lastPage;
    int pagesVisited = 0;
    int itemsVisited = 0;
    int itemsCreated = 0;
    for (long pageNumber = firstPage; pageNumber <= lastPage; pageNumber++) {
      if (!fullRebuild && (pageNumber < dirtyFirstPage || pageNumber > dirtyLastPage)) continue;
      HistoryAxisPage page = pages.get(pageNumber);
      SegmentBlock block = SegmentBlock.create(extent, pageNumber, page);
      blocks.put(pageNumber, block);
      pagesVisited++;
      itemsVisited += block.items.size();
      itemsCreated += block.items.size();
    }
    SegmentIndex result = new SegmentIndex(firstPage, lastPage, blocks);
    TerminalRenderMetrics.historyAxisSegmentsBuilt(
        System.nanoTime() - started, pagesVisited, itemsVisited, itemsCreated);
    return result;
  }

  public List<Item> items() {
    return items;
  }

  /** 在指定轴行之后寻找第一个已加载/活动行，不遍历全历史 segment。 */
  public Item firstLoadedItemAtOrAfter(long axisRow) {
    long target = Math.max(0, axisRow);
    if (target < history.rowCount && !history.extent.isEmpty()) {
      long seq = history.extent.firstSeq + target;
      long firstPage = HistoryResidencyIndex.pageNumber(seq);
      for (long page = firstPage; page <= history.segmentIndex.lastPage; page++) {
        SegmentBlock block = history.segmentIndex.block(page);
        if (block == null) continue;
        for (Item item : block.items) {
          if (item.kind == Kind.LOADED_LINE && item.startRow >= target) return item;
        }
      }
    }
    int activeStart = (int) Math.min(
        activeItems.size(), Math.max(0L, target - history.rowCount));
    for (int i = activeStart; i < activeItems.size(); i++) {
      Item item = activeItems.get(i);
      if (item.line != null) return item;
    }
    return null;
  }

  /** 返回最后一个已加载/活动行；只按页目录倒序查找。 */
  public Item lastLoadedItem() {
    for (int i = activeItems.size() - 1; i >= 0; i--) {
      Item item = activeItems.get(i);
      if (item.line != null) return item;
    }
    for (long page = history.segmentIndex.lastPage;
         page >= history.segmentIndex.firstPage; page--) {
      SegmentBlock block = history.segmentIndex.block(page);
      if (block == null) continue;
      for (int i = block.items.size() - 1; i >= 0; i--) {
        Item item = block.items.get(i);
        if (item.kind == Kind.LOADED_LINE) return item;
      }
    }
    return null;
  }

  public long rowCount() {
    return rowCount;
  }

  public long historyRowCount() {
    return historyRowCount;
  }

  public Long rowOfLineId(long lineId) {
    Long active = activeRowByLineId.get(lineId);
    if (active != null) return active;
    return historyAxisRow(historyCatalog.historySeqByLineId(lineId));
  }

  public Long rowOfLineKey(LineKey key) {
    Long active = activeRowByLineKey.get(key);
    if (active != null) return active;
    return historyAxisRow(historyCatalog.historySeq(key));
  }

  private Long historyAxisRow(Long seq) {
    if (seq == null) return null;
    HistoryExtent extent = historyCatalog.extent();
    if (extent.isEmpty() || !extent.contains(seq)) return null;
    return seq - extent.firstSeq;
  }

  public Item itemAtRow(long axisRow) {
    if (axisRow < 0 || axisRow >= rowCount) {
      throw new IndexOutOfBoundsException("axisRow=" + axisRow + " rows=" + rowCount);
    }
    if (axisRow < historyRowCount) {
      return history.itemAtAxisRow(axisRow, historyCatalog.extent());
    }
    int low = 0;
    int high = items.size() - 1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      Item item = items.get(mid);
      if (axisRow < item.startRow) {
        high = mid - 1;
      } else if (axisRow >= item.endRowExclusive()) {
        low = mid + 1;
      } else {
        return item;
      }
    }
    throw new IllegalStateException("axis row not covered");
  }

  /** 测试可见：未变更页是否保持同一对象。 */
  HistoryAxisPage pageForTest(long pageNumber) {
    return history.pages.get(pageNumber);
  }

  private static final class HistoryPart {
    static final HistoryPart EMPTY = new HistoryPart(
        HistoryExtent.INITIAL_EMPTY, Map.of(),
        new SegmentIndex(0, -1, Map.of()), 0, 0, 0, 0, false);

    final HistoryExtent extent;
    final Map<Long, HistoryAxisPage> pages;
    final SegmentIndex segmentIndex;
    final long rowCount;
    final int pagesRebuilt;
    final int pagesReused;
    final int rowsScanned;
    final boolean fullRebuild;

    HistoryPart(
        HistoryExtent extent, Map<Long, HistoryAxisPage> pages, SegmentIndex segmentIndex,
        long rowCount, int pagesRebuilt, int pagesReused, int rowsScanned, boolean fullRebuild) {
      this.extent = extent;
      this.pages = pages;
      this.segmentIndex = segmentIndex;
      this.rowCount = rowCount;
      this.pagesRebuilt = pagesRebuilt;
      this.pagesReused = pagesReused;
      this.rowsScanned = rowsScanned;
      this.fullRebuild = fullRebuild;
    }

    Item itemAtAxisRow(long axisRow, HistoryExtent liveExtent) {
      HistoryExtent use = liveExtent != null && !liveExtent.isEmpty() ? liveExtent : extent;
      long historySeq = use.firstSeq + axisRow;
      long pageNumber = HistoryResidencyIndex.pageNumber(historySeq);
      SegmentBlock block = segmentIndex.block(pageNumber);
      if (block != null) {
        for (Item item : block.items) {
          if (historySeq >= item.fromHistorySeq && historySeq <= item.toHistorySeq) {
            return item;
          }
        }
      }
      return Item.missing(axisRow, historySeq, historySeq);
    }
  }

  private static final class SegmentBlock {
    final long pageNumber;
    final List<Item> items;

    private SegmentBlock(long pageNumber, List<Item> items) {
      this.pageNumber = pageNumber;
      this.items = items;
    }

    static SegmentBlock create(
        HistoryExtent extent, long pageNumber, HistoryAxisPage page) {
      if (page != null && !page.items.isEmpty()) {
        return new SegmentBlock(pageNumber, page.items);
      }
      long from = Math.max(extent.firstSeq, HistoryResidencyIndex.pageFirstSeq(pageNumber));
      long to = Math.min(extent.lastSeq, HistoryResidencyIndex.pageLastSeq(pageNumber));
      if (from > to) return new SegmentBlock(pageNumber, List.of());
      return new SegmentBlock(
          pageNumber,
          List.of(Item.missing(from - extent.firstSeq, from, to)));
    }
  }

  /** 页级的惰性 Item 列表；索引只维护页偏移，不创建全历史扁平对象。 */
  private static final class SegmentIndex extends AbstractList<Item> {
    final long firstPage;
    final long lastPage;
    final Map<Long, SegmentBlock> blocksByPage;
    private final List<SegmentBlock> orderedBlocks;
    private final int[] offsets;

    SegmentIndex(long firstPage, long lastPage, Map<Long, SegmentBlock> blocks) {
      this.firstPage = firstPage;
      this.lastPage = lastPage;
      this.blocksByPage = Collections.unmodifiableMap(new HashMap<>(blocks));
      if (lastPage < firstPage) {
        orderedBlocks = List.of();
        offsets = new int[] {0};
        return;
      }
      List<SegmentBlock> ordered = new ArrayList<>();
      for (long page = firstPage; page <= lastPage; page++) {
        SegmentBlock block = this.blocksByPage.get(page);
        if (block != null) ordered.add(block);
      }
      orderedBlocks = Collections.unmodifiableList(ordered);
      offsets = new int[ordered.size() + 1];
      for (int i = 0; i < ordered.size(); i++) {
        offsets[i + 1] = offsets[i] + ordered.get(i).items.size();
      }
    }

    SegmentBlock block(long pageNumber) { return blocksByPage.get(pageNumber); }

    @Override
    public Item get(int index) {
      if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(index);
      int low = 0;
      int high = orderedBlocks.size() - 1;
      while (low <= high) {
        int mid = (low + high) >>> 1;
        if (index < offsets[mid]) {
          high = mid - 1;
        } else if (index >= offsets[mid + 1]) {
          low = mid + 1;
        } else {
          return orderedBlocks.get(mid).items.get(index - offsets[mid]);
        }
      }
      throw new IllegalStateException("segment index not covered");
    }

    @Override
    public int size() { return offsets[offsets.length - 1]; }
  }

  private static final class CombinedItems extends AbstractList<Item> {
    private final List<Item> history;
    private final List<Item> active;

    CombinedItems(List<Item> history, List<Item> active) {
      this.history = history;
      this.active = active;
    }

    @Override
    public Item get(int index) {
      return index < history.size() ? history.get(index) : active.get(index - history.size());
    }

    @Override
    public int size() {
      return history.size() + active.size();
    }
  }
}
