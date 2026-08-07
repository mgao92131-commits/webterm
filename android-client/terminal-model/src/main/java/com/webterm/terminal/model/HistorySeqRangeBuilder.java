package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Primitive history-sequence accumulator that emits coalesced ranges without per-line objects. */
final class HistorySeqRangeBuilder {
  private long[] values = new long[32];
  private int size;
  private int direction;
  private long previous;

  void add(long historySeq) {
    if (historySeq < 1) throw new IllegalArgumentException("invalid history sequence");
    if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
    if (size > 0 && historySeq != previous) {
      int nextDirection = historySeq > previous ? 1 : -1;
      if (direction == 0) direction = nextDirection;
      else if (direction != nextDirection) direction = 2;
    }
    values[size++] = historySeq;
    previous = historySeq;
  }

  List<HistorySeqRange> build() {
    if (size == 0) return List.of();
    if (direction == 2) Arrays.sort(values, 0, size);
    ArrayList<HistorySeqRange> result = new ArrayList<>();
    int index = direction == -1 ? size - 1 : 0;
    int step = direction == -1 ? -1 : 1;
    long from = values[index];
    long to = from;
    for (int visited = 1; visited < size; visited++) {
      index += step;
      long next = values[index];
      if (next <= to) continue;
      if (to != Long.MAX_VALUE && next == to + 1) {
        to = next;
      } else {
        result.add(new HistorySeqRange(from, to));
        from = to = next;
      }
    }
    result.add(new HistorySeqRange(from, to));
    return List.copyOf(result);
  }
}
