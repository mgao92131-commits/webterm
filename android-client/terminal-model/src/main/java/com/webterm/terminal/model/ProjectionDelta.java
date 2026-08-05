package com.webterm.terminal.model;

import java.util.BitSet;
import java.util.List;

/** Reducer 成功提交后供 Runtime/Renderer 使用的轻量变化摘要。 */
public record ProjectionDelta(
    boolean baseline,
    BitSet changedRows,
    BitSet exposedRows,
    int screenScrollRows,
    boolean historyChanged,
    boolean geometryChanged,
    List<HistorySeqRange> evictedHistoryRanges
) {
  public ProjectionDelta {
    changedRows = changedRows == null ? new BitSet() : (BitSet) changedRows.clone();
    exposedRows = exposedRows == null ? new BitSet() : (BitSet) exposedRows.clone();
    evictedHistoryRanges = List.copyOf(
        evictedHistoryRanges == null ? List.of() : evictedHistoryRanges);
  }

  public ProjectionDelta(
      boolean baseline,
      BitSet changedRows,
      BitSet exposedRows,
      int screenScrollRows,
      boolean historyChanged,
      boolean geometryChanged) {
    this(baseline, changedRows, exposedRows, screenScrollRows,
        historyChanged, geometryChanged, List.of());
  }

  public boolean screenChanged() {
    return baseline || screenScrollRows != 0 || !changedRows.isEmpty() || !exposedRows.isEmpty();
  }
}
