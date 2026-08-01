package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

import com.webterm.terminal.model.LineBodyRecord;
import com.webterm.terminal.model.LineKey;

import java.util.List;
import java.util.Map;

/** Direct/Relay 共用的 HTTP LineBodyBatch 抽象。 */
public interface LineBodyBatchSource {
  interface RequestHandle { void cancel(); }

  enum FailureKind {
    NETWORK,
    RETRYABLE,
    SESSION_NOT_READY,
    STALE_PROJECTION,
    SESSION_GONE,
    AUTH_REQUIRED,
    PROTOCOL
  }

  final class Result {
    public final String instanceId;
    public final long layoutEpoch;
    public final long historyGeneration;
    public final List<LineBodyRecord> bodies;
    public final List<LineKey> missingKeys;
    public final long completedAtNanos;

    public Result(
        @NonNull String instanceId,
        long layoutEpoch,
        long historyGeneration,
        @NonNull List<LineBodyRecord> bodies,
        @NonNull List<LineKey> missingKeys,
        long completedAtNanos) {
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.historyGeneration = historyGeneration;
      this.bodies = bodies;
      this.missingKeys = missingKeys;
      this.completedAtNanos = completedAtNanos;
    }
  }

  final class Failure {
    public final FailureKind kind;
    public final long retryAfterMs;
    public final long historyGeneration;
    public final long completedAtNanos;

    public Failure(@NonNull FailureKind kind, long retryAfterMs, long historyGeneration) {
      this(kind, retryAfterMs, historyGeneration, System.nanoTime());
    }

    public Failure(
        @NonNull FailureKind kind, long retryAfterMs, long historyGeneration,
        long completedAtNanos) {
      this.kind = kind;
      this.retryAfterMs = Math.max(0, retryAfterMs);
      this.historyGeneration = historyGeneration;
      this.completedAtNanos = completedAtNanos;
    }
  }

  interface Callback {
    void onResult(@NonNull Result result);
    void onFailure(@NonNull Failure failure);
  }

  @NonNull RequestHandle fetch(@NonNull VisibleBodyLoader.Batch batch, @NonNull Callback callback);

  @NonNull default Map<String, Object> diagnosticsSnapshot() { return Map.of(); }

  void close();
}
