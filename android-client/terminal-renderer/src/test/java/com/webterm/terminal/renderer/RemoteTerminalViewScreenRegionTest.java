package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryPush;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenLineContent;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class RemoteTerminalViewScreenRegionTest {
  @Test
  public void screenRegionDirtyReturnsScreenRegion() {
    Fixture fx = fixture();
    RenderDirtyState dirty = new RenderDirtyState();
    dirty.screenRegionInvalidate = true;

    assertEquals(
        InvalidationResult.SCREEN_REGION,
        fx.view.resolveInvalidation(dirty, fx.snapshot, fx.viewport, false));
  }

  @Test
  public void screenRegionDirtyDoesNotReturnFull() {
    Fixture fx = fixture();
    RenderDirtyState dirty = new RenderDirtyState();
    dirty.screenRegionInvalidate = true;

    assertNotEquals(
        InvalidationResult.FULL,
        fx.view.resolveInvalidation(dirty, fx.snapshot, fx.viewport, false));
  }

  @Test
  public void followTailScrollDoesNotInvalidateHistoryArea() {
    Fixture fx = fixture();
    fx.viewport.followTail(TerminalBufferKind.MAIN);
    RenderDirtyState dirty = new RenderDirtyState();
    dirty.screenScrollRows = 1;
    dirty.exposedScreenRows.set(3);
    dirty.changedScreenRows.set(3);

    assertEquals(
        InvalidationResult.SCREEN_REGION,
        fx.view.resolveInvalidation(dirty, fx.snapshot, fx.viewport, false));

    int[] rect = fx.view.invalidationRectForTest(dirty, fx.snapshot, fx.viewport);
    assertTrue(rect != null);
    // followTail 时 screenTop 贴近顶部 inset，Screen 区域刷新不应覆盖整 View。
    assertTrue(rect[3] - rect[1] < fx.view.getHeight());
  }

  private static Fixture fixture() {
    List<HistoryPush> history = new ArrayList<>();
    for (long seq = 1; seq <= 20; seq++) {
      history.add(new HistoryPush(seq, new LineKey(seq, 1)));
    }
    List<ScreenLineContent> screen = new ArrayList<>();
    for (int row = 0; row < 4; row++) {
      CellValue[] cells = new CellValue[80];
      java.util.Arrays.fill(cells, CellValue.EMPTY);
      screen.add(new ScreenLineContent(
          new LineKey(100 + row, 1), new LineBody(80, false, cells)));
    }
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "session", "instance-1", 1, 1, 1, 1,
        4, 80, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 20), history, screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    RemoteTerminalModel.RenderSnapshot snapshot = model.renderSnapshot();
    RemoteTerminalView view = new RemoteTerminalView(RuntimeEnvironment.getApplication());
    view.layout(0, 0, 400, 200);
    view.updateSize(400, 200);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail(TerminalBufferKind.MAIN);
    return new Fixture(view, snapshot, viewport);
  }

  private static final class Fixture {
    final RemoteTerminalView view;
    final RemoteTerminalModel.RenderSnapshot snapshot;
    final TerminalViewportState viewport;

    Fixture(RemoteTerminalView view,
            RemoteTerminalModel.RenderSnapshot snapshot,
            TerminalViewportState viewport) {
      this.view = view;
      this.snapshot = snapshot;
      this.viewport = viewport;
    }
  }
}
