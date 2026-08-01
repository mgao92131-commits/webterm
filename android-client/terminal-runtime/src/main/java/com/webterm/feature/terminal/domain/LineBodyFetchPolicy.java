package com.webterm.feature.terminal.domain;

/** 纯函数式 LineBodyBatch 请求策略：动态批量与抢占距离。 */
final class LineBodyFetchPolicy {
  static final int MIN_BATCH_KEYS = 128;
  static final int MAX_BATCH_KEYS = 512;
  static final int VIEWPORT_MULTIPLIER = 4;
  /** 与批次独立，避免扩大预取时延长无用在途请求的保留距离。 */
  static final int CANCEL_MIN_DISTANCE_LINES = 32;
  static final int CANCEL_VIEWPORT_MULTIPLIER = 2;

  int desiredBatchKeys(VisibleBodyLoader.Demand demand) {
    long visible = Math.max(1L, demand.visibleRowCount);
    return (int) Math.min(
        MAX_BATCH_KEYS,
        Math.max(MIN_BATCH_KEYS, visible * VIEWPORT_MULTIPLIER));
  }

  long cancelDistanceLines(VisibleBodyLoader.Demand demand) {
    long visible = Math.max(1L, demand.visibleRowCount);
    return Math.max(CANCEL_MIN_DISTANCE_LINES, visible * CANCEL_VIEWPORT_MULTIPLIER);
  }

  boolean shouldCancel(
      long activeFromSeq, long activeToSeq, VisibleBodyLoader.Demand next) {
    if (overlaps(activeFromSeq, activeToSeq, next.visibleFromSeq, next.visibleToSeq)) {
      return false;
    }
    long distance = distance(
        activeFromSeq, activeToSeq, next.visibleFromSeq, next.visibleToSeq);
    return distance > cancelDistanceLines(next);
  }

  static boolean overlaps(long aFrom, long aTo, long bFrom, long bTo) {
    return aFrom <= bTo && bFrom <= aTo;
  }

  static long distance(long aFrom, long aTo, long bFrom, long bTo) {
    if (overlaps(aFrom, aTo, bFrom, bTo)) return 0;
    if (aTo < bFrom) return bFrom - aTo - 1;
    return aFrom - bTo - 1;
  }
}
