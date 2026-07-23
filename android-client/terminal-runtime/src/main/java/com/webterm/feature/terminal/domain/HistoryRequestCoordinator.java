package com.webterm.feature.terminal.domain;

import java.util.concurrent.atomic.AtomicLong;
import java.util.LinkedHashMap;

/** history request ID、在途请求与迟到响应过滤的唯一所有者。 */
public final class HistoryRequestCoordinator {
  private final AtomicLong nextRequestId = new AtomicLong();
  public static final class Pending {
    public final String requestId;
    public final long fromSeq;
    public final long toSeq;
    public final long anchorSeq;
    public final String instanceId;
    public final long layoutEpoch;
    public final int retryAttempt;

    Pending(String requestId, long fromSeq, long toSeq, long anchorSeq,
            String instanceId, long layoutEpoch, int retryAttempt) {
      this.requestId = requestId;
      this.fromSeq = fromSeq;
      this.toSeq = toSeq;
      this.anchorSeq = anchorSeq;
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.retryAttempt = retryAttempt;
    }
  }

  private static final int MAX_PENDING = 4;
  private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();

  public String nextRequestId() {
    return "h-" + nextRequestId.incrementAndGet();
  }

  public synchronized void markPending(String requestId) {
    markPending(requestId, 0, 0, 0, "", 0, 0);
  }

  public synchronized void markPending(String requestId, long fromSeq, long toSeq,
                                       long anchorSeq) {
    markPending(requestId, fromSeq, toSeq, anchorSeq, "", 0, 0);
  }

  public synchronized void markPending(String requestId, long fromSeq, long toSeq,
                                       long anchorSeq, String instanceId, long layoutEpoch,
                                       int retryAttempt) {
    if (pending.size() >= MAX_PENDING) {
      String oldest = pending.entrySet().iterator().next().getKey();
      pending.remove(oldest);
    }
    pending.put(requestId, new Pending(
        requestId, fromSeq, toSeq, anchorSeq,
        instanceId == null ? "" : instanceId, layoutEpoch, retryAttempt));
  }

  public synchronized boolean isRangePending(long fromSeq, long toSeq) {
    for (Pending request : pending.values()) {
      if (request.fromSeq == fromSeq && request.toSeq == toSeq) return true;
    }
    return false;
  }

  public synchronized boolean accept(String requestId) {
    return requestId != null && pending.containsKey(requestId);
  }

  public synchronized Pending complete(String requestId) {
    return requestId == null ? null : pending.remove(requestId);
  }

  public synchronized Pending expire(String requestId) {
    return complete(requestId);
  }

  /** Baseline 替换投影时只丢弃身份/epoch 不兼容请求，保留同投影在途 Range。 */
  public synchronized void retainCompatible(String instanceId, long layoutEpoch) {
    pending.entrySet().removeIf(entry -> {
      Pending request = entry.getValue();
      return request.instanceId.isEmpty()
          || !request.instanceId.equals(instanceId)
          || request.layoutEpoch != layoutEpoch;
    });
  }

  public synchronized void clear() {
    pending.clear();
  }
}
