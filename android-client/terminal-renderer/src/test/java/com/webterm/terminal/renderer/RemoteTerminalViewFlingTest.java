package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;

import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class RemoteTerminalViewFlingTest {
  @Test
  public void activeFlingSchedulesNextFrameEvenWhenIntegerDeltaIsZero() throws Exception {
    Fixture fixture = fixture(TerminalBufferKind.MAIN, TerminalModes.defaults());

    continueFlingFrame(fixture.view, 1f);
    continueFlingFrame(fixture.view, 1f);

    assertEquals(1, fixture.host.mainScrollCalls);
    assertEquals(2, fixture.view.flingFramesScheduledForTest());
  }

  @Test
  public void alternateBufferFlingAlsoSchedulesNextFrame() throws Exception {
    Fixture fixture = fixture(TerminalBufferKind.ALTERNATE, TerminalModes.defaults());

    continueFlingFrame(fixture.view, 20f);

    assertEquals(1, fixture.host.alternateScrollCalls);
    assertEquals(1, fixture.view.flingFramesScheduledForTest());
  }

  @Test
  public void mouseTrackingFlingKeepsScrollerFrameSchedulingOwnedByView() throws Exception {
    TerminalModes modes = new TerminalModes(false, false, false,
        TerminalModes.MouseTracking.ANY_EVENT, TerminalModes.MouseEncoding.SGR, false);
    Fixture fixture = fixture(TerminalBufferKind.MAIN, modes);

    continueFlingFrame(fixture.view, 20f);

    assertEquals(1, fixture.host.mouseWheelCalls);
    assertEquals(0, fixture.host.mainScrollCalls);
    assertEquals(1, fixture.view.flingFramesScheduledForTest());
  }

  private static Fixture fixture(TerminalBufferKind buffer, TerminalModes modes) {
    RemoteTerminalView view = new RemoteTerminalView(RuntimeEnvironment.getApplication());
    view.layout(0, 0, 200, 200);
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalLine line = new TerminalLine(1, 1, 0, false,
        new TerminalCell[] {TerminalCell.EMPTY});
    model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, 1, buffer, HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(), Collections.singletonList(line), TerminalCursor.hidden(),
        modes, TerminalPalette.defaults()));
    view.setModel(model, new TerminalViewportState());
    RecordingHost host = new RecordingHost();
    view.setHost(host);
    return new Fixture(view, host);
  }

  private static void continueFlingFrame(RemoteTerminalView view, float currentY) throws Exception {
    Method method = RemoteTerminalView.class.getDeclaredMethod("continueFlingFrame", float.class);
    method.setAccessible(true);
    method.invoke(view, currentY);
  }

  private static final class Fixture {
    final RemoteTerminalView view;
    final RecordingHost host;

    Fixture(RemoteTerminalView view, RecordingHost host) {
      this.view = view;
      this.host = host;
    }
  }

  private static final class RecordingHost implements RemoteTerminalView.Host {
    int mainScrollCalls;
    int alternateScrollCalls;
    int mouseWheelCalls;

    @Override public void onTextInput(String text) {}
    @Override public void onPasteInput(String text) {}
    @Override public void onKeyEvent(KeyEvent event) {}
    @Override public void onRequestResize(int cols, int rows) {}
    @Override public void onRequestShowKeyboard() {}
    @Override public void onScrollPixels(
        int deltaPixels, int maxScrollOffsetPixels, int liveScreenExitOffsetPixels) {
      mainScrollCalls++;
    }
    @Override public void onFocusChanged(boolean focused) {}
    @Override public void onMouse(int row, int col, String button, int wheelDelta,
                                  boolean shift, boolean alt, boolean ctrl, boolean meta,
                                  boolean pressed) {
      mouseWheelCalls++;
    }
    @Override public void onAlternateScreenScroll(int rowsDown) {
      alternateScrollCalls++;
    }
  }
}
