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

/** 按可见 seq 范围收集缺失 LineKey，单在途 batch 拉取正文。 */
public final class VisibleBodyLoader {
  public static final int MIN_BATCH_KEYS = 128;
  public static final int MAX_BATCH_KEYS = 256;

  public static final class Demand {
    public final long visibleFromSeq;
    public final long visibleToSeq;
    public final long demandEpoch;

    public Demand(long visibleFromSeq, long visibleToSeq, long demandEpoch) {
      this.visibleFromSeq = visibleFromSeq;
      this.visibleToSeq = visibleToSeq;
      this.demandEpoch = demandEpoch;
    }
  }

  public static final class Batch {
    public final String instanceId;
    public final long layoutEpoch;
    public final long historyGeneration;
    public final List<LineKey> keys;
    public final long demandEpoch;

    public Batch(
        String instanceId, long layoutEpoch, long historyGeneration,
        List<LineKey> keys, long demandEpoch) {
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.historyGeneration = historyGeneration;
      this.keys = List.copyOf(keys);
      this.demandEpoch = demandEpoch;
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
      long visibleFromSeq, long visibleToSeq,
      @NonNull String instanceId, long layoutEpoch, long generation,
      @NonNull HistoryExtent extent, @NonNull HistoryRenderView history,
      @NonNull BodyCache bodyCache, @NonNull HistoryCatalog catalog) {
    if (closed || visibleFromSeq <= 0 || visibleToSeq < visibleFromSeq) return null;
    Batch missing = firstMissingBatch(
        instanceId, layoutEpoch, generation, visibleFromSeq, visibleToSeq,
        extent, history, bodyCache, catalog);
    if (missing == null) {
      latestDemand = new Demand(visibleFromSeq, visibleToSeq, nextDemandEpoch++);
      return latestDemand;
    }
    if (activeRequest != null && sameKeys(activeRequest.batch.keys, missing.keys)) {
      latestDemand = new Demand(visibleFromSeq, visibleToSeq, activeRequest.batch.demandEpoch);
      return latestDemand;
    }
    latestDemand = new Demand(visibleFromSeq, visibleToSeq, nextDemandEpoch++);
    return latestDemand;
  }

  @Nullable
  public synchronized Batch firstMissingBatch(
      @NonNull String instanceId, long layoutEpoch, long generation,
      long visibleFromSeq, long visibleToSeq,
      @NonNull HistoryExtent extent, @NonNull HistoryRenderView history,
      @NonNull BodyCache bodyCache, @NonNull HistoryCatalog catalog) {
    if (closed || !extent.contains(visibleFromSeq) || !extent.contains(visibleToSeq)) {
      return null;
    }
    LinkedHashSet<LineKey> missing = new LinkedHashSet<>();
    long from = Math.max(visibleFromSeq, extent.firstSeq);
    long to = Math.min(visibleToSeq, extent.lastSeq);
    for (long seq = from; seq <= to; seq++) {
      int index = history.findSeqIndex(seq);
      if (index < 0) continue;
      if (history.slotStateAt(index) == SlotState.LOADED) continue;
      LineKey key = catalog.key(seq);
      if (key == null) continue;
      if (bodyCache.body(key) == null) missing.add(key);
      if (missing.size() >= MAX_BATCH_KEYS) break;
    }
    if (missing.isEmpty()) return null;
    List<LineKey> keys = new ArrayList<>(missing);
    if (keys.size() > MAX_BATCH_KEYS) {
      keys = keys.subList(0, MAX_BATCH_KEYS);
    }
    long epoch = latestDemand == null ? nextDemandEpoch : latestDemand.demandEpoch;
    return new Batch(instanceId, layoutEpoch, generation, keys, epoch);
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

  private static boolean sameKeys(List<LineKey> left, List<LineKey> right) {
    if (left.size() != right.size()) return false;
    for (int i = 0; i < left.size(); i++) {
      if (!left.get(i).equals(right.get(i))) return false;
    }
    return true;
  }
}
