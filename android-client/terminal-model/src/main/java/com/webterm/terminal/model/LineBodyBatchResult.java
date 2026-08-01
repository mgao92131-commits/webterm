package com.webterm.terminal.model;

import java.util.List;

/** HTTP LineBodyBatch 响应的 domain 边界对象。 */
public final class LineBodyBatchResult {
  public enum Status { OK, STALE_PROJECTION, SESSION_GONE, RETRYABLE }

  public final String requestId;
  public final String instanceId;
  public final long layoutEpoch;
  public final long historyGeneration;
  public final Status status;
  public final List<LineBodyRecord> bodies;
  public final List<LineKey> missingKeys;
  public final long retryAfterMs;

  public LineBodyBatchResult(
      String requestId,
      String instanceId,
      long layoutEpoch,
      long historyGeneration,
      Status status,
      List<LineBodyRecord> bodies,
      List<LineKey> missingKeys,
      long retryAfterMs) {
    this.requestId = requestId;
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.historyGeneration = historyGeneration;
    this.status = status;
    this.bodies = List.copyOf(bodies);
    this.missingKeys = List.copyOf(missingKeys);
    this.retryAfterMs = retryAfterMs;
  }
}
