package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;

import org.robolectric.RuntimeEnvironment;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.model.TerminalViewportState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class RemoteTerminalViewInvalidationTest {

  private static TerminalLine line(long id, int cols) {
    TerminalCell[] cells = new TerminalCell[cols];
    Arrays.fill(cells, TerminalCell.EMPTY);
    return new TerminalLine(id, false, cells);
  }

  private static TerminalLine historyLine(long id, long historySeq, int cols) {
    TerminalCell[] cells = new TerminalCell[cols];
    Arrays.fill(cells, TerminalCell.EMPTY);
    return new TerminalLine(id, 1, historySeq, false, cells);
  }

  private static RemoteTerminalModel modelWithScreen(int rows, int cols) {
    return modelWithScreenAndHistory(rows, cols, 0);
  }

  private static RemoteTerminalModel modelWithScreenAndHistory(int rows, int cols, int historyRows) {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalLine[] screen = new TerminalLine[rows];
    for (int i = 0; i < rows; i++) screen[i] = line(i + 1, cols);
    TerminalLine[] history = new TerminalLine[historyRows];
    for (int i = 0; i < historyRows; i++) {
      history[i] = historyLine(1000L + i, i + 1L, cols);
    }
    HistoryExtent extent = historyRows == 0
        ? HistoryExtent.INITIAL_EMPTY
        : new HistoryExtent(1, historyRows);
    ScreenBaseline baseline = new ScreenBaseline(
        "session-1", "term-1", 1L, 1L, 1L, rows, cols, TerminalBufferKind.MAIN,
        extent, Arrays.asList(history), Arrays.asList(screen),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        "", "");
    model.applyBaseline(baseline);
    model.consumeRenderUpdate();
    return model;
  }

  private static RemoteTerminalView view(int w, int h) {
    RemoteTerminalView view = new RemoteTerminalView(RuntimeEnvironment.getApplication());
    view.setTextSize(14);
    view.layout(0, 0, w, h);
    try {
      java.lang.reflect.Field rendererField = RemoteTerminalView.class.getDeclaredField("renderer");
      rendererField.setAccessible(true);
      ((RemoteTerminalRenderer) rendererField.get(view)).setFontMetrics(10f, 20f, 15f);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
    return view;
  }

  @Test
  public void historyOnlyFollowTailIsNone() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RemoteTerminalView view = view(100, 200);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail = true;

    // 模拟 history-only 更新：不修改 screen。
    RenderDirtyState dirty = new RenderDirtyState();
    dirty.historyChanged = true;

    InvalidationResult result = view.resolveInvalidation(dirty, model.renderSnapshot(), viewport, false);
    assertEquals(InvalidationResult.NONE, result);
  }

  @Test
  public void nonEmptyHistoryFollowTailHistoryOnlyIsNone() {
    RemoteTerminalModel model = modelWithScreenAndHistory(5, 10, 5);
    RemoteTerminalView view = view(100, 200);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail = true;

    RenderDirtyState dirty = new RenderDirtyState();
    dirty.historyChanged = true;

    InvalidationResult result = view.resolveInvalidation(dirty, model.renderSnapshot(), viewport, false);
    assertEquals(InvalidationResult.NONE, result);
  }

  @Test
  public void historyOnlyNotFollowTailIsFull() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RemoteTerminalView view = view(100, 200);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail = false;

    RenderDirtyState dirty = new RenderDirtyState();
    dirty.historyChanged = true;

    InvalidationResult result = view.resolveInvalidation(dirty, model.renderSnapshot(), viewport, false);
    assertEquals(InvalidationResult.FULL, result);
  }

  @Test
  public void visibleHistoryAndScreenChangeInSameFrameIsFull() {
    RemoteTerminalModel model = modelWithScreenAndHistory(5, 10, 5);
    RemoteTerminalView view = view(100, 200);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail = false;

    RenderDirtyState dirty = new RenderDirtyState();
    dirty.historyChanged = true;
    dirty.changedScreenRows.set(2);

    InvalidationResult result =
        view.resolveInvalidation(dirty, model.renderSnapshot(), viewport, false);
    assertEquals(InvalidationResult.FULL, result);
  }

  @Test
  public void singleRowChangeIsPartial() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RemoteTerminalView view = view(100, 200);
    TerminalViewportState viewport = new TerminalViewportState();

    RenderDirtyState dirty = new RenderDirtyState();
    dirty.changedScreenRows.set(2);

    InvalidationResult result = view.resolveInvalidation(dirty, model.renderSnapshot(), viewport, false);
    assertEquals(InvalidationResult.PARTIAL, result);
  }

  @Test
  public void screenScrollIsScreenRegion() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RemoteTerminalView view = view(100, 200);
    TerminalViewportState viewport = new TerminalViewportState();

    RenderDirtyState dirty = new RenderDirtyState();
    dirty.screenScrollRows = 1;
    dirty.exposedScreenRows.set(4);
    dirty.changedScreenRows.set(4);

    InvalidationResult result = view.resolveInvalidation(dirty, model.renderSnapshot(), viewport, false);
    assertEquals(InvalidationResult.SCREEN_REGION, result);
  }

  @Test
  public void geometryChangeIsFull() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RemoteTerminalView view = view(100, 200);
    TerminalViewportState viewport = new TerminalViewportState();

    RenderDirtyState dirty = new RenderDirtyState();
    dirty.changedScreenRows.set(2);

    InvalidationResult result = view.resolveInvalidation(dirty, model.renderSnapshot(), viewport, true);
    assertEquals(InvalidationResult.FULL, result);
  }

  @Test
  public void fullInvalidateIsFull() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RemoteTerminalView view = view(100, 200);
    TerminalViewportState viewport = new TerminalViewportState();

    RenderDirtyState dirty = new RenderDirtyState();
    dirty.fullInvalidate = true;

    InvalidationResult result = view.resolveInvalidation(dirty, model.renderSnapshot(), viewport, false);
    assertEquals(InvalidationResult.FULL, result);
  }

  @Test
  public void screenRegionReportsScrollRowsOnlyForRealScroll() throws Exception {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RemoteTerminalView view = view(100, 200);
    TerminalRenderMetrics.Snapshot base = TerminalRenderMetrics.snapshot();

    // 真实滚动：按 dirty.screenScrollRows 的绝对值累计滚动行数。
    RenderDirtyState scrollUp = new RenderDirtyState();
    scrollUp.screenScrollRows = 3;
    invokeInvalidateScreenRegion(view, scrollUp, model.renderSnapshot());

    // 非滚动回退（screenScrollRows == 0）不再把整屏行数计入滚动指标。
    RenderDirtyState fallback = new RenderDirtyState();
    invokeInvalidateScreenRegion(view, fallback, model.renderSnapshot());

    RenderDirtyState scrollDown = new RenderDirtyState();
    scrollDown.screenScrollRows = -2;
    invokeInvalidateScreenRegion(view, scrollDown, model.renderSnapshot());

    TerminalRenderMetrics.Snapshot after = TerminalRenderMetrics.snapshot();
    assertEquals(2, after.screenScrollEventCount - base.screenScrollEventCount);
    assertEquals(5, after.screenScrollRowTotal - base.screenScrollRowTotal);
    assertEquals(3, after.screenRegionInvalidateCount - base.screenRegionInvalidateCount);
  }

  private static void invokeInvalidateScreenRegion(RemoteTerminalView view,
                                                   RenderDirtyState dirty,
                                                   RemoteTerminalModel.RenderSnapshot snapshot)
      throws Exception {
    java.lang.reflect.Method method = RemoteTerminalView.class.getDeclaredMethod(
        "invalidateScreenRegion", RenderDirtyState.class,
        RemoteTerminalModel.RenderSnapshot.class);
    method.setAccessible(true);
    method.invoke(view, dirty, snapshot);
  }
}
