package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.PixelCopy;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineBodyRecord;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class RemoteTerminalDecorationParityTest {
  private static final int COLUMNS = 12;
  private static final int VIEW_HEIGHT = 120;
  private static final int BLACK = 0xFF000000;
  private static final int RED = 0xFFFF0000;
  private static final int GREEN = 0xFF00FF00;
  private static final int BLUE = 0xFF000080;

  @Test
  public void directCanvasAndRenderNodeKeepDecorationSemantics() throws Exception {
    RemoteTerminalModel model = model();
    RenderUpdate update = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    AtomicReference<RemoteTerminalView> viewRef = new AtomicReference<>();
    AtomicReference<RenderDiagnostics> stateRef = new AtomicReference<>();
    AtomicReference<Window> windowRef = new AtomicReference<>();
    AtomicReference<int[]> locationRef = new AtomicReference<>();
    AtomicReference<int[]> windowSizeRef = new AtomicReference<>();
    Bitmap direct;
    Bitmap hardware;

    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      scenario.onActivity(activity -> {
        RemoteTerminalView view = new RemoteTerminalView(activity);
        viewRef.set(view);
        windowRef.set(activity.getWindow());
        activity.setContentView(view, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, VIEW_HEIGHT));
      });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();

      DrawWaiter draw = new DrawWaiter();
      scenario.onActivity(activity -> {
        RemoteTerminalView view = viewRef.get();
        assertTrue(view.isHardwareAccelerated());
        draw.attach(view);
        view.bindModel(model);
        view.applyRenderUpdate(update, viewport);
        stateRef.set(view.renderDiagnostics());
        int[] location = new int[2];
        view.getLocationInWindow(location);
        locationRef.set(location);
        windowSizeRef.set(new int[] {
            activity.getWindow().getDecorView().getWidth(),
            activity.getWindow().getDecorView().getHeight()
        });
      });
      assertTrue("decoration parity frame did not draw", draw.await());
      scenario.onActivity(activity -> draw.detach(viewRef.get()));

      RenderDiagnostics state = stateRef.get();
      int width = viewRef.get().getWidth();
      int height = viewRef.get().getHeight();
      direct = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      RemoteTerminalRenderer directRenderer = new RemoteTerminalRenderer();
      float textSizePx = TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_SP,
          state.fontSizeSp,
          InstrumentationRegistry.getInstrumentation().getTargetContext()
              .getResources().getDisplayMetrics());
      directRenderer.updateFont(textSizePx, android.graphics.Typeface.MONOSPACE);
      directRenderer.setFontMetrics(state.cellWidth, state.lineHeight, state.baseline);
      directRenderer.render(new Canvas(direct), model.renderSnapshot(), viewport, true);

      int[] windowSize = windowSizeRef.get();
      Bitmap windowPixels = Bitmap.createBitmap(
          windowSize[0], windowSize[1], Bitmap.Config.ARGB_8888);
      copyWindowPixels(windowRef.get(), windowPixels);
      int[] location = locationRef.get();
      hardware = Bitmap.createBitmap(windowPixels, location[0], location[1], width, height);
      windowPixels.recycle();
    }

    RenderDiagnostics state = stateRef.get();
    int top = Math.round(Math.max(0f, state.lineHeight - state.baseline));
    int bottom = top + Math.round(state.lineHeight);
    int strikeTop = top + Math.round(state.lineHeight * 0.45f);
    int strikeBottom = top + Math.round(state.lineHeight * 0.60f) + 1;
    int terminalRight = edgePx(state.cellWidth, COLUMNS);
    assertOutsideTerminalMatches(direct, hardware, terminalRight, bottom + 2);

    for (int column = 0; column < 7; column++) {
      int left = edgePx(state.cellWidth, column);
      int right = edgePx(state.cellWidth, column + 1);
      assertTrue("direct underline missing at column " + column,
          hasColorFamily(direct, GREEN, left, right, bottom - 6, bottom));
      assertTrue("hardware underline missing at column " + column,
          hasColorFamily(hardware, GREEN, left, right, bottom - 6, bottom));
      assertTrue("direct strike missing at column " + column,
          hasColorFamily(direct, RED, left, right, strikeTop, strikeBottom));
      assertTrue("hardware strike missing at column " + column,
          hasColorFamily(hardware, RED, left, right, strikeTop, strikeBottom));
    }

    int hiddenLeft = edgePx(state.cellWidth, 7);
    int hiddenRight = edgePx(state.cellWidth, 8);
    int hiddenCenter = (hiddenLeft + hiddenRight) / 2;
    assertEquals(BLUE, direct.getPixel(hiddenCenter, top + 5));
    assertEquals(BLUE, hardware.getPixel(hiddenCenter, top + 5));
    assertTrue("hidden direct cell must not draw decoration",
        !hasColorFamily(direct, GREEN, hiddenLeft, hiddenRight, top, bottom)
            && !hasColorFamily(direct, RED, hiddenLeft, hiddenRight, top, bottom));
    assertTrue("hidden hardware cell must not draw decoration",
        !hasColorFamily(hardware, GREEN, hiddenLeft, hiddenRight, top, bottom)
            && !hasColorFamily(hardware, RED, hiddenLeft, hiddenRight, top, bottom));
    System.out.println("DECORATION_PARITY_DEVICE direct_and_rendernode=true top=" + top
        + " bottom=" + bottom + " cell_width=" + state.cellWidth);
    direct.recycle();
    hardware.recycle();
  }

  private static RemoteTerminalModel model() {
    CellValue[] cells = new CellValue[COLUMNS];
    Arrays.fill(cells, CellValue.EMPTY);
    StyleValue single = style((1 << 3) | (1 << 12));
    cells[0] = new CellValue("a", (byte) 1, single, null);
    cells[1] = new CellValue("b", (byte) 1, single, null);
    cells[2] = new CellValue("c", (byte) 1, single, null);
    // 空格 + strike 让下面的红色断言只能由 decoration 产生，避免被 glyph 误满足。
    cells[3] = new CellValue(" ", (byte) 1, style((1 << 4) | (1 << 12)), null);
    cells[4] = new CellValue(" ", (byte) 1, style((1 << 5) | (1 << 12)), null);
    cells[5] = new CellValue(" ", (byte) 1, style((1 << 6) | (1 << 12)), null);
    cells[6] = new CellValue(" ", (byte) 1, style((1 << 7) | (1 << 12)), null);
    cells[7] = new CellValue("X", (byte) 1,
        new StyleValue(TerminalColor.rgb(0xFF0000), TerminalColor.rgb(0x000080),
            TerminalColor.rgb(0x00FF00), (1 << 3) | (1 << 11) | (1 << 12)), null);
    LineKey key = new LineKey(1, 1);
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "decoration-parity", "instance", 1, 1, 1,
        1, COLUMNS, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(),
        List.of(key), List.of(new LineBodyRecord(key, new LineBody(COLUMNS, false, cells))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static StyleValue style(int attrs) {
    return new StyleValue(TerminalColor.rgb(0xFF0000), TerminalColor.DEFAULT_BG,
        TerminalColor.rgb(0x00FF00), attrs);
  }

  private static void assertOutsideTerminalMatches(Bitmap direct, Bitmap hardware,
                                                    int terminalRight, int terminalBottom) {
    for (int y = 0; y < direct.getHeight(); y++) {
      for (int x = 0; x < direct.getWidth(); x++) {
        if (x < terminalRight && y < terminalBottom) continue;
        assertEquals("outside decoration parity area differs at " + x + "," + y,
            direct.getPixel(x, y), hardware.getPixel(x, y));
      }
    }
  }

  private static boolean hasColorFamily(Bitmap bitmap, int color,
                                        int left, int right, int top, int bottom) {
    int rgb = color & 0x00FFFFFF;
    left = Math.max(0, left);
    right = Math.min(bitmap.getWidth(), right);
    top = Math.max(0, top);
    bottom = Math.min(bitmap.getHeight(), bottom);
    for (int y = top; y < bottom; y++) {
      for (int x = left; x < right; x++) {
        int pixel = bitmap.getPixel(x, y) & 0x00FFFFFF;
        if (pixel == rgb) return true;
        if (rgb == 0x00FF00 && (pixel & 0x00FF00) != 0 && (pixel & 0xFF00FF) == 0) {
          return true;
        }
        if (rgb == 0xFF0000 && (pixel & 0xFF0000) != 0 && (pixel & 0x00FFFF) == 0) {
          return true;
        }
      }
    }
    return false;
  }

  private static int edgePx(float cellWidth, int column) {
    return Math.round(column * cellWidth);
  }

  private static void copyWindowPixels(Window window, Bitmap target) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger result = new AtomicInteger(-1);
    PixelCopy.request(window, target, status -> {
      result.set(status);
      latch.countDown();
    }, new Handler(Looper.getMainLooper()));
    assertTrue("PixelCopy did not complete", latch.await(5, TimeUnit.SECONDS));
    assertEquals("PixelCopy failed", PixelCopy.SUCCESS, result.get());
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

    @Override public void onDraw() {
      latch.countDown();
    }
  }
}
