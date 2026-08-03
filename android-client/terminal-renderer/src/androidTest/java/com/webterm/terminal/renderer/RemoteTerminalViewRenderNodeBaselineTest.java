package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineBodyRecord;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenLineContent;
import com.webterm.terminal.model.ScreenMutation;
import com.webterm.terminal.model.ScreenRowWrite;
import com.webterm.terminal.model.ScreenScroll;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCommit;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.TerminalStateUpdate;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.model.capture.CapturedScreenshot;

import java.util.ArrayList;
import java.util.Arrays;
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
  private static final int MIXED_ROWS = 40;
  private static final int MIXED_COLS = 120;

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
          "i1", 1, 1, 2, 1, TerminalBufferKind.MAIN,
          Collections.singletonList(new LineBodyRecord(
              new LineKey(200_000, 1),
              line(200_000, 1, 0, "new").body())),
          new ScreenMutation(new ScreenScroll(0, ROWS, 1),
              Collections.singletonList(ScreenRowWrite.fromLine(
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
        + " baseline_records=" + baselineRecords
        + " baseline_cache_hits="
        + (afterBaseline.rowCacheHitCount - before.rowCacheHitCount)
        + " baseline_cache_misses="
        + (afterBaseline.rowCacheMissCount - before.rowCacheMissCount)
        + " patch_records=" + patchRecords
        + " patch_cache_hits="
        + (afterPatch.rowCacheHitCount - afterBaseline.rowCacheHitCount)
        + " patch_cache_misses="
        + (afterPatch.rowCacheMissCount - afterBaseline.rowCacheMissCount));
  }

  @Test
  public void sameSnapshotSecondFrameHitsWithoutRerecord() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    RenderUpdate update = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    AtomicReference<RemoteTerminalView> viewRef = new AtomicReference<>();
    TerminalRenderMetrics.Snapshot afterFirst;
    TerminalRenderMetrics.Snapshot afterSecond;

    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      attachView(scenario, viewRef, ViewGroup.LayoutParams.MATCH_PARENT);
      DrawWaiter firstDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        RemoteTerminalView view = viewRef.get();
        assertTrue(view.isHardwareAccelerated());
        assertTrue(view.getHeight() >= Math.ceil(ROWS * view.lineHeight()));
        firstDraw.attach(view);
        view.bindModel(model);
        view.applyRenderUpdate(update, viewport);
      });
      assertTrue("first hardware draw did not complete", firstDraw.await());
      scenario.onActivity(activity -> firstDraw.detach(viewRef.get()));
      afterFirst = TerminalRenderMetrics.snapshot();

      DrawWaiter secondDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        secondDraw.attach(viewRef.get());
        viewRef.get().invalidate();
      });
      assertTrue("second hardware draw did not complete", secondDraw.await());
      scenario.onActivity(activity -> secondDraw.detach(viewRef.get()));
      afterSecond = TerminalRenderMetrics.snapshot();
    }

    assertEquals("cache hit frame must not rerecord rows", 0L,
        afterSecond.rowNodeRecordCount - afterFirst.rowNodeRecordCount);
    assertTrue("cache hit frame must report row hits",
        afterSecond.rowCacheHitCount > afterFirst.rowCacheHitCount);
    System.out.println("PERF_DEVICE_CACHE_HIT records_delta="
        + (afterSecond.rowNodeRecordCount - afterFirst.rowNodeRecordCount)
        + " cache_hits_delta=" + (afterSecond.rowCacheHitCount - afterFirst.rowCacheHitCount)
        + " row_cache_miss_delta="
        + (afterSecond.rowCacheMissCount - afterFirst.rowCacheMissCount)
        + " render_duration_delta_nanos="
        + (afterSecond.renderDurationNanos - afterFirst.renderDurationNanos));
  }

  @Test
  public void cursorBlinkDoesNotRerecordStaticRows() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(ROWS, COLS,
        new TerminalCursor(2, 3, true, TerminalCursor.Shape.BLOCK, true))));
    RenderUpdate update = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    AtomicReference<RemoteTerminalView> viewRef = new AtomicReference<>();
    TerminalRenderMetrics.Snapshot afterFirst;
    TerminalRenderMetrics.Snapshot afterBlinkOff;

    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      attachView(scenario, viewRef, ViewGroup.LayoutParams.MATCH_PARENT);
      DrawWaiter firstDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        firstDraw.attach(viewRef.get());
        viewRef.get().bindModel(model);
        viewRef.get().applyRenderUpdate(update, viewport);
      });
      assertTrue("blink baseline draw did not complete", firstDraw.await());
      scenario.onActivity(activity -> firstDraw.detach(viewRef.get()));
      afterFirst = TerminalRenderMetrics.snapshot();

      AtomicReference<Boolean> blinkOff = new AtomicReference<>(false);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      while (System.nanoTime() < deadline && !blinkOff.get()) {
        scenario.onActivity(activity -> blinkOff.set(
            !viewRef.get().captureDiagnostics().cursorBlinkOn));
        if (!blinkOff.get()) Thread.sleep(100L);
      }
      assertTrue("cursor blink did not reach the off phase", blinkOff.get());

      // captureDiagnostics() observes the state transition before the posted
      // invalidation necessarily reaches onDraw(). Force one frame after the
      // off phase and sample metrics only after that frame has completed.
      DrawWaiter blinkOffDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        blinkOffDraw.attach(viewRef.get());
        viewRef.get().invalidate();
      });
      assertTrue("cursor blink off draw did not complete", blinkOffDraw.await());
      scenario.onActivity(activity -> blinkOffDraw.detach(viewRef.get()));
      afterBlinkOff = TerminalRenderMetrics.snapshot();
    }

    assertEquals("cursor blink must not rerecord static row nodes", 0L,
        afterBlinkOff.rowNodeRecordCount - afterFirst.rowNodeRecordCount);
    assertTrue("cursor blink must redraw through cache hits",
        afterBlinkOff.rowCacheHitCount > afterFirst.rowCacheHitCount);
    System.out.println("PERF_DEVICE_CURSOR_BLINK records_delta="
        + (afterBlinkOff.rowNodeRecordCount - afterFirst.rowNodeRecordCount)
        + " cache_hits_delta="
        + (afterBlinkOff.rowCacheHitCount - afterFirst.rowCacheHitCount)
        + " row_cache_miss_delta="
        + (afterBlinkOff.rowCacheMissCount - afterFirst.rowCacheMissCount));
  }

  @Test
  public void textBlinkDoesNotRerecordStaticRows() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(textBlinkBaseline()));
    RenderUpdate update = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    AtomicReference<RemoteTerminalView> viewRef = new AtomicReference<>();
    TerminalRenderMetrics.Snapshot afterFirst;
    TerminalRenderMetrics.Snapshot afterBlinkOff;
    AtomicReference<CapturedScreenshot> onShot = new AtomicReference<>();
    AtomicReference<CapturedScreenshot> offShot = new AtomicReference<>();

    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      attachView(scenario, viewRef, ViewGroup.LayoutParams.MATCH_PARENT);
      DrawWaiter firstDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        firstDraw.attach(viewRef.get());
        viewRef.get().bindModel(model);
        viewRef.get().applyRenderUpdate(update, viewport);
      });
      assertTrue("text blink baseline draw did not complete", firstDraw.await());
      scenario.onActivity(activity -> {
        firstDraw.detach(viewRef.get());
        assertTrue("text blink scheduler must be active",
            viewRef.get().animationScheduledForTest());
        viewRef.get().onWindowVisibilityChanged(View.INVISIBLE);
        assertFalse("hidden window must stop text blink scheduler",
            viewRef.get().animationScheduledForTest());
        viewRef.get().onWindowVisibilityChanged(View.VISIBLE);
        assertTrue("visible window must restart text blink scheduler",
            viewRef.get().animationScheduledForTest());
      });
      afterFirst = TerminalRenderMetrics.snapshot();

      waitForBlinkState(scenario, viewRef, true, 2_000L);
      scenario.onActivity(activity -> onShot.set(viewRef.get().captureScreenshot()));
      waitForBlinkState(scenario, viewRef, false, 2_000L);

      DrawWaiter blinkOffDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        blinkOffDraw.attach(viewRef.get());
        viewRef.get().invalidate();
      });
      assertTrue("text blink off draw did not complete", blinkOffDraw.await());
      scenario.onActivity(activity -> {
        blinkOffDraw.detach(viewRef.get());
        offShot.set(viewRef.get().captureScreenshot());
      });
      afterBlinkOff = TerminalRenderMetrics.snapshot();
    }

    assertEquals("text blink must not rerecord static row nodes", 0L,
        afterBlinkOff.rowNodeRecordCount - afterFirst.rowNodeRecordCount);
    assertTrue("text blink frame must draw cached rows",
        afterBlinkOff.rowCacheHitCount > afterFirst.rowCacheHitCount);
    assertTrue("text blink phase must change foreground pixels",
        differentPixels(onShot.get(), offShot.get()) > 0);
  }

  @Test
  public void aggregatedVisibilityRestartsTextBlinkScheduler() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(textBlinkBaseline()));
    RenderUpdate update = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    AtomicReference<RemoteTerminalView> viewRef = new AtomicReference<>();
    AtomicReference<FrameLayout> parentRef = new AtomicReference<>();

    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      scenario.onActivity(activity -> {
        FrameLayout parent = new FrameLayout(activity);
        RemoteTerminalView view = new RemoteTerminalView(activity);
        parentRef.set(parent);
        viewRef.set(view);
        parent.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setContentView(parent, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();

      DrawWaiter firstDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        firstDraw.attach(viewRef.get());
        viewRef.get().bindModel(model);
        viewRef.get().applyRenderUpdate(update, viewport);
      });
      assertTrue("aggregated visibility baseline draw did not complete", firstDraw.await());
      scenario.onActivity(activity -> {
        firstDraw.detach(viewRef.get());
        assertTrue("text blink scheduler must start while aggregated visible",
            viewRef.get().animationScheduledForTest());
        viewRef.get().setVisibility(View.INVISIBLE);
      });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(activity -> assertFalse(
          "View visibility must stop text blink scheduler",
          viewRef.get().animationScheduledForTest()));

      scenario.onActivity(activity -> viewRef.get().setVisibility(View.VISIBLE));
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(activity -> assertTrue(
          "View visibility restore must restart text blink scheduler",
          viewRef.get().animationScheduledForTest()));

      scenario.onActivity(activity -> parentRef.get().setVisibility(View.GONE));
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(activity -> assertFalse(
          "parent visibility must stop text blink scheduler",
          viewRef.get().animationScheduledForTest()));

      scenario.onActivity(activity -> parentRef.get().setVisibility(View.VISIBLE));
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(activity -> assertTrue(
          "parent visibility restore must restart text blink scheduler",
          viewRef.get().animationScheduledForTest()));
    }
  }

  @Test
  public void selectionChangeDoesNotRerecordStaticRows() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    RenderUpdate initial = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    AtomicReference<RemoteTerminalView> viewRef = new AtomicReference<>();
    TerminalRenderMetrics.Snapshot afterFirst;
    TerminalRenderMetrics.Snapshot afterSelection;
    AtomicReference<CapturedScreenshot> beforeSelectionShot = new AtomicReference<>();
    AtomicReference<CapturedScreenshot> afterSelectionShot = new AtomicReference<>();

    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      attachView(scenario, viewRef, ViewGroup.LayoutParams.MATCH_PARENT);
      DrawWaiter firstDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        firstDraw.attach(viewRef.get());
        viewRef.get().bindModel(model);
        viewRef.get().applyRenderUpdate(initial, viewport);
      });
      assertTrue("selection baseline draw did not complete", firstDraw.await());
      scenario.onActivity(activity -> {
        firstDraw.detach(viewRef.get());
        beforeSelectionShot.set(viewRef.get().captureScreenshot());
      });
      afterFirst = TerminalRenderMetrics.snapshot();

      viewport.selection = new TerminalSelection(
          new TerminalSelection.Anchor(0, 0, 1),
          new TerminalSelection.Anchor(0, 0, 5));
      RenderUpdate selectionUpdate = new RenderUpdate(
          initial.publicationVersion + 1, initial.snapshot,
          new RenderDirtyState(), new TerminalStateUpdate());
      DrawWaiter selectionDraw = new DrawWaiter();
      scenario.onActivity(activity -> {
        selectionDraw.attach(viewRef.get());
        viewRef.get().applyRenderUpdate(selectionUpdate, viewport);
        // Selection is a viewport overlay and has no model dirty rows.
        viewRef.get().invalidate();
      });
      assertTrue("selection overlay draw did not complete", selectionDraw.await());
      scenario.onActivity(activity -> {
        selectionDraw.detach(viewRef.get());
        afterSelectionShot.set(viewRef.get().captureScreenshot());
      });
      afterSelection = TerminalRenderMetrics.snapshot();
    }

    assertEquals("selection change must not rerecord static row nodes", 0L,
        afterSelection.rowNodeRecordCount - afterFirst.rowNodeRecordCount);
    assertTrue("selection frame must still draw cached rows",
        afterSelection.rowCacheHitCount > afterFirst.rowCacheHitCount);
    assertTrue("selection overlay must change captured pixels",
        differentPixels(beforeSelectionShot.get(), afterSelectionShot.get()) > 0);
    System.out.println("PERF_DEVICE_SELECTION records_delta="
        + (afterSelection.rowNodeRecordCount - afterFirst.rowNodeRecordCount)
        + " cache_hits_delta="
        + (afterSelection.rowCacheHitCount - afterFirst.rowCacheHitCount)
        + " row_cache_miss_delta="
        + (afterSelection.rowCacheMissCount - afterFirst.rowCacheMissCount)
        + " changed_pixels="
        + differentPixels(beforeSelectionShot.get(), afterSelectionShot.get()));
  }

  @Test
  public void mixedUnicodeFortyByOneTwentyReportsRenderMetrics() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(mixedBaseline()));
    RenderUpdate update = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    AtomicReference<RemoteTerminalView> viewRef = new AtomicReference<>();
    AtomicReference<Integer> visibleRowsRef = new AtomicReference<>();
    AtomicReference<Integer> recordableRowsRef = new AtomicReference<>();
    TerminalRenderMetrics.Snapshot before = TerminalRenderMetrics.snapshot();
    TerminalRenderMetrics.Snapshot after;

    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      attachView(scenario, viewRef, ViewGroup.LayoutParams.MATCH_PARENT);
      DrawWaiter draw = new DrawWaiter();
      scenario.onActivity(activity -> {
        RemoteTerminalView view = viewRef.get();
        assertTrue(view.isHardwareAccelerated());
        int usablePixels = view.liveScreenExitOffsetPixels();
        int visibleRows = Math.min(MIXED_ROWS,
            Math.max(0, (int) Math.floor(usablePixels / view.lineHeight())));
        // RemoteTerminalRenderer includes one anti-aliasing guard row at the clip bottom.
        int recordableRows = Math.min(MIXED_ROWS,
            Math.max(0, (int) Math.ceil(usablePixels / view.lineHeight()) + 1));
        visibleRowsRef.set(visibleRows);
        recordableRowsRef.set(recordableRows);
        draw.attach(view);
        view.bindModel(model);
        view.applyRenderUpdate(update, viewport);
      });
      assertTrue("mixed Unicode hardware draw did not complete", draw.await());
      scenario.onActivity(activity -> draw.detach(viewRef.get()));
      after = TerminalRenderMetrics.snapshot();
    }

    long records = after.rowNodeRecordCount - before.rowNodeRecordCount;
    assertTrue("mixed Unicode frame must draw at least one visible row", records > 0);
    assertEquals((long) recordableRowsRef.get(), records);
    assertTrue(after.renderDurationNanos > before.renderDurationNanos);
    assertEquals(records,
        bucketTotal(after.renderNodeRecordLatencyBuckets)
            - bucketTotal(before.renderNodeRecordLatencyBuckets));
    System.out.println("PERF_DEVICE_MIXED hardware_render_node=true rows=" + MIXED_ROWS
        + " cols=" + MIXED_COLS + " records=" + records
        + " visible_rows=" + visibleRowsRef.get()
        + " recordable_rows=" + recordableRowsRef.get()
        + " view_height=" + viewRef.get().getHeight()
        + " line_height=" + viewRef.get().lineHeight()
        + " render_duration_nanos="
        + (after.renderDurationNanos - before.renderDurationNanos)
        + " row_cache_hits="
        + (after.rowCacheHitCount - before.rowCacheHitCount)
        + " row_cache_misses="
        + (after.rowCacheMissCount - before.rowCacheMissCount)
        + " render_node_record_events="
        + (bucketTotal(after.renderNodeRecordLatencyBuckets)
            - bucketTotal(before.renderNodeRecordLatencyBuckets))
        + " visible_history_rows="
        + (after.visibleHistoryRowsDrawn - before.visibleHistoryRowsDrawn));
  }

  private static ScreenBaseline baseline() {
    return baseline(ROWS, COLS, TerminalCursor.hidden());
  }

  private static ScreenBaseline textBlinkBaseline() {
    List<ScreenLineContent> screen = new ArrayList<>();
    StyleValue blink = new StyleValue(
        TerminalColor.rgb(0xFF0000), TerminalColor.DEFAULT_BG, null, 1 << 8);
    for (int row = 0; row < ROWS; row++) {
      CellValue[] cells = new CellValue[COLS];
      Arrays.fill(cells, CellValue.EMPTY);
      cells[0] = row == 0
          ? new CellValue("B", (byte) 1, blink, null)
          : new CellValue("row", (byte) 1, null, null);
      screen.add(new ScreenLineContent(
          new LineKey(110_000 + row, 1), new LineBody(COLS, false, cells)));
    }
    return new ScreenBaseline(
        "text-blink", "text-blink-instance", 1, 1, 1,
        ROWS, COLS, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(),
        screen.stream().map(ScreenLineContent::key).toList(),
        screen.stream().map(line -> new LineBodyRecord(line.key(), line.body())).toList(),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static ScreenBaseline baseline(int rows, int columns, TerminalCursor cursor) {
    List<ScreenLineContent> screen = new ArrayList<>();
    for (int row = 0; row < rows; row++) {
      screen.add(lineWithColumns(columns, 100_000 + row, 1, "row"));
    }
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1,
        rows, columns, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(),
        screen.stream().map(ScreenLineContent::key).toList(),
        screen.stream().map(line -> new LineBodyRecord(line.key(), line.body())).toList(),
        cursor, TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static ScreenLineContent line(long id, long version, long historySeq, String text) {
    return lineWithColumns(COLS, id, version, text);
  }

  private static ScreenLineContent lineWithColumns(
      int columns, long id, long version, String text) {
    CellValue[] cells = new CellValue[columns];
    cells[0] = new CellValue(text, (byte) 1, null, null);
    for (int column = 1; column < columns; column++) cells[column] = CellValue.EMPTY;
    return new ScreenLineContent(
        new LineKey(id, version),
        new LineBody(columns, false, cells));
  }

  private static ScreenBaseline mixedBaseline() {
    List<ScreenLineContent> screen = new ArrayList<>();
    for (int row = 0; row < MIXED_ROWS; row++) {
      screen.add(mixedLine(300_000 + row, row));
    }
    return new ScreenBaseline(
        "mixed", "mixed-instance", 1, 1, 1,
        MIXED_ROWS, MIXED_COLS, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(),
        screen.stream().map(ScreenLineContent::key).toList(),
        screen.stream().map(line -> new LineBodyRecord(line.key(), line.body())).toList(),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static ScreenLineContent mixedLine(long id, int row) {
    CellValue[] cells = new CellValue[MIXED_COLS];
    Arrays.fill(cells, CellValue.EMPTY);
    putWide(cells, 10, "界");
    putWide(cells, 20, "😀");
    cells[30] = new CellValue("┌", (byte) 1, null, null);
    cells[40] = new CellValue("█", (byte) 1, null, null);
    cells[50] = new CellValue("⣿", (byte) 1, null, null);
    cells[60] = new CellValue("\uE0B0", (byte) 1, null, null);
    cells[70] = new CellValue("A", (byte) 1, null, null);
    if ((row & 1) == 0) {
      cells[80] = new CellValue("e\u0301", (byte) 1, null, null);
    } else {
      cells[80] = new CellValue("हि", (byte) 1, null, null);
    }
    return new ScreenLineContent(
        new LineKey(id, 1), new LineBody(MIXED_COLS, false, cells));
  }

  private static void putWide(CellValue[] cells, int column, String text) {
    cells[column] = new CellValue(text, (byte) 2, null, null);
    cells[column + 1] = CellValue.SPACER;
  }

  private static void attachView(ActivityScenario<ClipboardTestActivity> scenario,
                                 AtomicReference<RemoteTerminalView> viewRef,
                                 int height) {
    scenario.onActivity(activity -> {
      RemoteTerminalView view = new RemoteTerminalView(activity);
      viewRef.set(view);
      activity.setContentView(view, new ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, height));
    });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  private static void waitForBlinkState(
      ActivityScenario<ClipboardTestActivity> scenario,
      AtomicReference<RemoteTerminalView> viewRef,
      boolean expected,
      long timeoutMillis) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    AtomicReference<Boolean> state = new AtomicReference<>(false);
    while (System.nanoTime() < deadline) {
      scenario.onActivity(activity -> state.set(viewRef.get().slowBlinkOnForTest()));
      if (state.get() == expected) return;
      Thread.sleep(50L);
    }
    assertTrue("text blink did not reach expected phase=" + expected, state.get() == expected);
  }

  private static int differentPixels(CapturedScreenshot first, CapturedScreenshot second) {
    if (first == null || second == null || first.width != second.width
        || first.height != second.height) return -1;
    int count = 0;
    for (int i = 0; i < first.argbPixels.length; i += 4) {
      if (first.argbPixels[i] != second.argbPixels[i]
          || first.argbPixels[i + 1] != second.argbPixels[i + 1]
          || first.argbPixels[i + 2] != second.argbPixels[i + 2]
          || first.argbPixels[i + 3] != second.argbPixels[i + 3]) {
        count++;
      }
    }
    return count;
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
