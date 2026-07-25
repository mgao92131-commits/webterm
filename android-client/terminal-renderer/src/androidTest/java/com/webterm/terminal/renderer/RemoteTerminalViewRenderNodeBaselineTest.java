package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenPatchV2;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.model.TerminalViewportState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** 模拟器/真机生产 View + RenderNode 缓存链路的无正文性能 smoke baseline。 */
@RunWith(AndroidJUnit4.class)
public final class RemoteTerminalViewRenderNodeBaselineTest {
  private static final int ROWS = 40;
  private static final int COLS = 80;

  @Test
  public void baselineAndSingleLinePatchUseHardwareRowCache() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    RenderUpdate baselineUpdate = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    TerminalRenderMetrics.Snapshot before = TerminalRenderMetrics.snapshot();
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
      scenario.onActivity(activity -> {
        RemoteTerminalView view = viewRef.get();
        assertTrue(view.getWidth() > 0);
        assertTrue(view.getHeight() > 0);
        assertTrue(view.isHardwareAccelerated());
        view.bindModel(model);
        view.applyRenderUpdate(baselineUpdate, viewport);
      });

      long[] scrolledLayout = new long[ROWS];
      for (int row = 0; row < ROWS - 1; row++) scrolledLayout[row] = 100_001 + row;
      scrolledLayout[ROWS - 1] = 200_000;
      model.applyScreenPatch(new ScreenPatchV2(
          "i1", 1, 1, 1, 2, scrolledLayout,
          Collections.singletonList(line(200_000, 1, 0, "new")),
          null, null, null, null, null, null));
      RenderUpdate patchUpdate = model.consumeRenderUpdate();
      assertEquals(1, patchUpdate.dirty.screenScrollRows);
      scenario.onActivity(activity -> {
        viewRef.get().applyRenderUpdate(patchUpdate, viewport);
      });
    } catch (Exception e) {
      throw new AssertionError(e);
    }

    TerminalRenderMetrics.Snapshot after = TerminalRenderMetrics.snapshot();
    long recorded = after.rowNodeRecordCount - before.rowNodeRecordCount;
    assertEquals(ROWS + 1L, recorded);
    assertEquals(recorded, bucketTotal(after.renderNodeRecordLatencyBuckets)
        - bucketTotal(before.renderNodeRecordLatencyBuckets));
    System.out.println("PERF_DEVICE_BASELINE hardware_render_node=true rows=" + ROWS
        + " baseline_records=" + ROWS + " patch_records=1");
  }

  private static ScreenBaseline baseline() {
    List<TerminalLine> screen = new ArrayList<>();
    for (int row = 0; row < ROWS; row++) {
      screen.add(line(100_000 + row, 1, 0, "row"));
    }
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1, ROWS, COLS, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(), screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
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
}
