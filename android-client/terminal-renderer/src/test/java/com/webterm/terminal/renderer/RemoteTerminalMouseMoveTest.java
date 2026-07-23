package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class RemoteTerminalMouseMoveTest {

  @Test
  public void moveBurstKeepsLatestCoordinateAndFlushesBeforeRelease() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    RemoteTerminalView view = new RemoteTerminalView(activity);
    RecordingHost host = new RecordingHost();
    view.setHost(host);
    view.setModel(mouseTrackingModel(), new TerminalViewportState());
    view.layout(0, 0, 800, 400);

    MotionEvent first = mouseEvent(MotionEvent.ACTION_MOVE, 20f, 40f,
        MotionEvent.BUTTON_PRIMARY);
    MotionEvent latest = mouseEvent(MotionEvent.ACTION_MOVE, 300f, 100f,
        MotionEvent.BUTTON_PRIMARY);
    MotionEvent release = mouseEvent(MotionEvent.ACTION_UP, 300f, 100f, 0);
    try {
      assertTrue(view.onGenericMotionEvent(first));
      assertTrue(view.onGenericMotionEvent(latest));
      assertTrue(view.onGenericMotionEvent(release));
    } finally {
      first.recycle();
      latest.recycle();
      release.recycle();
    }

    assertEquals("two MOVE events in one frame must collapse before release", 2,
        host.mouseEvents.size());
    MouseRecord move = host.mouseEvents.get(0);
    MouseRecord up = host.mouseEvents.get(1);
    assertTrue(move.pressed);
    assertTrue("the queued MOVE must use the latest x coordinate", move.col > 10);
    assertEquals(move.row, up.row);
    assertEquals(move.col, up.col);
    assertTrue(!up.pressed);
  }

  private static RemoteTerminalModel mouseTrackingModel() {
    int rows = 20;
    int cols = 80;
    List<TerminalLine> screen = new ArrayList<>(rows);
    for (int row = 0; row < rows; row++) screen.add(TerminalLine.empty(1000 + row, cols));
    TerminalModes modes = new TerminalModes(false, false, false,
        TerminalModes.MouseTracking.ANY_EVENT, TerminalModes.MouseEncoding.SGR, false);
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applyBaseline(new ScreenBaseline("s", "i", 1, 1, 1, rows, cols,
        TerminalBufferKind.MAIN, HistoryExtent.INITIAL_EMPTY, Collections.emptyList(), screen,
        TerminalCursor.hidden(), modes, TerminalPalette.defaults(), "", ""));
    return model;
  }

  private static MotionEvent mouseEvent(int action, float x, float y, int buttonState) {
    MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
    properties.id = 0;
    properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
    MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
    coords.x = x;
    coords.y = y;
    coords.pressure = 1f;
    coords.size = 1f;
    return MotionEvent.obtain(1, 1, action, 1,
        new MotionEvent.PointerProperties[] {properties},
        new MotionEvent.PointerCoords[] {coords}, 0, buttonState, 1f, 1f,
        0, 0, InputDevice.SOURCE_MOUSE, 0);
  }

  private static final class MouseRecord {
    final int row;
    final int col;
    final boolean pressed;

    MouseRecord(int row, int col, boolean pressed) {
      this.row = row;
      this.col = col;
      this.pressed = pressed;
    }
  }

  private static final class RecordingHost implements RemoteTerminalView.Host {
    final List<MouseRecord> mouseEvents = new ArrayList<>();

    @Override public void onTextInput(String text) {}
    @Override public void onPasteInput(String text) {}
    @Override public void onKeyEvent(KeyEvent event) {}
    @Override public void onRequestResize(int cols, int rows) {}
    @Override public void onRequestShowKeyboard() {}
    @Override public void onScrollPixels(
        int deltaPixels, int maxScrollOffsetPixels, int liveScreenExitOffsetPixels) {}
    @Override public void onFocusChanged(boolean focused) {}
    @Override public void onMouse(int row, int col, String button, int wheelDelta,
                                  boolean shift, boolean alt, boolean ctrl, boolean meta,
                                  boolean pressed) {
      mouseEvents.add(new MouseRecord(row, col, pressed));
    }
    @Override public void onAlternateScreenScroll(int rowsDown) {}
  }
}
