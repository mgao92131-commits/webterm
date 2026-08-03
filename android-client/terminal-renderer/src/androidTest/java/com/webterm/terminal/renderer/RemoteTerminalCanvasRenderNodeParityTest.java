package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Typeface;
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
import com.webterm.terminal.model.capture.CapturedViewState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
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
  private static final int CHANNEL_TOLERANCE = 48;
  private static final float MAX_DIFF_RATIO = 0.15f;
  private static final int INK_EDGE_TOLERANCE = 3;

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
      float textSizePx = TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_SP,
          state.fontSizeSp,
          InstrumentationRegistry.getInstrumentation().getTargetContext()
              .getResources().getDisplayMetrics());
      directRenderer.updateFont(textSizePx, Typeface.MONOSPACE);
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
        edgePx(state.cellWidth, COLUMNS));
    int terminalBottom = Math.min(direct.getHeight(),
        topInset + Math.round(state.lineHeight) + 2);
    Rect terminalBounds = new Rect(0, 0, terminalRight, terminalBottom);
    Diff diff = diff(direct, hardware, terminalBounds, CHANNEL_TOLERANCE);
    File artifactDir = saveArtifacts(direct, hardware, terminalBounds, CHANNEL_TOLERANCE);
    assertBackgroundOutsideTerminal(direct, hardware, terminalBounds);
    assertTrue("Canvas/RenderNode difference is too large: " + diff,
        diff.differentPixels <= diff.totalPixels * MAX_DIFF_RATIO);
    assertTrue("Canvas/RenderNode difference must remain in the terminal row: " + diff,
        diff.bounds == null || diff.bounds.bottom <= terminalBottom + 1);

    CellValue[] cells = cells();
    for (int column = 0; column < COLUMNS; column++) {
      CellValue cell = cells[column];
      if (cell.isSpacer() || cell.text().equals(" ")) continue;
      int widthColumns = cell.isWideStart() ? 2 : 1;
      Rect cellBounds = new Rect(
          edgePx(state.cellWidth, column), topInset,
          Math.min(terminalRight, edgePx(state.cellWidth, column + widthColumns)),
          terminalBottom);
      Rect directInk = findInkBounds(direct, cellBounds);
      Rect hardwareInk = findInkBounds(hardware, cellBounds);
      assertEquals("glyph presence mismatch at cell " + column,
          directInk != null, hardwareInk != null);
      if (directInk != null && hardwareInk != null) {
        assertInkBoundsClose("cell " + column, directInk, hardwareInk);
      }
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

  @Test
  public void edgeCellsHaveComparableInkBounds() throws Exception {
    List<String> knownClippingCases = new ArrayList<>();
    for (EdgeCase edge : edgeCases()) {
      ParityCapture capture = captureParity(
          new CellValue[][] {edge.cells}, COLUMNS, VIEW_HEIGHT);
      try {
        CapturedViewState state = capture.state;
        int topInset = Math.round(Math.max(0f, state.lineHeight - state.baseline));
        int scanLeft = edgePx(state.cellWidth, edge.startColumn);
        Rect scan = new Rect(scanLeft, 0, capture.direct.getWidth(), capture.direct.getHeight());
        Rect directInk = findInkBounds(capture.direct, scan);
        Rect hardwareInk = findInkBounds(capture.hardware, scan);
        assertNotNull(edge.name + " direct Canvas ink is missing", directInk);
        assertNotNull(edge.name + " RenderNode ink is missing", hardwareInk);
        int directInkPixels = countInk(capture.direct, scan);
        int hardwareInkPixels = countInk(capture.hardware, scan);
        double inkCountDeltaRatio = directInkPixels == 0 ? 0.0
            : Math.abs(directInkPixels - hardwareInkPixels) / (double) directInkPixels;
        if (inkBoundsClose(directInk, hardwareInk)) {
          System.out.println("PARITY_EDGE name=" + edge.name
              + " known=none direct_ink_bounds=" + directInk
              + " hardware_ink_bounds=" + hardwareInk
              + " direct_ink_pixels=" + directInkPixels
              + " hardware_ink_pixels=" + hardwareInkPixels
              + " ink_count_delta_ratio=" + inkCountDeltaRatio);
        } else if (isExpectedRenderNodeClipping(edge, directInk, hardwareInk, state)) {
          knownClippingCases.add(edge.name);
          System.out.println("PARITY_EDGE name=" + edge.name
              + " known=KNOWN-05 direct_ink_bounds=" + directInk
              + " hardware_ink_bounds=" + hardwareInk
              + " direct_ink_pixels=" + directInkPixels
              + " hardware_ink_pixels=" + hardwareInkPixels
              + " ink_count_delta_ratio=" + inkCountDeltaRatio);
        } else {
          assertInkBoundsClose(edge.name, directInk, hardwareInk);
        }
        assertTrue(edge.name + " must reach the last cell row",
            directInk.bottom >= topInset + 1);
      } finally {
        capture.recycle();
      }
    }
    System.out.println("PARITY_EDGE_SUMMARY known=KNOWN-05 cases=" + knownClippingCases);
  }

  @Test
  public void multiRowRenderNodeHasNoBoundarySeams() throws Exception {
    int columns = 8;
    int[] colors = {0xFFCC2222, 0xFF22AA44, 0xFF2255CC};
    CellValue[][] rows = new CellValue[colors.length][];
    for (int row = 0; row < colors.length; row++) {
      rows[row] = backgroundRow(columns, colors[row]);
    }

    ParityCapture capture = captureParity(rows, columns, VIEW_HEIGHT);
    try {
      CapturedViewState state = capture.state;
      int topInset = Math.round(Math.max(0f, state.lineHeight - state.baseline));
      int terminalRight = Math.min(capture.direct.getWidth(),
          edgePx(state.cellWidth, columns));
      for (int row = 1; row < colors.length; row++) {
        int boundary = Math.round(topInset + row * state.lineHeight);
        for (int y = Math.max(0, boundary - 1);
             y <= Math.min(capture.direct.getHeight() - 1, boundary + 1); y++) {
          int expected = y < boundary ? colors[row - 1] : colors[row];
          for (int x = 0; x < terminalRight; x++) {
            assertEquals("direct Canvas seam at row=" + row + " x=" + x + " y=" + y,
                expected, capture.direct.getPixel(x, y));
            assertEquals("RenderNode seam at row=" + row + " x=" + x + " y=" + y,
                expected, capture.hardware.getPixel(x, y));
          }
        }
      }
    } finally {
      capture.recycle();
    }
  }

  @Test
  public void specialTerminalGlyphsHaveCanvasRenderNodeParity() throws Exception {
    CellValue[][] rows = new CellValue[][] {
        row(COLUMNS, "┌", "─", "─", "─", "─", "┐", " ", " ", " ", " ", " ", " ",
            "", "", "", ""),
        row(COLUMNS, "│", "█", "▀", "▄", "│", " ", " ", " ", " ", " ", " ", "├", "⣿", "▒", "┤"),
        row(COLUMNS, "└", "─", "─", "─", "─", "┘", " ", " ", " ", " ", " ", "╔", "═", "╗")
    };
    ParityCapture capture = captureParity(rows, COLUMNS, VIEW_HEIGHT);
    try {
      CapturedViewState state = capture.state;
      int topInset = Math.round(Math.max(0f, state.lineHeight - state.baseline));
      int terminalRight = Math.min(capture.direct.getWidth(), edgePx(state.cellWidth, COLUMNS));
      for (int row = 0; row < rows.length; row++) {
        int rowTop = topInset + Math.round(row * state.lineHeight);
        int rowBottom = Math.min(capture.direct.getHeight(),
            rowTop + Math.round(state.lineHeight));
        for (int column = 0; column < COLUMNS; column++) {
          String text = rows[row][column].text();
          if (!isSpecialGlyph(text)) continue;
          int left = edgePx(state.cellWidth, column);
          int right = Math.min(terminalRight, edgePx(state.cellWidth, column + 1));
          for (int y = rowTop; y < rowBottom; y++) {
            for (int x = left; x < right; x++) {
              boolean directInk = capture.direct.getPixel(x, y) != BACKGROUND;
              boolean hardwareInk = capture.hardware.getPixel(x, y) != BACKGROUND;
              assertEquals("special mask mismatch at row=" + row + " col=" + column
                  + " x=" + x + " y=" + y + " text=" + text,
                  directInk, hardwareInk);
            }
          }
        }
      }
      System.out.println("PARITY_SPECIAL canvas_rendernode=true rows=" + rows.length
          + " columns=" + COLUMNS + " cell_width=" + state.cellWidth
          + " line_height=" + state.lineHeight + " baseline=" + state.baseline);
    } finally {
      capture.recycle();
    }
  }

  @Test
  public void contextualTextRowsHaveCanvasRenderNodeParity() throws Exception {
    CellValue[][] rows = contextualTextRows();
    ParityCapture capture = captureParity(rows, COLUMNS, VIEW_HEIGHT);
    try {
      CapturedViewState state = capture.state;
      int topInset = Math.round(Math.max(0f, state.lineHeight - state.baseline));
      int terminalRight = Math.min(capture.direct.getWidth(), edgePx(state.cellWidth, COLUMNS));
      int terminalBottom = Math.min(capture.direct.getHeight(),
          topInset + rows.length * Math.round(state.lineHeight));
      Rect terminalBounds = new Rect(0, topInset, terminalRight, terminalBottom);
      Diff diff = diff(capture.direct, capture.hardware, terminalBounds, CHANNEL_TOLERANCE);
      assertBackgroundOutsideTerminal(capture.direct, capture.hardware, terminalBounds);
      assertTrue("contextual text parity difference is too large: " + diff,
          diff.differentPixels <= diff.totalPixels * MAX_DIFF_RATIO);

      for (int row = 0; row < rows.length; row++) {
        int rowTop = topInset + row * Math.round(state.lineHeight);
        int rowBottom = Math.min(capture.direct.getHeight(),
            rowTop + Math.round(state.lineHeight));
        for (int column = 0; column < COLUMNS; column++) {
          CellValue cell = rows[row][column];
          if (cell.isSpacer() || cell.text().equals(" ")) continue;
          int width = cell.isWideStart() ? 2 : 1;
          Rect cellBounds = new Rect(
              edgePx(state.cellWidth, column), rowTop,
              Math.min(terminalRight, edgePx(state.cellWidth, column + width)), rowBottom);
          assertEquals("contextual text ink mismatch row=" + row + " col=" + column,
              findInkBounds(capture.direct, cellBounds) != null,
              findInkBounds(capture.hardware, cellBounds) != null);
          if (cell.isWideStart()) column++;
        }
      }
      System.out.println("PARITY_TEXT_RUN canvas_rendernode=true rows=" + rows.length
          + " columns=" + COLUMNS + " different_pixels=" + diff.differentPixels
          + "/" + diff.totalPixels + " diff_bounds=" + diff.bounds);
    } finally {
      capture.recycle();
    }
  }

  @Test
  public void coloredShadeAndVerticalDashHaveParityWithOddLineGeometry() throws Exception {
    CellValue[][] rows = new CellValue[][] {
        coloredSpecialRow(COLUMNS, "▒", 0x00FF00, "┊", 0xFF0000),
        coloredSpecialRow(COLUMNS, "▓", 0x0000FF, "┋", 0xFFFF00),
        coloredSpecialRow(COLUMNS, "░", 0xFF0000, "┊", 0x00FFFF)
    };
    ParityCapture capture = captureParity(rows, COLUMNS, 240, 10);
    try {
      CapturedViewState state = capture.state;
      int lineHeight = Math.round(state.lineHeight);
      int topInset = Math.round(Math.max(0f, state.lineHeight - state.baseline));
      assertTrue("test must use an odd line height: " + lineHeight
          + " for textSizeSp=" + state.fontSizeSp, (lineHeight & 1) == 1);
      assertTrue("test must use an odd top inset: " + topInset
          + " for textSizeSp=" + state.fontSizeSp, (topInset & 1) == 1);
      int terminalRight = Math.min(capture.direct.getWidth(), edgePx(state.cellWidth, COLUMNS));
      int terminalBottom = Math.min(capture.direct.getHeight(),
          topInset + rows.length * lineHeight);
      for (int y = topInset; y < terminalBottom; y++) {
        for (int x = 0; x < terminalRight; x++) {
          assertEquals("colored special pixel mismatch at x=" + x + " y=" + y,
              capture.direct.getPixel(x, y), capture.hardware.getPixel(x, y));
        }
      }
      System.out.println("PARITY_SPECIAL_ODD canvas_rendernode=true rows=" + rows.length
          + " columns=" + COLUMNS + " text_size_sp=" + state.fontSizeSp
          + " cell_width=" + state.cellWidth + " line_height=" + state.lineHeight
          + " top_inset=" + topInset);
    } finally {
      capture.recycle();
    }
  }

  private static RemoteTerminalModel model() {
    return parityModel(new CellValue[][] {cells()}, COLUMNS);
  }

  private static RemoteTerminalModel parityModel(CellValue[][] rows, int columns) {
    List<LineKey> keys = new java.util.ArrayList<>();
    List<LineBodyRecord> bodies = new java.util.ArrayList<>();
    for (int row = 0; row < rows.length; row++) {
      LineKey key = new LineKey(1000 + row, 1);
      keys.add(key);
      bodies.add(new LineBodyRecord(key,
          new LineBody(columns, false, Arrays.copyOf(rows[row], rows[row].length))));
    }
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "parity", "parity-instance", 1, 1, 1,
        rows.length, columns, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(),
        keys, bodies,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static ParityCapture captureParity(CellValue[][] rows, int columns, int viewHeight)
      throws Exception {
    return captureParity(rows, columns, viewHeight, -1);
  }

  private static ParityCapture captureParity(CellValue[][] rows, int columns, int viewHeight,
                                             int textSizeSp) throws Exception {
    RemoteTerminalModel model = parityModel(rows, columns);
    RenderUpdate update = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    AtomicReference<RemoteTerminalView> viewRef = new AtomicReference<>();
    AtomicReference<CapturedViewState> stateRef = new AtomicReference<>();
    AtomicReference<Window> windowRef = new AtomicReference<>();
    AtomicReference<int[]> viewLocationRef = new AtomicReference<>();
    AtomicReference<int[]> windowSizeRef = new AtomicReference<>();
    Bitmap hardware;
    Bitmap direct;

    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      scenario.onActivity(activity -> {
        RemoteTerminalView view = new RemoteTerminalView(activity);
        if (textSizeSp > 0) view.setTextSize(textSizeSp);
        viewRef.set(view);
        windowRef.set(activity.getWindow());
        activity.setContentView(view, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, viewHeight));
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
      float textSizePx = TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_SP,
          state.fontSizeSp,
          InstrumentationRegistry.getInstrumentation().getTargetContext()
              .getResources().getDisplayMetrics());
      directRenderer.updateFont(textSizePx, Typeface.MONOSPACE);
      directRenderer.setFontMetrics(state.cellWidth, state.lineHeight, state.baseline);
      directRenderer.render(new Canvas(direct), model.renderSnapshot(), viewport, true);

      int[] windowSize = windowSizeRef.get();
      Bitmap windowPixels = Bitmap.createBitmap(
          windowSize[0], windowSize[1], Bitmap.Config.ARGB_8888);
      copyWindowPixels(windowRef.get(), windowPixels);
      int[] location = viewLocationRef.get();
      hardware = Bitmap.createBitmap(windowPixels, location[0], location[1], width, height);
      windowPixels.recycle();
      return new ParityCapture(direct, hardware, state);
    }
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

  private static CellValue[] row(int columns, String... graphemes) {
    CellValue[] cells = new CellValue[columns];
    Arrays.fill(cells, CellValue.EMPTY);
    int column = 0;
    for (String grapheme : graphemes) {
      if (column >= columns) break;
      cells[column++] = new CellValue(grapheme, (byte) 1, null, null);
    }
    return cells;
  }

  private static CellValue[] coloredSpecialRow(int columns, String shade, int shadeColor,
                                               String dashed, int dashedColor) {
    CellValue[] cells = new CellValue[columns];
    Arrays.fill(cells, CellValue.EMPTY);
    cells[0] = coloredCell(shade, shadeColor);
    cells[1] = coloredCell(dashed, dashedColor);
    cells[2] = coloredCell(dashed, dashedColor);
    return cells;
  }

  private static CellValue[][] contextualTextRows() {
    StyleValue bold = new StyleValue(
        TerminalColor.rgb(0xF5F5F5), TerminalColor.DEFAULT_BG, null, 1 << 0);
    StyleValue italic = new StyleValue(
        TerminalColor.rgb(0xF5F5F5), TerminalColor.DEFAULT_BG, null, 1 << 2);
    CellValue[][] rows = new CellValue[3][];
    rows[0] = emptyRow(COLUMNS);
    rows[0][0] = new CellValue("A", (byte) 1, null, null);
    rows[0][1] = new CellValue("e\u0301", (byte) 1, null, null);
    rows[0][2] = new CellValue("中", (byte) 2, null, null);
    rows[0][3] = CellValue.SPACER;
    rows[0][4] = new CellValue("😀", (byte) 2, null, null);
    rows[0][5] = CellValue.SPACER;
    rows[0][6] = new CellValue("Z", (byte) 1, null, null);

    rows[1] = emptyRow(COLUMNS);
    rows[1][0] = new CellValue("a", (byte) 1, null, null);
    rows[1][1] = new CellValue("b", (byte) 1, null, null);
    rows[1][2] = new CellValue("c", (byte) 1, null, null);
    rows[1][3] = new CellValue("D", (byte) 1, bold, null);
    rows[1][4] = new CellValue("E", (byte) 1, bold, null);
    rows[1][5] = new CellValue("F", (byte) 1, bold, null);
    rows[1][6] = new CellValue("g", (byte) 1, italic, null);
    rows[1][7] = new CellValue("h", (byte) 1, italic, null);
    rows[1][8] = new CellValue("i", (byte) 1, italic, null);

    rows[2] = emptyRow(COLUMNS);
    String[] arabic = {"ا", "ل", "ع", "ر", "ب", "ي", "ة"};
    for (int i = 0; i < arabic.length; i++) {
      rows[2][i] = new CellValue(arabic[i], (byte) 1, null, null);
    }
    rows[2][7] = new CellValue("हि", (byte) 1, null, null);
    rows[2][8] = new CellValue("न्", (byte) 1, null, null);
    rows[2][9] = new CellValue("दी", (byte) 1, null, null);
    rows[2][10] = new CellValue("❤️", (byte) 2, null, null);
    rows[2][11] = CellValue.SPACER;
    return rows;
  }

  private static CellValue[] emptyRow(int columns) {
    CellValue[] cells = new CellValue[columns];
    Arrays.fill(cells, CellValue.EMPTY);
    return cells;
  }

  private static CellValue coloredCell(String text, int color) {
    return new CellValue(text, (byte) 1,
        new StyleValue(TerminalColor.rgb(color), TerminalColor.DEFAULT_BG, null, 0), null);
  }

  private static boolean isSpecialGlyph(String text) {
    return TerminalSpecialGlyphPainter.familyFor(text)
        != TerminalSpecialGlyphPainter.Family.NONE;
  }

  private static List<EdgeCase> edgeCases() {
    return List.of(
        new EdgeCase("last-italic-f", 15, edgeLine(15, italic("f"))),
        new EdgeCase("last-italic-W", 15, edgeLine(15, italic("W"))),
        new EdgeCase("last-two-emoji", 14, edgeLine(14, new CellValue("😀", (byte) 2, null, null))),
        new EdgeCase("last-two-cjk", 14, edgeLine(14, new CellValue("界", (byte) 2, null, null))),
        new EdgeCase("last-italic-overhang", 15, edgeLine(15, italic("/"))));
  }

  private static CellValue[] edgeLine(int startColumn, CellValue cell) {
    CellValue[] cells = new CellValue[COLUMNS];
    Arrays.fill(cells, CellValue.EMPTY);
    cells[startColumn] = cell;
    if (cell.isWideStart()) cells[startColumn + 1] = CellValue.SPACER;
    return cells;
  }

  private static CellValue italic(String text) {
    StyleValue style = new StyleValue(
        TerminalColor.rgb(0xFFFFFF), TerminalColor.DEFAULT_BG, null, 1 << 2);
    return new CellValue(text, (byte) 1, style, null);
  }

  private static CellValue[] backgroundRow(int columns, int background) {
    StyleValue style = new StyleValue(
        TerminalColor.rgb(0xFFFFFF), TerminalColor.rgb(background & 0x00FFFFFF), null, 0);
    CellValue[] cells = new CellValue[columns];
    for (int column = 0; column < columns; column++) {
      cells[column] = new CellValue(" ", (byte) 1, style, null);
    }
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

  private static Rect findInkBounds(Bitmap bitmap, Rect bounds) {
    Rect clipped = new Rect(bounds);
    if (!clipped.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) return null;
    Rect result = null;
    for (int y = clipped.top; y < clipped.bottom; y++) {
      for (int x = clipped.left; x < clipped.right; x++) {
        if (bitmap.getPixel(x, y) != BACKGROUND) {
          if (result == null) result = new Rect(x, y, x + 1, y + 1);
          else result.union(x, y, x + 1, y + 1);
        }
      }
    }
    return result;
  }

  private static int countInk(Bitmap bitmap, Rect bounds) {
    Rect clipped = new Rect(bounds);
    if (!clipped.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) return 0;
    int count = 0;
    for (int y = clipped.top; y < clipped.bottom; y++) {
      for (int x = clipped.left; x < clipped.right; x++) {
        if (bitmap.getPixel(x, y) != BACKGROUND) count++;
      }
    }
    return count;
  }

  private static int countInk(Bitmap bitmap, int left, int right, int top, int bottom) {
    return countInk(bitmap, new Rect(left, top, right, bottom));
  }

  private static boolean inkBoundsClose(Rect direct, Rect hardware) {
    return Math.abs(direct.left - hardware.left) <= INK_EDGE_TOLERANCE
        && Math.abs(direct.top - hardware.top) <= INK_EDGE_TOLERANCE
        && Math.abs(direct.right - hardware.right) <= INK_EDGE_TOLERANCE
        && Math.abs(direct.bottom - hardware.bottom) <= INK_EDGE_TOLERANCE;
  }

  private static boolean isExpectedRenderNodeClipping(EdgeCase edge, Rect direct,
                                                       Rect hardware, CapturedViewState state) {
    int terminalRight = edgePx(state.cellWidth, COLUMNS);
    // KNOWN-05 只覆盖末列斜体/fallback glyph。Contextual advance 会让 legacy X-only
    // fitting 保留一个小的浮点宽度差，软件 Canvas 的斜体 overhang 可能再多出 1px
    // 的顶部抗锯齿像素；这仍属于同一个末列 RenderNode 裁切问题，不扩大到 CJK/emoji。
    int topTolerance = edge.name.startsWith("last-italic-")
        ? INK_EDGE_TOLERANCE + 1 : INK_EDGE_TOLERANCE;
    return Math.abs(direct.left - hardware.left) <= INK_EDGE_TOLERANCE
        && Math.abs(direct.top - hardware.top) <= topTolerance
        && Math.abs(direct.bottom - hardware.bottom) <= INK_EDGE_TOLERANCE
        && direct.right > terminalRight
        && hardware.right == terminalRight
        && edge.startColumn + (edge.cells[edge.startColumn].isWideStart() ? 2 : 1) == COLUMNS;
  }

  private static void assertInkBoundsClose(String label, Rect direct, Rect hardware) {
    assertTrue(label + " left edge diverged: direct=" + direct + " hardware=" + hardware,
        Math.abs(direct.left - hardware.left) <= INK_EDGE_TOLERANCE);
    assertTrue(label + " top edge diverged: direct=" + direct + " hardware=" + hardware,
        Math.abs(direct.top - hardware.top) <= INK_EDGE_TOLERANCE);
    assertTrue(label + " right edge diverged: direct=" + direct + " hardware=" + hardware,
        Math.abs(direct.right - hardware.right) <= INK_EDGE_TOLERANCE);
    assertTrue(label + " bottom edge diverged: direct=" + direct + " hardware=" + hardware,
        Math.abs(direct.bottom - hardware.bottom) <= INK_EDGE_TOLERANCE);
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

  private static int edgePx(float cellWidth, int column) {
    return Math.round(column * cellWidth);
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

  private static final class EdgeCase {
    final String name;
    final int startColumn;
    final CellValue[] cells;

    EdgeCase(String name, int startColumn, CellValue[] cells) {
      this.name = name;
      this.startColumn = startColumn;
      this.cells = cells;
    }
  }

  private static final class ParityCapture {
    final Bitmap direct;
    final Bitmap hardware;
    final CapturedViewState state;

    ParityCapture(Bitmap direct, Bitmap hardware, CapturedViewState state) {
      this.direct = direct;
      this.hardware = hardware;
      this.state = state;
    }

    void recycle() {
      direct.recycle();
      hardware.recycle();
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
