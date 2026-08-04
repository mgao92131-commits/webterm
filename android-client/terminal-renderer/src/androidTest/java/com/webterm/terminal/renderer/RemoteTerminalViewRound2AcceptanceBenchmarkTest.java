package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.FrameMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.webterm.terminal.model.BodyBatchRequestContext;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryBodyResult;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryPush;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineBodyBatchResult;
import com.webterm.terminal.model.LineBodyRecord;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.ProjectionIdentity;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenLineContent;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.model.TerminalViewportState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Round 2.5 的真实 View 链路基准：验证 history 回滚时 RenderNode 淘汰后，CPU prepared
 * 行结果仍可独立复用。它不是网络端到端测试，只从 immutable RenderUpdate 进入生产 View。
 */
@RunWith(AndroidJUnit4.class)
public final class RemoteTerminalViewRound2AcceptanceBenchmarkTest {
  private static final int HISTORY_ROWS = 1_200;
  private static final int SCREEN_ROWS = 8;
  private static final int COLUMNS = 120;
  // 每次约移动 30 行；小于一个屏幕的可见行数，能测到相邻视口的 RenderNode hit，
  // 累计覆盖全部 1,200 行后仍会超过 96 个 RenderNode 和 512 个 prepared entry。
  private static final int POSITION_COUNT = 40;
  private static final long FRAME_BUDGET_NANOS = 16_666_667L;

  @Test
  public void backScrollAndReturnThroughEvictedRows() throws Exception {
    Bundle args = InstrumentationRegistry.getArguments();
    Assume.assumeTrue("webtermPerf=true is required",
        "true".equalsIgnoreCase(args.getString("webtermPerf")));

    Fixture fixture = fixture();
    RemoteTerminalModel model = fixture.model;
    RenderUpdate baselineUpdate = model.consumeRenderUpdate();
    assertNotNull(baselineUpdate);
    assertTrue(model.applyLineBodyBatch(fixture.bodyBatch, fixture.bodyRequest)
        instanceof HistoryBodyResult.Applied);
    RenderUpdate bodyUpdate = model.consumeRenderUpdate();
    assertNotNull(bodyUpdate);
    assertEquals(HISTORY_ROWS, bodyUpdate.snapshot.history.loadedLineCount());

    TerminalViewportState viewport = new TerminalViewportState();
    TerminalPreparedLineCache preparedCache = new TerminalPreparedLineCache();
    FrameStats frameStats = new FrameStats();
    AtomicReference<RemoteTerminalView> viewRef = new AtomicReference<>();
    TerminalRenderMetrics.Snapshot before = TerminalRenderMetrics.snapshot();
    FrameMetricsCollector frameMetrics = new FrameMetricsCollector();

    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      scenario.onActivity(activity -> {
        RemoteTerminalView view = new RemoteTerminalView(activity, preparedCache);
        viewRef.set(view);
        activity.getWindow().addOnFrameMetricsAvailableListener(
            frameMetrics, new Handler(Looper.getMainLooper()));
        activity.setContentView(view, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();

      DrawWaiter initialDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        RemoteTerminalView view = viewRef.get();
        assertTrue(view.isHardwareAccelerated());
        initialDraw.attach(view);
        view.bindModel(model);
        view.applyRenderUpdate(baselineUpdate, viewport);
        view.applyRenderUpdate(bodyUpdate, viewport);
      });
      assertTrue("history baseline draw did not complete", initialDraw.await());
      scenario.onActivity(activity -> {
        initialDraw.detach(viewRef.get());
        frameStats.record(preparedCache);
        frameMetrics.clear();
      });

      int maxOffset = maxScrollOffset(viewRef, bodyUpdate.snapshot);
      assertTrue("history fixture must be scrollable", maxOffset > 0);
      List<Integer> targets = scrollTargets(maxOffset);
      for (int target : targets) {
        DrawWaiter draw = new DrawWaiter();
        long started = System.nanoTime();
        scenario.onActivity(activity -> {
          RemoteTerminalView view = viewRef.get();
          draw.attach(view);
          int current = viewport.derivedScrollOffsetPixels(
              bodyUpdate.snapshot, view.lineHeight(), view.maxScrollOffsetPixels(bodyUpdate.snapshot));
          viewport.scrollBy(
              target - current, view.maxScrollOffsetPixels(bodyUpdate.snapshot),
              bodyUpdate.snapshot, view.lineHeight());
          view.invalidate();
        });
        assertTrue("history scroll draw did not complete target=" + target, draw.await());
        frameStats.frameDurationsNanos.add(Math.max(0L, System.nanoTime() - started));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
          draw.detach(viewRef.get());
          frameStats.record(preparedCache);
        });
      }

