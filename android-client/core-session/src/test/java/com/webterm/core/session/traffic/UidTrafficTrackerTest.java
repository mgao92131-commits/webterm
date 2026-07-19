package com.webterm.core.session.traffic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class UidTrafficTrackerTest {

  @Test
  public void snapshotReturnsZeroBeforeStart() {
    UidTrafficTracker tracker = new UidTrafficTracker(fixedSource(100L, 200L));
    UidTrafficTracker.Snapshot snapshot = tracker.snapshot();
    assertEquals(0L, snapshot.rxBytes);
    assertEquals(0L, snapshot.txBytes);
  }

  @Test
  public void snapshotReturnsDifferenceSinceStart() {
    MutableSource source = new MutableSource(100L, 200L);
    UidTrafficTracker tracker = new UidTrafficTracker(source);
    tracker.start();

    source.set(250L, 460L);
    UidTrafficTracker.Snapshot snapshot = tracker.snapshot();
    assertEquals(150L, snapshot.rxBytes);
    assertEquals(260L, snapshot.txBytes);

    // snapshot 不修改基准，再次调用应得到相同结果。
    UidTrafficTracker.Snapshot again = tracker.snapshot();
    assertEquals(150L, again.rxBytes);
    assertEquals(260L, again.txBytes);
  }

  @Test
  public void stopReturnsDifferenceAndResets() {
    MutableSource source = new MutableSource(100L, 200L);
    UidTrafficTracker tracker = new UidTrafficTracker(source);
    tracker.start();

    source.set(500L, 800L);
    UidTrafficTracker.Snapshot stopped = tracker.stop();
    assertEquals(400L, stopped.rxBytes);
    assertEquals(600L, stopped.txBytes);

    // stop 后 snapshot 回到零，直到再次 start。
    source.set(900L, 1200L);
    UidTrafficTracker.Snapshot afterStop = tracker.snapshot();
    assertEquals(0L, afterStop.rxBytes);
    assertEquals(0L, afterStop.txBytes);
  }

  @Test
  public void concurrentSnapshotsDoNotLoseData() throws InterruptedException {
    MutableSource source = new MutableSource(0L, 0L);
    UidTrafficTracker tracker = new UidTrafficTracker(source);
    tracker.start();

    int threads = 8;
    int iterations = 1000;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(threads * 2);
    AtomicLong maxRx = new AtomicLong(0L);
    AtomicLong maxTx = new AtomicLong(0L);

    for (int i = 0; i < threads; i++) {
      executor.execute(() -> {
        try {
          for (int j = 0; j < iterations; j++) {
            source.add(1L, 2L);
            UidTrafficTracker.Snapshot s = tracker.snapshot();
            updateMax(maxRx, s.rxBytes);
            updateMax(maxTx, s.txBytes);
          }
        } finally {
          latch.countDown();
        }
      });
      executor.execute(() -> {
        try {
          for (int j = 0; j < iterations; j++) {
            UidTrafficTracker.Snapshot s = tracker.snapshot();
            updateMax(maxRx, s.rxBytes);
            updateMax(maxTx, s.txBytes);
          }
        } finally {
          latch.countDown();
        }
      });
    }

    assert latch.await(10L, TimeUnit.SECONDS) : "concurrent test timed out";
    executor.shutdown();

    // 最终差值至少应达到 writers 产生的增量（可能有读线程读到较小值，但最大值应接近）。
    assertEquals(threads * iterations, maxRx.get());
    assertEquals(threads * iterations * 2L, maxTx.get());
  }

  @Test
  public void negativeTrafficStatsTreatedAsZero() {
    UidTrafficTracker tracker = new UidTrafficTracker(fixedSource(-1L, -1L));
    tracker.start();
    UidTrafficTracker.Snapshot snapshot = tracker.snapshot();
    assertEquals(0L, snapshot.rxBytes);
    assertEquals(0L, snapshot.txBytes);
  }

  private static UidTrafficTracker.Source fixedSource(long rx, long tx) {
    return () -> new UidTrafficTracker.Snapshot(rx, tx);
  }

  private static void updateMax(AtomicLong target, long value) {
    long current;
    do {
      current = target.get();
      if (value <= current) break;
    } while (!target.compareAndSet(current, value));
  }

  private static final class MutableSource implements UidTrafficTracker.Source {
    private final AtomicLong rx = new AtomicLong();
    private final AtomicLong tx = new AtomicLong();

    MutableSource(long rx, long tx) {
      this.rx.set(rx);
      this.tx.set(tx);
    }

    void set(long rx, long tx) {
      this.rx.set(rx);
      this.tx.set(tx);
    }

    void add(long rxDelta, long txDelta) {
      rx.addAndGet(rxDelta);
      tx.addAndGet(txDelta);
    }

    @Override
    public UidTrafficTracker.Snapshot read() {
      return new UidTrafficTracker.Snapshot(rx.get(), tx.get());
    }
  }
}
