package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
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
import com.webterm.terminal.model.TerminalViewportState;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class RemoteTerminalRendererDecorationBitmapTest {
  private static final int BACKGROUND = 0xFF000000;
  private static final int FOREGROUND = 0xFFFF0000;
  private static final int UNDERLINE = 0xFF00FF00;
  private static final int CELL_WIDTH = 10;

  @Test
  public void asciiRunAndPerCellPathUseTheSameDecorationColors() {
    StyleValue style = style(TerminalColor.rgb(FOREGROUND & 0x00FFFFFF),
        TerminalColor.DEFAULT_BG, TerminalColor.rgb(UNDERLINE & 0x00FFFFFF),
        (1 << 3) | (1 << 12));
    Bitmap ascii = render(new CellValue[] {
        new CellValue("a", (byte) 1, style, null),
        new CellValue("b", (byte) 1, style, null),
        new CellValue("c", (byte) 1, style, null)
    });
    Bitmap cells = render(new CellValue[] {
        new CellValue("界", (byte) 2, style, null),
        CellValue.SPACER,
        new CellValue("界", (byte) 2, style, null),
        CellValue.SPACER
    });

    assertTrue(hasColorFamily(ascii, UNDERLINE, 0, 30, 20, 25));
    assertTrue(hasColorFamily(ascii, FOREGROUND, 0, 30, 13, 18));
    assertTrue(hasColorFamily(cells, UNDERLINE, 0, 40, 20, 25));
    assertTrue(hasColorFamily(cells, FOREGROUND, 0, 40, 13, 18));
    ascii.recycle();
    cells.recycle();
  }

  @Test
  public void allUnderlineKindsReachTheRendererBitmap() {
    int[] attrs = {(1 << 3), (1 << 4), (1 << 5), (1 << 6), (1 << 7)};
    for (int attr : attrs) {
      CellValue[] cells = new CellValue[6];
      Arrays.fill(cells, CellValue.EMPTY);
      cells[2] = new CellValue("X", (byte) 1,
          style(TerminalColor.rgb(FOREGROUND & 0x00FFFFFF), TerminalColor.DEFAULT_BG,
              TerminalColor.rgb(UNDERLINE & 0x00FFFFFF), attr), null);
      Bitmap bitmap = render(cells);
      assertTrue("decoration attr " + attr + " must produce ink",
          hasColorFamily(bitmap, UNDERLINE, 20, 30, 20, 25));
      bitmap.recycle();
    }
  }

  @Test
  public void underlineColorDoesNotTintStrike() {
    StyleValue style = style(TerminalColor.rgb(FOREGROUND & 0x00FFFFFF),
        TerminalColor.DEFAULT_BG, TerminalColor.rgb(UNDERLINE & 0x00FFFFFF),
        (1 << 3) | (1 << 12));
    Bitmap bitmap = render(new CellValue[] {
        new CellValue("界", (byte) 2, style, null), CellValue.SPACER
    });

    assertTrue("underline uses explicit underline color",
        hasColorFamily(bitmap, UNDERLINE, 0, 20, 20, 25));
    assertTrue("strike uses final foreground",
        hasColorFamily(bitmap, FOREGROUND, 0, 20, 13, 18));
    bitmap.recycle();
  }

  @Test
  public void hiddenKeepsBackgroundAndSkipsAllDecoration() {
    StyleValue style = style(TerminalColor.rgb(FOREGROUND & 0x00FFFFFF),
        TerminalColor.rgb(0x000080), TerminalColor.rgb(UNDERLINE & 0x00FFFFFF),
        (1 << 3) | (1 << 11) | (1 << 12));
    Bitmap bitmap = render(new CellValue[] {
        new CellValue("X", (byte) 1, style, null)
    });

    for (int y = 5; y < 25; y++) {
      for (int x = 0; x < CELL_WIDTH; x++) {
        assertEquals("hidden cell must retain only its background", 0xFF000080,
            bitmap.getPixel(x, y));
      }
    }
    bitmap.recycle();
  }

  private static Bitmap render(CellValue[] cells) {
    Bitmap bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(BACKGROUND);
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(CELL_WIDTH, 20f, 15f);
    RemoteTerminalModel model = model(cells);
    renderer.render(new Canvas(bitmap), model.renderSnapshot(),
        new TerminalViewportState(), true);
    return bitmap;
  }

  private static RemoteTerminalModel model(CellValue[] cells) {
    CellValue[] copy = Arrays.copyOf(cells, cells.length);
    LineKey key = new LineKey(1, 1);
    LineBody body = new LineBody(copy.length, false, copy);
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "decoration", "instance", 1, 1, 1,
        1, copy.length, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(),
        List.of(key), List.of(new LineBodyRecord(key, body)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static StyleValue style(TerminalColor foreground, TerminalColor background,
                                  TerminalColor underlineColor, int attrs) {
    return new StyleValue(foreground, background, underlineColor, attrs);
  }

  private static boolean hasColorFamily(Bitmap bitmap, int color,
                                        int left, int right, int top, int bottom) {
    int rgb = color & 0x00FFFFFF;
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
}
