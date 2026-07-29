package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 当前 projection identity 内规范化的历史正文失败区间集合。 */
final class UnavailableIntervalSet {
  static final class Interval {
    final long fromSeq;
    final long toSeq;
    final String fault;

    Interval(long fromSeq, long toSeq, @NonNull String fault) {
      this.fromSeq = fromSeq;
      this.toSeq = toSeq;
      this.fault = fault;
    }
  }

  private final List<Interval> intervals = new ArrayList<>();

  void clear() {
    intervals.clear();
  }

  boolean isEmpty() {
    return intervals.isEmpty();
  }

  boolean contains(long seq) {
    for (Interval interval : intervals) {
      if (seq < interval.fromSeq) return false;
      if (seq <= interval.toSeq) return true;
    }
    return false;
  }

  long lowerBarrier(long seq) {
    long barrier = 0;
    for (Interval interval : intervals) {
      if (interval.toSeq >= seq) break;
      barrier = Math.max(barrier, interval.toSeq);
    }
    return barrier;
  }

  long upperBarrier(long seq) {
    for (Interval interval : intervals) {
      if (interval.fromSeq > seq) return interval.fromSeq;
    }
    return Long.MAX_VALUE;
  }

  void add(long fromSeq, long toSeq, @NonNull String fault) {
    if (fromSeq <= 0 || toSeq < fromSeq) return;
    intervals.add(new Interval(fromSeq, toSeq, fault));
    intervals.sort(Comparator.comparingLong(value -> value.fromSeq));
    List<Interval> merged = new ArrayList<>(intervals.size());
    for (Interval next : intervals) {
      if (merged.isEmpty()) {
        merged.add(next);
        continue;
      }
      Interval previous = merged.get(merged.size() - 1);
      boolean adjacent = previous.toSeq == Long.MAX_VALUE
          || next.fromSeq <= previous.toSeq + 1;
      if (adjacent) {
        String mergedFault = previous.fault.equals(next.fault)
            ? previous.fault : "MULTIPLE";
        merged.set(merged.size() - 1,
            new Interval(previous.fromSeq, Math.max(previous.toSeq, next.toSeq), mergedFault));
      } else {
        merged.add(next);
      }
    }
    intervals.clear();
    intervals.addAll(merged);
  }

  void remove(long seq) {
    for (int i = 0; i < intervals.size(); i++) {
      Interval interval = intervals.get(i);
      if (seq < interval.fromSeq) return;
      if (seq > interval.toSeq) continue;
      intervals.remove(i);
      if (interval.fromSeq < seq) {
        intervals.add(i++, new Interval(interval.fromSeq, seq - 1, interval.fault));
      }
      if (seq < interval.toSeq) {
        intervals.add(i, new Interval(seq + 1, interval.toSeq, interval.fault));
      }
      return;
    }
  }

  List<Interval> snapshot() {
    return new ArrayList<>(intervals);
  }
}
