package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RecoveryArbiterTest {
  @Test
  public void duplicateAndLowerLevelTriggersAreSuppressed() {
    List<RecoveryArbiter.Level> actions = new ArrayList<>();
    FakeClock clock = new FakeClock();
    RecoveryArbiter arbiter = new RecoveryArbiter(
        (level, recoveryId, reason) -> actions.add(level), clock);

    assertTrue(arbiter.request(
        RecoveryArbiter.Level.CHANNEL_REBUILD, "fatal overflow", 7, 3, false));
    assertFalse(arbiter.request(
        RecoveryArbiter.Level.IN_BAND_RESYNC, "revision gap", 7, 3, false));
    assertFalse(arbiter.request(
        RecoveryArbiter.Level.CHANNEL_REBUILD, "EOF", 7, 3, false));

    assertEquals(List.of(RecoveryArbiter.Level.CHANNEL_REBUILD), actions);
    Map<String, Object> metrics = arbiter.diagnosticsSnapshot();
    assertEquals(2L, metrics.get("duplicateTriggerSuppressedCount"));
    assertEquals(1L, metrics.get("channelRebuildCount"));
  }

  @Test
  public void recoveryOnlyUpgradesAndExecutesEachNewHighestLevelOnce() {
    List<RecoveryArbiter.Level> actions = new ArrayList<>();
    RecoveryArbiter arbiter = new RecoveryArbiter(
        (level, recoveryId, reason) -> actions.add(level), new FakeClock());

    arbiter.request(RecoveryArbiter.Level.IN_BAND_RESYNC, "gap", 11, 4, false);
    arbiter.request(RecoveryArbiter.Level.FORCE_BASELINE, "dictionary", 11, 4, false);
    arbiter.request(RecoveryArbiter.Level.CHANNEL_REBUILD, "channel closed", 11, 4, false);
    arbiter.request(RecoveryArbiter.Level.IN_BAND_RESYNC, "late overflow", 11, 4, false);

    assertEquals(List.of(
        RecoveryArbiter.Level.IN_BAND_RESYNC,
        RecoveryArbiter.Level.FORCE_BASELINE,
        RecoveryArbiter.Level.CHANNEL_REBUILD), actions);
    Map<String, Object> metrics = arbiter.diagnosticsSnapshot();
    assertEquals(2L, metrics.get("recoveryUpgradeCount"));
    assertEquals(1L, metrics.get("duplicateTriggerSuppressedCount"));
  }

  @Test
  public void authoritativeSnapshotCompletesRecoveryAfterEpochAdvance() {
    FakeClock clock = new FakeClock();
    RecoveryArbiter arbiter = new RecoveryArbiter((level, id, reason) -> {}, clock);

    arbiter.request(RecoveryArbiter.Level.CHANNEL_REBUILD, "rebuild", 20, 8, false);
    String recoveryId = arbiter.activeRecoveryId();
    arbiter.advanceEpoch(recoveryId, 21, 9);
    clock.advanceMillis(25);
    assertEquals(
        RecoveryArbiter.CompletionResult.COMPLETED_AFTER_EPOCH_CHANGE,
        arbiter.completeAuthoritative(20, "BASELINE"));

    assertFalse(arbiter.isRecovering());
    Map<String, Object> metrics = arbiter.diagnosticsSnapshot();
    assertEquals(1L, metrics.get("recoveryCompletedCount"));
    assertEquals(0L, metrics.get("recoveryFailedCount"));
    assertEquals("RECOVERED_AFTER_EPOCH_CHANGE", metrics.get("recoveryFinalOutcome"));
    assertEquals("BASELINE", metrics.get("recoverySnapshotKind"));
    assertEquals(25L, metrics.get("recoveryDurationMs"));
  }

  @Test
  public void closePreventsScheduledOrLateTriggersFromRevivingRecovery() {
    List<RecoveryArbiter.Level> actions = new ArrayList<>();
    RecoveryArbiter arbiter = new RecoveryArbiter(
        (level, id, reason) -> actions.add(level), new FakeClock());
    arbiter.request(RecoveryArbiter.Level.IN_BAND_RESYNC, "gap", 1, 1, false);
    arbiter.close();

    assertFalse(arbiter.request(
        RecoveryArbiter.Level.CHANNEL_REBUILD, "late timeout", 1, 1, false));
    assertFalse(arbiter.isRecovering());
    assertEquals(1, actions.size());
    assertEquals(0L, arbiter.diagnosticsSnapshot().get("recoveryFailedCount"));
    assertEquals(1L, arbiter.diagnosticsSnapshot().get("recoveryAbortedCount"));
  }

  @Test
  public void abortEndsRecoveryWithoutCountingFailure() {
    RecoveryArbiter arbiter =
        new RecoveryArbiter((level, id, reason) -> {}, new FakeClock());
    arbiter.request(RecoveryArbiter.Level.IN_BAND_RESYNC, "gap", 4, 2, false);

    assertTrue(arbiter.abort("SESSION_SUSPENDED"));
    assertFalse(arbiter.isRecovering());
    assertFalse(arbiter.abort("DUPLICATE_ABORT"));
    Map<String, Object> metrics = arbiter.diagnosticsSnapshot();
    assertEquals("SESSION_SUSPENDED", metrics.get("recoveryFinalOutcome"));
    assertEquals(1L, metrics.get("recoveryAbortedCount"));
    assertEquals(0L, metrics.get("recoveryFailedCount"));
  }

  @Test
  public void externallyStartedReconnectIsTrackedWithoutDuplicateAction() {
    List<RecoveryArbiter.Level> actions = new ArrayList<>();
    RecoveryArbiter arbiter = new RecoveryArbiter(
        (level, id, reason) -> actions.add(level), new FakeClock());

    assertTrue(arbiter.request(
        RecoveryArbiter.Level.TRANSPORT_RECONNECT, "EOF", 9, 5, true));
    assertFalse(arbiter.request(
        RecoveryArbiter.Level.CHANNEL_REBUILD, "cookie updated", 9, 5, false));

    assertTrue(actions.isEmpty());
    assertEquals(1L, arbiter.diagnosticsSnapshot().get("transportReconnectCount"));
    assertEquals(1L, arbiter.diagnosticsSnapshot().get("duplicateTriggerSuppressedCount"));
  }

  private static final class FakeClock implements RecoveryArbiter.Clock {
    private long nanos = 1_000_000L;
    private long millis = 100L;

    @Override public long nanoTime() { return nanos; }
    @Override public long currentTimeMillis() { return millis; }

    void advanceMillis(long value) {
      millis += value;
      nanos += value * 1_000_000L;
    }
  }
}
