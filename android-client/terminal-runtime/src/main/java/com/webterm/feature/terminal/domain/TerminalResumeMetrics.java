package com.webterm.feature.terminal.domain;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 不记录终端/剪贴板/通知正文的进程级恢复指标。
 * 正常事件只累计计数，不写 logcat；异常事件由调用方通过 Diagnostics 上报。
 */
public final class TerminalResumeMetrics {
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
  private static final AtomicLong MAILBOX_OVERFLOW = new AtomicLong();
  private static final AtomicLong MAILBOX_MAX_PENDING_BYTES = new AtomicLong();
  private static final AtomicLong MAILBOX_RECOVERED = new AtomicLong();

  private TerminalResumeMetrics() {}

  static void pageReattach() { PAGE_REATTACH.incrementAndGet(); }
  static void exactResume(long clientRevision, long serverRevision) { EXACT.incrementAndGet(); }
  static void cumulativePatch(long clientRevision, long serverRevision, int rows, int history) {
    PATCH.incrementAndGet();
  }
  static void snapshot(long serverRevision) { SNAPSHOT.incrementAndGet(); }
  static void resync(String reason) { RESYNC.incrementAndGet(); }
  static void syncTimeout() { SYNC_TIMEOUT.incrementAndGet(); }
  static void hotToWarm() { HOT_TO_WARM.incrementAndGet(); }
  static void warmToCold() { WARM_TO_COLD.incrementAndGet(); }
  static void leaseAcquire(boolean renewal) {
    (renewal ? LEASE_RENEW : LEASE_ACQUIRE).incrementAndGet();
  }
  static void leaseDenied() { LEASE_DENIED.incrementAndGet(); }
  static void leaseRetry() { LEASE_RETRY.incrementAndGet(); }
  static void leaseRevoked() { LEASE_REVOKED.incrementAndGet(); }
  static void leaseStaleResponse() { LEASE_STALE_RESPONSE.incrementAndGet(); }
  static void screenMailboxOverflow(String reason, long discardedBytes, long occurrences) {
    MAILBOX_OVERFLOW.addAndGet(Math.max(1L, occurrences));
  }
  static void screenMailboxHighWater(long pendingBytes) {
    long current = MAILBOX_MAX_PENDING_BYTES.get();
    while (pendingBytes > current && !MAILBOX_MAX_PENDING_BYTES.compareAndSet(current, pendingBytes)) {
      current = MAILBOX_MAX_PENDING_BYTES.get();
    }
  }
  static void screenMailboxRecovered(String result) { MAILBOX_RECOVERED.incrementAndGet(); }

  /** 生成当前全部计数器与 mailbox 高水位的不可变快照。 */
  public static Snapshot snapshot() {
    return new Snapshot(PAGE_REATTACH.get(), EXACT.get(), PATCH.get(), SNAPSHOT.get(),
        RESYNC.get(), SYNC_TIMEOUT.get(), HOT_TO_WARM.get(), WARM_TO_COLD.get(),
        LEASE_ACQUIRE.get(), LEASE_DENIED.get(), LEASE_RETRY.get(), LEASE_RENEW.get(),
        LEASE_REVOKED.get(), LEASE_STALE_RESPONSE.get(), MAILBOX_OVERFLOW.get(),
        MAILBOX_RECOVERED.get(), MAILBOX_MAX_PENDING_BYTES.get());
  }

  public static final class Snapshot {
    public final long pageReattachCount;
    public final long exactResumeCount;
    public final long cumulativePatchCount;
    public final long snapshotCount;
    public final long resyncCount;
    public final long syncTimeoutCount;
    public final long hotToWarmCount;
    public final long warmToColdCount;
    public final long leaseAcquireCount;
    public final long leaseDeniedCount;
    public final long leaseRetryCount;
    public final long leaseRenewCount;
    public final long leaseRevokedCount;
    public final long leaseStaleResponseCount;
    public final long mailboxOverflowCount;
    public final long mailboxRecoveredCount;
    public final long mailboxMaxPendingBytes;

    Snapshot(long pageReattachCount, long exactResumeCount, long cumulativePatchCount,
             long snapshotCount, long resyncCount, long syncTimeoutCount,
             long hotToWarmCount, long warmToColdCount, long leaseAcquireCount,
             long leaseDeniedCount, long leaseRetryCount, long leaseRenewCount,
             long leaseRevokedCount, long leaseStaleResponseCount, long mailboxOverflowCount,
             long mailboxRecoveredCount, long mailboxMaxPendingBytes) {
      this.pageReattachCount = pageReattachCount;
      this.exactResumeCount = exactResumeCount;
      this.cumulativePatchCount = cumulativePatchCount;
      this.snapshotCount = snapshotCount;
      this.resyncCount = resyncCount;
      this.syncTimeoutCount = syncTimeoutCount;
      this.hotToWarmCount = hotToWarmCount;
      this.warmToColdCount = warmToColdCount;
      this.leaseAcquireCount = leaseAcquireCount;
      this.leaseDeniedCount = leaseDeniedCount;
      this.leaseRetryCount = leaseRetryCount;
      this.leaseRenewCount = leaseRenewCount;
      this.leaseRevokedCount = leaseRevokedCount;
      this.leaseStaleResponseCount = leaseStaleResponseCount;
      this.mailboxOverflowCount = mailboxOverflowCount;
      this.mailboxRecoveredCount = mailboxRecoveredCount;
      this.mailboxMaxPendingBytes = mailboxMaxPendingBytes;
    }
  }
}
