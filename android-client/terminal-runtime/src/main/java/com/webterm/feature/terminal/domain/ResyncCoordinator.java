package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;

/** revision gap、无效 snapshot 与 mailbox overflow 的有界恢复状态机。 */
public final class ResyncCoordinator {
  public interface Actions {
    void sendResync(@NonNull String reason);
    void rebuildScreenChannel(@NonNull String reason);
  }

  private enum State { IDLE, WAITING_SNAPSHOT, RETRY_SCHEDULED, RECONNECT_REQUIRED }

  private final TerminalSessionRuntime.TimeoutScheduler scheduler;
  private final Executor executor;
  private final Actions actions;
  private final int maxRetries;
  private final long waitTimeoutMs;
  private final long[] retryBackoffMs;
  private State state = State.IDLE;
  private int attempt;
  private long generation;
  private String reason = "";
  private int suppressedOverflowCount;

  public ResyncCoordinator(TerminalSessionRuntime.TimeoutScheduler scheduler,
                           Executor executor,
                           Actions actions,
                           int maxRetries,
                           long waitTimeoutMs,
                           long[] retryBackoffMs) {
    this.scheduler = scheduler;
    this.executor = executor;
    this.actions = actions;
    this.maxRetries = maxRetries;
    this.waitTimeoutMs = waitTimeoutMs;
    this.retryBackoffMs = retryBackoffMs.clone();
  }

  public boolean start(@NonNull String reason) {
    if (state != State.IDLE) return false;
    attempt = 0;
    suppressedOverflowCount = 0;
    this.reason = reason;
    state = State.WAITING_SNAPSHOT;
    generation++;
    actions.sendResync(reason);
    armWaitTimeout();
    return true;
  }

  public void onMailboxOverflow(@NonNull String reason) {
    if (state == State.IDLE) {
      start(reason);
      return;
    }
    if (state == State.RECONNECT_REQUIRED) return;
    // 已在等待权威 snapshot 或已安排退避重试：重复 overflow 只更新统计，
    // 不立即重发 resync、也不顺延等待超时——否则一次拥塞会在快照在途期间
    // 连续触发多个全量快照（resync 风暴），退避重试路径仍照常兜底。
    this.reason = reason;
    suppressedOverflowCount++;
  }

  public void onInvalidSnapshot(@NonNull String reason) {
    if (state == State.IDLE) {
      start(reason);
    } else if (state == State.WAITING_SNAPSHOT) {
      scheduleRetry(reason);
    }
  }

  public void onAuthoritativeSnapshot() {
    state = State.IDLE;
    attempt = 0;
    suppressedOverflowCount = 0;
    generation++;
  }

  public void reset() {
    state = State.IDLE;
    attempt = 0;
    suppressedOverflowCount = 0;
    generation++;
    reason = "";
  }

  public boolean isRecovering() {
    return state != State.IDLE;
  }

  @NonNull
  public String reason() {
    return reason;
  }

  /** 本轮恢复期间被抑制的重复 overflow 次数（随 start/reset 清零）。 */
  public int suppressedOverflowCount() {
    return suppressedOverflowCount;
  }

  /** 诊断日志用的状态名。 */
  @NonNull
  public String stateName() {
    return state.name();
  }

  private void scheduleRetry(String reason) {
    if (state != State.WAITING_SNAPSHOT) return;
    this.reason = reason;
    if (attempt >= maxRetries) {
      state = State.RECONNECT_REQUIRED;
      generation++;
      actions.rebuildScreenChannel(
          "resync retries exhausted after " + maxRetries + " attempts: " + reason);
      return;
    }
    attempt++;
    state = State.RETRY_SCHEDULED;
    long token = ++generation;
    scheduler.schedule(() -> executor.execute(() -> onRetry(token)),
        retryBackoffMs[attempt - 1]);
  }

  private void armWaitTimeout() {
    long token = generation;
    scheduler.schedule(() -> executor.execute(() -> onWaitTimeout(token)), waitTimeoutMs);
  }

  private void onWaitTimeout(long token) {
    if (token != generation || state != State.WAITING_SNAPSHOT) return;
    scheduleRetry("snapshot timeout after resync: " + reason);
  }

  private void onRetry(long token) {
    if (token != generation || state != State.RETRY_SCHEDULED) return;
    state = State.WAITING_SNAPSHOT;
    generation++;
    actions.sendResync(reason);
    armWaitTimeout();
  }
}
