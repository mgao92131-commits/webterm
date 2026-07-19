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

  /**
   * TerminalRenderMetrics.inboundScreenFrame 按 int kind 分类，
   * 其内部 MessageKind 顺序与 ScreenMailbox.MessageKind 一致：
   * 0=SNAPSHOT, 1=PATCH, 2=HISTORY_PAGE, 3=HISTORY_TRIM, 4=OTHER, 5=UNKNOWN。
   */
  private static final int SNAPSHOT = 0;
  private static final int PATCH = 1;
  private static final int HISTORY_PAGE = 2;
  private static final int HISTORY_TRIM = 3;
  private static final int OTHER = 4;
  private static final int UNKNOWN = 5;

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
    TerminalRenderMetrics.inboundScreenFrame(SNAPSHOT, 100);
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
    TerminalRenderMetrics.inboundScreenFrame(PATCH, 42);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.patchFrameCount);
    assertEquals(42L, s.patchFrameBytes);
    assertEquals(0L, s.snapshotFrameCount);
  }

  @Test
  public void classifyHistoryPage() {
    TerminalRenderMetrics.inboundScreenFrame(HISTORY_PAGE, 2048);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.historyPageFrameCount);
    assertEquals(2048L, s.historyPageFrameBytes);
  }

  @Test
  public void classifyHistoryTrim() {
    TerminalRenderMetrics.inboundScreenFrame(HISTORY_TRIM, 16);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.historyTrimFrameCount);
    assertEquals(16L, s.historyTrimFrameBytes);
  }

  @Test
  public void classifyOtherAndUnknown() {
    TerminalRenderMetrics.inboundScreenFrame(OTHER, 10);
    TerminalRenderMetrics.inboundScreenFrame(UNKNOWN, 20);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(2L, s.otherFrameCount);
    assertEquals(30L, s.otherFrameBytes);
  }

  @Test
  public void negativeBytesTreatedAsZero() {
    TerminalRenderMetrics.inboundScreenFrame(SNAPSHOT, -50);
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

    for (int i = 0; i < threads; i++) {
      final int kind = i % 4;
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
