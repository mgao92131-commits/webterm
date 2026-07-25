package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public class TerminalRenderMetricsTest {

  @Before
  public void resetCounters() throws Exception {
    for (Field field : TerminalRenderMetrics.class.getDeclaredFields()) {
      if (field.getType() == AtomicLong.class
          && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
        field.setAccessible(true);
        AtomicLong counter = (AtomicLong) field.get(null);
        counter.set(0L);
      } else if (field.getType() == AtomicLongArray.class
          && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
        field.setAccessible(true);
        AtomicLongArray counters = (AtomicLongArray) field.get(null);
        for (int i = 0; i < counters.length(); i++) counters.set(i, 0L);
      }
    }
  }

  @Test
  public void latencyBucketsUseFixedExclusiveUpperBounds() {
    long[] samples = {
        249_999L, 250_000L, 500_000L, 1_000_000L,
        2_000_000L, 4_000_000L, 8_000_000L, 16_000_000L
    };
    for (long sample : samples) {
      TerminalRenderMetrics.screenPatchApplyDuration(sample);
      TerminalRenderMetrics.protobufParseDuration(sample);
      TerminalRenderMetrics.mapperDuration(sample);
      TerminalRenderMetrics.renderNodeRecordDuration(sample);
      TerminalRenderMetrics.mailboxResidenceDuration(sample);
    }

    long[] expected = {1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L};
    TerminalRenderMetrics.Snapshot snapshot = TerminalRenderMetrics.snapshot();
    assertArrayEquals(expected, snapshot.screenPatchApplyLatencyBuckets);
    assertArrayEquals(expected, snapshot.protobufParseLatencyBuckets);
    assertArrayEquals(expected, snapshot.mapperLatencyBuckets);
    assertArrayEquals(expected, snapshot.renderNodeRecordLatencyBuckets);
    assertArrayEquals(expected, snapshot.mailboxResidenceLatencyBuckets);
  }

  @Test
  public void boundedCountersNeverCaptureContent() {
    TerminalRenderMetrics.historyCacheHit();
    TerminalRenderMetrics.historyCacheMiss();
    TerminalRenderMetrics.backgroundPatchDropped();
    TerminalRenderMetrics.screenLineStoreSize(40);
    TerminalRenderMetrics.screenLineStoreSize(10);
    TerminalRenderMetrics.visibleHistoryRowsDrawn(12);

    TerminalRenderMetrics.Snapshot snapshot = TerminalRenderMetrics.snapshot();
    assertEquals(1L, snapshot.historyCacheHitCount);
    assertEquals(1L, snapshot.historyCacheMissCount);
    assertEquals(1L, snapshot.backgroundPatchDroppedCount);
    assertEquals(40L, snapshot.screenLineStoreMaxSize);
    assertEquals(12L, snapshot.visibleHistoryRowsDrawn);
  }

  @Test
  public void classifySnapshot() {
    TerminalRenderMetrics.inboundScreenFrame(TerminalRenderMetrics.ScreenTrafficKind.BASELINE, 100);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.baselineFrameCount);
    assertEquals(100L, s.baselineFrameBytes);
    assertEquals(0L, s.patchFrameCount);
    assertEquals(0L, s.historyRangeFrameCount);
    assertEquals(0L, s.historyDeltaFrameCount);
    assertEquals(0L, s.otherFrameCount);
  }

  @Test
  public void classifyPatch() {
    TerminalRenderMetrics.inboundScreenFrame(TerminalRenderMetrics.ScreenTrafficKind.PATCH, 42);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.patchFrameCount);
    assertEquals(42L, s.patchFrameBytes);
    assertEquals(0L, s.baselineFrameCount);
  }

  @Test
  public void classifyHistoryRange() {
    TerminalRenderMetrics.inboundScreenFrame(TerminalRenderMetrics.ScreenTrafficKind.HISTORY_RANGE, 2048);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.historyRangeFrameCount);
    assertEquals(2048L, s.historyRangeFrameBytes);
  }

  @Test
  public void classifyHistoryDelta() {
    TerminalRenderMetrics.inboundScreenFrame(TerminalRenderMetrics.ScreenTrafficKind.HISTORY_DELTA, 16);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.historyDeltaFrameCount);
    assertEquals(16L, s.historyDeltaFrameBytes);
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
    TerminalRenderMetrics.inboundScreenFrame(TerminalRenderMetrics.ScreenTrafficKind.BASELINE, -50);
    TerminalRenderMetrics.Snapshot s = TerminalRenderMetrics.snapshot();
    assertEquals(1L, s.baselineFrameCount);
    assertEquals(0L, s.baselineFrameBytes);
  }

  @Test
  public void concurrentIncrementsDoNotLoseData() throws InterruptedException {
    int threads = 8;
    int iterations = 1000;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(threads);

    TerminalRenderMetrics.ScreenTrafficKind[] kinds = {
        TerminalRenderMetrics.ScreenTrafficKind.BASELINE,
        TerminalRenderMetrics.ScreenTrafficKind.PATCH,
        TerminalRenderMetrics.ScreenTrafficKind.HISTORY_RANGE,
        TerminalRenderMetrics.ScreenTrafficKind.HISTORY_DELTA
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
    assertEquals(threads * iterations / 4L, s.baselineFrameCount);
    assertEquals(threads * iterations / 4L, s.patchFrameCount);
    assertEquals(threads * iterations / 4L, s.historyRangeFrameCount);
    assertEquals(threads * iterations / 4L, s.historyDeltaFrameCount);
    assertEquals(threads * iterations * 10L,
        s.baselineFrameBytes + s.patchFrameBytes
            + s.historyRangeFrameBytes + s.historyDeltaFrameBytes);
  }
}
