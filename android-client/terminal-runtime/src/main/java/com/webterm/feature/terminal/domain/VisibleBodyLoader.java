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
    private final Set<LineKey> keySet;

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
      this.keySet = Set.copyOf(this.keys);
    }

    boolean containsKey(LineKey key) { return keySet.contains(key); }
  }

  static final class PendingPlan {
    @Nullable final Batch batch;

    PendingPlan(@Nullable Batch batch) {
      this.batch = batch;
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

    boolean isCancelled() { return state.get() == State.CANCELLED; }

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
  @Nullable private PendingPlan pendingPlan;
  private long nextCallId = 1;
  private long nextDemandEpoch = 1;
  private long lastPlannedDemandEpoch = Long.MIN_VALUE;
  private boolean closed;

  public synchronized void setDemand(@Nullable Demand demand) {
    if (closed) return;
    latestDemand = demand;
    // setDemand 只用于投影尚未完整时的占位需求；它没有对应的已规划批次。
    pendingPlan = null;
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
    Demand candidate = new Demand(
        visibleFromSeq, visibleToSeq, anchorSeq, direction, rows,
        nextDemandEpoch++, createdAtNanos);

    // 活动请求已经覆盖新视口时，不需要重新规划；新 Demand 复用活动请求的
    // epoch，响应仍归属于同一个在途批次。
    if (activeRequest != null
        && activeRequest.state() == ActiveRequest.State.FETCHING
        && activeCoversVisibleMissing(
            activeRequest.batch, visibleFromSeq, visibleToSeq,
            extent, history, bodyCache, catalog)) {
      // 在途请求已经覆盖当前可见缺口；请求完成后允许 pump 继续检查
      // 最新 demand，不留下一个会吞掉后续规划的空决定。
      pendingPlan = null;
      latestDemand = new Demand(
          visibleFromSeq, visibleToSeq, anchorSeq, direction, rows,
          activeRequest.batch.demandEpoch, createdAtNanos);
      HistoryDemandMetrics.fetchCoveredByActive();
      return latestDemand;
    }

    // 一个 Demand 只在这里规划一次。pumpVisibleBodies() 只消费 pendingPlan。
    Batch missing = planMissingBatch(
        candidate, instanceId, layoutEpoch, generation,
        extent, history, bodyCache, catalog);
    if (missing == null) {
      latestDemand = candidate;
      pendingPlan = new PendingPlan(null);
      HistoryDemandMetrics.fetchPlanReused();
      return latestDemand;
    }
    if (activeRequest != null
        && activeRequest.state() == ActiveRequest.State.FETCHING
        && fetchPolicy.shouldCancel(
            activeRequest.batch.plannedFromSeq,
            activeRequest.batch.plannedToSeq,
            candidate)) {
      ActiveRequest previous = activeRequest;
      previous.cancel();
      previous.handle.cancel();
      activeRequest = null;
      HistoryDemandMetrics.fetchCancelledForDistance();
    }
    latestDemand = candidate;
    pendingPlan = new PendingPlan(missing);
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
    long startedNanos = System.nanoTime();
    boolean duplicateInvocation = demand.demandEpoch > 0
        && demand.demandEpoch == lastPlannedDemandEpoch;
    if (demand.demandEpoch > 0) lastPlannedDemandEpoch = demand.demandEpoch;
    PlanState state = new PlanState();
    try {
      if (closed || extent.isEmpty()
          || !extent.contains(demand.visibleFromSeq)
          || !extent.contains(demand.visibleToSeq)) {
        return null;
      }
      int desired = fetchPolicy.desiredBatchKeys(demand);

      long visFrom = Math.max(demand.visibleFromSeq, extent.firstSeq);
      long visTo = Math.min(demand.visibleToSeq, extent.lastSeq);
      for (long seq = visFrom; seq <= visTo; seq++) {
        if (!tryAddMissing(state, seq, extent, history, bodyCache, catalog)) continue;
        state.visibleKeyCount = state.keys.size();
        if (state.keys.size() >= LineBodyFetchPolicy.MAX_BATCH_KEYS) {
          return toBatch(
              instanceId, layoutEpoch, generation, state.keys, demand.demandEpoch,
              state.plannedFromSeq, state.plannedToSeq, state.visibleKeyCount);
        }
      }

      long cursorLeft = visFrom;
      long cursorRight = visTo;
      boolean preferOlder = demand.direction <= 0;
      expandUntilDesired(
          state, cursorLeft, cursorRight, preferOlder, desired,
          extent, history, bodyCache, catalog);
      if (state.keys.size() < desired && demand.direction != 0) {
        expandUntilDesired(
            state, cursorLeft, cursorRight, !preferOlder, desired,
            extent, history, bodyCache, catalog);
      }

      if (state.keys.isEmpty()) return null;

      return toBatch(
          instanceId, layoutEpoch, generation, state.keys, demand.demandEpoch,
          state.plannedFromSeq == Long.MAX_VALUE ? visFrom : state.plannedFromSeq,
          state.plannedToSeq == 0 ? visTo : state.plannedToSeq,
          state.visibleKeyCount);
    } finally {
      HistoryDemandMetrics.planCompleted(
          demand.demandEpoch, state.seqScanned, System.nanoTime() - startedNanos,
          duplicateInvocation);
    }
  }

  /** 取出 acceptDemand 已经生成的规划决定；不会再次扫描历史。 */
  @Nullable
  synchronized PendingPlan takePendingPlan(
      @NonNull Demand demand, @NonNull String instanceId,
      long layoutEpoch, long generation) {
    PendingPlan plan = pendingPlan;
    if (plan == null) return null;
    if (plan.batch != null && (plan.batch.demandEpoch != demand.demandEpoch
        || !plan.batch.instanceId.equals(instanceId)
        || plan.batch.layoutEpoch != layoutEpoch
        || plan.batch.historyGeneration != generation)) {
      pendingPlan = null;
      return null;
    }
    pendingPlan = null;
    return plan;
  }

  /** 兼容测试/旧调用方的 batch 读取接口；空规划决定返回 null。 */
  @Nullable
  public synchronized Batch takePendingBatch(
      @NonNull Demand demand, @NonNull String instanceId,
      long layoutEpoch, long generation) {
    PendingPlan plan = takePendingPlan(demand, instanceId, layoutEpoch, generation);
    return plan == null ? null : plan.batch;
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
    pendingPlan = null;
  }

  public synchronized void close() {
    closed = true;
    if (activeRequest != null) {
      activeRequest.cancel();
      activeRequest.handle.cancel();
      activeRequest = null;
    }
    latestDemand = null;
    pendingPlan = null;
  }

  public synchronized boolean closed() { return closed; }

  private static void expandUntilDesired(
      PlanState state,
      long startLeft, long startRight,
      boolean preferOlderFirst, int desired,
      HistoryExtent extent, HistoryRenderView history,
      BodyCache bodyCache, HistoryCatalog catalog) {
    long left = startLeft;
    long right = startRight;
    while (state.keys.size() < desired
        && state.keys.size() < LineBodyFetchPolicy.MAX_BATCH_KEYS) {
      long candidate = -1;
      if (preferOlderFirst) {
        if (left > extent.firstSeq) {
          candidate = history.nearestUnloadedSeq(extent.firstSeq, left - 1, -1);
        }
        if (candidate < 0 && right < extent.lastSeq) {
          candidate = history.nearestUnloadedSeq(right + 1, extent.lastSeq, 1);
        }
      } else {
        if (right < extent.lastSeq) {
          candidate = history.nearestUnloadedSeq(right + 1, extent.lastSeq, 1);
        }
        if (candidate < 0 && left > extent.firstSeq) {
          candidate = history.nearestUnloadedSeq(extent.firstSeq, left - 1, -1);
        }
      }
      if (candidate < 0) break;
      if (candidate < left) {
        left = candidate;
      } else {
        right = candidate;
      }
      // candidate 已是页索引定位出的 UNLOADED 槽位；无绑定或正文已在缓存
      // 时仍推进边界，下一轮不会再次检查同一段热页。
      tryAddMissing(state, candidate, extent, history, bodyCache, catalog);
    }
  }

  private static boolean tryAddMissing(
      PlanState state, long seq,
      HistoryExtent extent, HistoryRenderView history,
      BodyCache bodyCache, HistoryCatalog catalog) {
    state.seqScanned++;
    LinkedHashSet<LineKey> keys = state.keys;
    if (!extent.contains(seq) || keys.size() >= LineBodyFetchPolicy.MAX_BATCH_KEYS) {
      return false;
    }
    int index = history.findSeqIndex(seq);
    if (index < 0 || history.slotStateAt(index) == SlotState.LOADED) return false;
    LineKey key = catalog.key(seq);
    if (key == null || bodyCache.body(key) != null) return false;
    if (!keys.add(key)) return false;
    state.plannedFromSeq = Math.min(state.plannedFromSeq, seq);
    state.plannedToSeq = Math.max(state.plannedToSeq, seq);
    return true;
  }

  private boolean activeCoversVisibleMissing(
      Batch active, long visibleFromSeq, long visibleToSeq,
      HistoryExtent extent, HistoryRenderView history,
      BodyCache bodyCache, HistoryCatalog catalog) {
    long from = Math.max(visibleFromSeq, extent.firstSeq);
    long to = Math.min(visibleToSeq, extent.lastSeq);
    for (long seq = from; seq <= to; seq++) {
      int index = history.findSeqIndex(seq);
      if (index < 0 || history.slotStateAt(index) == SlotState.LOADED) continue;
      LineKey key = catalog.key(seq);
      if (key == null || bodyCache.body(key) != null) continue;
      if (!active.containsKey(key)) return false;
    }
    return true;
  }

  private static final class PlanState {
    final LinkedHashSet<LineKey> keys = new LinkedHashSet<>();
    long plannedFromSeq = Long.MAX_VALUE;
    long plannedToSeq;
    int visibleKeyCount;
    int seqScanned;
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
