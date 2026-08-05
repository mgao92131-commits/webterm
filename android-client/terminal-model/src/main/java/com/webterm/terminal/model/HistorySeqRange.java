package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 一个闭区间的 HistorySeq 脏范围。 */
public record HistorySeqRange(long fromSeq, long toSeq) {
  public HistorySeqRange {
    if (fromSeq < 1 || toSeq < fromSeq) {
      throw new IllegalArgumentException("invalid history sequence range");
    }
  }

  /** 合并重叠或相邻范围，返回新的不可变列表。 */
  public static List<HistorySeqRange> coalesce(Iterable<HistorySeqRange> source) {
    List<HistorySeqRange> sorted = new ArrayList<>();
    if (source != null) {
      for (HistorySeqRange range : source) {
        if (range != null) sorted.add(range);
      }
    }
    sorted.sort(Comparator.comparingLong(HistorySeqRange::fromSeq));
    if (sorted.isEmpty()) return List.of();
    List<HistorySeqRange> result = new ArrayList<>();
    long from = sorted.get(0).fromSeq();
    long to = sorted.get(0).toSeq();
    for (int i = 1; i < sorted.size(); i++) {
      HistorySeqRange next = sorted.get(i);
      boolean adjacent = to != Long.MAX_VALUE && next.fromSeq() == to + 1;
      if (next.fromSeq() <= to || adjacent) {
        to = Math.max(to, next.toSeq());
      } else {
        result.add(new HistorySeqRange(from, to));
        from = next.fromSeq();
        to = next.toSeq();
      }
    }
    result.add(new HistorySeqRange(from, to));
    return List.copyOf(result);
  }
}
