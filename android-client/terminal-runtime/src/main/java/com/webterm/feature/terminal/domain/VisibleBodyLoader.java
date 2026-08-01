package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.BodyCache;
import com.webterm.terminal.model.HistoryCatalog;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryRenderView;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.SlotState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 按可见 seq 与滚动方向规划缺失 LineKey 批次，单在途 batch 拉取正文。
 *
 * <p>批次至少 {@link LineBodyFetchPolicy#MIN_BATCH_KEYS}，最多
 * {@link LineBodyFetchPolicy#MAX_BATCH_KEYS}，优先覆盖当前视口再方向性预取。</p>
 */
public final class VisibleBodyLoader {
  /** @deprecated 使用 {@link LineBodyFetchPolicy#MIN_BATCH_KEYS}。 */
  @Deprecated
  public static final int MIN_BATCH_KEYS = LineBodyFetchPolicy.MIN_BATCH_KEYS;
  /** @deprecated 使用 {@link LineBodyFetchPolicy#MAX_BATCH_KEYS}。 */
  @Deprecated
  public static final int MAX_BATCH_KEYS = LineBodyFetchPolicy.MAX_BATCH_KEYS;

  public static final class Demand {
    public final long visibleFromSeq;
    public final long visibleToSeq;
    public final long anchorSeq;
    public final int direction;
    public final int visibleRowCount;
    public final long demandEpoch;
    public final long createdAtNanos;

    public Demand(
        long visibleFromSeq, long visibleToSeq, long anchorSeq, int direction,
        int visibleRowCount, long demandEpoch, long createdAtNanos) {
      this.visibleFromSeq = visibleFromSeq;
      this.visibleToSeq = visibleToSeq;
      this.anchorSeq = anchorSeq;
      this.direction = direction;
      this.visibleRowCount = visibleRowCount;
      this.demandEpoch = demandEpoch;
      this.createdAtNanos = createdAtNanos;
    }
  }

  public static final class Batch {
    public final String instanceId;
    public final long layoutEpoch;
    public final long historyGeneration;
    public final List<LineKey> keys;
    public final long demandEpoch;
    public final long plannedFromSeq;
    public final long plannedToSeq;
    public final int visibleKeyCount;
    public final int prefetchKeyCount;

    public Batch(
        String instanceId, long layoutEpoch, long historyGeneration,
        List<LineKey> keys, long demandEpoch,
        long plannedFromSeq, long plannedToSeq,
        int visibleKeyCount, int prefetchKeyCount) {
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.historyGeneration = historyGeneration;
      this.keys = List.copyOf(keys);
      this.demandEpoch = demandEpoch;
      this.plannedFromSeq = plannedFromSeq;
      this.plannedToSeq = plannedToSeq;
      this.visibleKeyCount = visibleKeyCount;
      this.prefetchKeyCount = prefetchKeyCount;
    }
  }

  public static final class ActiveRequest {
    public final long callId;
    public final Batch batch;
    public final LineBodyBatchSource.RequestHandle handle;
    private final AtomicReference<State> state = new AtomicReference<>(State.FETCHING);

    enum State { FETCHING, APPLYING, COMPLETED, CANCELLED }

    ActiveRequest(long callId, Batch batch, LineBodyBatchSource.RequestHandle handle) {
      this.callId = callId;
      this.batch = batch;
      this.handle = handle;
    }

    State state() { return state.get(); }

    boolean beginApplying() {
      return state.compareAndSet(State.FETCHING, State.APPLYING);
    }

    void complete() {
      state.set(State.COMPLETED);
    }

    void cancel() {
      state.set(State.CANCELLED);
    }
  }

  private final LineBodyFetchPolicy fetchPolicy = new LineBodyFetchPolicy();

  @Nullable private Demand latestDemand;
  @Nullable private ActiveRequest activeRequest;
  private long nextCallId = 1;
  private long nextDemandEpoch = 1;
  private boolean closed;

  public synchronized void setDemand(@Nullable Demand demand) {
    if (closed) return;
    latestDemand = demand;
    if (demand != null) {
      nextDemandEpoch = Math.max(nextDemandEpoch, demand.demandEpoch + 1);
    }
  }

  @Nullable
  public synchronized Demand acceptDemand(
      long visibleFromSeq, long visibleToSeq, long anchorSeq, int direction,
      int visibleRowCount, long createdAtNanos,
      @NonNull String instanceId, long layoutEpoch, long generation,
      @NonNull HistoryExtent extent, @NonNull HistoryRenderView history,
      @NonNull BodyCache bodyCache, @NonNull HistoryCatalog catalog) {
    if (closed || visibleFromSeq <= 0 || visibleToSeq < visibleFromSeq) return null;
    int rows = visibleRowCount > 0
        ? visibleRowCount
        : (int) Math.min(Integer.MAX_VALUE, visibleToSeq - visibleFromSeq + 1);
    Demand probe = new Demand(
        visibleFromSeq, visibleToSeq, anchorSeq, direction, rows, 0, createdAtNanos);
    Batch missing = planMissingBatch(
        probe, instanceId, layoutEpoch, generation,
        extent, history, bodyCache, catalog);
    if (missing == null) {
      latestDemand = new Demand(
          visibleFromSeq, visibleToSeq, anchorSeq, direction, rows,
          nextDemandEpoch++, createdAtNanos);
      HistoryDemandMetrics.fetchPlanReused();
      return latestDemand;
    }
    if (activeRequest != null
        && activeRequest.state() == ActiveRequest.State.FETCHING
        && activeCoversVisibleMissing(
            activeRequest.batch, visibleFromSeq, visibleToSeq,
            extent, history, bodyCache, catalog)) {
      latestDemand = new Demand(
          visibleFromSeq, visibleToSeq, anchorSeq, direction, rows,
          activeRequest.batch.demandEpoch, createdAtNanos);
      HistoryDemandMetrics.fetchCoveredByActive();
      return latestDemand;
    }
    if (activeRequest != null
        && activeRequest.state() == ActiveRequest.State.FETCHING
        && fetchPolicy.shouldCancel(
            activeRequest.batch.plannedFromSeq,
            activeRequest.batch.plannedToSeq,
            probe)) {
      ActiveRequest previous = activeRequest;
      previous.cancel();
      previous.handle.cancel();
      activeRequest = null;
      HistoryDemandMetrics.fetchCancelledForDistance();
    }
    latestDemand = new Demand(
        visibleFromSeq, visibleToSeq, anchorSeq, direction, rows,
        nextDemandEpoch++, createdAtNanos);
    HistoryDemandMetrics.fetchPlanCreated();
    return latestDemand;
  }

  @Nullable
  public synchronized Batch firstMissingBatch(
      @NonNull String instanceId, long layoutEpoch, long generation,
      long visibleFromSeq, long visibleToSeq,
      @NonNull HistoryExtent extent, @NonNull HistoryRenderView history,
      @NonNull BodyCache bodyCache, @NonNull HistoryCatalog catalog) {
    Demand demand = latestDemand;
    if (demand == null) {
      int rows = (int) Math.min(Integer.MAX_VALUE, visibleToSeq - visibleFromSeq + 1);
      demand = new Demand(
          visibleFromSeq, visibleToSeq, visibleFromSeq, 0, rows, nextDemandEpoch, 0L);
    } else if (demand.visibleFromSeq != visibleFromSeq
        || demand.visibleToSeq != visibleToSeq) {
      demand = new Demand(
          visibleFromSeq, visibleToSeq, demand.anchorSeq, demand.direction,
          demand.visibleRowCount, demand.demandEpoch, demand.createdAtNanos);
    }
    return planMissingBatch(
        demand, instanceId, layoutEpoch, generation,
        extent, history, bodyCache, catalog);
  }

  @Nullable
  public synchronized Batch planMissingBatch(
      @NonNull Demand demand,
      @NonNull String instanceId, long layoutEpoch, long generation,
      @NonNull HistoryExtent extent, @NonNull HistoryRenderView history,
      @NonNull BodyCache bodyCache, @NonNull HistoryCatalog catalog) {
    if (closed || extent.isEmpty()
        || !extent.contains(demand.visibleFromSeq)
        || !extent.contains(demand.visibleToSeq)) {
      return null;
    }
    int desired = fetchPolicy.desiredBatchKeys(demand);
    LinkedHashSet<LineKey> keys = new LinkedHashSet<>();
    long plannedFrom = Long.MAX_VALUE;
    long plannedTo = 0;
    int visibleKeyCount = 0;

    long visFrom = Math.max(demand.visibleFromSeq, extent.firstSeq);
    long visTo = Math.min(demand.visibleToSeq, extent.lastSeq);
    for (long seq = visFrom; seq <= visTo; seq++) {
      if (!tryAddMissing(keys, seq, extent, history, bodyCache, catalog)) continue;
      plannedFrom = Math.min(plannedFrom, seq);
      plannedTo = Math.max(plannedTo, seq);
      visibleKeyCount = keys.size();
      if (keys.size() >= LineBodyFetchPolicy.MAX_BATCH_KEYS) {
        return toBatch(
            instanceId, layoutEpoch, generation, keys, demand.demandEpoch,
            plannedFrom, plannedTo, visibleKeyCount);
      }
    }

    long cursorLeft = visFrom;
    long cursorRight = visTo;
    boolean preferOlder = demand.direction <= 0;
    expandUntilDesired(
        keys, cursorLeft, cursorRight, preferOlder, desired,
        extent, history, bodyCache, catalog);
    if (keys.size() < desired && demand.direction != 0) {
      expandUntilDesired(
          keys, cursorLeft, cursorRight, !preferOlder, desired,
          extent, history, bodyCache, catalog);
    }

    if (keys.isEmpty()) return null;

    long[] bounds = plannedBounds(keys, catalog, extent, visFrom, visTo);
    return toBatch(
        instanceId, layoutEpoch, generation, keys, demand.demandEpoch,
        bounds[0], bounds[1], visibleKeyCount);
  }

  public synchronized boolean begin(@NonNull Batch batch, @NonNull Runnable onCancel) {
    if (closed || activeRequest != null) return false;
    activeRequest = new ActiveRequest(nextCallId++, batch, () -> onCancel.run());
    return true;
  }

  @Nullable public synchronized ActiveRequest activeRequest() { return activeRequest; }
  @Nullable public synchronized Demand latestDemand() { return latestDemand; }

  public synchronized void complete(@NonNull ActiveRequest request) {
    if (activeRequest == request) activeRequest = null;
    request.complete();
  }

  public synchronized void clearDemand() {
    latestDemand = null;
  }

  public synchronized void close() {
    closed = true;
    if (activeRequest != null) {
      activeRequest.cancel();
      activeRequest.handle.cancel();
      activeRequest = null;
    }
    latestDemand = null;
  }

  public synchronized boolean closed() { return closed; }

  private static void expandUntilDesired(
      LinkedHashSet<LineKey> keys,
      long startLeft, long startRight,
      boolean preferOlderFirst, int desired,
      HistoryExtent extent, HistoryRenderView history,
      BodyCache bodyCache, HistoryCatalog catalog) {
    long left = startLeft;
    long right = startRight;
    while (keys.size() < desired
        && keys.size() < LineBodyFetchPolicy.MAX_BATCH_KEYS) {
      boolean extended = false;
      if (preferOlderFirst) {
        if (left > extent.firstSeq
            && tryAddMissing(keys, left - 1, extent, history, bodyCache, catalog)) {
          left--;
          extended = true;
        } else if (right < extent.lastSeq
            && tryAddMissing(keys, right + 1, extent, history, bodyCache, catalog)) {
          right++;
          extended = true;
        }
      } else if (right < extent.lastSeq
          && tryAddMissing(keys, right + 1, extent, history, bodyCache, catalog)) {
        right++;
        extended = true;
      } else if (left > extent.firstSeq
          && tryAddMissing(keys, left - 1, extent, history, bodyCache, catalog)) {
        left--;
        extended = true;
      }
      if (extended) continue;

      // 跳过已加载/无绑定位置，继续向外寻找缺口。
      boolean advanced = false;
      if (preferOlderFirst && left > extent.firstSeq) {
        left--;
        advanced = true;
      } else if (!preferOlderFirst && right < extent.lastSeq) {
        right++;
        advanced = true;
      } else if (preferOlderFirst && right < extent.lastSeq) {
        right++;
        advanced = true;
      } else if (!preferOlderFirst && left > extent.firstSeq) {
        left--;
        advanced = true;
      }
      if (!advanced) break;
    }
  }

  private static boolean tryAddMissing(
      LinkedHashSet<LineKey> keys, long seq,
      HistoryExtent extent, HistoryRenderView history,
      BodyCache bodyCache, HistoryCatalog catalog) {
    if (!extent.contains(seq) || keys.size() >= LineBodyFetchPolicy.MAX_BATCH_KEYS) {
      return false;
    }
    int index = history.findSeqIndex(seq);
    if (index < 0 || history.slotStateAt(index) == SlotState.LOADED) return false;
    LineKey key = catalog.key(seq);
    if (key == null || bodyCache.body(key) != null) return false;
    return keys.add(key);
  }

  private boolean activeCoversVisibleMissing(
      Batch active, long visibleFromSeq, long visibleToSeq,
      HistoryExtent extent, HistoryRenderView history,
      BodyCache bodyCache, HistoryCatalog catalog) {
    Set<LineKey> activeKeys = new HashSet<>(active.keys);
    long from = Math.max(visibleFromSeq, extent.firstSeq);
    long to = Math.min(visibleToSeq, extent.lastSeq);
    for (long seq = from; seq <= to; seq++) {
      int index = history.findSeqIndex(seq);
      if (index < 0 || history.slotStateAt(index) == SlotState.LOADED) continue;
      LineKey key = catalog.key(seq);
      if (key == null || bodyCache.body(key) != null) continue;
      if (!activeKeys.contains(key)) return false;
    }
    return true;
  }

  private static long[] plannedBounds(
      LinkedHashSet<LineKey> keys, HistoryCatalog catalog, HistoryExtent extent,
      long fallbackFrom, long fallbackTo) {
    long from = 0;
    long to = 0;
    for (long seq = extent.firstSeq; seq <= extent.lastSeq; seq++) {
      LineKey key = catalog.key(seq);
      if (key == null || !keys.contains(key)) continue;
      if (from == 0) from = seq;
      to = seq;
    }
    if (from == 0) return new long[] {fallbackFrom, fallbackTo};
    return new long[] {from, to};
  }

  private static Batch toBatch(
      String instanceId, long layoutEpoch, long generation,
      LinkedHashSet<LineKey> keys, long demandEpoch,
      long plannedFrom, long plannedTo, int visibleKeyCount) {
    int prefetch = Math.max(0, keys.size() - visibleKeyCount);
    return new Batch(
        instanceId, layoutEpoch, generation, new ArrayList<>(keys), demandEpoch,
        plannedFrom, plannedTo, visibleKeyCount, prefetch);
  }
}
