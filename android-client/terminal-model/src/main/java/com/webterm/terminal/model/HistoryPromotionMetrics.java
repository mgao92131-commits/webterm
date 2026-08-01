package com.webterm.terminal.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** HistoryBinding 晋升复用与 BodyBatch miss 归因的进程级指标。 */
public final class HistoryPromotionMetrics {
  private static final int MAX_RECENT_MISS_REASONS = 512;

  private static final AtomicLong PROMOTION_EXACT_REUSE = new AtomicLong();
  private static final AtomicLong PROMOTION_BODY_INVARIANT_FAILURE = new AtomicLong();

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

  public static void recordBodyInvariantFailure(long historySeq) {
    PROMOTION_BODY_INVARIANT_FAILURE.incrementAndGet();
    RECENT_MISS_REASONS.put(
        historySeq, HistoryBodyMissReason.PROMOTION_BODY_INVARIANT_FAILURE);
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
    out.put("promotionBodyInvariantFailureCount", PROMOTION_BODY_INVARIANT_FAILURE.get());
    Map<String, Long> byReason = new LinkedHashMap<>();
    for (HistoryBodyMissReason reason : HistoryBodyMissReason.values()) {
      byReason.put(reason.name(), HISTORY_REQUEST_BY_MISS_REASON[reason.ordinal()].get());
    }
    out.put("historyRequestByMissReason", byReason);
    return out;
  }

  public static void resetForTest() {
    PROMOTION_EXACT_REUSE.set(0);
    PROMOTION_BODY_INVARIANT_FAILURE.set(0);
    for (AtomicLong counter : HISTORY_REQUEST_BY_MISS_REASON) {
      counter.set(0);
    }
    RECENT_MISS_REASONS.clear();
  }
}
