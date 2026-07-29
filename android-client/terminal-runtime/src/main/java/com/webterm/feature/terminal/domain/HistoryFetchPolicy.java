package com.webterm.feature.terminal.domain;

/** 纯函数式请求策略：动态批量、抢占距离和热尾聚合判断。 */
final class HistoryFetchPolicy {
  static final int MIN_BATCH_LINES = 32;
  static final long TAIL_QUIET_PERIOD_MS = 40L;
  static final long TAIL_MAX_WAIT_MS = 100L;

  long desiredBatchLines(HistoryRangeLoader.Demand demand) {
    long visible = Math.max(1L, demand.visibleRowCount);
    return Math.max(MIN_BATCH_LINES, visible * 2L);
  }

  boolean shouldCancel(
      HistoryRangeLoader.Range active, HistoryRangeLoader.Demand next) {
    if (overlaps(active.fromSeq, active.toSeq, next.visibleFromSeq, next.visibleToSeq)) {
      return false;
    }
    long distance = distance(
        active.fromSeq, active.toSeq, next.visibleFromSeq, next.visibleToSeq);
    return distance > desiredBatchLines(next);
  }

  boolean shouldDebounceTail(
      HistoryRangeLoader.Range range,
      HistoryRangeLoader.Demand demand,
      long authoritativeLastSeq) {
    return range.visibleMissingLineCount == 1
        && range.toSeq == authoritativeLastSeq
        && demand.visibleToSeq >= authoritativeLastSeq
        // 用户主动滚动时，可见缺口优先于热尾聚合，立即请求。
        && demand.direction == 0;
  }

  static boolean overlaps(long aFrom, long aTo, long bFrom, long bTo) {
    return aFrom <= bTo && bFrom <= aTo;
  }

  private static long distance(long aFrom, long aTo, long bFrom, long bTo) {
    if (overlaps(aFrom, aTo, bFrom, bTo)) return 0;
    if (aTo < bFrom) return bFrom - aTo - 1;
    return aFrom - bTo - 1;
  }
}