      AtomicReference<Boolean> returnedToTail = new AtomicReference<>(false);
      scenario.onActivity(activity -> returnedToTail.set(
          viewRef.get().captureDiagnostics().followTail));
      assertTrue("returning to offset zero must restore follow-tail", returnedToTail.get());
      scenario.onActivity(activity -> activity.getWindow()
          .removeOnFrameMetricsAvailableListener(frameMetrics));
    }

    TerminalRenderMetrics.Snapshot after = TerminalRenderMetrics.snapshot();
    long recordDelta = after.rowNodeRecordCount - before.rowNodeRecordCount;
    long hitDelta = after.rowCacheHitCount - before.rowCacheHitCount;
    long missDelta = after.rowCacheMissCount - before.rowCacheMissCount;
    assertTrue("back-scroll must record row nodes", recordDelta > 0);
    assertTrue("back-scroll must reuse some row nodes", hitDelta > 0);
    assertTrue("the workload must evict prepared entries", frameStats.evictions > 0);
    assertTrue("prepared cache must stay within its byte budget",
        frameStats.maxBytes <= TerminalPreparedLineCache.DEFAULT_BYTE_LIMIT);

    long[] frameDurations = toSortedArray(frameStats.frameDurationsNanos);
    long[] frameMetricDurations = frameMetrics.sortedDurations();
    System.out.println("{\"scenario\":\"view_back_scroll_eviction\""
        + ",\"history_rows\":" + HISTORY_ROWS
        + ",\"positions\":" + frameStats.frameDurationsNanos.size()
        + ",\"view_frame_p50_ns\":" + percentile(frameDurations, 0.50)
        + ",\"view_frame_p95_ns\":" + percentile(frameDurations, 0.95)
        + ",\"view_frame_p99_ns\":" + percentile(frameDurations, 0.99)
        + ",\"frame_metrics_samples\":" + frameMetricDurations.length
        + ",\"frame_metrics_p50_ns\":" + percentile(frameMetricDurations, 0.50)
        + ",\"frame_metrics_p95_ns\":" + percentile(frameMetricDurations, 0.95)
        + ",\"frame_metrics_p99_ns\":" + percentile(frameMetricDurations, 0.99)
        + ",\"frame_metrics_jank_over_16_67ms\":"
        + countOver(frameMetricDurations, FRAME_BUDGET_NANOS)
        + ",\"row_node_records\":" + recordDelta
        + ",\"row_cache_hits\":" + hitDelta
        + ",\"row_cache_misses\":" + missDelta
        + ",\"prepared_hits\":" + frameStats.hits
        + ",\"prepared_misses\":" + frameStats.misses
        + ",\"prepared_evictions\":" + frameStats.evictions
        + ",\"prepared_max_bytes\":" + frameStats.maxBytes + "}");
  }

  private static int maxScrollOffset(
      AtomicReference<RemoteTerminalView> viewRef,
      RemoteTerminalModel.RenderSnapshot snapshot) {
    AtomicReference<Integer> result = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(
        () -> result.set(viewRef.get().maxScrollOffsetPixels(snapshot)));
    return result.get();
  }

  private static List<Integer> scrollTargets(int maxOffset) {
    ArrayList<Integer> targets = new ArrayList<>((POSITION_COUNT - 1) * 2);
    for (int i = 1; i < POSITION_COUNT; i++) {
      targets.add(Math.round(maxOffset * i / (float) (POSITION_COUNT - 1)));
    }
    for (int i = POSITION_COUNT - 2; i >= 0; i--) {
      targets.add(Math.round(maxOffset * i / (float) (POSITION_COUNT - 1)));
    }
    return targets;
  }

  private static Fixture fixture() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ArrayList<HistoryPush> history = new ArrayList<>(HISTORY_ROWS);
    ArrayList<LineBodyRecord> bodies = new ArrayList<>(HISTORY_ROWS);
    HashSet<LineKey> requested = new HashSet<>(HISTORY_ROWS);
    for (int seq = 1; seq <= HISTORY_ROWS; seq++) {
      LineKey key = new LineKey(1_000_000L + seq, 1);
      history.add(new HistoryPush(seq, key));
      bodies.add(new LineBodyRecord(key, lineBody("H")));
      requested.add(key);
    }

    ArrayList<ScreenLineContent> screen = new ArrayList<>(SCREEN_ROWS);
    for (int row = 0; row < SCREEN_ROWS; row++) {
      LineKey key = new LineKey(2_000_000L + row, 1);
      screen.add(new ScreenLineContent(key, lineBody("S")));
    }
    ArrayList<LineKey> screenKeys = new ArrayList<>(SCREEN_ROWS);
    ArrayList<LineBodyRecord> screenBodies = new ArrayList<>(SCREEN_ROWS);
    for (ScreenLineContent line : screen) {
      screenKeys.add(line.key());
      screenBodies.add(new LineBodyRecord(line.key(), line.body()));
    }

    ScreenBaseline baseline = new ScreenBaseline(
        "round2.5", "round2.5-instance", 1, 1, 1,
        SCREEN_ROWS, COLUMNS, TerminalBufferKind.MAIN,
        new HistoryExtent(1, HISTORY_ROWS), history,
        screenKeys, screenBodies, TerminalCursor.hidden(),
        TerminalModes.defaults(), TerminalPalette.defaults());
    assertTrue(model.applyBaseline(baseline));

    ProjectionIdentity identity = new ProjectionIdentity("round2.5-instance", 1, 1);
    LineBodyBatchResult batch = new LineBodyBatchResult(
        "round2.5-bodies", "round2.5-instance", 1, 1,
        LineBodyBatchResult.Status.OK, bodies, Collections.emptyList(), 0);
    return new Fixture(model, batch, new BodyBatchRequestContext(identity, requested));
  }

  private static LineBody lineBody(String text) {
    CellValue[] cells = new CellValue[COLUMNS];
    Arrays.fill(cells, CellValue.EMPTY);
    cells[0] = new CellValue(text, (byte) 1, null, null);
    return new LineBody(COLUMNS, false, cells);
  }

  private static long[] toSortedArray(List<Long> values) {
    long[] result = new long[values.size()];
    for (int i = 0; i < result.length; i++) result[i] = values.get(i);
    Arrays.sort(result);
    return result;
  }

  private static long percentile(long[] sorted, double fraction) {
    if (sorted.length == 0) return 0L;
    int index = Math.min(sorted.length - 1,
        Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1));
    return sorted[index];
  }

  private static long countOver(long[] sorted, long threshold) {
    long count = 0;
    for (long value : sorted) if (value > threshold) count++;
    return count;
  }

  private static final class Fixture {
    final RemoteTerminalModel model;
    final LineBodyBatchResult bodyBatch;
    final BodyBatchRequestContext bodyRequest;

    Fixture(RemoteTerminalModel model, LineBodyBatchResult bodyBatch,
            BodyBatchRequestContext bodyRequest) {
      this.model = model;
      this.bodyBatch = bodyBatch;
      this.bodyRequest = bodyRequest;
    }
  }

  private static final class FrameStats {
    final List<Long> frameDurationsNanos = new ArrayList<>();
    long hits;
    long misses;
    long evictions;
    long maxBytes;

    void record(TerminalPreparedLineCache cache) {
      hits += cache.hitCountForTest();
      misses += cache.missCountForTest();
      evictions += cache.evictionCountForTest();
      maxBytes = Math.max(maxBytes, cache.estimatedBytesForTest());
    }
  }

  private static final class FrameMetricsCollector
      implements Window.OnFrameMetricsAvailableListener {
    private final List<Long> durationsNanos = new ArrayList<>();

    @Override
    public synchronized void onFrameMetricsAvailable(
        Window window, FrameMetrics frameMetrics, int dropCountSinceLastInvocation) {
      long duration = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION);
      if (duration > 0) durationsNanos.add(duration);
    }

    synchronized void clear() {
      durationsNanos.clear();
    }

    synchronized long[] sortedDurations() {
      long[] result = new long[durationsNanos.size()];
      for (int i = 0; i < result.length; i++) result[i] = durationsNanos.get(i);
      Arrays.sort(result);
      return result;
    }
  }

  private static final class DrawWaiter implements ViewTreeObserver.OnDrawListener {
    private final CountDownLatch latch = new CountDownLatch(1);

    void attach(RemoteTerminalView view) {
      view.getViewTreeObserver().addOnDrawListener(this);
    }

    void detach(RemoteTerminalView view) {
      if (view.getViewTreeObserver().isAlive()) {
        view.getViewTreeObserver().removeOnDrawListener(this);
      }
    }

    boolean await() throws InterruptedException {
      return latch.await(10, TimeUnit.SECONDS);
    }

    @Override
    public void onDraw() {
      latch.countDown();
    }
  }
}
