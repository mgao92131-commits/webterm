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

  @NonNull
  public static Map<String, Long> snapshot() {
    Map<String, Long> out = new LinkedHashMap<>();
    out.put("viewportDemandProducedCount", VIEWPORT_DEMAND_PRODUCED.get());
    out.put("viewportDemandFrameCoalescedCount", VIEWPORT_DEMAND_FRAME_COALESCED.get());
    out.put("mailboxScheduledCount", MAILBOX_SCHEDULED.get());
    out.put("mailboxConflatedCount", MAILBOX_CONFLATED.get());
    out.put("mailboxDeduplicatedCount", MAILBOX_DEDUPLICATED.get());
    out.put("modelDemandAppliedCount", MODEL_DEMAND_APPLIED.get());
    return out;
  }
}
