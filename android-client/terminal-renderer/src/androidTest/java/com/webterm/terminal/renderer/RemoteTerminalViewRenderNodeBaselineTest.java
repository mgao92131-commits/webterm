package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenMutation;
import com.webterm.terminal.model.ScreenRowWrite;
import com.webterm.terminal.model.ScreenScroll;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCommit;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.model.TerminalViewportState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** 模拟器/真机生产 View + RenderNode 缓存链路的无正文性能 smoke baseline。 */
@RunWith(AndroidJUnit4.class)
public final class RemoteTerminalViewRenderNodeBaselineTest {
  private static final int ROWS = 8;
  private static final int COLS = 80;

  @Test
  public void baselineAndSingleLinePatchUseHardwareRowCache() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    RenderUpdate baselineUpdate = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    TerminalRenderMetrics.Snapshot before = TerminalRenderMetrics.snapshot();
    TerminalRenderMetrics.Snapshot afterBaseline;
    TerminalRenderMetrics.Snapshot afterPatch;
    AtomicReference<RemoteTerminalView> viewRef = new AtomicReference<>();

    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      scenario.onActivity(activity -> {
        RemoteTerminalView view = new RemoteTerminalView(activity);
        viewRef.set(view);
        activity.setContentView(view, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      DrawWaiter baselineDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        RemoteTerminalView view = viewRef.get();
        assertTrue(view.getWidth() > 0);
        assertTrue(view.getHeight() > 0);
        assertTrue(view.isHardwareAccelerated());
        assertTrue("test rows must fit in the actual View",
            view.getHeight() >= Math.ceil(ROWS * view.lineHeight()));
        baselineDraw.attach(view);
        view.bindModel(model);
        view.applyRenderUpdate(baselineUpdate, viewport);
      });
      assertTrue("Baseline hardware draw did not complete", baselineDraw.await());
      scenario.onActivity(activity -> baselineDraw.detach(viewRef.get()));
      afterBaseline = TerminalRenderMetrics.snapshot();

      model.applyTerminalCommit(new TerminalCommit(
          "i1", 1, 1, 2, 1, 1,
          com.webterm.terminal.model.DictionaryEntries.EMPTY, null,
          new ScreenMutation(new ScreenScroll(0, ROWS, 1),
              Collections.singletonList(new ScreenRowWrite(
                  ROWS - 1, line(200_000, 1, 0, "new")))),
          null, null, null, null));
      RenderUpdate patchUpdate = model.consumeRenderUpdate();
      assertEquals(1, patchUpdate.dirty.screenScrollRows);
      DrawWaiter patchDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        RemoteTerminalView view = viewRef.get();
        patchDraw.attach(view);
        view.applyRenderUpdate(patchUpdate, viewport);
      });
      assertTrue("Patch hardware draw did not complete", patchDraw.await());
      scenario.onActivity(activity -> patchDraw.detach(viewRef.get()));
      afterPatch = TerminalRenderMetrics.snapshot();
    }

    long baselineRecords = afterBaseline.rowNodeRecordCount - before.rowNodeRecordCount;
    long patchRecords = afterPatch.rowNodeRecordCount - afterBaseline.rowNodeRecordCount;
    assertTrue(baselineRecords > 0);
    assertEquals("all test rows fit and must be recorded in the Baseline frame",
        ROWS, baselineRecords);
    assertEquals(1L, patchRecords);
      assertTrue("the TerminalCommit frame must reuse visible retained lines",
        afterPatch.rowCacheHitCount > afterBaseline.rowCacheHitCount);
    assertEquals(baselineRecords,
        bucketTotal(afterBaseline.renderNodeRecordLatencyBuckets)
            - bucketTotal(before.renderNodeRecordLatencyBuckets));
    assertEquals(patchRecords,
        bucketTotal(afterPatch.renderNodeRecordLatencyBuckets)
            - bucketTotal(afterBaseline.renderNodeRecordLatencyBuckets));
    System.out.println("PERF_DEVICE_BASELINE hardware_render_node=true rows=" + ROWS
        + " baseline_records=" + baselineRecords + " patch_records=" + patchRecords);
  }

  private static ScreenBaseline baseline() {
    List<TerminalLine> screen = new ArrayList<>();
    for (int row = 0; row < ROWS; row++) {
      screen.add(line(100_000 + row, 1, 0, "row"));
    }
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, false,
        com.webterm.terminal.model.DictionaryEntries.EMPTY,
        ROWS, COLS, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(), screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static TerminalLine line(long id, long version, long historySeq, String text) {
    TerminalCell[] cells = new TerminalCell[COLS];
    cells[0] = new TerminalCell(text, (byte) 1, null, null);
    for (int column = 1; column < COLS; column++) cells[column] = TerminalCell.EMPTY;
    return new TerminalLine(id, version, historySeq, false, cells);
  }

  private static long bucketTotal(long[] buckets) {
    long total = 0;
    for (long bucket : buckets) total += bucket;
    return total;
  }

  private static final class DrawWaiter implements ViewTreeObserver.OnDrawListener {
    private final CountDownLatch latch = new CountDownLatch(1);

    void attach(RemoteTerminalView view) {
      view.getViewTreeObserver().addOnDrawListener(this);
    }

    void detach(RemoteTerminalView view) {
      ViewTreeObserver observer = view.getViewTreeObserver();
      if (observer.isAlive()) observer.removeOnDrawListener(this);
    }

    boolean await() throws InterruptedException {
      return latch.await(5, TimeUnit.SECONDS);
    }

    @Override
    public void onDraw() {
      latch.countDown();
    }
  }
}
