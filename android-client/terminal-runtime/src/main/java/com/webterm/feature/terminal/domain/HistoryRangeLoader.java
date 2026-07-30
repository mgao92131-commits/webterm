package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryRenderView;
import com.webterm.terminal.model.SlotState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 每会话单主请求在途、只保留最新视口 Demand 的历史 Range 状态机。 */
public final class HistoryRangeLoader {
  enum RequestState {
    FETCHING, RESPONSE_QUEUED, APPLYING, CANCELLED, TIMED_OUT, COMPLETED
  }

  enum CompletionDisposition {
    CURRENT, PARTIAL, OBSOLETE
  }

  public static final class Demand {
    public final long visibleFromSeq, visibleToSeq, anchorSeq;
    public final int direction;
    public final int visibleRowCount;
    public final long demandEpoch;
    public final long createdAtNanos;

    public Demand(long visibleFromSeq, long visibleToSeq, long anchorSeq, int direction) {
      this(visibleFromSeq, visibleToSeq, anchorSeq, direction,
          (int) Math.max(1L, visibleToSeq - visibleFromSeq + 1L),
          0, System.nanoTime());
    }

    public Demand(
        long visibleFromSeq, long visibleToSeq, long anchorSeq, int direction,
        int visibleRowCount, long demandEpoch, long createdAtNanos) {
      this.visibleFromSeq = visibleFromSeq;
      this.visibleToSeq = visibleToSeq;
      this.anchorSeq = anchorSeq;
      this.direction = direction;
      this.visibleRowCount = Math.max(1, visibleRowCount);
      this.demandEpoch = demandEpoch;
      this.createdAtNanos = createdAtNanos;
    }
  }

  public static final class Range {
    public final String instanceId;
    public final long layoutEpoch, generation, fromSeq, toSeq, demandEpoch;
    public final long visibleMissingLineCount;

    public Range(String instanceId, long layoutEpoch, long generation, long fromSeq, long toSeq) {
      this(instanceId, layoutEpoch, generation, fromSeq, toSeq, 0,
          Math.max(1L, toSeq - fromSeq + 1L));
    }

    public Range(
        String instanceId, long layoutEpoch, long generation,
        long fromSeq, long toSeq, long demandEpoch) {
      this(instanceId, layoutEpoch, generation, fromSeq, toSeq, demandEpoch,
          Math.max(1L, toSeq - fromSeq + 1L));
    }

    Range(
        String instanceId, long layoutEpoch, long generation,
        long fromSeq, long toSeq, long demandEpoch, long visibleMissingLineCount) {
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.generation = generation;
      this.fromSeq = fromSeq;
      this.toSeq = toSeq;
      this.demandEpoch = demandEpoch;
      this.visibleMissingLineCount = visibleMissingLineCount;
    }
  }

  public static final class ActiveRequest {
    public final long callId, lifecycleEpoch, startedAtNanos;
    public final Range range;
    public final HistoryRangeSource.RequestHandle handle;
    private final AtomicReference<RequestState> state =
        new AtomicReference<>(RequestState.FETCHING);
    private final AtomicLong responseArrivedAtNanos = new AtomicLong();

    ActiveRequest(long callId, long lifecycleEpoch, Range range,
                  HistoryRangeSource.RequestHandle handle) {
      this.callId = callId;
      this.lifecycleEpoch = lifecycleEpoch;
      this.range = range;
      this.handle = handle;
      this.startedAtNanos = System.nanoTime();
    }

    RequestState state() { return state.get(); }
    long responseArrivedAtNanos() { return responseArrivedAtNanos.get(); }

    void responseArrived(long atNanos) {
      responseArrivedAtNanos.compareAndSet(0, atNanos);
      state.compareAndSet(RequestState.FETCHING, RequestState.RESPONSE_QUEUED);
    }
  }

