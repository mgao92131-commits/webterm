package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

import com.webterm.terminal.model.ModelChange;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.TerminalRenderMetrics;

import java.util.concurrent.Executor;

/**
 * Bounded bridge from the serial model executor to the UI callback executor.
 *
 * <p>Protocol frames are never coalesced here: {@link RemoteTerminalModel} has already applied
 * each one in revision order. Only their UI invalidation summaries are merged, so a slow main
 * thread receives the newest authoritative model once instead of a FIFO of obsolete callbacks.</p>
 */
final class ModelChangeDispatcher {

  interface Consumer {
    void accept(@NonNull ModelChange change, long callbackDelayNanos);
  }

  private final Object lock = new Object();
  private final Executor callbackExecutor;
  private final Consumer consumer;
  private ModelChange pending;
  private boolean callbackScheduled;
  private long generation;
  private long scheduledAtNanos;

  ModelChangeDispatcher(@NonNull Executor callbackExecutor, @NonNull Consumer consumer) {
    this.callbackExecutor = callbackExecutor;
    this.consumer = consumer;
  }

  void dispatch(@NonNull ModelChange change) {
    long scheduledGeneration = 0L;
    boolean schedule = false;
    synchronized (lock) {
      TerminalRenderMetrics.modelChange();
      if (pending != null) {
        pending = pending.merge(change);
        TerminalRenderMetrics.uiCallbackCoalesced();
      } else {
        pending = change;
      }
      if (!callbackScheduled) {
        callbackScheduled = true;
        scheduledAtNanos = System.nanoTime();
        scheduledGeneration = generation;
        schedule = true;
        TerminalRenderMetrics.uiCallbackScheduled();
      }
    }
    if (schedule) {
      final long generationToDrain = scheduledGeneration;
      callbackExecutor.execute(() -> drain(generationToDrain));
    }
  }

  private void drain(long callbackGeneration) {
    ModelChange next;
    long delayNanos;
    synchronized (lock) {
      if (!callbackScheduled || callbackGeneration != generation || pending == null) return;
      next = pending;
      pending = null;
      callbackScheduled = false;
      delayNanos = Math.max(0L, System.nanoTime() - scheduledAtNanos);
    }
    TerminalRenderMetrics.mainThreadCallbackDelay(delayNanos);
    consumer.accept(next, delayNanos);

    long nextGeneration = 0L;
    boolean schedule = false;
    synchronized (lock) {
      if (pending != null && !callbackScheduled) {
        callbackScheduled = true;
        scheduledAtNanos = System.nanoTime();
        nextGeneration = generation;
        schedule = true;
        TerminalRenderMetrics.uiCallbackScheduled();
      }
    }
    if (schedule) {
      final long generationToDrain = nextGeneration;
      callbackExecutor.execute(() -> drain(generationToDrain));
    }
  }

  void cancel() {
    synchronized (lock) {
      generation++;
      pending = null;
      callbackScheduled = false;
      scheduledAtNanos = 0L;
    }
  }
}
