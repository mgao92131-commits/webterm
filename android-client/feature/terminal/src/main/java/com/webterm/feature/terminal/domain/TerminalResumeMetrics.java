package com.webterm.feature.terminal.domain;

import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

/** 不记录终端/剪贴板/通知正文的进程级恢复指标。 */
public final class TerminalResumeMetrics {
  private static final String TAG = "ScreenResume";
  private static final AtomicLong PAGE_REATTACH = new AtomicLong();
  private static final AtomicLong EXACT = new AtomicLong();
  private static final AtomicLong PATCH = new AtomicLong();
  private static final AtomicLong SNAPSHOT = new AtomicLong();
  private static final AtomicLong RESYNC = new AtomicLong();
  private static final AtomicLong SYNC_TIMEOUT = new AtomicLong();
  private static final AtomicLong HOT_TO_WARM = new AtomicLong();
  private static final AtomicLong WARM_TO_COLD = new AtomicLong();
  private static final AtomicLong LEASE_ACQUIRE = new AtomicLong();
  private static final AtomicLong LEASE_DENIED = new AtomicLong();
  private static final AtomicLong LEASE_RETRY = new AtomicLong();
  private static final AtomicLong LEASE_RENEW = new AtomicLong();
  private static final AtomicLong LEASE_REVOKED = new AtomicLong();
  private static final AtomicLong LEASE_STALE_RESPONSE = new AtomicLong();

  private TerminalResumeMetrics() {}

  static void pageReattach() { record(PAGE_REATTACH, "page_reattach runtime_state=hot"); }
  static void exactResume(long clientRevision, long serverRevision) {
    record(EXACT, "exact_resume client_revision=" + clientRevision
        + " server_revision=" + serverRevision + " sync_state=connected");
  }
  static void cumulativePatch(long clientRevision, long serverRevision, int rows, int history) {
    record(PATCH, "cumulative_patch client_revision=" + clientRevision
        + " server_revision=" + serverRevision + " changed_rows=" + rows
        + " history_append_lines=" + history + " sync_state=connected");
  }
  static void snapshot(long serverRevision) {
    record(SNAPSHOT, "snapshot server_revision=" + serverRevision + " sync_state=connected");
  }
  static void resync(String reason) { record(RESYNC, "resync reason=" + safeReason(reason)); }
  static void syncTimeout() { record(SYNC_TIMEOUT, "sync_timeout sync_state=syncing"); }
  static void hotToWarm() { record(HOT_TO_WARM, "runtime_transition from=hot to=warm runtime_state=warm"); }
  static void warmToCold() { record(WARM_TO_COLD, "runtime_transition from=warm to=cold runtime_state=cold"); }
  static void leaseAcquire(boolean renewal) {
    record(renewal ? LEASE_RENEW : LEASE_ACQUIRE,
        renewal ? "layout_lease action=renew" : "layout_lease action=acquire");
  }
  static void leaseDenied() { record(LEASE_DENIED, "layout_lease action=denied"); }
  static void leaseRetry() { record(LEASE_RETRY, "layout_lease action=retry"); }
  static void leaseRevoked() { record(LEASE_REVOKED, "layout_lease action=revoked"); }
  static void leaseStaleResponse() {
    record(LEASE_STALE_RESPONSE, "layout_lease action=stale_response");
  }

  private static void record(AtomicLong counter, String fields) {
    long count = counter.incrementAndGet();
    Log.i(TAG, "event=" + fields + " count=" + count);
  }

  private static String safeReason(String reason) {
    if (reason == null) return "unknown";
    // 原因仅保留枚举风格字符，防止解析异常正文进入日志。
    return reason.replaceAll("[^a-zA-Z0-9_.:-]", "_");
  }

  public static Snapshot snapshot() {
    return new Snapshot(PAGE_REATTACH.get(), EXACT.get(), PATCH.get(), SNAPSHOT.get(),
        RESYNC.get(), SYNC_TIMEOUT.get(), HOT_TO_WARM.get(), WARM_TO_COLD.get());
  }

  public static final class Snapshot {
    public final long pageReattach;
    public final long exactResume;
    public final long cumulativePatch;
    public final long snapshotFallback;
    public final long resync;
    public final long syncTimeout;
    public final long hotToWarm;
    public final long warmToCold;

    Snapshot(long pageReattach, long exactResume, long cumulativePatch, long snapshotFallback,
             long resync, long syncTimeout, long hotToWarm, long warmToCold) {
      this.pageReattach = pageReattach;
      this.exactResume = exactResume;
      this.cumulativePatch = cumulativePatch;
      this.snapshotFallback = snapshotFallback;
      this.resync = resync;
      this.syncTimeout = syncTimeout;
      this.hotToWarm = hotToWarm;
      this.warmToCold = warmToCold;
    }
  }
}