  private final HistoryFetchPolicy fetchPolicy = new HistoryFetchPolicy();
  private final HistoryRangeMetrics metrics = new HistoryRangeMetrics();
  @Nullable private Demand latestDemand;
  @Nullable private ActiveRequest activeRequest;
  private long lifecycleEpoch = 1;
  private long nextCallId = 1;
  private long nextDemandEpoch = 1;
  private String latestDemandInstanceId = "";
  private long latestDemandLayoutEpoch;
  private long latestDemandGeneration;
  private boolean hasLatestPlannedFetch;
  private String latestPlannedFetchInstanceId = "";
  private long latestPlannedFetchLayoutEpoch;
  private long latestPlannedFetchGeneration;
  private long latestPlannedFetchFromSeq;
  private long latestPlannedFetchToSeq;
  private String observedInstanceId = "";
  private long observedLayoutEpoch;
  private long observedGeneration;
  private long observedServerFirstSeq;
  private final UnavailableIntervalSet unavailable = new UnavailableIntervalSet();
  private long tailDebounceToken;
  private long tailDebounceDemandEpoch;
  private long tailDebounceSatisfiedEpoch;
  private long tailDebounceStartedAtNanos;
  private long tailDebounceAuthoritativeLastSeq;
  private int consecutiveSessionNotReadyFailures;
  private boolean closed;

  /** 测试和旧调用兼容入口；生产 demand 由 acceptDemand 分配 epoch。 */
  public synchronized void setDemand(@Nullable Demand demand) {
    if (closed) return;
    latestDemand = demand;
    clearLatestDemandProjection();
    if (demand != null) nextDemandEpoch = Math.max(nextDemandEpoch, demand.demandEpoch + 1);
    clearTailDebounce();
  }

  @Nullable
  public synchronized Demand acceptDemand(
      long visibleFromSeq, long visibleToSeq, long anchorSeq, int direction,
      int visibleRowCount, long createdAtNanos) {
    return acceptDemandInternal(
        visibleFromSeq, visibleToSeq, anchorSeq, direction,
        visibleRowCount, createdAtNanos, false, false, "", 0, 0);
  }

  /**
   * 在 model actor 上同时观察当前正文驻留状态。viewport demand 始终保留给 eviction pins；
   * 只有实际缺失目标改变且未被在途请求覆盖时才分配新的 demand epoch。
   */
  @Nullable
  public synchronized Demand acceptDemand(
      long visibleFromSeq, long visibleToSeq, long anchorSeq, int direction,
      int visibleRowCount, long createdAtNanos,
      @NonNull String instanceId, long layoutEpoch, long generation,
      @NonNull HistoryExtent extent, @NonNull HistoryRenderView history) {
    if (closed) return null;
    Demand probe = new Demand(
        visibleFromSeq, visibleToSeq, anchorSeq, direction, visibleRowCount,
        latestDemand == null ? 0 : latestDemand.demandEpoch, createdAtNanos);
    Range missing = firstMissingRangeForDemand(
        probe, instanceId, layoutEpoch, generation, extent, history);
    boolean samePlan = missing != null && samePlannedFetch(missing);
    boolean coveredByActive = missing != null && (activeRequestCovers(missing)
        || activeRequestCoversVisibleDemand(
            probe, instanceId, layoutEpoch, generation));
    boolean fetchAlreadySatisfied = missing == null || samePlan || coveredByActive;
    if (coveredByActive) {
      metrics.onFetchPlanCoveredByActive();
    } else if (missing == null || samePlan) {
      metrics.onFetchPlanReused();
    } else {
      metrics.onFetchPlanCreated();
    }
    Demand accepted = acceptDemandInternal(
        visibleFromSeq, visibleToSeq, anchorSeq, direction,
        visibleRowCount, createdAtNanos, true, fetchAlreadySatisfied,
        instanceId, layoutEpoch, generation);
    rememberPlannedFetch(missing);
    return accepted;
  }

  @Nullable
  private Demand acceptDemandInternal(
      long visibleFromSeq, long visibleToSeq, long anchorSeq, int direction,
      int visibleRowCount, long createdAtNanos,
      boolean requireSameProjection, boolean fetchAlreadySatisfied,
      @NonNull String instanceId, long layoutEpoch, long generation) {
    if (closed) return null;
    boolean sameCoverage = latestDemand != null
        && latestDemand.visibleFromSeq == visibleFromSeq
        && latestDemand.visibleToSeq == visibleToSeq;
    boolean reuseEpoch = latestDemand != null
        && (!requireSameProjection || sameProjectionIdentity(instanceId, layoutEpoch, generation))
        && (sameCoverage || fetchAlreadySatisfied);
    long demandEpoch = reuseEpoch ? latestDemand.demandEpoch : nextDemandEpoch++;
    Demand demand = new Demand(
        visibleFromSeq, visibleToSeq, anchorSeq, direction, visibleRowCount,
        demandEpoch, createdAtNanos);
    latestDemand = demand;
    if (!instanceId.isEmpty()) {
      latestDemandInstanceId = instanceId;
      latestDemandLayoutEpoch = layoutEpoch;
      latestDemandGeneration = generation;
    }
    metrics.onDemandApplied(System.nanoTime() - createdAtNanos);
    if (reuseEpoch) metrics.onDemandDeduplicated();
    if (!reuseEpoch && activeRequest != null
        && activeRequest.state() == RequestState.FETCHING) {
      metrics.onDemandChangedWhileFetching();
    }
    if (!reuseEpoch) tailDebounceSatisfiedEpoch = 0;
    return demand;
  }

