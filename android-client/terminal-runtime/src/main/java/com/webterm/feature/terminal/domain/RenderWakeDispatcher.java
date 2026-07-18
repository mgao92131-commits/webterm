package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

import com.webterm.terminal.model.TerminalRenderMetrics;

import java.util.concurrent.Executor;

/**
 * 从模型线程到 UI 线程的有界“需要绘制”唤醒。
 *
 * <p>不传递任何渲染脏区：脏区仅存于 RemoteTerminalModel，UI 到 VSync 时自行原子消费。
 * 同一个未消费周期内只保留一个布尔唤醒。</p>
 */
final class RenderWakeDispatcher {
  interface Consumer {
    void accept(long callbackDelayNanos);
  }

  private final Object lock = new Object();
  private final Executor callbackExecutor;
  private final Consumer consumer;
  private boolean pendingRenderWake;
  private boolean callbackScheduled;
  private long generation;
  private long scheduledAtNanos;

  RenderWakeDispatcher(@NonNull Executor callbackExecutor, @NonNull Consumer consumer) {
    this.callbackExecutor = callbackExecutor;
    this.consumer = consumer;
  }

  void dispatch() {
    long scheduledGeneration = 0L;
    boolean schedule = false;
    synchronized (lock) {
      TerminalRenderMetrics.modelChange();
      if (pendingRenderWake) TerminalRenderMetrics.uiCallbackCoalesced();
      pendingRenderWake = true;
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
    long delayNanos;
    synchronized (lock) {
      if (!callbackScheduled || callbackGeneration != generation) return;
      pendingRenderWake = false;
      callbackScheduled = false;
      delayNanos = Math.max(0L, System.nanoTime() - scheduledAtNanos);
    }
    TerminalRenderMetrics.mainThreadCallbackDelay(delayNanos);
    consumer.accept(delayNanos);

    long nextGeneration = 0L;
    boolean schedule = false;
    synchronized (lock) {
      if (pendingRenderWake && !callbackScheduled) {
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
      pendingRenderWake = false;
      callbackScheduled = false;
      scheduledAtNanos = 0L;
    }
  }
}
