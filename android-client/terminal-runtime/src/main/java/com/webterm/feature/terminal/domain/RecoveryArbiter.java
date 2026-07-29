package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 终端恢复请求的单一仲裁点。
 *
 * <p>同一恢复事务只允许向更高等级升级；相同或更低等级的重复触发只计数，不重复执行动作。
 * 本类不拥有 transport/channel，仅通过窄 {@link Actions} 接口发起动作。</p>
 */
public final class RecoveryArbiter {
  public enum Level {
    IN_BAND_RESYNC,
    FORCE_BASELINE,
    CHANNEL_REBUILD,
    TRANSPORT_RECONNECT
  }

  public enum CompletionResult {
    NO_ACTIVE_RECOVERY,
    COMPLETED,
    COMPLETED_AFTER_EPOCH_CHANGE
  }

  public interface Actions {
    void execute(@NonNull Level level, @NonNull String recoveryId, @NonNull String reason);
  }

  interface Clock {
    long nanoTime();
    long currentTimeMillis();
  }

  private static final Clock SYSTEM_CLOCK = new Clock() {
    @Override public long nanoTime() { return System.nanoTime(); }
    @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
  };

  private final Actions actions;
  private final Clock clock;

  private boolean closed;
  private String recoveryId = "";
  private Level level;
  private Level lastLevel;
  private String reason = "";
  private long connectionEpoch;
  private long transportGeneration;
  private long startedAtNanos;
  private long startedAtEpochMs;
  private int attempt;
  private long suppressedTriggerCount;
  private long upgradeCount;
  private String finalOutcome = "";
  private String snapshotKind = "";
  private long completedAtEpochMs;
  private long lastDurationMs;

  private long inBandResyncCount;
  private long forceBaselineCount;
  private long channelRebuildCount;
  private long transportReconnectCount;
  private long recoveryStartedCount;
  private long recoveryCompletedCount;
  private long recoveryFailedCount;
  private long recoveryAbortedCount;
  private long recoveryUpgradeCount;
  private long duplicateTriggerSuppressedCount;

  public RecoveryArbiter(@NonNull Actions actions) {
    this(actions, SYSTEM_CLOCK);
  }

  RecoveryArbiter(@NonNull Actions actions, @NonNull Clock clock) {
    this.actions = actions;
    this.clock = clock;
  }

  /**
   * 请求恢复。externalActionStarted 表示外层 transport 已经开始该动作，仲裁器只接管事务与
   * 去重，不重复调用 Actions。
   */
  public synchronized boolean request(
      @NonNull Level requested,
      @NonNull String requestedReason,
      long requestedConnectionEpoch,
      long requestedTransportGeneration,
      boolean externalActionStarted) {
    if (closed) return false;
    if (level != null && requestedConnectionEpoch == connectionEpoch) {
      if (requested.ordinal() <= level.ordinal()) {
        suppressedTriggerCount++;
        duplicateTriggerSuppressedCount++;
        return false;
      }
      level = requested;
      lastLevel = requested;
      reason = requestedReason;
      transportGeneration = requestedTransportGeneration;
      upgradeCount++;
      recoveryUpgradeCount++;
      noteLevelAction(requested);
      if (!externalActionStarted) {
        actions.execute(requested, recoveryId, requestedReason);
      }
      return true;
    }

    if (level != null) {
      finishActive("SUPERSEDED_BY_NEW_EPOCH", "", false, true);
    }
    recoveryId = UUID.randomUUID().toString();
    level = requested;
    lastLevel = requested;
    reason = requestedReason;
    connectionEpoch = requestedConnectionEpoch;
    transportGeneration = requestedTransportGeneration;
    startedAtNanos = clock.nanoTime();
    startedAtEpochMs = clock.currentTimeMillis();
    attempt = 0;
    suppressedTriggerCount = 0;
    upgradeCount = 0;
    finalOutcome = "";
    snapshotKind = "";
    completedAtEpochMs = 0L;
    recoveryStartedCount++;
    noteLevelAction(requested);
    if (!externalActionStarted) {
      actions.execute(requested, recoveryId, requestedReason);
    }
    return true;
  }

  /** channel/transport 重建推进连接代际后，让当前事务随新代际继续，而不是另起一轮。 */
  public synchronized void advanceEpoch(
      @NonNull String expectedRecoveryId,
      long nextConnectionEpoch,
      long nextTransportGeneration) {
    if (level == null || !recoveryId.equals(expectedRecoveryId)) return;
    connectionEpoch = nextConnectionEpoch;
    transportGeneration = nextTransportGeneration;
  }

