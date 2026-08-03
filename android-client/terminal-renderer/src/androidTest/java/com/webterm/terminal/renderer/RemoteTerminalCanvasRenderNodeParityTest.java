package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
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
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.model.capture.CapturedViewState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

/** 同一设备上的直接 Canvas 与生产 View/RenderNode 画面基线。 */
@RunWith(AndroidJUnit4.class)
public final class RemoteTerminalCanvasRenderNodeParityTest {
  private static final int COLUMNS = 16;
  private static final int VIEW_HEIGHT = 240;
  private static final int BACKGROUND = 0xFF000000;

  @Test
  public void directCanvasAndHardwareRenderNodeHaveComparableGeometry() throws Exception {
    RemoteTerminalModel model = model();
    RenderUpdate update = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    AtomicReference<RemoteTerminalView> viewRef = new AtomicReference<>();
    AtomicReference<CapturedViewState> stateRef = new AtomicReference<>();
    AtomicReference<Window> windowRef = new AtomicReference<>();
    AtomicReference<int[]> viewLocationRef = new AtomicReference<>();
    AtomicReference<int[]> windowSizeRef = new AtomicReference<>();
    Bitmap hardware = null;
    Bitmap direct = null;

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
        assertTrue(view.getWidth() > 0 && view.getHeight() > 0);
        draw.attach(view);
        view.bindModel(model);
        view.applyRenderUpdate(update, viewport);
        stateRef.set(view.captureDiagnostics());
        int[] location = new int[2];
        view.getLocationInWindow(location);
        viewLocationRef.set(location);
        windowSizeRef.set(new int[] {
            activity.getWindow().getDecorView().getWidth(),
            activity.getWindow().getDecorView().getHeight()
        });
      });
      assertTrue("RenderNode parity frame did not draw", draw.await());
      scenario.onActivity(activity -> draw.detach(viewRef.get()));

      CapturedViewState state = stateRef.get();
      int width = viewRef.get().getWidth();
      int height = viewRef.get().getHeight();
      direct = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      RemoteTerminalRenderer directRenderer = new RemoteTerminalRenderer();
      directRenderer.setFontMetrics(state.cellWidth, state.lineHeight, state.baseline);
      directRenderer.render(new Canvas(direct), model.renderSnapshot(), viewport, true);

      int[] windowSize = windowSizeRef.get();
      Bitmap windowPixels = Bitmap.createBitmap(
          windowSize[0], windowSize[1], Bitmap.Config.ARGB_8888);
      copyWindowPixels(windowRef.get(), windowPixels);
      int[] location = viewLocationRef.get();
      hardware = Bitmap.createBitmap(windowPixels, location[0], location[1], width, height);
      windowPixels.recycle();
    }

    CapturedViewState state = stateRef.get();
    int topInset = Math.round(Math.max(0f, state.lineHeight - state.baseline));
    int terminalRight = Math.min(direct.getWidth(),
        (int) Math.ceil(COLUMNS * state.cellWidth));
    int terminalBottom = Math.min(direct.getHeight(),
        topInset + (int) Math.ceil(state.lineHeight) + 2);
    Rect terminalBounds = new Rect(0, 0, terminalRight, terminalBottom);
    Diff diff = diff(direct, hardware, terminalBounds, 48);
    File artifactDir = saveArtifacts(direct, hardware, terminalBounds, 48);
    assertBackgroundOutsideTerminal(direct, hardware, terminalBounds);
    assertTrue("Canvas/RenderNode difference is too large: " + diff,
        diff.differentPixels <= diff.totalPixels * 0.35f);
    assertTrue("Canvas/RenderNode difference must remain in the terminal row: " + diff,
        diff.bounds == null || diff.bounds.bottom <= terminalBottom + 1);

    CellValue[] cells = cells();
    for (int column = 0; column < COLUMNS; column++) {
      CellValue cell = cells[column];
      if (cell.isSpacer() || cell.text().equals(" ")) continue;
      int widthColumns = cell.isWideStart() ? 2 : 1;
      Rect cellBounds = new Rect(
          Math.round(column * state.cellWidth), topInset,
          Math.min(terminalRight, Math.round((column + widthColumns) * state.cellWidth)),
          terminalBottom);
      assertEquals("glyph presence mismatch at cell " + column,
          hasInk(direct, cellBounds), hasInk(hardware, cellBounds));
      if (cell.isWideStart()) column++;
    }

    System.out.println("PARITY_DEVICE canvas_rendernode=true width=" + direct.getWidth()
        + " height=" + direct.getHeight() + " cell_width=" + state.cellWidth
        + " line_height=" + state.lineHeight + " baseline=" + state.baseline
        + " font_size_sp=" + state.fontSizeSp
        + " typeface=" + state.typefaceDescription
        + " different_pixels=" + diff.differentPixels + "/" + diff.totalPixels
        + " max_channel_diff=" + diff.maxChannelDiff + " diff_bounds=" + diff.bounds);
    System.out.println("PARITY_DEVICE_ARTIFACTS dir=" + artifactDir.getAbsolutePath()
        + " files=direct-canvas.png,hardware-rendernode.png,diff.png");
    direct.recycle();
    hardware.recycle();
  }

  private static RemoteTerminalModel model() {
    CellValue[] cells = cells();
    LineKey key = new LineKey(1, 1);
    LineBody body = new LineBody(COLUMNS, false, cells);
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "parity", "parity-instance", 1, 1, 1,
        1, COLUMNS, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(),
        List.of(key), List.of(new LineBodyRecord(key, body)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static CellValue[] cells() {
    CellValue[] cells = new CellValue[COLUMNS];
    Arrays.fill(cells, CellValue.EMPTY);
    cells[0] = new CellValue("A", (byte) 1, null, null);
    cells[1] = new CellValue("B", (byte) 1, null, null);
    cells[2] = new CellValue("C", (byte) 1, null, null);
    cells[4] = new CellValue("界", (byte) 2, null, null);
    cells[5] = CellValue.SPACER;
    cells[7] = new CellValue("😀", (byte) 2, null, null);
    cells[8] = CellValue.SPACER;
    cells[10] = new CellValue("┌", (byte) 1, null, null);
    cells[11] = new CellValue("█", (byte) 1, null, null);
    cells[12] = new CellValue("⣿", (byte) 1, null, null);
    cells[13] = new CellValue("\uE0B0", (byte) 1, null, null);
    cells[14] = new CellValue("e\u0301", (byte) 1, null, null);
    cells[15] = new CellValue("A", (byte) 1, null, null);
    return cells;
  }

  private static void copyWindowPixels(Window window, Bitmap target)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger result = new AtomicInteger(-1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    PixelCopy.request(window, target, status -> {
      result.set(status);
      latch.countDown();
    }, new Handler(Looper.getMainLooper()));
    assertTrue("PixelCopy did not complete", latch.await(5, TimeUnit.SECONDS));
    if (result.get() != PixelCopy.SUCCESS) {
      failure.set(new AssertionError("PixelCopy status=" + result.get()));
    }
    if (failure.get() != null) throw new AssertionError(failure.get());
  }

  private static void assertBackgroundOutsideTerminal(Bitmap direct, Bitmap hardware,
                                                       Rect terminal) {
    int different = 0;
    for (int y = 0; y < direct.getHeight(); y++) {
      for (int x = 0; x < direct.getWidth(); x++) {
        if (terminal.contains(x, y)) continue;
        int expected = direct.getPixel(x, y);
        int actual = hardware.getPixel(x, y);
        if (expected != actual) different++;
        assertEquals("background/geometry mismatch outside terminal area at " + x + "," + y,
            expected, actual);
      }
    }
    assertEquals(0, different);
  }

  private static boolean hasInk(Bitmap bitmap, Rect bounds) {
    Rect clipped = new Rect(bounds);
    clipped.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight());
    for (int y = clipped.top; y < clipped.bottom; y++) {
      for (int x = clipped.left; x < clipped.right; x++) {
        if (bitmap.getPixel(x, y) != BACKGROUND) return true;
      }
    }
    return false;
  }

  private static Diff diff(Bitmap first, Bitmap second, Rect bounds, int tolerance) {
    int different = 0;
    int total = bounds.width() * bounds.height();
    int maxChannelDiff = 0;
    Rect diffBounds = null;
    for (int y = bounds.top; y < bounds.bottom; y++) {
      for (int x = bounds.left; x < bounds.right; x++) {
        int a = first.getPixel(x, y);
        int b = second.getPixel(x, y);
        int channelDiff = maxChannelDiff(a, b);
        maxChannelDiff = Math.max(maxChannelDiff, channelDiff);
        if (channelDiff <= tolerance) continue;
        different++;
        if (diffBounds == null) diffBounds = new Rect(x, y, x + 1, y + 1);
        else diffBounds.union(x, y, x + 1, y + 1);
      }
    }
    return new Diff(different, total, maxChannelDiff, diffBounds);
  }

  private static File saveArtifacts(Bitmap direct, Bitmap hardware, Rect bounds, int tolerance)
      throws IOException {
    File external = InstrumentationRegistry.getInstrumentation().getTargetContext()
        .getExternalFilesDir("render-baseline");
    if (external == null) throw new IOException("external files directory is unavailable");
    File directory = new File(external, "canvas-rendernode-parity");
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new IOException("cannot create artifact directory: " + directory);
    }
    writePng(new File(directory, "direct-canvas.png"), direct);
    writePng(new File(directory, "hardware-rendernode.png"), hardware);

    Bitmap difference = Bitmap.createBitmap(
        direct.getWidth(), direct.getHeight(), Bitmap.Config.ARGB_8888);
    try {
      for (int y = 0; y < direct.getHeight(); y++) {
        for (int x = 0; x < direct.getWidth(); x++) {
          int first = direct.getPixel(x, y);
          int second = hardware.getPixel(x, y);
          difference.setPixel(x, y,
              bounds.contains(x, y) && maxChannelDiff(first, second) > tolerance
                  ? 0xFFFF1744 : first);
        }
      }
      writePng(new File(directory, "diff.png"), difference);
    } finally {
      difference.recycle();
    }
    return directory;
  }

  private static void writePng(File file, Bitmap bitmap) throws IOException {
    try (FileOutputStream output = new FileOutputStream(file)) {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
        throw new IOException("cannot write PNG: " + file);
      }
    }
  }

  private static int maxChannelDiff(int first, int second) {
    int alpha = Math.abs(((first >>> 24) & 0xFF) - ((second >>> 24) & 0xFF));
    int red = Math.abs(((first >>> 16) & 0xFF) - ((second >>> 16) & 0xFF));
    int green = Math.abs(((first >>> 8) & 0xFF) - ((second >>> 8) & 0xFF));
    int blue = Math.abs((first & 0xFF) - (second & 0xFF));
    return Math.max(Math.max(alpha, red), Math.max(green, blue));
  }

  private static final class Diff {
    final int differentPixels;
    final int totalPixels;
    final int maxChannelDiff;
    final Rect bounds;

    Diff(int differentPixels, int totalPixels, int maxChannelDiff, Rect bounds) {
      this.differentPixels = differentPixels;
      this.totalPixels = totalPixels;
      this.maxChannelDiff = maxChannelDiff;
      this.bounds = bounds;
    }

    @Override
    public String toString() {
      return differentPixels + "/" + totalPixels + " max=" + maxChannelDiff
          + " bounds=" + bounds;
    }
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
