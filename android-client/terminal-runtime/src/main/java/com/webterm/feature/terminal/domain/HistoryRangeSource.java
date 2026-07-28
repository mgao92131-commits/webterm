package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.TerminalLine;

import java.util.List;

/** Direct/Relay 共用的 HTTP History Range 抽象。 */
public interface HistoryRangeSource {
  interface RequestHandle { void cancel(); }

  enum FailureKind {
    NETWORK, RETRYABLE, STALE_PROJECTION, SESSION_GONE, AUTH_REQUIRED, PROTOCOL
  }

  final class Result {
    public final String instanceId;
    public final long layoutEpoch;
    public final long historyGeneration;
    public final HistoryExtent currentExtent;
    public final List<TerminalLine> lines;

    public Result(@NonNull String instanceId, long layoutEpoch, long historyGeneration,
                  @NonNull HistoryExtent currentExtent,
                  @NonNull List<TerminalLine> lines) {
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.historyGeneration = historyGeneration;
      this.currentExtent = currentExtent;
      this.lines = lines;
    }
  }

  final class Failure {
    public final FailureKind kind;
    public final long retryAfterMs;
    public final long historyGeneration;

    public Failure(@NonNull FailureKind kind, long retryAfterMs, long historyGeneration) {
      this.kind = kind;
      this.retryAfterMs = Math.max(0, retryAfterMs);
      this.historyGeneration = historyGeneration;
    }
  }

  interface Callback {
    void onResult(@NonNull Result result);
    void onFailure(@NonNull Failure failure);
  }

  @NonNull RequestHandle fetch(
      @NonNull HistoryRangeLoader.Range range, @NonNull Callback callback);

  void close();
}
