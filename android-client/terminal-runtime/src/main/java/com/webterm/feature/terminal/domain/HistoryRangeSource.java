package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryBodyEntry;

import java.util.List;

/** Direct/Relay 共用的 HTTP History Range 抽象。 */
public interface HistoryRangeSource {
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
    public final HistoryExtent currentExtent;
    public final List<HistoryBodyEntry> lines;
    /** HTTP 响应完成解析并准备派发 callback 的单调时钟时间。 */
    public final long completedAtNanos;

    public Result(@NonNull String instanceId, long layoutEpoch, long historyGeneration,
                  @NonNull HistoryExtent currentExtent,
                  @NonNull List<HistoryBodyEntry> lines) {
      this(instanceId, layoutEpoch, historyGeneration, currentExtent, lines,
          System.nanoTime());
    }

    public Result(@NonNull String instanceId, long layoutEpoch, long historyGeneration,
                  @NonNull HistoryExtent currentExtent,
                  @NonNull List<HistoryBodyEntry> lines,
                  long completedAtNanos) {
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.historyGeneration = historyGeneration;
      this.currentExtent = currentExtent;
      this.lines = lines;
      this.completedAtNanos = completedAtNanos;
    }
  }

  final class Failure {
    public final FailureKind kind;
    public final long retryAfterMs;
    public final long historyGeneration;
    /** HTTP 失败已确定并准备派发 callback 的单调时钟时间。 */
    public final long completedAtNanos;

    public Failure(@NonNull FailureKind kind, long retryAfterMs, long historyGeneration) {
      this(kind, retryAfterMs, historyGeneration, System.nanoTime());
    }

    public Failure(@NonNull FailureKind kind, long retryAfterMs, long historyGeneration,
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

  @NonNull RequestHandle fetch(
      @NonNull HistoryRangeLoader.Range range, @NonNull Callback callback);

  void close();
}
