package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.HistoryCatalog;
import com.webterm.terminal.model.PagedTerminalHistorySnapshot;
import com.webterm.terminal.model.SegmentKey;
import com.webterm.terminal.model.SlotState;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 基于视口的单活动分段加载器：无 pendingRange 合并、无长期队列。
 * 普通滚动不取消 activeRequest。
 * 对 NOT_FOUND / TRIMMED / PROTOCOL 等坏段记入跳过集，避免队头永久阻塞。
 * NOT_SEALED 为临时封存竞态，由 Runtime 退避重试，不跳过。
 * <p>
 * Demand 字段相等时 {@link #setDemand} 去重；pump 侧用 {@link State} 状态机
 * 只在进入/离开 BLOCKED_* 时记诊断事件，避免 pump_idle 风暴。
 */
public final class HistorySegmentLoader {
  private static final int MAX_SKIPPED = 64;

  public enum State {
    READY,
    BLOCKED_NOT_LIVE,
    BLOCKED_BUSY,
    BLOCKED_NO_SOURCE,
    BLOCKED_PROJECTION_INCOMPLETE,
    BLOCKED_NOT_SEALED,
    BLOCKED_NO_DEMAND,
    BLOCKED_NO_TARGET,
    FETCHING,
    RETRY_WAIT,
    CLOSED
  }

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

    boolean sameViewportAs(@Nullable Demand other) {
      return other != null
          && visibleFromSeq == other.visibleFromSeq
          && visibleToSeq == other.visibleToSeq
          && anchorSeq == other.anchorSeq
          && direction == other.direction
          && visibleRowCount == other.visibleRowCount;
    }
  }

  public static final class ActiveRequest {
    public final long callId;
    public final long lifecycleEpoch;
    public final SegmentKey key;
    public final HistorySegmentSource.RequestHandle handle;
    public final long startedAtNanos;
    public final int retryAttempt;

    ActiveRequest(long callId, long lifecycleEpoch, SegmentKey key,
                  HistorySegmentSource.RequestHandle handle,
                  long startedAtNanos, int retryAttempt) {
      this.callId = callId;
      this.lifecycleEpoch = lifecycleEpoch;
      this.key = key;
      this.handle = handle;
      this.startedAtNanos = startedAtNanos;
      this.retryAttempt = retryAttempt;
    }
  }

  /** Pump 状态转换结果：仅在进入/离开 blocked 时需要写诊断事件。 */
  public static final class StateTransition {
    public final State previous;
    public final State next;
    @Nullable public final String previousReason;
    public final long blockedDurationMs;
    public final long suppressedPumpCount;
    public final boolean enteredBlocked;
    public final boolean leftBlocked;

    StateTransition(State previous, State next, @Nullable String previousReason,
                    long blockedDurationMs, long suppressedPumpCount,
                    boolean enteredBlocked, boolean leftBlocked) {
      this.previous = previous;
      this.next = next;
      this.previousReason = previousReason;
      this.blockedDurationMs = blockedDurationMs;
      this.suppressedPumpCount = suppressedPumpCount;
      this.enteredBlocked = enteredBlocked;
      this.leftBlocked = leftBlocked;
    }
  }

  private Demand latestDemand;
  private ActiveRequest activeRequest;
  private long nextCallId = 1;
  private long lifecycleEpoch = 1;
  private boolean closed;
  private long skippedGeneration;
  private final Set<Long> skippedNumbers = new LinkedHashSet<>();
  private long demandDeduplicatedCount;
  private State state = State.READY;
  private long blockedEnteredAtNanos;
  private long suppressedPumpCount;
  private int nextRetryAttempt;

  public synchronized boolean setDemand(@Nullable Demand demand) {
    if (closed) return false;
    if (demand != null && demand.sameViewportAs(latestDemand)) {
      demandDeduplicatedCount++;
      return false;
    }
    latestDemand = demand;
    return true;
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

  public synchronized long demandDeduplicatedCount() {
    return demandDeduplicatedCount;
  }

  public synchronized State state() {
    return state;
  }

  /** 诊断导出：loader 状态与计数（不含 segment 正文）。 */
  public synchronized Map<String, Object> diagnosticsSnapshot() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("state", state.name());
    out.put("lifecycleEpoch", lifecycleEpoch);
    out.put("closed", closed);
    out.put("demandDeduplicatedCount", demandDeduplicatedCount);
    out.put("hasDemand", latestDemand != null);
    out.put("hasActiveRequest", activeRequest != null);
    if (activeRequest != null) {
      out.put("activeCallId", activeRequest.callId);
      out.put("activeRetryAttempt", activeRequest.retryAttempt);
    }
    return out;
  }

  /** 将本 generation 下的坏段移出候选，防止反复堵在同一 SegmentKey。 */
  public synchronized void markSkipped(@NonNull SegmentKey key) {
    if (key.generation != skippedGeneration) {
      skippedNumbers.clear();
      skippedGeneration = key.generation;
    }
    skippedNumbers.add(key.number);
    while (skippedNumbers.size() > MAX_SKIPPED) {
      Iterator<Long> it = skippedNumbers.iterator();
      it.next();
      it.remove();
    }
  }

  public synchronized boolean isSkipped(@NonNull SegmentKey key) {
    return key.generation == skippedGeneration && skippedNumbers.contains(key.number);
  }

  public synchronized void clearSkipped() {
    skippedNumbers.clear();
    skippedGeneration = 0;
  }

  /** identity/close 变化时取消活动请求并推进 lifecycle。 */
  public synchronized void resetLifecycle(@Nullable HistorySegmentSource.RequestHandle toCancel) {
    if (activeRequest != null && activeRequest.handle != null) {
      activeRequest.handle.cancel();
    }
    activeRequest = null;
    clearSkipped();
    nextRetryAttempt = 0;
    lifecycleEpoch++;
    if (toCancel != null) toCancel.cancel();
  }

  public synchronized void close() {
    closed = true;
    latestDemand = null;
    resetLifecycle(null);
    transitionTo(State.CLOSED);
  }

  public synchronized boolean begin(@NonNull SegmentKey key,
                                    @NonNull HistorySegmentSource.RequestHandle handle) {
    return begin(key, handle, System.nanoTime());
  }

  public synchronized boolean begin(@NonNull SegmentKey key,
                                    @NonNull HistorySegmentSource.RequestHandle handle,
                                    long startedAtNanos) {
    if (closed || activeRequest != null) {
      handle.cancel();
      return false;
    }
    int attempt = nextRetryAttempt;
    nextRetryAttempt = 0;
    activeRequest = new ActiveRequest(
        nextCallId++, lifecycleEpoch, key, handle, startedAtNanos, attempt);
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

  /** 标记下次 begin 的 retryAttempt（NOT_SEALED 等退避重试路径）。 */
  public synchronized void markRetryPending() {
    if (closed) return;
    nextRetryAttempt++;
    transitionTo(State.RETRY_WAIT);
  }

  /**
   * 进入新 pump 状态。同 blocked 态重复进入只累计 suppressedPumpCount，不产生转换事件。
   * @return null 表示无诊断事件（同态停留）；非 null 表示进入/离开 blocked。
   */
  @Nullable
  public synchronized StateTransition observeState(@NonNull State next) {
    if (closed && next != State.CLOSED) {
      return null;
    }
    return transitionTo(next);
  }

  @Nullable
  private StateTransition transitionTo(@NonNull State next) {
    State previous = state;
    if (previous == next) {
      if (isBlocked(previous)) {
        suppressedPumpCount++;
      }
      return null;
    }
    boolean wasBlocked = isBlocked(previous);
    boolean nowBlocked = isBlocked(next);
    long durationMs = 0L;
    long suppressed = 0L;
    String previousReason = null;
    if (wasBlocked) {
      previousReason = blockedReason(previous);
      if (blockedEnteredAtNanos > 0) {
        durationMs = Math.max(0L, (System.nanoTime() - blockedEnteredAtNanos) / 1_000_000L);
      }
      suppressed = suppressedPumpCount;
    }
    state = next;
    if (nowBlocked) {
      blockedEnteredAtNanos = System.nanoTime();
      suppressedPumpCount = 0L;
    } else {
      blockedEnteredAtNanos = 0L;
      suppressedPumpCount = 0L;
    }
    boolean enteredBlocked = !wasBlocked && nowBlocked;
    boolean leftBlocked = wasBlocked && !nowBlocked;
    if (!enteredBlocked && !leftBlocked) {
      return null;
    }
    return new StateTransition(
        previous, next, previousReason, durationMs, suppressed, enteredBlocked, leftBlocked);
  }

  static boolean isBlocked(@NonNull State state) {
    switch (state) {
      case BLOCKED_NOT_LIVE:
      case BLOCKED_BUSY:
      case BLOCKED_NO_SOURCE:
      case BLOCKED_PROJECTION_INCOMPLETE:
      case BLOCKED_NOT_SEALED:
      case BLOCKED_NO_DEMAND:
      case BLOCKED_NO_TARGET:
        return true;
      default:
        return false;
    }
  }

  @Nullable
  static String blockedReason(@NonNull State state) {
    switch (state) {
      case BLOCKED_NOT_LIVE:
        return "not_live";
      case BLOCKED_BUSY:
        return "busy";
      case BLOCKED_NO_SOURCE:
        return "no_source";
      case BLOCKED_PROJECTION_INCOMPLETE:
        return "projection_incomplete";
      case BLOCKED_NOT_SEALED:
        return "sealed_zero";
      case BLOCKED_NO_DEMAND:
        return "no_demand";
      case BLOCKED_NO_TARGET:
        return "no_target";
      default:
        return null;
    }
  }

  /**
   * 从可见范围（含上下各约一段预取窗）派生最高优先级缺失 SegmentKey。
   * 排除 resident、in-flight 与跳过集；仅选择 lastSeq &lt;= sealedThroughSeq 的段。
   */
  @Nullable
  public synchronized SegmentKey highestPriorityMissing(
      @NonNull HistoryCatalog catalog,
      @NonNull PagedTerminalHistorySnapshot history) {
    if (closed || latestDemand == null || catalog.generation < 1
        || catalog.sealedThroughSeq < 1) {
      return null;
    }
    if (catalog.generation != skippedGeneration && skippedGeneration != 0) {
      clearSkipped();
    }
    Demand demand = latestDemand;
    long visibleFrom = Math.max(demand.visibleFromSeq, catalog.trimBeforeSeq);
    long visibleTo = Math.min(demand.visibleToSeq, catalog.sealedThroughSeq);
    visibleTo = Math.min(visibleTo, history.extent().lastSeq);
    visibleFrom = Math.max(visibleFrom, history.extent().firstSeq);
    SegmentKey inFlight = activeRequest != null ? activeRequest.key : null;
    if (visibleFrom > 0 && visibleTo >= visibleFrom) {
      SegmentKey best = firstMissingInRange(catalog, history, visibleFrom, visibleTo, inFlight);
      if (best != null) return best;
    }
    // 可见区内已齐：再补可见区上方一段（滚到顶的冷历史主路径）。
    long olderTo = visibleFrom > 0 ? visibleFrom - 1 : catalog.sealedThroughSeq;
    long olderFrom = Math.max(catalog.trimBeforeSeq,
        Math.max(history.extent().firstSeq, olderTo - SegmentKey.SIZE + 1));
    if (olderFrom > 0 && olderTo >= olderFrom) {
      SegmentKey older = firstMissingInRange(catalog, history, olderFrom, olderTo, inFlight);
      if (older != null) return older;
    }
    return prefetchOnly(catalog, history, demand);
  }

  @Nullable
  private SegmentKey prefetchOnly(HistoryCatalog catalog, PagedTerminalHistorySnapshot history,
                                  Demand demand) {
    // 未报告方向时默认向上补冷历史（滚到顶的主路径）。
    int direction = demand.direction != 0 ? demand.direction : -1;
    long anchor = demand.anchorSeq > 0 ? demand.anchorSeq : demand.visibleFromSeq;
    long prefetchSeq = direction < 0
        ? Math.max(1, anchor - SegmentKey.SIZE)
        : anchor + SegmentKey.SIZE;
    if (prefetchSeq > catalog.sealedThroughSeq || prefetchSeq < catalog.trimBeforeSeq) {
      return null;
    }
    SegmentKey key = SegmentKey.forSeq(catalog.generation, prefetchSeq);
    if (activeRequest != null && key.equals(activeRequest.key)) return null;
    if (isSkipped(key)) return null;
    if (!hasUnloadedInSegment(history, key, catalog)) return null;
    return key;
  }

  @Nullable
  private SegmentKey firstMissingInRange(
      HistoryCatalog catalog, PagedTerminalHistorySnapshot history,
      long from, long to, @Nullable SegmentKey inFlight) {
    long seq = from;
    while (seq <= to) {
      SegmentKey key = SegmentKey.forSeq(catalog.generation, seq);
      if ((inFlight == null || !key.equals(inFlight)) && !isSkipped(key)) {
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
