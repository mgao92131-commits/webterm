package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.PagedTerminalHistorySnapshot;
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
  private boolean closed;

  public synchronized void setDemand(@Nullable Demand demand) {
    if (!closed) latestDemand = demand;
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
      @NonNull PagedTerminalHistorySnapshot history) {
    Demand demand = latestDemand;
    if (closed || demand == null || extent.isEmpty()) return null;
    long from = Math.max(extent.firstSeq, demand.visibleFromSeq);
    long to = Math.min(extent.lastSeq, demand.visibleToSeq);
    long missingFrom = 0;
    long missingTo = 0;
    for (long seq = from; seq <= to; seq++) {
      int index = history.findSeqIndex(seq);
      boolean missing = index >= 0 && history.slotStateAt(index) != SlotState.LOADED;
      if (missing && missingFrom == 0) missingFrom = seq;
      if (missing) missingTo = seq;
      if (!missing && missingFrom != 0) break;
      if (seq == Long.MAX_VALUE) break;
    }
    if (missingFrom == 0) return null;
    if (demand.direction < 0) missingFrom = Math.max(extent.firstSeq, missingFrom - PREFETCH_LINES);
    if (demand.direction > 0) missingTo = Math.min(extent.lastSeq, missingTo + PREFETCH_LINES);
    return new Range(instanceId, layoutEpoch, generation, missingFrom, missingTo);
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
