package com.webterm.feature.terminal.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** history request ID、在途请求、待发队列与迟到响应过滤的唯一所有者。 */
public final class HistoryRequestCoordinator {
  private final AtomicLong nextRequestId = new AtomicLong();
  public static final class Pending {
    public final String requestId;
    public final long fromSeq;
    public final long toSeq;
    public final long anchorSeq;
    public final String instanceId;
    public final long layoutEpoch;
    public final long historyGeneration;
    public final int retryAttempt;

    Pending(String requestId, long fromSeq, long toSeq, long anchorSeq,
            String instanceId, long layoutEpoch, long historyGeneration, int retryAttempt) {
      this.requestId = requestId;
      this.fromSeq = fromSeq;
      this.toSeq = toSeq;
      this.anchorSeq = anchorSeq;
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.historyGeneration = historyGeneration;
      this.retryAttempt = retryAttempt;
    }
  }

  /** 同时占用网络/服务端资源的历史请求上限。 */
  private static final int MAX_IN_FLIGHT = 4;
  /** 未发出的滚动请求只保留最近可见区域附近的有限候选。 */
  private static final int MAX_QUEUED = 16;
  private final LinkedHashMap<String, Pending> inFlight = new LinkedHashMap<>();
  private final LinkedHashMap<String, Pending> queued = new LinkedHashMap<>();

  public enum Submission {
    SEND_NOW,
    QUEUED,
    DUPLICATE
  }

  /**
   * 提交一个历史页请求。已发出的请求绝不因容量被移除；容量满时仅把尚未发送的
   * 新请求放进待发队列，等任一 in-flight 请求完成、超时或被明确取消后再派发。
   */
  public synchronized Submission submit(String requestId, long fromSeq, long toSeq,
                                        long anchorSeq, String instanceId, long layoutEpoch,
                                        long historyGeneration, int retryAttempt) {
    if (isRangePending(fromSeq, toSeq)) return Submission.DUPLICATE;
    Pending request = newPending(requestId, fromSeq, toSeq, anchorSeq,
        instanceId, layoutEpoch, historyGeneration, retryAttempt);
    if (inFlight.size() < MAX_IN_FLIGHT) {
      inFlight.put(requestId, request);
      return Submission.SEND_NOW;
    }
    enqueue(request);
    return Submission.QUEUED;
  }

  public String nextRequestId() {
    return "h-" + nextRequestId.incrementAndGet();
  }

  public synchronized void markPending(String requestId) {
    markPending(requestId, 0, 0, 0, "", 0, 0, 0);
  }

  public synchronized void markPending(String requestId, long fromSeq, long toSeq,
                                       long anchorSeq) {
    markPending(requestId, fromSeq, toSeq, anchorSeq, "", 0, 0, 0);
  }

  public synchronized void markPending(String requestId, long fromSeq, long toSeq,
                                       long anchorSeq, String instanceId, long layoutEpoch,
                                       long historyGeneration, int retryAttempt) {
    reserve(requestId, fromSeq, toSeq, anchorSeq,
        instanceId, layoutEpoch, historyGeneration, retryAttempt);
  }

  public synchronized Pending reserve(String requestId, long fromSeq, long toSeq,
                                      long anchorSeq, String instanceId, long layoutEpoch,
                                      long historyGeneration, int retryAttempt) {
    Pending reservation = newPending(requestId, fromSeq, toSeq, anchorSeq,
        instanceId, layoutEpoch, historyGeneration, retryAttempt);
    if (inFlight.size() < MAX_IN_FLIGHT) inFlight.put(requestId, reservation);
    else enqueue(reservation);
    return reservation;
  }

  public synchronized Pending cancel(String requestId) {
    Pending removed = requestId == null ? null : inFlight.remove(requestId);
    return removed != null ? removed : requestId == null ? null : queued.remove(requestId);
  }

  public synchronized boolean isRangePending(long fromSeq, long toSeq) {
    for (Pending request : inFlight.values()) {
      if (request.fromSeq == fromSeq && request.toSeq == toSeq) return true;
    }
    for (Pending request : queued.values()) {
      if (request.fromSeq == fromSeq && request.toSeq == toSeq) return true;
    }
    return false;
  }

  public synchronized boolean accept(String requestId) {
    return requestId != null && inFlight.containsKey(requestId);
  }

  public synchronized Pending complete(String requestId) {
    return requestId == null ? null : inFlight.remove(requestId);
  }

  public synchronized Pending expire(String requestId) {
    return complete(requestId);
  }

  /** Baseline 替换投影时只丢弃身份/epoch 不兼容请求，保留同投影在途 Range。 */
  public synchronized void retainCompatible(
      String instanceId, long layoutEpoch, long historyGeneration) {
    inFlight.entrySet().removeIf(entry -> !isCompatible(
        entry.getValue(), instanceId, layoutEpoch, historyGeneration));
    queued.entrySet().removeIf(entry -> !isCompatible(
        entry.getValue(), instanceId, layoutEpoch, historyGeneration));
  }

  public synchronized void clear() {
    inFlight.clear();
    queued.clear();
  }

  /** 释放一个 in-flight 槽位后，优先派发最近一次滚动请求的页。 */
  public synchronized Pending promoteNext() {
    if (inFlight.size() >= MAX_IN_FLIGHT || queued.isEmpty()) return null;
    Pending newest = null;
    for (Pending request : queued.values()) newest = request;
    if (newest == null) return null;
    queued.remove(newest.requestId);
    inFlight.put(newest.requestId, newest);
    return newest;
  }

  private Pending newPending(String requestId, long fromSeq, long toSeq,
                             long anchorSeq, String instanceId, long layoutEpoch,
                             long historyGeneration, int retryAttempt) {
    return new Pending(requestId, fromSeq, toSeq, anchorSeq,
        instanceId == null ? "" : instanceId, layoutEpoch, historyGeneration, retryAttempt);
  }

  private void enqueue(Pending request) {
    // 同页新请求代表更靠近当前视口的意图，移动到队尾以提升派发优先级。
    queued.remove(request.requestId);
    if (queued.size() >= MAX_QUEUED) {
      java.util.Iterator<Map.Entry<String, Pending>> iterator = queued.entrySet().iterator();
      if (iterator.hasNext()) {
        iterator.next();
        iterator.remove();
      }
    }
    queued.put(request.requestId, request);
  }

  private static boolean isCompatible(Pending request, String instanceId,
                                      long layoutEpoch, long historyGeneration) {
    return !request.instanceId.isEmpty()
        && request.instanceId.equals(instanceId)
        && request.layoutEpoch == layoutEpoch
        && request.historyGeneration == historyGeneration;
  }
}
