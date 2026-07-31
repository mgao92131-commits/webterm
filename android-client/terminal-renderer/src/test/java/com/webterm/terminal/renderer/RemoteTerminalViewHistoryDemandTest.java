package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryPush;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenLineContent;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalStateUpdate;
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
public final class RemoteTerminalViewHistoryDemandTest {
  @Test
  public void fixedViewportDoesNotReportDemandForScreenOnlyUpdates() {
    RemoteTerminalModel model = model("instance-1");
    RemoteTerminalModel.RenderSnapshot snapshot = model.renderSnapshot();
    RenderUpdate baseline = model.consumeRenderUpdate();
    assertNotNull(baseline);
    RemoteTerminalView view = new RemoteTerminalView(RuntimeEnvironment.getApplication());
    CountingHost host = new CountingHost();
    view.setHost(host);
    view.layout(0, 0, 400, 100);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.anchorLine(TerminalBufferKind.MAIN, 100, 60);

    view.applyRenderUpdate(baseline, viewport);
    assertEquals(1, host.historyDemandCount);

    RenderDirtyState screenOnly = new RenderDirtyState();
    screenOnly.changedScreenRows.set(0);
    RenderUpdate update = new RenderUpdate(
        baseline.publicationVersion + 1, snapshot, screenOnly, new TerminalStateUpdate());
    for (int i = 0; i < 10_000; i++) {
      view.applyRenderUpdate(update, viewport);
    }

    assertEquals(1, host.historyDemandCount);
  }

  @Test
  public void projectionIdentityChangeReportsSameVisibleCoverageAgain() {
    RemoteTerminalModel first = model("instance-1");
    RemoteTerminalModel second = model("instance-2");
    RemoteTerminalView view = new RemoteTerminalView(RuntimeEnvironment.getApplication());
    CountingHost host = new CountingHost();
    view.setHost(host);
    view.layout(0, 0, 400, 100);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.anchorLine(TerminalBufferKind.MAIN, 100, 60);

    view.applyRenderUpdate(first.consumeRenderUpdate(), viewport);
    view.applyRenderUpdate(second.consumeRenderUpdate(), viewport);

    assertEquals(2, host.historyDemandCount);
  }

  @Test
  public void followTailDoesNotReportVisibleHistoryDemand() {
    RemoteTerminalModel model = model("instance-1");
    RemoteTerminalModel.RenderSnapshot snapshot = model.renderSnapshot();
    RenderUpdate baseline = model.consumeRenderUpdate();
    assertNotNull(baseline);
    RemoteTerminalView view = new RemoteTerminalView(RuntimeEnvironment.getApplication());
    CountingHost host = new CountingHost();
    view.setHost(host);
    view.layout(0, 0, 400, 100);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail(TerminalBufferKind.MAIN);

    view.applyRenderUpdate(baseline, viewport);

    RenderDirtyState historyDirty = new RenderDirtyState();
    historyDirty.historyChanged = true;
    RenderUpdate update = new RenderUpdate(
        baseline.publicationVersion + 1, snapshot, historyDirty, new TerminalStateUpdate());
    for (int i = 0; i < 100; i++) {
      view.applyRenderUpdate(update, viewport);
    }

    assertEquals(0, host.visibleDemandCount);
    assertEquals(0, host.historyDemandCount);
  }

  @Test
  public void returningToFollowTailClearsPreviousVisibleHistoryDemand() {
    RemoteTerminalModel model = model("instance-1");
    RemoteTerminalModel.RenderSnapshot snapshot = model.renderSnapshot();
    RenderUpdate baseline = model.consumeRenderUpdate();
    assertNotNull(baseline);
    RemoteTerminalView view = new RemoteTerminalView(RuntimeEnvironment.getApplication());
    CountingHost host = new CountingHost();
    view.setHost(host);
    view.layout(0, 0, 400, 100);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.anchorLine(TerminalBufferKind.MAIN, 100, 60);

    view.applyRenderUpdate(baseline, viewport);
    assertEquals(1, host.visibleDemandCount);
    assertEquals(0, host.clearDemandCount);

    viewport.followTail(TerminalBufferKind.MAIN);
    RenderDirtyState historyDirty = new RenderDirtyState();
    historyDirty.historyChanged = true;
    RenderUpdate update = new RenderUpdate(
        baseline.publicationVersion + 1, snapshot, historyDirty, new TerminalStateUpdate());
    view.applyRenderUpdate(update, viewport);

    assertEquals(1, host.visibleDemandCount);
    assertEquals(1, host.clearDemandCount);
    assertEquals(0L, host.lastFromSeq);
    assertEquals(0L, host.lastToSeq);
    assertEquals(0, host.lastDirection);
  }

  private static RemoteTerminalModel model(String instanceId) {
    List<HistoryPush> history = new ArrayList<>();
    for (long seq = 1; seq <= 20; seq++) {
      history.add(new HistoryPush(seq, new LineKey(seq, 1)));
    }
    List<ScreenLineContent> screen = new ArrayList<>();
    for (int row = 0; row < 4; row++) {
      screen.add(new ScreenLineContent(
          new LineKey(100 + row, 1), new LineBody(80, false, cells(80))));
    }
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "session", instanceId, 1, 1, 1, 1,
        4, 80, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 20), history, screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static CellValue[] cells(int columns) {
    CellValue[] cells = new CellValue[columns];
    java.util.Arrays.fill(cells, CellValue.EMPTY);
    return cells;
  }

  private static final class CountingHost implements RemoteTerminalView.Host {
    int historyDemandCount;
    int visibleDemandCount;
    int clearDemandCount;
    long lastFromSeq;
    long lastToSeq;
    int lastDirection;

    @Override public void onTextInput(String text) {}
    @Override public void onPasteInput(String text) {}
    @Override public void onKeyEvent(KeyEvent event) {}
    @Override public void onRequestResize(int cols, int rows) {}
    @Override public void onRequestShowKeyboard() {}
    @Override public void onScrollPixels(
        int deltaPixels, int maxScrollOffsetPixels, int liveScreenExitOffsetPixels) {}
    @Override public void onVisibleHistoryDemand(
        long fromSeq, long toSeq, long anchorSeq, int direction) {
      historyDemandCount++;
      lastFromSeq = fromSeq;
      lastToSeq = toSeq;
      lastDirection = direction;
      if (fromSeq == 0 && toSeq == 0 && direction == 0) {
        clearDemandCount++;
      } else {
        visibleDemandCount++;
      }
    }
    @Override public void onFocusChanged(boolean focused) {}
    @Override public void onMouse(
        int row, int col, String button, int wheelDelta,
        boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed) {}
    @Override public void onAlternateScreenScroll(int rowsDown) {}
  }
}