  /** 每次实际发送 in-band ResyncRequest 时计一次 attempt。 */
  public synchronized void noteAttempt(@NonNull String expectedRecoveryId) {
    if (level == null || !recoveryId.equals(expectedRecoveryId)) return;
    attempt++;
  }

  public synchronized CompletionResult completeAuthoritative(
      long completedConnectionEpoch, @NonNull String authoritativeSnapshotKind) {
    if (level == null) return CompletionResult.NO_ACTIVE_RECOVERY;
    boolean epochChanged = completedConnectionEpoch != connectionEpoch;
    finishActive(
        epochChanged ? "RECOVERED_AFTER_EPOCH_CHANGE" : "RECOVERED",
        authoritativeSnapshotKind,
        true,
        false);
    return epochChanged
        ? CompletionResult.COMPLETED_AFTER_EPOCH_CHANGE
        : CompletionResult.COMPLETED;
  }

  public synchronized boolean abort(@NonNull String outcome) {
    if (level == null) return false;
    finishActive(outcome, "", false, true);
    return true;
  }

  public synchronized void fail(@NonNull String outcome) {
    if (level == null) return;
    finishActive(outcome, "", false, false);
  }

  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (level != null) finishActive("RUNTIME_CLOSED", "", false, true);
  }

  public synchronized boolean isRecovering() {
    return level != null;
  }

  public synchronized boolean isAtLeast(@NonNull Level requested) {
    return level != null && level.ordinal() >= requested.ordinal();
  }

  @NonNull
  public synchronized String activeRecoveryId() {
    return recoveryId;
  }

  @NonNull
  public synchronized Map<String, Object> diagnosticsSnapshot() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("recoveryActive", level != null);
    result.put("recoveryId", recoveryId);
    result.put("recoveryLevel", level != null
        ? level.name() : (lastLevel != null ? lastLevel.name() : ""));
    result.put("recoveryReason", reason);
    result.put("recoveryConnectionEpoch", connectionEpoch);
    result.put("recoveryTransportGeneration", transportGeneration);
    result.put("recoveryAttempt", attempt);
    result.put("recoveryStartedAt", startedAtEpochMs);
    result.put("recoveryCompletedAt", completedAtEpochMs);
    result.put("recoveryDurationMs", level != null
        ? elapsedMillis(startedAtNanos) : lastDurationMs);
    result.put("recoverySuppressedTriggerCount", suppressedTriggerCount);
    result.put("recoveryActiveUpgradeCount", upgradeCount);
    result.put("recoveryFinalOutcome", finalOutcome);
    result.put("recoverySnapshotKind", snapshotKind);
    result.put("inBandResyncCount", inBandResyncCount);
    result.put("forceBaselineCount", forceBaselineCount);
    result.put("channelRebuildCount", channelRebuildCount);
    result.put("transportReconnectCount", transportReconnectCount);
    result.put("recoveryStartedCount", recoveryStartedCount);
    result.put("recoveryCompletedCount", recoveryCompletedCount);
    result.put("recoveryFailedCount", recoveryFailedCount);
    result.put("recoveryAbortedCount", recoveryAbortedCount);
    result.put("recoveryUpgradeCount", recoveryUpgradeCount);
    result.put("duplicateTriggerSuppressedCount", duplicateTriggerSuppressedCount);
    return result;
  }

  private void noteLevelAction(Level requested) {
    switch (requested) {
      case IN_BAND_RESYNC:
        inBandResyncCount++;
        break;
      case FORCE_BASELINE:
        forceBaselineCount++;
        // Force Baseline 通过下一条 Hello 表达，因此会实际重建 logical channel。
        channelRebuildCount++;
        break;
      case CHANNEL_REBUILD:
        channelRebuildCount++;
        break;
      case TRANSPORT_RECONNECT:
        transportReconnectCount++;
        break;
    }
  }

  private void finishActive(
      String outcome, String completedSnapshotKind, boolean completed, boolean aborted) {
    lastDurationMs = elapsedMillis(startedAtNanos);
    completedAtEpochMs = clock.currentTimeMillis();
    finalOutcome = outcome;
    snapshotKind = completedSnapshotKind;
    if (completed) {
      recoveryCompletedCount++;
    } else if (aborted) {
      recoveryAbortedCount++;
    } else {
      recoveryFailedCount++;
    }
    level = null;
  }

  private long elapsedMillis(long startNanos) {
    if (startNanos <= 0L) return 0L;
    return Math.max(0L, clock.nanoTime() - startNanos) / 1_000_000L;
  }
}
