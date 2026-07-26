package com.webterm.terminal.model;

import java.util.ArrayList;
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
    public final TerminalLine line;

    private Item(Kind kind, long startRow, long rowCount,
                 long fromHistorySeq, long toHistorySeq,
                 long lineId, TerminalLine line) {
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
  private final Map<Long, Long> rowByLineId;
  private final long rowCount;
  private final long historyRowCount;

  private UnifiedContentAxis(
      List<Item> items, Map<Long, Long> rowByLineId,
      long rowCount, long historyRowCount) {
    this.items = Collections.unmodifiableList(items);
    this.rowByLineId = Collections.unmodifiableMap(rowByLineId);
    this.rowCount = rowCount;
    this.historyRowCount = historyRowCount;
  }

  public static UnifiedContentAxis empty() {
    return new UnifiedContentAxis(
        Collections.emptyList(), Collections.emptyMap(), 0, 0);
  }

  static UnifiedContentAxis build(
      PagedTerminalHistorySnapshot history, ActiveRows activeRows, LineStore lineStore) {
    List<Item> result = new ArrayList<>();
    Map<Long, Long> rowsById = new HashMap<>();
    long row = 0;
    HistoryExtent extent = history.extent();
    long nextSeq = extent.isEmpty() ? 1 : extent.firstSeq;
    for (PagedTerminalHistorySnapshot.LoadedEntry loaded : history.loadedEntries()) {
      if (!extent.contains(loaded.historySeq)) continue;
      if (loaded.historySeq > nextSeq) {
        long count = loaded.historySeq - nextSeq;
        result.add(new Item(Kind.MISSING_HISTORY_RANGE, row, count,
            nextSeq, loaded.historySeq - 1, 0, null));
        row += count;
      }
      TerminalLine canonical = lineStore.line(loaded.line.id);
      if (canonical != null) {
        result.add(new Item(Kind.LOADED_LINE, row, 1,
            loaded.historySeq, loaded.historySeq, canonical.id, canonical));
        rowsById.put(canonical.id, row);
      } else {
        result.add(new Item(Kind.MISSING_HISTORY_RANGE, row, 1,
            loaded.historySeq, loaded.historySeq, 0, null));
      }
      row++;
      if (loaded.historySeq != Long.MAX_VALUE) nextSeq = loaded.historySeq + 1;
    }
    if (!extent.isEmpty() && nextSeq <= extent.lastSeq) {
      long count = extent.lastSeq - nextSeq + 1;
      result.add(new Item(Kind.MISSING_HISTORY_RANGE, row, count,
          nextSeq, extent.lastSeq, 0, null));
      row += count;
    }
    long historyRows = row;
    for (int activeRow = 0; activeRow < activeRows.size(); activeRow++) {
      long lineId = activeRows.lineIdAt(activeRow);
      TerminalLine line = lineStore.line(lineId);
      if (line == null) {
        throw new IllegalStateException("ActiveRows references missing LineID " + lineId);
      }
      result.add(new Item(Kind.ACTIVE_LINE, row, 1, 0, 0, lineId, line));
      rowsById.put(lineId, row);
      row++;
    }
    return new UnifiedContentAxis(result, rowsById, row, historyRows);
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
    return rowByLineId.get(lineId);
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
}
