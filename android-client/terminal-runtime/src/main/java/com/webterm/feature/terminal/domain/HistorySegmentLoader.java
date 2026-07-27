package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.HistoryCatalog;
import com.webterm.terminal.model.PagedTerminalHistorySnapshot;
import com.webterm.terminal.model.SegmentKey;
import com.webterm.terminal.model.SlotState;

/**
 * 基于视口的单活动分段加载器：无 pendingRange 合并、无长期队列。
 * 普通滚动不取消 activeRequest。
 */
public final class HistorySegmentLoader {
  public static final class Demand {
    public final long visibleFromSeq;
    public final long visibleToSeq;
    public final long anchorSeq;
    public final int direction; // -1 older, +1 newer, 0 unknown
    public final int visibleRowCount;

    public Demand(long visibleFromSeq, long visibleToSeq, long anchorSeq,
                  int direction, int visibleRowCount) {
      this.visibleFromSeq = visibleFromSeq;
      this.visibleToSeq = visibleToSeq;
      this.anchorSeq = anchorSeq;
      this.direction = direction;
      this.visibleRowCount = visibleRowCount;
    }
  }

  public static final class ActiveRequest {
    public final long callId;
    public final long lifecycleEpoch;
    public final SegmentKey key;
    public final HistorySegmentSource.RequestHandle handle;

    ActiveRequest(long callId, long lifecycleEpoch, SegmentKey key,
                  HistorySegmentSource.RequestHandle handle) {
      this.callId = callId;
      this.lifecycleEpoch = lifecycleEpoch;
      this.key = key;
      this.handle = handle;
    }
  }

  private Demand latestDemand;
  private ActiveRequest activeRequest;
  private long nextCallId = 1;
  private long lifecycleEpoch = 1;
  private boolean closed;

  public synchronized void setDemand(@Nullable Demand demand) {
    if (closed) return;
    latestDemand = demand;
  }

  public synchronized void clearDemand() {
    latestDemand = null;
  }

  public synchronized Demand latestDemand() {
    return latestDemand;
  }

  public synchronized ActiveRequest activeRequest() {
    return activeRequest;
  }

  public synchronized long lifecycleEpoch() {
    return lifecycleEpoch;
  }

  public synchronized boolean closed() {
    return closed;
  }

  /** identity/close 变化时取消活动请求并推进 lifecycle。 */
  public synchronized void resetLifecycle(@Nullable HistorySegmentSource.RequestHandle toCancel) {
    if (activeRequest != null && activeRequest.handle != null) {
      activeRequest.handle.cancel();
    }
    activeRequest = null;
    lifecycleEpoch++;
    if (toCancel != null) toCancel.cancel();
  }

  public synchronized void close() {
    closed = true;
    latestDemand = null;
    resetLifecycle(null);
  }

  public synchronized boolean begin(@NonNull SegmentKey key,
                                    @NonNull HistorySegmentSource.RequestHandle handle) {
    if (closed || activeRequest != null) {
      handle.cancel();
      return false;
    }
    activeRequest = new ActiveRequest(nextCallId++, lifecycleEpoch, key, handle);
    return true;
  }

  public synchronized boolean complete(@NonNull ActiveRequest expected) {
    if (activeRequest != expected) return false;
    activeRequest = null;
    return true;
  }

  public synchronized boolean isActive(@NonNull ActiveRequest expected) {
    return !closed && activeRequest == expected;
  }

  /**
   * 从可见范围派生最高优先级缺失 SegmentKey；排除 resident 与 in-flight。
   * 仅选择 lastSeq &lt;= sealedThroughSeq 的段。
   */
  @Nullable
  public synchronized SegmentKey highestPriorityMissing(
      @NonNull HistoryCatalog catalog,
      @NonNull PagedTerminalHistorySnapshot history) {
    if (closed || latestDemand == null || catalog.generation < 1
        || catalog.sealedThroughSeq < 1) {
      return null;
    }
    Demand demand = latestDemand;
    long from = Math.max(demand.visibleFromSeq, catalog.trimBeforeSeq);
    long to = Math.min(demand.visibleToSeq, catalog.sealedThroughSeq);
    to = Math.min(to, history.extent().lastSeq);
    from = Math.max(from, history.extent().firstSeq);
    if (from <= 0 || to < from) {
      // 尝试方向预取
      return prefetchOnly(catalog, history, demand);
    }
    SegmentKey inFlight = activeRequest != null ? activeRequest.key : null;
    SegmentKey best = firstMissingInRange(catalog, history, from, to, inFlight);
    if (best != null) return best;
    return prefetchOnly(catalog, history, demand);
  }

  @Nullable
  private SegmentKey prefetchOnly(HistoryCatalog catalog, PagedTerminalHistorySnapshot history,
                                  Demand demand) {
    if (demand.direction == 0) return null;
    long anchor = demand.anchorSeq > 0 ? demand.anchorSeq : demand.visibleFromSeq;
    long prefetchSeq = demand.direction < 0
        ? Math.max(1, anchor - SegmentKey.SIZE)
        : anchor + SegmentKey.SIZE;
    if (prefetchSeq > catalog.sealedThroughSeq || prefetchSeq < catalog.trimBeforeSeq) {
      return null;
    }
    SegmentKey key = SegmentKey.forSeq(catalog.generation, prefetchSeq);
    if (activeRequest != null && key.equals(activeRequest.key)) return null;
    if (!hasUnloadedInSegment(history, key, catalog)) return null;
    return key;
  }

  @Nullable
  private static SegmentKey firstMissingInRange(
      HistoryCatalog catalog, PagedTerminalHistorySnapshot history,
      long from, long to, @Nullable SegmentKey inFlight) {
    long seq = from;
    while (seq <= to) {
      SegmentKey key = SegmentKey.forSeq(catalog.generation, seq);
      if (inFlight == null || !key.equals(inFlight)) {
        if (hasUnloadedInSegment(history, key, catalog)) return key;
      }
      seq = key.lastSeq() + 1;
    }
    return null;
  }

  private static boolean hasUnloadedInSegment(
      PagedTerminalHistorySnapshot history, SegmentKey key, HistoryCatalog catalog) {
    long from = Math.max(key.firstSeq(), Math.max(catalog.trimBeforeSeq, history.extent().firstSeq));
    long to = Math.min(key.lastSeq(),
        Math.min(catalog.sealedThroughSeq, history.extent().lastSeq));
    if (from > to) return false;
    for (long seq = from; seq <= to; seq++) {
      int index = history.findSeqIndex(seq);
      if (index >= 0 && history.slotStateAt(index) == SlotState.UNLOADED) return true;
    }
    return false;
  }
}