package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
    this.items = new CombinedItems(history.segments, activeItems);
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
    List<Item> segments = buildSegments(extent, pages).publishAndGet();
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
    List<Item> segments = buildSegments(extent, pages).publishAndGet();
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
   * 将有正文的页与中间空洞拼成 segment 目录。空洞跨多个空页合并为单个 MISSING。
   */
  private static SegmentBuildResult buildSegments(
      HistoryExtent extent, Map<Long, HistoryAxisPage> pages) {
    long started = System.nanoTime();
    if (pages.isEmpty()) {
      List<Item> onlyMissing = List.of(Item.missing(0, extent.firstSeq, extent.lastSeq));
      return new SegmentBuildResult(onlyMissing, 0, 0, onlyMissing.size(), started);
    }
    TreeMap<Long, HistoryAxisPage> ordered = new TreeMap<>(pages);
    List<Item> segments = new ArrayList<>();
    int pagesVisited = 0;
    int itemsVisited = 0;
    long nextSeq = extent.firstSeq;
    for (HistoryAxisPage page : ordered.values()) {
      pagesVisited++;
      for (Item item : page.items) {
        itemsVisited++;
        if (item.fromHistorySeq > nextSeq) {
          segments.add(Item.missing(
              nextSeq - extent.firstSeq, nextSeq, item.fromHistorySeq - 1));
        }
        // 重新锚定 startRow，保证 HistorySeq 是唯一位置来源。
        if (item.kind == Kind.LOADED_LINE) {
          segments.add(Item.loaded(
              item.fromHistorySeq - extent.firstSeq, item.fromHistorySeq, item.line));
        } else {
          segments.add(Item.missing(
              item.fromHistorySeq - extent.firstSeq, item.fromHistorySeq, item.toHistorySeq));
        }
        nextSeq = item.toHistorySeq + 1;
      }
    }
    if (nextSeq <= extent.lastSeq) {
      segments.add(Item.missing(nextSeq - extent.firstSeq, nextSeq, extent.lastSeq));
    }
    List<Item> frozen = Collections.unmodifiableList(segments);
    return new SegmentBuildResult(frozen, pagesVisited, itemsVisited, frozen.size(), started);
  }

  private static final class SegmentBuildResult {
    final List<Item> segments;
    final int pagesVisited;
    final int itemsVisited;
    final int itemsCreated;
    final long startedNanos;

    SegmentBuildResult(
        List<Item> segments, int pagesVisited, int itemsVisited, int itemsCreated,
        long startedNanos) {
      this.segments = segments;
      this.pagesVisited = pagesVisited;
      this.itemsVisited = itemsVisited;
      this.itemsCreated = itemsCreated;
      this.startedNanos = startedNanos;
    }

    List<Item> publishAndGet() {
      TerminalRenderMetrics.historyAxisSegmentsBuilt(
          System.nanoTime() - startedNanos,
          pagesVisited, itemsVisited, itemsCreated);
      return segments;
    }
  }

  public List<Item> items() {
    return items;
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
        HistoryExtent.INITIAL_EMPTY, Map.of(), List.of(), 0, 0, 0, 0, false);

    final HistoryExtent extent;
    final Map<Long, HistoryAxisPage> pages;
    final List<Item> segments;
    final long rowCount;
    final int pagesRebuilt;
    final int pagesReused;
    final int rowsScanned;
    final boolean fullRebuild;

    HistoryPart(
        HistoryExtent extent, Map<Long, HistoryAxisPage> pages, List<Item> segments,
        long rowCount, int pagesRebuilt, int pagesReused, int rowsScanned, boolean fullRebuild) {
      this.extent = extent;
      this.pages = pages;
      this.segments = segments;
      this.rowCount = rowCount;
      this.pagesRebuilt = pagesRebuilt;
      this.pagesReused = pagesReused;
      this.rowsScanned = rowsScanned;
      this.fullRebuild = fullRebuild;
    }

    Item itemAtAxisRow(long axisRow, HistoryExtent liveExtent) {
      // 优先返回 segments 中的既有对象，保证 screen-only 更新时 Item 身份稳定。
      int low = 0;
      int high = segments.size() - 1;
      while (low <= high) {
        int mid = (low + high) >>> 1;
        Item item = segments.get(mid);
        if (axisRow < item.startRow) {
          high = mid - 1;
        } else if (axisRow >= item.endRowExclusive()) {
          low = mid + 1;
        } else {
          return item;
        }
      }
      HistoryExtent use = liveExtent != null && !liveExtent.isEmpty() ? liveExtent : extent;
      long historySeq = use.firstSeq + axisRow;
      long pageNumber = HistoryResidencyIndex.pageNumber(historySeq);
      HistoryAxisPage page = pages.get(pageNumber);
      if (page != null) {
        for (Item item : page.items) {
          if (historySeq >= item.fromHistorySeq && historySeq <= item.toHistorySeq) {
            return item;
          }
        }
      }
      return Item.missing(axisRow, historySeq, historySeq);
    }
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
