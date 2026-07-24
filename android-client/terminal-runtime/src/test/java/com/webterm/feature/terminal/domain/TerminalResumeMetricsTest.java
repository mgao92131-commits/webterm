package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * TerminalResumeMetrics 的计数与快照测试。
 * 计数器是进程级静态的，测试间互相影响，因此全部断言使用调用前后差值。
 */
public class TerminalResumeMetricsTest {

  @Test
  public void countersAccumulateIntoSnapshot() {
    TerminalResumeMetrics.Snapshot before = TerminalResumeMetrics.snapshot();

    TerminalResumeMetrics.pageReattach();
    TerminalResumeMetrics.exactResume(1, 2);
    TerminalResumeMetrics.cumulativePatch(1, 2, 3, 4);
    TerminalResumeMetrics.snapshot(5);
    TerminalResumeMetrics.resync("test");
    TerminalResumeMetrics.syncTimeout();
    TerminalResumeMetrics.hotToWarm();
    TerminalResumeMetrics.warmToCold();
    TerminalResumeMetrics.leaseAcquire(false);
    TerminalResumeMetrics.leaseAcquire(true);
    TerminalResumeMetrics.leaseDenied();
    TerminalResumeMetrics.leaseRetry();
    TerminalResumeMetrics.leaseRevoked();
    TerminalResumeMetrics.leaseStaleResponse();
    TerminalResumeMetrics.screenMailboxOverflow("test", 128, 3);
    TerminalResumeMetrics.screenMailboxRecovered("ok");
    TerminalResumeMetrics.staleStreamGeneration();

    TerminalResumeMetrics.Snapshot after = TerminalResumeMetrics.snapshot();
    assertEquals(before.pageReattachCount + 1, after.pageReattachCount);
    assertEquals(before.exactResumeCount + 1, after.exactResumeCount);
    assertEquals(before.cumulativePatchCount + 1, after.cumulativePatchCount);
    assertEquals(before.snapshotCount + 1, after.snapshotCount);
    assertEquals(before.resyncCount + 1, after.resyncCount);
    assertEquals(before.syncTimeoutCount + 1, after.syncTimeoutCount);
    assertEquals(before.hotToWarmCount + 1, after.hotToWarmCount);
    assertEquals(before.warmToColdCount + 1, after.warmToColdCount);
    assertEquals(before.leaseAcquireCount + 1, after.leaseAcquireCount);
    assertEquals(before.leaseRenewCount + 1, after.leaseRenewCount);
    assertEquals(before.leaseDeniedCount + 1, after.leaseDeniedCount);
    assertEquals(before.leaseRetryCount + 1, after.leaseRetryCount);
    assertEquals(before.leaseRevokedCount + 1, after.leaseRevokedCount);
    assertEquals(before.leaseStaleResponseCount + 1, after.leaseStaleResponseCount);
    assertEquals(before.mailboxOverflowCount + 3, after.mailboxOverflowCount);
    assertEquals(before.mailboxRecoveredCount + 1, after.mailboxRecoveredCount);
    assertEquals(before.staleStreamGenerationCount + 1, after.staleStreamGenerationCount);
  }

  @Test
  public void mailboxOverflowCountsAtLeastOne() {
    TerminalResumeMetrics.Snapshot before = TerminalResumeMetrics.snapshot();
    TerminalResumeMetrics.screenMailboxOverflow("test", 64, 0);
    assertEquals(before.mailboxOverflowCount + 1,
        TerminalResumeMetrics.snapshot().mailboxOverflowCount);
  }

  @Test
  public void highWaterOnlyIncreases() {
    long base = TerminalResumeMetrics.snapshot().mailboxMaxPendingBytes;
    TerminalResumeMetrics.screenMailboxHighWater(base + 100);
    assertEquals(base + 100, TerminalResumeMetrics.snapshot().mailboxMaxPendingBytes);
    // 较低的高水位不会拉低已记录值。
    TerminalResumeMetrics.screenMailboxHighWater(base + 50);
    assertEquals(base + 100, TerminalResumeMetrics.snapshot().mailboxMaxPendingBytes);
  }

  @Test
  public void highWaterCasKeepsMaxUnderConcurrency() throws InterruptedException {
    long base = TerminalResumeMetrics.snapshot().mailboxMaxPendingBytes;
    int threads = 4;
    int perThread = 50;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    for (int t = 0; t < threads; t++) {
      final int threadIndex = t;
      pool.execute(() -> {
        try {
          start.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        for (int i = 0; i < perThread; i++) {
          TerminalResumeMetrics.screenMailboxHighWater(base + threadIndex * perThread + i + 1);
        }
      });
    }
    start.countDown();
    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);
    assertEquals(base + (long) threads * perThread,
        TerminalResumeMetrics.snapshot().mailboxMaxPendingBytes);
  }
}
