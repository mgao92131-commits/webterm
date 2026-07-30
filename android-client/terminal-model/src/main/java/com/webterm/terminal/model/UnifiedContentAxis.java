package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RenderSnapshot 的统一纵向内容轴：历史前缀（已加载行或缺失范围）后接 ActiveRows。
 * 所有滚动坐标均使用 axis row，不再维护 history/screen 两套坐标。
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
  }

  private final List<Item> items;
  private final HistoryPart history;
  private final Map<Long, Long> activeRowByLineId;
  private final Map<LineKey, Long> activeRowByLineKey;
  private final long rowCount;
  private final long historyRowCount;

  private UnifiedContentAxis(
      HistoryPart history, List<Item> activeItems,
      Map<Long, Long> activeRowByLineId, Map<LineKey, Long> activeRowByLineKey,
      long rowCount) {
    this.history = history;
    this.items = new CombinedItems(history.items, activeItems);
    this.activeRowByLineId = Collections.unmodifiableMap(activeRowByLineId);
    this.activeRowByLineKey = Collections.unmodifiableMap(activeRowByLineKey);
    this.rowCount = rowCount;
    this.historyRowCount = history.rowCount;
  }

  public static UnifiedContentAxis empty() {
    return new UnifiedContentAxis(
        HistoryPart.EMPTY, Collections.emptyList(),
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
    return build(surface, ScreenRenderView.takeOwnership(rows), null, true);
  }

  static UnifiedContentAxis build(
      TerminalSurfaceState surface,
      ScreenRenderView screen,
      UnifiedContentAxis previous,
      boolean rebuildHistory) {
    HistoryPart history = previous != null && !rebuildHistory
        ? previous.history : buildHistory(surface);
    List<Item> activeItems = new ArrayList<>(screen.size());
    Map<Long, Long> activeRowsById = new HashMap<>();
    Map<LineKey, Long> activeRowsByKey = new HashMap<>();
    long row = history.rowCount;
    for (int activeRow = 0; activeRow < screen.size(); activeRow++) {
      RenderLine line = screen.lineAt(activeRow);
      LineKey key = line.key();
      activeItems.add(new Item(Kind.ACTIVE_LINE, row, 1, 0, 0, key.lineId(), line));
      activeRowsById.put(key.lineId(), row);
      activeRowsByKey.put(key, row);
      row++;
    }
    return new UnifiedContentAxis(
        history, Collections.unmodifiableList(activeItems),
        activeRowsById, activeRowsByKey, row);
  }

  private static HistoryPart buildHistory(TerminalSurfaceState surface) {
    List<Item> result = new ArrayList<>();
    Map<Long, Long> rowsById = new HashMap<>();
    Map<LineKey, Long> rowsByKey = new HashMap<>();
    long row = 0;
    HistoryExtent extent = surface.historyCatalog.extent();
    long nextSeq = extent.isEmpty() ? 1 : extent.firstSeq;
    for (HistoryResidencyIndex.ResidentEntry resident
        : surface.bodyCache.historyResidency().residentEntries()) {
      long seq = resident.historySeq();
      if (!extent.contains(seq)) continue;
      if (seq > nextSeq) {
        long count = seq - nextSeq;
        result.add(new Item(Kind.MISSING_HISTORY_RANGE, row, count,
            nextSeq, seq - 1, 0, null));
        row += count;
      }
      LineKey expected = surface.historyCatalog.key(seq);
      LineBody body = expected != null && expected.equals(resident.key())
          ? surface.bodyCache.body(expected) : null;
      if (body != null) {
        RenderLine line = new RenderLine(expected, body);
        result.add(new Item(Kind.LOADED_LINE, row, 1,
            seq, seq, expected.lineId(), line));
        rowsById.put(expected.lineId(), row);
        rowsByKey.put(expected, row);
      } else {
        result.add(new Item(Kind.MISSING_HISTORY_RANGE, row, 1,
            seq, seq, 0, null));
      }
      row++;
      if (seq != Long.MAX_VALUE) nextSeq = seq + 1;
    }
    if (!extent.isEmpty() && nextSeq <= extent.lastSeq) {
      long count = extent.lastSeq - nextSeq + 1;
      result.add(new Item(Kind.MISSING_HISTORY_RANGE, row, count,
          nextSeq, extent.lastSeq, 0, null));
      row += count;
    }
    return new HistoryPart(
        Collections.unmodifiableList(result),
        Collections.unmodifiableMap(rowsById),
        Collections.unmodifiableMap(rowsByKey),
        row);
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
    return active != null ? active : history.rowByLineId.get(lineId);
  }

  public Long rowOfLineKey(LineKey key) {
    Long active = activeRowByLineKey.get(key);
    return active != null ? active : history.rowByLineKey.get(key);
  }

  public Item itemAtRow(long axisRow) {
    if (axisRow < 0 || axisRow >= rowCount) {
      throw new IndexOutOfBoundsException("axisRow=" + axisRow + " rows=" + rowCount);
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

  private static final class HistoryPart {
    static final HistoryPart EMPTY = new HistoryPart(
        Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(), 0);

    final List<Item> items;
    final Map<Long, Long> rowByLineId;
    final Map<LineKey, Long> rowByLineKey;
    final long rowCount;

    HistoryPart(
        List<Item> items, Map<Long, Long> rowByLineId,
        Map<LineKey, Long> rowByLineKey, long rowCount) {
      this.items = items;
      this.rowByLineId = rowByLineId;
      this.rowByLineKey = rowByLineKey;
      this.rowCount = rowCount;
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
