package com.webterm.terminal.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** HistoryPush 晋升复用与 Range 请求 miss 归因的进程级指标。 */
public final class HistoryPromotionMetrics {
  private static final int MAX_RECENT_MISS_REASONS = 512;

  private static final AtomicLong PROMOTION_EXACT_REUSE = new AtomicLong();
  private static final AtomicLong PROMOTION_VERSION_MISMATCH = new AtomicLong();
  private static final AtomicLong PROMOTION_BODY_ABSENT = new AtomicLong();
  private static final AtomicLong BODY_PRESENT_NOT_RESIDENT = new AtomicLong();

  private static final AtomicLong[] HISTORY_REQUEST_BY_MISS_REASON =
      new AtomicLong[HistoryBodyMissReason.values().length];

  private static final Map<Long, HistoryBodyMissReason> RECENT_MISS_REASONS =
      Collections.synchronizedMap(new LinkedHashMap<Long, HistoryBodyMissReason>(
          MAX_RECENT_MISS_REASONS, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, HistoryBodyMissReason> eldest) {
          return size() > MAX_RECENT_MISS_REASONS;
        }
      });

  static {
    for (int i = 0; i < HISTORY_REQUEST_BY_MISS_REASON.length; i++) {
      HISTORY_REQUEST_BY_MISS_REASON[i] = new AtomicLong();
    }
  }

  private HistoryPromotionMetrics() {}

  public static void recordExactReuse() {
    PROMOTION_EXACT_REUSE.incrementAndGet();
  }

  public static void recordVersionMismatch(
      long historySeq, long previousVersion, long pushVersion) {
    PROMOTION_VERSION_MISMATCH.incrementAndGet();
    RECENT_MISS_REASONS.put(historySeq, HistoryBodyMissReason.PROMOTION_VERSION_MISMATCH);
  }

  public static void recordBodyAbsent(long historySeq) {
    PROMOTION_BODY_ABSENT.incrementAndGet();
    RECENT_MISS_REASONS.put(historySeq, HistoryBodyMissReason.PROMOTION_BODY_ABSENT);
  }

  public static HistoryBodyMissReason reasonFor(long historySeq) {
    return RECENT_MISS_REASONS.get(historySeq);
  }

  public static void recordHistoryRequestMissReason(HistoryBodyMissReason reason) {
    HistoryBodyMissReason safe =
        reason == null ? HistoryBodyMissReason.UNKNOWN : reason;
    HISTORY_REQUEST_BY_MISS_REASON[safe.ordinal()].incrementAndGet();
  }

  public static Map<String, Object> snapshot() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("promotionExactReuseCount", PROMOTION_EXACT_REUSE.get());
    out.put("promotionVersionMismatchCount", PROMOTION_VERSION_MISMATCH.get());
    out.put("promotionBodyAbsentCount", PROMOTION_BODY_ABSENT.get());
    out.put("bodyPresentNotResidentCount", BODY_PRESENT_NOT_RESIDENT.get());
    Map<String, Long> byReason = new LinkedHashMap<>();
    for (HistoryBodyMissReason reason : HistoryBodyMissReason.values()) {
      byReason.put(reason.name(), HISTORY_REQUEST_BY_MISS_REASON[reason.ordinal()].get());
    }
    out.put("historyRequestByMissReason", byReason);
    return out;
  }

  public static void resetForTest() {
    PROMOTION_EXACT_REUSE.set(0);
    PROMOTION_VERSION_MISMATCH.set(0);
    PROMOTION_BODY_ABSENT.set(0);
    BODY_PRESENT_NOT_RESIDENT.set(0);
    for (AtomicLong counter : HISTORY_REQUEST_BY_MISS_REASON) {
      counter.set(0);
    }
    RECENT_MISS_REASONS.clear();
  }
}
