package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.TerminalViewportState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class RemoteTerminalSelectionInteractionTest {

  @Test
  public void sequentialStartEndStartDragsKeepBothHandlesEffective() throws Exception {
    Fixture fixture = fixture(TerminalBufferKind.MAIN);
    invoke(fixture.view, "startSelectionAt", new Class<?>[] {float.class, float.class},
        20f, 100f);

    TerminalSelection initial = fixture.viewport.selection;
    assertNotNull(initial);
    dragHandle(fixture.view, true, -10f);
    TerminalSelection afterStart = fixture.viewport.selection;
    assertTrue(afterStart.start.compareTo(initial.start) < 0);
    assertEquals(initial.end, afterStart.end);

    dragHandle(fixture.view, false, 10f);
    TerminalSelection afterEnd = fixture.viewport.selection;
    assertEquals(afterStart.start, afterEnd.start);
    assertTrue("end col did not advance: before=" + afterStart.end.col
        + " after=" + afterEnd.end.col + " start=" + afterEnd.start.col,
        afterEnd.end.compareTo(afterStart.end) > 0);

    dragHandle(fixture.view, true, -5f);
    TerminalSelection afterSecondStart = fixture.viewport.selection;
    assertTrue(afterSecondStart.start.compareTo(afterEnd.start) < 0);
    assertEquals(afterEnd.end, afterSecondStart.end);
  }

  @Test
  public void endpointConstraintPreventsHandleIdentityFromCrossing() {
    TerminalSelection.Anchor start = screen(2, 3);
    TerminalSelection.Anchor end = screen(4, 6);

    assertEquals(end, RemoteTerminalView.constrainSelectionEndpoint(
        true, screen(5, 0), start, end));
    assertEquals(start, RemoteTerminalView.constrainSelectionEndpoint(
        false, screen(1, 0), start, end));
    assertEquals(screen(3, 2), RemoteTerminalView.constrainSelectionEndpoint(
        true, screen(3, 2), start, end));
  }

  @Test
  public void topEdgeWithoutHistoryMapsToFirstScreenRowInsteadOfCrashing() throws Exception {
    Fixture fixture = fixture(TerminalBufferKind.MAIN);
    TerminalSelection.Anchor anchor = (TerminalSelection.Anchor) invoke(fixture.view,
        "pointToAnchor", new Class<?>[] {float.class, float.class}, 20f, -20f);
    assertNotNull(anchor);
    assertEquals(0, anchor.screenRow);
  }

  @Test
  public void alternateBufferSelectionNeverTurnsEdgeDragIntoArrowKeys() throws Exception {
    Fixture fixture = fixture(TerminalBufferKind.ALTERNATE);
    invoke(fixture.view, "startSelectionAt", new Class<?>[] {float.class, float.class},
        20f, 100f);
    float[] center = handleCenter(fixture.view, true);
    send(fixture.view, MotionEvent.ACTION_DOWN, center[0], center[1]);
    send(fixture.view, MotionEvent.ACTION_MOVE, center[0], 1f);
    Robolectric.flushForegroundThreadScheduler();
    send(fixture.view, MotionEvent.ACTION_UP, center[0], 1f);

    assertEquals(0, fixture.host.alternateScrollCalls);
    assertEquals(0, fixture.host.viewportScrollCalls);
  }

  @Test
  public void mainBufferEdgeDragScrollsViewportAndStopsOnRelease() throws Exception {
    Fixture fixture = fixture(TerminalBufferKind.MAIN, 50);
    invoke(fixture.view, "startSelectionAt", new Class<?>[] {float.class, float.class},
        20f, 100f);
    float[] center = handleCenter(fixture.view, true);
    send(fixture.view, MotionEvent.ACTION_DOWN, center[0], center[1]);
    send(fixture.view, MotionEvent.ACTION_MOVE, center[0], 1f);
    java.lang.reflect.Field scheduled = RemoteTerminalView.class
        .getDeclaredField("autoScrollScheduled");
    scheduled.setAccessible(true);
    assertTrue("edge move did not schedule auto-scroll", scheduled.getBoolean(fixture.view));
    invoke(fixture.view, "runSelectionAutoScrollFrame", new Class<?>[0]);
    send(fixture.view, MotionEvent.ACTION_UP, center[0], 1f);

    assertTrue(fixture.host.viewportScrollCalls > 0);
    assertTrue(fixture.viewport.scrollOffsetPixels > 0);
    assertEquals(false, scheduled.getBoolean(fixture.view));
  }

  private static Fixture fixture(TerminalBufferKind bufferKind) {
    return fixture(bufferKind, 0);
  }

  private static Fixture fixture(TerminalBufferKind bufferKind, int historyLines) {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    RemoteTerminalView view = new RemoteTerminalView(activity);
    TerminalViewportState viewport = new TerminalViewportState();
    RecordingHost host = new RecordingHost(viewport);
    view.setHost(host);
    view.setModel(model(bufferKind, historyLines), viewport);
    view.layout(0, 0, 800, 400);
    try {
      java.lang.reflect.Field rendererField = RemoteTerminalView.class.getDeclaredField("renderer");
      rendererField.setAccessible(true);
      ((RemoteTerminalRenderer) rendererField.get(view)).setFontMetrics(10f, 20f, 15f);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
    return new Fixture(view, viewport, host);
  }

  private static RemoteTerminalModel model(TerminalBufferKind bufferKind, int historyLines) {
    int rows = 20;
    int cols = 80;
    List<TerminalLine> screen = new ArrayList<>(rows);
    for (int row = 0; row < rows; row++) {
      TerminalCell[] cells = new TerminalCell[cols];
      for (int col = 0; col < cols; col++) {
        cells[col] = new TerminalCell(col % 2 == 0 ? "x" : " ", (byte) 1, null, null);
      }
      screen.add(new TerminalLine(1000 + row, false, cells));
    }
    List<TerminalLine> history = new ArrayList<>(historyLines);
    for (int line = 1; line <= historyLines; line++) {
      history.add(new TerminalLine(line, 1, line, false, screen.get(0).cells));
    }
    RemoteTerminalModel model = new RemoteTerminalModel();
    HistoryExtent extent = historyLines == 0
        ? HistoryExtent.INITIAL_EMPTY : new HistoryExtent(1, historyLines);
    model.applyBaseline(new ScreenBaseline("s", "i", 1, 1, 1, rows, cols, bufferKind,
        extent, history, screen, TerminalCursor.hidden(), TerminalModes.defaults(),
        TerminalPalette.defaults(), "", ""));
    return model;
  }

  private static void dragHandle(RemoteTerminalView view, boolean start, float deltaX)
      throws Exception {
    float[] center = handleCenter(view, start);
    send(view, MotionEvent.ACTION_DOWN, center[0], center[1]);
    send(view, MotionEvent.ACTION_MOVE, center[0] + deltaX, center[1]);
    send(view, MotionEvent.ACTION_UP, center[0] + deltaX, center[1]);
  }

  private static float[] handleCenter(RemoteTerminalView view, boolean start) throws Exception {
    TerminalSelection selection = viewportSelection(view);
    return (float[]) invoke(view, "anchorToHandleCenter",
        new Class<?>[] {TerminalSelection.Anchor.class},
        start ? selection.start : selection.end);
  }

  private static TerminalSelection viewportSelection(RemoteTerminalView view) throws Exception {
    java.lang.reflect.Field field = RemoteTerminalView.class.getDeclaredField("viewport");
    field.setAccessible(true);
    return ((TerminalViewportState) field.get(view)).selection;
  }

  private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                               Object... args) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  private static void send(RemoteTerminalView view, int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(1, 1, action, x, y, 0);
    try {
      assertTrue(view.onTouchEvent(event));
    } finally {
      event.recycle();
    }
  }

  private static TerminalSelection.Anchor screen(int row, int col) {
    return new TerminalSelection.Anchor(0, row, col);
  }

  private static final class Fixture {
    final RemoteTerminalView view;
    final TerminalViewportState viewport;
    final RecordingHost host;

    Fixture(RemoteTerminalView view, TerminalViewportState viewport, RecordingHost host) {
      this.view = view;
      this.viewport = viewport;
      this.host = host;
    }
  }

  private static final class RecordingHost implements RemoteTerminalView.Host {
    final TerminalViewportState viewport;
    int viewportScrollCalls;
    int alternateScrollCalls;

    RecordingHost(TerminalViewportState viewport) {
      this.viewport = viewport;
    }

    @Override public void onTextInput(String text) {}
    @Override public void onPasteInput(String text) {}
    @Override public void onKeyEvent(KeyEvent event) {}
    @Override public void onRequestResize(int cols, int rows) {}
    @Override public void onRequestShowKeyboard() {}
    @Override public void onScrollPixels(
        int deltaPixels, int maxScrollOffsetPixels, int liveScreenExitOffsetPixels) {
      viewportScrollCalls++;
      viewport.scrollBy(deltaPixels, maxScrollOffsetPixels);
    }
    @Override public void onFocusChanged(boolean focused) {}
    @Override public void onMouse(int row, int col, String button, int wheelDelta,
                                  boolean shift, boolean alt, boolean ctrl, boolean meta,
                                  boolean pressed) {}
    @Override public void onAlternateScreenScroll(int rowsDown) { alternateScrollCalls++; }
  }
}
