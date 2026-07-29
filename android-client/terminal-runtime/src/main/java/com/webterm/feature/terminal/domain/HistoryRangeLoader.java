package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryRenderView;
import com.webterm.terminal.model.SlotState;

import java.util.LinkedHashMap;
import java.util.Map;

/** 每会话单在途、只保留最新视口 Demand 的历史 Range 状态机。 */
public final class HistoryRangeLoader {
  private static final long PREFETCH_LINES = 64;

  public static final class Demand {
    public final long visibleFromSeq, visibleToSeq, anchorSeq;
    public final int direction;

    public Demand(long visibleFromSeq, long visibleToSeq, long anchorSeq, int direction) {
      this.visibleFromSeq = visibleFromSeq;
      this.visibleToSeq = visibleToSeq;
      this.anchorSeq = anchorSeq;
      this.direction = direction;
    }
  }

  public static final class Range {
    public final String instanceId;
    public final long layoutEpoch, generation, fromSeq, toSeq;

    public Range(String instanceId, long layoutEpoch, long generation, long fromSeq, long toSeq) {
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.generation = generation;
      this.fromSeq = fromSeq;
      this.toSeq = toSeq;
    }
  }

  public static final class ActiveRequest {
    public final long callId, lifecycleEpoch, startedAtNanos;
    public final Range range;
    public final HistoryRangeSource.RequestHandle handle;

    ActiveRequest(long callId, long lifecycleEpoch, Range range,
                  HistoryRangeSource.RequestHandle handle) {
      this.callId = callId;
      this.lifecycleEpoch = lifecycleEpoch;
      this.range = range;
      this.handle = handle;
      this.startedAtNanos = System.nanoTime();
    }
  }

  @Nullable private Demand latestDemand;
  @Nullable private ActiveRequest activeRequest;
  private long lifecycleEpoch = 1;
  private long nextCallId = 1;
  private String observedInstanceId = "";
  private long observedLayoutEpoch;
  private long observedGeneration;
  private long observedServerFirstSeq;
  private final UnavailableIntervalSet unavailable = new UnavailableIntervalSet();
  private boolean closed;

  public synchronized void setDemand(@Nullable Demand demand) {
    if (closed) return;
    latestDemand = demand;
  }

  @Nullable public synchronized Demand latestDemand() {
    return latestDemand;
  }

  @Nullable public synchronized ActiveRequest activeRequest() {
    return activeRequest;
  }

  public synchronized long lifecycleEpoch() {
    return lifecycleEpoch;
  }

  public synchronized boolean closed() {
    return closed;
  }

  public synchronized Map<String, Object> diagnosticsSnapshot() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("lifecycleEpoch", lifecycleEpoch);
    out.put("closed", closed);
    out.put("hasDemand", latestDemand != null);
    out.put("hasActiveRequest", activeRequest != null);
    out.put("hasUnavailableRange", !unavailable.isEmpty());
    if (activeRequest != null) {
      out.put("activeCallId", activeRequest.callId);
      out.put("activeFromSeq", activeRequest.range.fromSeq);
      out.put("activeToSeq", activeRequest.range.toSeq);
    }
    return out;
  }

  public synchronized void clearDemand() {
    latestDemand = null;
  }

  public synchronized void resetLifecycle() {
    if (activeRequest != null && activeRequest.handle != null) activeRequest.handle.cancel();
    activeRequest = null;
    clearObservedServerExtent();
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
    if (demand.direction < 0) {
      long barrier = unavailable.lowerBarrier(missingFrom);
      missingFrom = Math.max(
          Math.max(extent.firstSeq, missingFrom - PREFETCH_LINES),
          barrier == Long.MAX_VALUE ? Long.MAX_VALUE : barrier + 1);
    }
    if (demand.direction > 0) {
      long barrier = unavailable.upperBarrier(missingTo);
      missingTo = Math.min(
          Math.min(extent.lastSeq, missingTo + PREFETCH_LINES),
          barrier == Long.MAX_VALUE ? Long.MAX_VALUE : barrier - 1);
    }
    return new Range(instanceId, layoutEpoch, generation, missingFrom, missingTo);
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
  }

  /**
   * WS 已权威声明该位置的新绑定；旧正文故障不应阻止为新 LineKey 再次取回正文。
   */
  public synchronized void onAuthoritativeBinding(
      @NonNull String instanceId, long layoutEpoch, long generation, long historySeq) {
    ensureObservedProjection(instanceId, layoutEpoch, generation);
    unavailable.remove(historySeq);
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

  public synchronized boolean begin(
      @NonNull Range range, @NonNull HistoryRangeSource.RequestHandle handle) {
    if (closed || activeRequest != null) {
      handle.cancel();
      return false;
    }
    activeRequest = new ActiveRequest(nextCallId++, lifecycleEpoch, range, handle);
    return true;
  }

  public synchronized boolean isActive(@NonNull ActiveRequest expected) {
    return !closed && activeRequest == expected && expected.lifecycleEpoch == lifecycleEpoch;
  }

  public synchronized boolean complete(@NonNull ActiveRequest expected) {
    if (activeRequest != expected) return false;
    activeRequest = null;
    return true;
  }
}
