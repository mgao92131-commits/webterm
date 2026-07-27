package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.SegmentKey;
import com.webterm.terminal.model.TerminalLine;

import java.util.List;

/** 历史分段传输抽象；Direct/Relay 实现细节对 Loader 不可见。 */
public interface HistorySegmentSource {
  interface RequestHandle {
    void cancel();
  }

  interface Callback {
    void onResult(@NonNull DecodedHistorySegment result);
    void onFailure(@NonNull Failure failure);
  }

  enum FailureKind {
    NETWORK,
    RETRYABLE,
    STALE_GENERATION,
    NOT_SEALED,
    TRIMMED,
    NOT_FOUND,
    SESSION_GONE,
    PROTOCOL
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

  final class DecodedHistorySegment {
    public final SegmentKey key;
    public final long firstSeq;
    public final long lastSeq;
    public final List<TerminalLine> lines;
    public final byte[] checksum;

    public DecodedHistorySegment(@NonNull SegmentKey key, long firstSeq, long lastSeq,
                                 @NonNull List<TerminalLine> lines, @Nullable byte[] checksum) {
      this.key = key;
      this.firstSeq = firstSeq;
      this.lastSeq = lastSeq;
      this.lines = lines;
      this.checksum = checksum;
    }
  }

  @NonNull RequestHandle fetch(@NonNull SegmentKey key, @NonNull Callback callback);

  void close();
}
