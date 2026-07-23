package com.webterm.terminal.model;

import java.util.List;

public final class HistoryRangeResult {
  public enum Status { OK, STALE_PROJECTION, TRIMMED, RETRYABLE }

  public final String requestId;
  public final String instanceId;
  public final long layoutEpoch;
  public final Status status;
  public final HistoryExtent availableExtent;
  public final List<TerminalLine> lines;
  public final long retryAfterMs;

  public HistoryRangeResult(
      String requestId, String instanceId, long layoutEpoch, Status status,
      HistoryExtent availableExtent, List<TerminalLine> lines, long retryAfterMs) {
    this.requestId = requestId;
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.status = status;
    this.availableExtent = availableExtent;
    this.lines = lines;
    this.retryAfterMs = retryAfterMs;
  }
}