  @Nullable public synchronized Demand latestDemand() { return latestDemand; }
  @Nullable public synchronized ActiveRequest activeRequest() { return activeRequest; }
  public synchronized long lifecycleEpoch() { return lifecycleEpoch; }
  public synchronized boolean closed() { return closed; }
  HistoryRangeMetrics metrics() { return metrics; }

  public synchronized Map<String, Object> diagnosticsSnapshot() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("lifecycleEpoch", lifecycleEpoch);
    out.put("closed", closed);
    out.put("hasDemand", latestDemand != null);
    out.put("hasActiveRequest", activeRequest != null);
    out.put("hasUnavailableRange", !unavailable.isEmpty());
    if (latestDemand != null) out.put("latestDemandEpoch", latestDemand.demandEpoch);
    if (activeRequest != null) {
      out.put("activeCallId", activeRequest.callId);
      out.put("activeFromSeq", activeRequest.range.fromSeq);
      out.put("activeToSeq", activeRequest.range.toSeq);
      out.put("activeDemandEpoch", activeRequest.range.demandEpoch);
      out.put("activeRequestState", activeRequest.state().name());
    }
    out.putAll(metrics.snapshot());
    return out;
  }

  public synchronized void clearDemand() {
    latestDemand = null;
    clearLatestDemandProjection();
    clearTailDebounce();
  }

  public synchronized void resetLifecycle() {
    if (activeRequest != null && activeRequest.handle != null) {
      activeRequest.state.set(RequestState.CANCELLED);
      activeRequest.handle.cancel();
      metrics.onRequestCancelled();
    }
    activeRequest = null;
    clearTailDebounce();
    clearObservedServerExtent();
    clearLatestDemandProjection();
    consecutiveSessionNotReadyFailures = 0;
    lifecycleEpoch++;
  }

  public synchronized void close() {
    closed = true;
    latestDemand = null;
    resetLifecycle();
  }

  @Nullable
  public synchronized Range firstMissingRange(
      @NonNull String instanceId, long layoutEpoch, long generation,
      @NonNull HistoryExtent extent,
      @NonNull HistoryRenderView history) {
    Demand demand = latestDemand;
    if (closed || demand == null || extent.isEmpty()) return null;
    return firstMissingRangeForDemand(
        demand, instanceId, layoutEpoch, generation, extent, history);
  }

  @Nullable
  private Range firstMissingRangeForDemand(
      @NonNull Demand demand,
      @NonNull String instanceId, long layoutEpoch, long generation,
      @NonNull HistoryExtent extent,
      @NonNull HistoryRenderView history) {
    if (extent.isEmpty()) return null;
    ensureObservedProjection(instanceId, layoutEpoch, generation);
    long from = Math.max(Math.max(extent.firstSeq, demand.visibleFromSeq),
        observedServerFirstSeq);
    long to = Math.min(extent.lastSeq, demand.visibleToSeq);
    if (from > to) return null;
    long missingFrom = 0;
    long missingTo = 0;
    for (long seq = from; seq <= to; seq++) {
      if (unavailable.contains(seq)) {
        if (missingFrom != 0) break;
        continue;
      }
      int index = history.findSeqIndex(seq);
      boolean missing = index >= 0 && history.slotStateAt(index) != SlotState.LOADED;
      if (missing && missingFrom == 0) missingFrom = seq;
      if (missing) missingTo = seq;
      if (!missing && missingFrom != 0) break;
      if (seq == Long.MAX_VALUE) break;
    }
    if (missingFrom == 0) return null;

    long minAllowed = extent.firstSeq;
    long lowerBarrier = unavailable.lowerBarrier(missingFrom);
    if (lowerBarrier > 0) minAllowed = Math.max(minAllowed, lowerBarrier + 1);
    long maxAllowed = extent.lastSeq;
    long upperBarrier = unavailable.upperBarrier(missingTo);
    if (upperBarrier != Long.MAX_VALUE) maxAllowed = Math.min(maxAllowed, upperBarrier - 1);
    long desired = Math.max(
        missingTo - missingFrom + 1, fetchPolicy.desiredBatchLines(demand));
    long[] expanded = expand(
        missingFrom, missingTo, minAllowed, maxAllowed, desired, demand.direction);
    return new Range(
        instanceId, layoutEpoch, generation, expanded[0], expanded[1], demand.demandEpoch,
        missingTo - missingFrom + 1);
  }

  private boolean activeRequestCovers(@NonNull Range target) {
    if (activeRequest == null || activeRequest.state() != RequestState.FETCHING) return false;
    Range active = activeRequest.range;
    return active.instanceId.equals(target.instanceId)
        && active.layoutEpoch == target.layoutEpoch
        && active.generation == target.generation
        && active.fromSeq <= target.fromSeq
        && active.toSeq >= target.toSeq;
  }

  private boolean activeRequestCoversVisibleDemand(
      @NonNull Demand demand,
      @NonNull String instanceId, long layoutEpoch, long generation) {
    if (activeRequest == null || activeRequest.state() != RequestState.FETCHING) return false;
    Range active = activeRequest.range;
    return active.instanceId.equals(instanceId)
        && active.layoutEpoch == layoutEpoch
        && active.generation == generation
        && active.fromSeq <= demand.visibleFromSeq
        && active.toSeq >= demand.visibleToSeq;
  }

  private boolean sameProjectionIdentity(
      @NonNull String instanceId, long layoutEpoch, long generation) {
    return instanceId.equals(latestDemandInstanceId)
        && layoutEpoch == latestDemandLayoutEpoch
        && generation == latestDemandGeneration;
  }

  private void clearLatestDemandProjection() {
    latestDemandInstanceId = "";
    latestDemandLayoutEpoch = 0;
    latestDemandGeneration = 0;
    hasLatestPlannedFetch = false;
    latestPlannedFetchInstanceId = "";
    latestPlannedFetchLayoutEpoch = 0;
    latestPlannedFetchGeneration = 0;
    latestPlannedFetchFromSeq = 0;
    latestPlannedFetchToSeq = 0;
  }

  private boolean samePlannedFetch(@NonNull Range range) {
    return hasLatestPlannedFetch
        && latestPlannedFetchInstanceId.equals(range.instanceId)
        && latestPlannedFetchLayoutEpoch == range.layoutEpoch
        && latestPlannedFetchGeneration == range.generation
        && latestPlannedFetchFromSeq == range.fromSeq
        && latestPlannedFetchToSeq == range.toSeq;
  }

  private void rememberPlannedFetch(@Nullable Range range) {
    if (range == null) {
      hasLatestPlannedFetch = false;
      return;
    }
    hasLatestPlannedFetch = true;
    latestPlannedFetchInstanceId = range.instanceId;
    latestPlannedFetchLayoutEpoch = range.layoutEpoch;
    latestPlannedFetchGeneration = range.generation;
    latestPlannedFetchFromSeq = range.fromSeq;
    latestPlannedFetchToSeq = range.toSeq;
  }

  public synchronized boolean shouldCancelFor(@NonNull Demand next) {
    return activeRequest != null
        && activeRequest.state() == RequestState.FETCHING
        && fetchPolicy.shouldCancel(activeRequest.range, next);
  }

  public synchronized boolean cancelActiveForDemand() {
    return cancelActive(RequestState.CANCELLED);
  }

  public synchronized boolean timeout(@NonNull ActiveRequest expected) {
    if (activeRequest != expected || expected.state() != RequestState.FETCHING) return false;
    return cancelActive(RequestState.TIMED_OUT);
  }

  public synchronized void responseArrived(
      @NonNull ActiveRequest request, long arrivedAtNanos) {
    request.responseArrived(arrivedAtNanos);
  }

  public synchronized boolean beginApplying(@NonNull ActiveRequest expected) {
    return activeRequest == expected
        && expected.state.compareAndSet(RequestState.RESPONSE_QUEUED, RequestState.APPLYING);
  }

  public synchronized boolean sameLifecycle(@NonNull ActiveRequest request) {
    return !closed && request.lifecycleEpoch == lifecycleEpoch;
  }

  public synchronized boolean usefulForLatestDemand(@NonNull ActiveRequest request) {
    return completionDisposition(request) != CompletionDisposition.OBSOLETE;
  }

  @NonNull
  public synchronized CompletionDisposition completionDisposition(
      @NonNull ActiveRequest request) {
    Demand demand = latestDemand;
    if (demand == null || !HistoryFetchPolicy.overlaps(
        request.range.fromSeq, request.range.toSeq,
        demand.visibleFromSeq, demand.visibleToSeq)) {
      return CompletionDisposition.OBSOLETE;
    }
    return request.range.demandEpoch == demand.demandEpoch
        ? CompletionDisposition.CURRENT : CompletionDisposition.PARTIAL;
  }

  public synchronized long armTailDebounce(
      @NonNull Range range, @NonNull HistoryExtent extent) {
    Demand demand = latestDemand;
    if (demand == null) return 0;
    if (!fetchPolicy.shouldDebounceTail(range, demand, extent.lastSeq)) {
      if (tailDebounceDemandEpoch != 0) clearTailDebounce();
      return 0;
    }
    if (tailDebounceSatisfiedEpoch == demand.demandEpoch) return 0;
    if (tailDebounceDemandEpoch != 0) {
      tailDebounceDemandEpoch = demand.demandEpoch;
      if (extent.lastSeq > tailDebounceAuthoritativeLastSeq) {
        tailDebounceAuthoritativeLastSeq = extent.lastSeq;
        long elapsedNanos = System.nanoTime() - tailDebounceStartedAtNanos;
        if (elapsedNanos >= HistoryFetchPolicy.TAIL_MAX_WAIT_MS * 1_000_000L) {
          tailDebounceSatisfiedEpoch = demand.demandEpoch;
          tailDebounceDemandEpoch = 0;
          return 0;
        }
        return ++tailDebounceToken;
      }
      return -1;
    }
    tailDebounceDemandEpoch = demand.demandEpoch;
    tailDebounceStartedAtNanos = System.nanoTime();
    tailDebounceAuthoritativeLastSeq = extent.lastSeq;
    tailDebounceToken++;
    metrics.onTailDebounce();
    return tailDebounceToken;
  }

  public synchronized boolean releaseTailDebounce(long token) {
    if (token <= 0 || token != tailDebounceToken || latestDemand == null
        || tailDebounceDemandEpoch != latestDemand.demandEpoch) {
      return false;
    }
    tailDebounceSatisfiedEpoch = latestDemand.demandEpoch;
    tailDebounceDemandEpoch = 0;
    return true;
  }

  public synchronized long tailDebounceDelayMs(long token) {
    if (token <= 0 || token != tailDebounceToken || tailDebounceDemandEpoch == 0) {
      return 0L;
    }
    long elapsedNanos = Math.max(0L, System.nanoTime() - tailDebounceStartedAtNanos);
    long remainingMs = Math.max(0L,
        HistoryFetchPolicy.TAIL_MAX_WAIT_MS - elapsedNanos / 1_000_000L);
    return Math.min(HistoryFetchPolicy.TAIL_QUIET_PERIOD_MS, remainingMs);
  }

  /**
   * 记录 HTTP 已观察到的服务端最旧水位，只抑制已被服务端裁剪区域的重复请求。
   * 它不修改 WS extent、HistoryCatalog 或渲染范围。
   */
  public synchronized void observeServerExtent(
      @NonNull String instanceId, long layoutEpoch, long generation,
      @NonNull HistoryExtent extent) {
    ensureObservedProjection(instanceId, layoutEpoch, generation);
    if (extent.firstSeq > observedServerFirstSeq) {
      observedServerFirstSeq = extent.firstSeq;
    }
  }

  /** 隔离损坏正文区间，避免缓存故障形成无限 HTTP 循环；不影响 WS 投影。 */
  public synchronized void markRangeUnavailable(@NonNull Range range) {
    markRangeUnavailable(range, range.fromSeq, range.toSeq, "PROTOCOL");
  }

  public synchronized void markRangeUnavailable(
      @NonNull Range range, long fromSeq, long toSeq, @NonNull String fault) {
    ensureObservedProjection(range.instanceId, range.layoutEpoch, range.generation);
    unavailable.add(
        Math.max(range.fromSeq, fromSeq),
        Math.min(range.toSeq, toSeq),
        fault);
    metrics.onRangeUnavailableProtocol();
  }

  public synchronized int noteSessionNotReadyFailure() {
    consecutiveSessionNotReadyFailures++;
    metrics.onSessionNotReady();
    return consecutiveSessionNotReadyFailures;
  }

  public synchronized void noteSessionGone() {
    metrics.onSessionGone();
    consecutiveSessionNotReadyFailures = 0;
  }

  public synchronized void clearTransientFailures() {
    consecutiveSessionNotReadyFailures = 0;
  }

  /** WS 已权威声明该位置的新绑定，旧正文故障不再阻止新 LineKey 加载。 */
  public synchronized void onAuthoritativeBinding(
      @NonNull String instanceId, long layoutEpoch, long generation, long historySeq) {
    ensureObservedProjection(instanceId, layoutEpoch, generation);
    unavailable.remove(historySeq);
  }

  public synchronized boolean begin(
      @NonNull Range range, @NonNull HistoryRangeSource.RequestHandle handle) {
    if (closed || activeRequest != null) {
      handle.cancel();
      return false;
    }
    activeRequest = new ActiveRequest(nextCallId++, lifecycleEpoch, range, handle);
    metrics.onRequestStarted(range.toSeq - range.fromSeq + 1);
    return true;
  }

  public synchronized boolean isActive(@NonNull ActiveRequest expected) {
    RequestState state = expected.state();
    return !closed && activeRequest == expected && expected.lifecycleEpoch == lifecycleEpoch
        && state != RequestState.CANCELLED && state != RequestState.TIMED_OUT
        && state != RequestState.COMPLETED;
  }

  public synchronized boolean complete(@NonNull ActiveRequest expected) {
    if (activeRequest != expected) return false;
    expected.state.set(RequestState.COMPLETED);
    activeRequest = null;
    return true;
  }

  private boolean cancelActive(RequestState terminalState) {
    ActiveRequest active = activeRequest;
    if (active == null
        || !active.state.compareAndSet(RequestState.FETCHING, terminalState)) {
      return false;
    }
    activeRequest = null;
    active.handle.cancel();
    metrics.onRequestCancelled();
    return true;
  }

  private void ensureObservedProjection(
      @NonNull String instanceId, long layoutEpoch, long generation) {
    if (!instanceId.equals(observedInstanceId)
        || layoutEpoch != observedLayoutEpoch || generation != observedGeneration) {
      clearObservedServerExtent();
      observedInstanceId = instanceId;
      observedLayoutEpoch = layoutEpoch;
      observedGeneration = generation;
    }
  }

  private void clearObservedServerExtent() {
    observedInstanceId = "";
    observedLayoutEpoch = 0;
    observedGeneration = 0;
    observedServerFirstSeq = 0;
    unavailable.clear();
  }

  private void clearTailDebounce() {
    tailDebounceDemandEpoch = 0;
    tailDebounceSatisfiedEpoch = 0;
    tailDebounceStartedAtNanos = 0L;
    tailDebounceAuthoritativeLastSeq = 0L;
    tailDebounceToken++;
  }

  private static long[] expand(
      long from, long to, long min, long max, long desired, int direction) {
    long targetFrom = from;
    long targetTo = to;
    if (direction < 0) {
      long add = Math.min(desired - size(targetFrom, targetTo), targetFrom - min);
      targetFrom -= Math.max(0, add);
    } else if (direction > 0) {
      long add = Math.min(desired - size(targetFrom, targetTo), max - targetTo);
      targetTo += Math.max(0, add);
    } else {
      long remaining = desired - size(targetFrom, targetTo);
      long older = Math.min((remaining + 1) / 2, targetFrom - min);
      targetFrom -= Math.max(0, older);
      remaining = desired - size(targetFrom, targetTo);
      long newer = Math.min(remaining, max - targetTo);
      targetTo += Math.max(0, newer);
      remaining = desired - size(targetFrom, targetTo);
      targetFrom -= Math.min(Math.max(0, remaining), targetFrom - min);
    }
    return new long[] {targetFrom, targetTo};
  }

  private static long size(long from, long to) {
    return to - from + 1;
  }
}
