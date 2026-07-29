package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 进入 modelExecutor 前的 latest-value mailbox；SET/CLEAR 共享同一顺序轴。 */
final class HistoryDemandMailbox {
  interface Drain {
    void accept(@NonNull Update update);
  }

  static final class Update {
    final boolean clear;
    final long visibleFromSeq;
    final long visibleToSeq;
    final long anchorSeq;
    final int direction;
    final int visibleRowCount;
    final long createdAtNanos;
    final long generation;

    private Update(
        boolean clear,
        long visibleFromSeq,
        long visibleToSeq,
        long anchorSeq,
        int direction,
        int visibleRowCount,
        long createdAtNanos,
        long generation) {
      this.clear = clear;
      this.visibleFromSeq = visibleFromSeq;
      this.visibleToSeq = visibleToSeq;
      this.anchorSeq = anchorSeq;
      this.direction = direction;
      this.visibleRowCount = visibleRowCount;
      this.createdAtNanos = createdAtNanos;
      this.generation = generation;
    }
  }

  enum OfferResult { SCHEDULED, CONFLATED, DEDUPLICATED, REJECTED }

  private final Executor executor;
  private final Drain drain;
  private final AtomicReference<Update> latest = new AtomicReference<>();
  private final AtomicReference<Update> lastDelivered = new AtomicReference<>();
  private final AtomicBoolean scheduled = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong generation = new AtomicLong(1);

  HistoryDemandMailbox(@NonNull Executor executor, @NonNull Drain drain) {
    this.executor = executor;
    this.drain = drain;
  }

  OfferResult offer(
      long visibleFromSeq,
      long visibleToSeq,
      long anchorSeq,
      int direction,
      int visibleRowCount,
      long createdAtNanos) {
    return offer(new Update(
        false, visibleFromSeq, visibleToSeq, anchorSeq, direction,
        visibleRowCount, createdAtNanos, generation.get()));
  }

  OfferResult offerClear(long createdAtNanos) {
    return offer(new Update(
        true, 0, 0, 0, 0, 0, createdAtNanos, generation.get()));
  }

  void invalidatePending() {
    generation.incrementAndGet();
    latest.set(null);
    lastDelivered.set(null);
  }

  void close() {
    closed.set(true);
    invalidatePending();
  }

  boolean closed() {
    return closed.get();
  }

  @Nullable Update pendingForTest() {
    return latest.get();
  }

  private OfferResult offer(Update update) {
    if (closed.get()) return OfferResult.REJECTED;
    if (!update.clear && !scheduled.get() && latest.get() == null
        && sameDemand(lastDelivered.get(), update)) {
      return OfferResult.DEDUPLICATED;
    }
    Update previous = latest.getAndSet(update);
    if (closed.get()) {
      latest.compareAndSet(update, null);
      return OfferResult.REJECTED;
    }
    if (scheduled.compareAndSet(false, true)) {
      executor.execute(this::drainLoop);
      return previous == null ? OfferResult.SCHEDULED : OfferResult.CONFLATED;
    }
    return OfferResult.CONFLATED;
  }

  private void drainLoop() {
    while (true) {
      Update update = latest.getAndSet(null);
      if (update != null && !closed.get() && update.generation == generation.get()) {
        drain.accept(update);
        lastDelivered.set(update);
      }
      scheduled.set(false);
      if (closed.get() || latest.get() == null
          || !scheduled.compareAndSet(false, true)) {
        return;
      }
    }
  }

  /**
   * 网络需求身份只由可见 coverage 和 mailbox 生命周期决定。anchor、方向和行数只是
   * 下一次真正 coverage 变化时使用的预取提示，不能单独制造 actor 任务或 demand epoch。
   */
  private static boolean sameDemand(@Nullable Update a, @NonNull Update b) {
    return a != null
        && !a.clear
        && !b.clear
        && a.visibleFromSeq == b.visibleFromSeq
        && a.visibleToSeq == b.visibleToSeq
        && a.generation == b.generation;
  }
}
