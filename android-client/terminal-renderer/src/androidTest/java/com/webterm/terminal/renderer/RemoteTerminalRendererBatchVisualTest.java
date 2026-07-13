package com.webterm.terminal.renderer;

import static org.junit.Assert.assertArrayEquals;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.webterm.terminal.model.HistoryWindow;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalStyle;
import com.webterm.terminal.model.TerminalViewportState;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Verifies on a real Android Canvas that styled run batching preserves per-cell pixels. */
@RunWith(AndroidJUnit4.class)
public final class RemoteTerminalRendererBatchVisualTest {

  @Test
  public void styledRunMatchesEquivalentPerCellRendering() {
    int cols = 12;
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.updateFont(14f, Typeface.MONOSPACE);
    int width = (int) Math.ceil(cols * renderer.getCellWidth());
    int height = (int) Math.ceil(renderer.getLineHeight() + renderer.getTopInset());

    Bitmap batched = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    renderer.render(new Canvas(batched), model(cols, false).renderSnapshot(),
        new TerminalViewportState());

    Bitmap perCell = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    renderer.render(new Canvas(perCell), model(cols, true).renderSnapshot(),
        new TerminalViewportState());

    int[] batchedPixels = new int[width * height];
    int[] perCellPixels = new int[width * height];
    batched.getPixels(batchedPixels, 0, width, 0, 0, width, height);
    perCell.getPixels(perCellPixels, 0, width, 0, 0, width, height);
    assertArrayEquals(perCellPixels, batchedPixels);
  }

  private static RemoteTerminalModel model(int cols, boolean alternateEquivalentStyleIds) {
    TerminalCell[] cells = new TerminalCell[cols];
    for (int col = 0; col < cols; col++) {
      int styleId = alternateEquivalentStyleIds ? 1 + col % 2 : 1;
      cells[col] = new TerminalCell(String.valueOf((char) ('A' + col)), (byte) 1, styleId, 0);
    }
    int attrs = (1 << 0) | (1 << 2) | (1 << 3) | (1 << 12);
    Map<Integer, TerminalStyle> styles = new HashMap<>();
    styles.put(1, new TerminalStyle(1, TerminalColor.indexed(2), TerminalColor.indexed(4),
        TerminalColor.indexed(3), attrs));
    styles.put(2, new TerminalStyle(2, TerminalColor.indexed(2), TerminalColor.indexed(4),
        TerminalColor.indexed(3), attrs));
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(new ScreenSnapshot("s", "i", 1, 1, 1, cols,
        ScreenSnapshot.BufferKind.MAIN, TerminalCursor.hidden(), TerminalModes.defaults(),
        TerminalPalette.defaults(), HistoryWindow.empty(),
        Collections.singletonList(new TerminalLine(0, false, cells)), styles,
        Collections.emptyMap(), "", ""));
    return model;
  }
}
