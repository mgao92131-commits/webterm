package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineBodyRecord;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.TerminalViewportState;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 软件 Bitmap 上只断言背景、覆盖层和 cell 几何，不断言字体轮廓。 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class RemoteTerminalRendererBitmapInvariantTest {
  private static final int COLUMNS = 8;
  private static final float CELL_WIDTH = 10f;
  private static final float LINE_HEIGHT = 20f;
  private static final float BASELINE_OFFSET = 15f;
  private static final int BACKGROUND = 0xFF000000;

  @Test
  public void styledBackgroundCoversExactlyItsCell() {
    StyleValue redBackground = new StyleValue(
        TerminalColor.rgb(0xFFFFFF), TerminalColor.rgb(0xCC1122), null, 0);
    CellValue[] cells = blankCells();
    cells[2] = new CellValue(" ", (byte) 1, redBackground, null);

    Bitmap bitmap = render(cells, new TerminalViewportState(), TerminalCursor.hidden());

    assertEquals(0xFFCC1122, bitmap.getPixel(25, 10));
    assertEquals(BACKGROUND, bitmap.getPixel(15, 10));
    assertEquals(BACKGROUND, bitmap.getPixel(35, 10));
  }

  @Test
  public void selectionOverlayUsesOneCellAndWideCellGeometry() {
    CellValue[] cells = blankCells();
    cells[2] = new CellValue("界", (byte) 2, null, null);
    cells[3] = CellValue.SPACER;
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.selection = new TerminalSelection(
        new TerminalSelection.Anchor(0, 0, 2),
        new TerminalSelection.Anchor(0, 0, 4));

    Bitmap bitmap = render(cells, viewport, TerminalCursor.hidden());
    int selectedLeft = bitmap.getPixel(21, 6);
    int selectedRight = bitmap.getPixel(31, 6);

    assertNotEquals(BACKGROUND, selectedLeft);
    assertEquals("selection must cover both physical columns of a wide cell",
        selectedLeft, selectedRight);
    assertEquals(BACKGROUND, bitmap.getPixel(11, 6));
  }

  @Test
  public void barAndUnderlineCursorsStayInsideTheirCell() {
    CellValue[] cells = blankCells();

    Bitmap bar = render(cells, new TerminalViewportState(),
        new TerminalCursor(0, 1, true, TerminalCursor.Shape.BAR, false));
    assertNotEquals(BACKGROUND, bar.getPixel(11, 10));
    assertEquals(BACKGROUND, bar.getPixel(18, 10));

    Bitmap underline = render(cells, new TerminalViewportState(),
        new TerminalCursor(0, 2, true, TerminalCursor.Shape.UNDERLINE, false));
    assertEquals(BACKGROUND, underline.getPixel(25, 10));
    assertNotEquals(BACKGROUND, underline.getPixel(25, 23));
  }

  @Test
  public void blockCursorOnWideSpacerCoversBothColumns() {
    CellValue[] cells = blankCells();
    cells[4] = new CellValue("界", (byte) 2, null, null);
    cells[5] = CellValue.SPACER;

    Bitmap bitmap = render(cells, new TerminalViewportState(),
        new TerminalCursor(0, 5, true, TerminalCursor.Shape.BLOCK, false));

    assertNotEquals(BACKGROUND, bitmap.getPixel(41, 23));
    assertNotEquals(BACKGROUND, bitmap.getPixel(59, 23));
    assertEquals(BACKGROUND, bitmap.getPixel(39, 23));
  }

  @Test
  public void hiddenTextKeepsBackgroundAndReverseSwapsBackgroundColor() {
    StyleValue hiddenBlue = new StyleValue(
        TerminalColor.rgb(0xFFFFFF), TerminalColor.rgb(0x123456), null, 1 << 11);
    CellValue[] hidden = blankCells();
    hidden[1] = new CellValue("X", (byte) 1, hiddenBlue, null);
    Bitmap hiddenBitmap = render(hidden, new TerminalViewportState(), TerminalCursor.hidden());
    assertEquals(0xFF123456, hiddenBitmap.getPixel(15, 10));

    StyleValue reverseHidden = new StyleValue(
        TerminalColor.rgb(0xAA0000), TerminalColor.rgb(0x0000AA), null,
        (1 << 10) | (1 << 11));
    CellValue[] reversed = blankCells();
    reversed[1] = new CellValue("X", (byte) 1, reverseHidden, null);
    Bitmap reversedBitmap = render(reversed, new TerminalViewportState(), TerminalCursor.hidden());
    assertEquals("reverse must exchange foreground/background semantics",
        0xFFAA0000, reversedBitmap.getPixel(15, 10));
  }

  @Test
  public void normalTextStaysWithinTerminalRowAndProducesSomeInk() {
    CellValue[] cells = blankCells();
    cells[0] = new CellValue("A", (byte) 1, null, null);
    Bitmap bitmap = render(cells, new TerminalViewportState(), TerminalCursor.hidden());
    boolean foundInk = false;
    for (int y = 5; y < 25 && !foundInk; y++) {
      for (int x = 0; x < COLUMNS * (int) CELL_WIDTH; x++) {
        if (bitmap.getPixel(x, y) != BACKGROUND) {
          foundInk = true;
          break;
        }
      }
    }
    assertTrue("normal glyph must leave ink in its target row", foundInk);
    assertEquals("bitmap bounds are exactly the terminal cell width",
        COLUMNS * (int) CELL_WIDTH, bitmap.getWidth());
  }

  private static Bitmap render(CellValue[] cells, TerminalViewportState viewport,
                               TerminalCursor cursor) {
    Bitmap bitmap = Bitmap.createBitmap(
        COLUMNS * (int) CELL_WIDTH, 40, Bitmap.Config.ARGB_8888);
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(CELL_WIDTH, LINE_HEIGHT, BASELINE_OFFSET);
    RemoteTerminalModel model = model(cells, cursor);
    renderer.render(new Canvas(bitmap), model.renderSnapshot(), viewport, true);
    return bitmap;
  }

  private static RemoteTerminalModel model(CellValue[] cells, TerminalCursor cursor) {
    LineKey key = new LineKey(1, 1);
    LineBody body = new LineBody(COLUMNS, false, Arrays.copyOf(cells, cells.length));
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "bitmap", "instance", 1, 1, 1,
        1, COLUMNS, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(),
        List.of(key), List.of(new LineBodyRecord(key, body)),
        cursor, TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static CellValue[] blankCells() {
    CellValue[] cells = new CellValue[COLUMNS];
    Arrays.fill(cells, CellValue.EMPTY);
    return cells;
  }
}
