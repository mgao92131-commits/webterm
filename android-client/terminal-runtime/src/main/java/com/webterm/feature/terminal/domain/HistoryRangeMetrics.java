package com.webterm.feature.terminal.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 历史正文调度指标；只记录范围、计数和耗时，不记录终端正文。 */
final class HistoryRangeMetrics {
  private final AtomicLong demandReceivedCount = new AtomicLong();
  private final AtomicLong demandConflatedCount = new AtomicLong();
  private final AtomicLong demandAppliedCount = new AtomicLong();
  private final AtomicLong demandChangedWhileFetchingCount = new AtomicLong();
  private final AtomicLong requestStartedCount = new AtomicLong();
  private final AtomicLong requestCompletedCount = new AtomicLong();
  private final AtomicLong requestCancelledCount = new AtomicLong();
  private final AtomicLong requestObsoleteAtCompletionCount = new AtomicLong();
  private final AtomicLong requestUsefulAtCompletionCount = new AtomicLong();
  private final AtomicLong staleProjectionResponseCount = new AtomicLong();
  private final AtomicLong singleLineRequestCount = new AtomicLong();
  private final AtomicLong tailDebounceCount = new AtomicLong();
  private final AtomicLong requestQueueDelayNanos = new AtomicLong();
  private final AtomicLong requestNetworkDurationNanos = new AtomicLong();
  private final AtomicLong responseCallbackQueueDelayNanos = new AtomicLong();
  private final AtomicLong responseApplyDurationNanos = new AtomicLong();

  void onDemandReceived() { demandReceivedCount.incrementAndGet(); }
  void onDemandConflated() { demandConflatedCount.incrementAndGet(); }
  void onDemandApplied(long queueDelayNanos) {
    demandAppliedCount.incrementAndGet();
    requestQueueDelayNanos.addAndGet(nonNegative(queueDelayNanos));
  }
  void onDemandChangedWhileFetching() {
    demandChangedWhileFetchingCount.incrementAndGet();
  }
  void onRequestStarted(long lineCount) {
    requestStartedCount.incrementAndGet();
    if (lineCount == 1) singleLineRequestCount.incrementAndGet();
  }
  void onRequestCompleted(long networkNanos, long callbackQueueNanos) {
    requestCompletedCount.incrementAndGet();
    requestNetworkDurationNanos.addAndGet(nonNegative(networkNanos));
    responseCallbackQueueDelayNanos.addAndGet(nonNegative(callbackQueueNanos));
  }
  void onRequestCancelled() { requestCancelledCount.incrementAndGet(); }
  void onRequestCompletionClassified(boolean useful) {
    if (useful) requestUsefulAtCompletionCount.incrementAndGet();
    else requestObsoleteAtCompletionCount.incrementAndGet();
  }
  void onStaleProjectionResponse() { staleProjectionResponseCount.incrementAndGet(); }
  void onTailDebounce() { tailDebounceCount.incrementAndGet(); }
  void onResponseApplied(long durationNanos) {
    responseApplyDurationNanos.addAndGet(nonNegative(durationNanos));
  }

  Map<String, Object> snapshot() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("demandReceivedCount", demandReceivedCount.get());
    out.put("demandConflatedCount", demandConflatedCount.get());
    out.put("demandAppliedCount", demandAppliedCount.get());
    out.put("demandChangedWhileFetchingCount", demandChangedWhileFetchingCount.get());
    out.put("requestStartedCount", requestStartedCount.get());
    out.put("requestCompletedCount", requestCompletedCount.get());
    out.put("requestCancelledCount", requestCancelledCount.get());
    out.put("requestObsoleteAtCompletionCount", requestObsoleteAtCompletionCount.get());
    out.put("requestUsefulAtCompletionCount", requestUsefulAtCompletionCount.get());
    out.put("staleProjectionResponseCount", staleProjectionResponseCount.get());
    out.put("singleLineRequestCount", singleLineRequestCount.get());
    out.put("tailDebounceCount", tailDebounceCount.get());
    out.put("requestQueueDelayMs", nanosToMillis(requestQueueDelayNanos.get()));
    out.put("requestNetworkDurationMs", nanosToMillis(requestNetworkDurationNanos.get()));
    out.put("responseCallbackQueueDelayMs",
        nanosToMillis(responseCallbackQueueDelayNanos.get()));
    out.put("responseApplyDurationMs", nanosToMillis(responseApplyDurationNanos.get()));
    return out;
  }

  private static long nonNegative(long value) {
    return Math.max(0L, value);
  }

  private static long nanosToMillis(long value) {
    return Math.max(0L, value) / 1_000_000L;
  }
}
