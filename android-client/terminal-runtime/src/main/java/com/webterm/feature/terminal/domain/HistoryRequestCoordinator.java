package com.webterm.feature.terminal.domain;

import java.util.concurrent.atomic.AtomicLong;

/** history request ID、在途请求与迟到响应过滤的唯一所有者。 */
public final class HistoryRequestCoordinator {
  private final AtomicLong nextRequestId = new AtomicLong();
  private volatile String pendingRequestId;

  public String nextRequestId() {
    return "h-" + nextRequestId.incrementAndGet();
  }

  public void markPending(String requestId) {
    pendingRequestId = requestId;
  }

  public boolean accept(String requestId) {
    return requestId != null && requestId.equals(pendingRequestId);
  }

  public void complete(String requestId) {
    if (accept(requestId)) pendingRequestId = null;
  }

  public void clear() {
    pendingRequestId = null;
  }
}
