package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** View → Controller → Mailbox 的进程级对账指标，不保存范围或会话身份。 */
public final class HistoryDemandMetrics {
  private static final AtomicLong VIEWPORT_DEMAND_PRODUCED = new AtomicLong();
  private static final AtomicLong VIEWPORT_DEMAND_FRAME_COALESCED = new AtomicLong();
  private static final AtomicLong MAILBOX_SCHEDULED = new AtomicLong();
  private static final AtomicLong MAILBOX_CONFLATED = new AtomicLong();
  private static final AtomicLong MAILBOX_DEDUPLICATED = new AtomicLong();
  private static final AtomicLong MODEL_DEMAND_APPLIED = new AtomicLong();
  private static final AtomicLong FETCH_PLAN_CREATED = new AtomicLong();
  private static final AtomicLong FETCH_PLAN_REUSED = new AtomicLong();
  private static final AtomicLong FETCH_COVERED_BY_ACTIVE = new AtomicLong();
  private static final AtomicLong FETCH_CANCELLED_FOR_DISTANCE = new AtomicLong();
  private static final AtomicLong BATCH_REQUESTED_KEY_COUNT = new AtomicLong();
  private static final AtomicLong BATCH_VISIBLE_KEY_COUNT = new AtomicLong();
  private static final AtomicLong BATCH_PREFETCH_KEY_COUNT = new AtomicLong();
  private static final AtomicLong BATCH_REQUEST_COUNT = new AtomicLong();
  private static final AtomicLong BATCH_MAX_KEY_COUNT = new AtomicLong();

  private HistoryDemandMetrics() {}

  public static void viewportProduced(boolean frameCoalesced) {
    VIEWPORT_DEMAND_PRODUCED.incrementAndGet();
    if (frameCoalesced) VIEWPORT_DEMAND_FRAME_COALESCED.incrementAndGet();
  }

  static void mailboxResult(HistoryDemandMailbox.OfferResult result) {
    if (result == HistoryDemandMailbox.OfferResult.SCHEDULED) {
      MAILBOX_SCHEDULED.incrementAndGet();
    } else if (result == HistoryDemandMailbox.OfferResult.CONFLATED) {
      MAILBOX_CONFLATED.incrementAndGet();
    } else if (result == HistoryDemandMailbox.OfferResult.DEDUPLICATED) {
      MAILBOX_DEDUPLICATED.incrementAndGet();
    }
  }

  static void modelApplied() {
    MODEL_DEMAND_APPLIED.incrementAndGet();
  }

  static void fetchPlanCreated() {
    FETCH_PLAN_CREATED.incrementAndGet();
  }

  static void fetchPlanReused() {
    FETCH_PLAN_REUSED.incrementAndGet();
  }

  static void fetchCoveredByActive() {
    FETCH_COVERED_BY_ACTIVE.incrementAndGet();
  }

  static void fetchCancelledForDistance() {
    FETCH_CANCELLED_FOR_DISTANCE.incrementAndGet();
  }

  static void batchRequested(int keyCount, int visibleKeyCount, int prefetchKeyCount) {
    BATCH_REQUEST_COUNT.incrementAndGet();
    BATCH_REQUESTED_KEY_COUNT.addAndGet(Math.max(0, keyCount));
    BATCH_VISIBLE_KEY_COUNT.addAndGet(Math.max(0, visibleKeyCount));
    BATCH_PREFETCH_KEY_COUNT.addAndGet(Math.max(0, prefetchKeyCount));
    long current = BATCH_MAX_KEY_COUNT.get();
    while (keyCount > current
        && !BATCH_MAX_KEY_COUNT.compareAndSet(current, keyCount)) {
      current = BATCH_MAX_KEY_COUNT.get();
    }
  }

  @NonNull
  public static Map<String, Long> snapshot() {
    Map<String, Long> out = new LinkedHashMap<>();
    out.put("viewportDemandProducedCount", VIEWPORT_DEMAND_PRODUCED.get());
    out.put("viewportDemandFrameCoalescedCount", VIEWPORT_DEMAND_FRAME_COALESCED.get());
    out.put("mailboxScheduledCount", MAILBOX_SCHEDULED.get());
    out.put("mailboxConflatedCount", MAILBOX_CONFLATED.get());
    out.put("mailboxDeduplicatedCount", MAILBOX_DEDUPLICATED.get());
    out.put("modelDemandAppliedCount", MODEL_DEMAND_APPLIED.get());
    out.put("historyDemandCount", MODEL_DEMAND_APPLIED.get());
    out.put("historyFetchPlanCreatedCount", FETCH_PLAN_CREATED.get());
    out.put("historyFetchPlanReusedCount", FETCH_PLAN_REUSED.get());
    out.put("historyFetchCoveredByActiveCount", FETCH_COVERED_BY_ACTIVE.get());
    out.put("historyFetchCancelledForDistanceCount", FETCH_CANCELLED_FOR_DISTANCE.get());
    out.put("historyBatchRequestedKeyCount", BATCH_REQUESTED_KEY_COUNT.get());
    out.put("historyBatchVisibleKeyCount", BATCH_VISIBLE_KEY_COUNT.get());
    out.put("historyBatchPrefetchKeyCount", BATCH_PREFETCH_KEY_COUNT.get());
    out.put("historyBatchMaxKeyCount", BATCH_MAX_KEY_COUNT.get());
    long requests = BATCH_REQUEST_COUNT.get();
    out.put("historyBatchAverageKeyCount",
        requests == 0 ? 0L : BATCH_REQUESTED_KEY_COUNT.get() / requests);
    return out;
  }
}
