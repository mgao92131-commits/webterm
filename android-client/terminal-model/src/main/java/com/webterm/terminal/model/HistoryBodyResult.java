package com.webterm.terminal.model;

import java.util.List;

/** HTTP Range reducer 的封闭结果，类型中故意不存在 NeedsBaseline/NeedsReconnect。 */
public sealed interface HistoryBodyResult {
  record Applied(
      TerminalSurfaceState state,
      long changedFromSeq,
      long changedToSeq,
      int appliedLineCount,
      int staleLineCount,
      List<HistorySeqRange> changedRanges,
      List<HistorySeqRange> evictedRanges) implements HistoryBodyResult {
    public Applied {
      changedRanges = List.copyOf(changedRanges == null ? List.of() : changedRanges);
      evictedRanges = List.copyOf(evictedRanges == null ? List.of() : evictedRanges);
    }
  }

  record StaleIgnored(int lineCount) implements HistoryBodyResult {}

  record Rejected(
      HistoryBodyFault fault,
      long failedFromSeq,
      long failedToSeq) implements HistoryBodyResult {
    public Rejected(HistoryBodyFault fault) {
      this(fault, 0, 0);
    }
  }
}
