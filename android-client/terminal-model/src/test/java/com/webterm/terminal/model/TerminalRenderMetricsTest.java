package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TerminalRenderMetricsTest {

  @Before
  public void resetCounters() throws Exception {
    for (Field field : TerminalRenderMetrics.class.getDeclaredFields()) {
      if (field.getType() == AtomicLong.class
          && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
        field.setAccessible(true);
        AtomicLong counter = (AtomicLong) field.get(null);
        counter.set(0L);
      }
    }
  }

  @Test
  public void classifySnapshot() {
    TerminalRenderMetrics.inboundScreenFrame(TerminalRenderMetrics.ScreenTrafficKind.SNAPSHOT, 100);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.snapshotFrameCount);
    assertEquals(100L, s.snapshotFrameBytes);
    assertEquals(0L, s.patchFrameCount);
    assertEquals(0L, s.historyPageFrameCount);
    assertEquals(0L, s.historyTrimFrameCount);
    assertEquals(0L, s.otherFrameCount);
  }

  @Test
  public void classifyPatch() {
    TerminalRenderMetrics.inboundScreenFrame(TerminalRenderMetrics.ScreenTrafficKind.PATCH, 42);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.patchFrameCount);
    assertEquals(42L, s.patchFrameBytes);
    assertEquals(0L, s.snapshotFrameCount);
  }

  @Test
  public void classifyHistoryPage() {
    TerminalRenderMetrics.inboundScreenFrame(TerminalRenderMetrics.ScreenTrafficKind.HISTORY_PAGE, 2048);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.historyPageFrameCount);
    assertEquals(2048L, s.historyPageFrameBytes);
  }

  @Test
  public void classifyHistoryTrim() {
    TerminalRenderMetrics.inboundScreenFrame(TerminalRenderMetrics.ScreenTrafficKind.HISTORY_TRIM, 16);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.historyTrimFrameCount);
    assertEquals(16L, s.historyTrimFrameBytes);
  }

  @Test
  public void classifyOther() {
    TerminalRenderMetrics.inboundScreenFrame(TerminalRenderMetrics.ScreenTrafficKind.OTHER, 10);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.otherFrameCount);
    assertEquals(10L, s.otherFrameBytes);
  }

  @Test
  public void negativeBytesTreatedAsZero() {
    TerminalRenderMetrics.inboundScreenFrame(TerminalRenderMetrics.ScreenTrafficKind.SNAPSHOT, -50);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.snapshotFrameCount);
    assertEquals(0L, s.snapshotFrameBytes);
  }

  @Test
  public void concurrentIncrementsDoNotLoseData() throws InterruptedException {
    int threads = 8;
    int iterations = 1000;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(threads);

    TerminalRenderMetrics.ScreenTrafficKind[] kinds = {
        TerminalRenderMetrics.ScreenTrafficKind.SNAPSHOT,
        TerminalRenderMetrics.ScreenTrafficKind.PATCH,
        TerminalRenderMetrics.ScreenTrafficKind.HISTORY_PAGE,
        TerminalRenderMetrics.ScreenTrafficKind.HISTORY_TRIM
    };
    for (int i = 0; i < threads; i++) {
      final TerminalRenderMetrics.ScreenTrafficKind kind = kinds[i % kinds.length];
      executor.execute(() -> {
        try {
          for (int j = 0; j < iterations; j++) {
            TerminalRenderMetrics.inboundScreenFrame(kind, 10);
          }
        } finally {
          latch.countDown();
        }
      });
    }

    assert latch.await(10L, TimeUnit.SECONDS) : "concurrent test timed out";
    executor.shutdown();

    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(threads * iterations / 4L, s.snapshotFrameCount);
    assertEquals(threads * iterations / 4L, s.patchFrameCount);
    assertEquals(threads * iterations / 4L, s.historyPageFrameCount);
    assertEquals(threads * iterations / 4L, s.historyTrimFrameCount);
    assertEquals(threads * iterations * 10L,
        s.snapshotFrameBytes + s.patchFrameBytes
            + s.historyPageFrameBytes + s.historyTrimFrameBytes);
  }
}
