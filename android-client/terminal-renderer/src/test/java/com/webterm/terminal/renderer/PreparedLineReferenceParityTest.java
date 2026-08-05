package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalPalette;

import java.util.Arrays;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** 独立比较第二轮逐 Span 路径与第三轮 prepared plan 路径，避免只比较两个相同后端。 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PreparedLineReferenceParityTest {
  private static final int BACKGROUND = 0xFF000000;
  private static final int COLUMNS = 24;
  private static final int CELL_WIDTH = 10;
  private static final int LINE_HEIGHT = 20;

  @Test
  public void preparedPlanMatchesLegacySpanDrawingForMixedTuiLine() {
    StyleValue decorated = new StyleValue(
        TerminalColor.rgb(0xDDEEFF), TerminalColor.rgb(0x102030),
        TerminalColor.rgb(0xFFAA33), (1 << 3) | (1 << 12));
    StyleValue alternateBackground = new StyleValue(
        TerminalColor.rgb(0xFFFFFF), TerminalColor.rgb(0x302010), null, 0);
    CellValue[] cells = new CellValue[COLUMNS];
    Arrays.fill(cells, CellValue.EMPTY);
    cells[0] = new CellValue("A", (byte) 1, decorated, null);
    cells[1] = new CellValue("┌", (byte) 1, decorated, null);
    cells[2] = new CellValue("▀", (byte) 1, decorated, null);
    cells[3] = new CellValue("⣿", (byte) 1, decorated, null);
    cells[4] = new CellValue("", (byte) 1, decorated, null);
    cells[5] = new CellValue("界", (byte) 2, alternateBackground, null);
    cells[6] = CellValue.SPACER;
    cells[7] = new CellValue("e\u0301", (byte) 1, decorated, null);
    cells[8] = new CellValue("B", (byte) 1, alternateBackground, null);

    RenderLine line = new RenderLine(
        new LineKey(8_001L, 1L), new LineBody(COLUMNS, false, cells));
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(CELL_WIDTH, LINE_HEIGHT, 15f);
    CompiledTerminalLine compiled = renderer.compileLine(
        line, COLUMNS, TerminalPalette.defaults(), BACKGROUND);
    PreparedTerminalLine prepared = renderer.compileAndPrepareLine(
        line, COLUMNS, TerminalPalette.defaults(), BACKGROUND);

    Bitmap legacy = bitmap();
    Bitmap optimized = bitmap();
    renderer.drawCompiledLineContent(new Canvas(legacy), compiled, 0f, BACKGROUND);
    renderer.drawPreparedLineContent(new Canvas(optimized), prepared, 0f, BACKGROUND);

    for (int y = 0; y < legacy.getHeight(); y++) {
      for (int x = 0; x < legacy.getWidth(); x++) {
        assertEquals("pixel differs at " + x + "," + y,
            legacy.getPixel(x, y), optimized.getPixel(x, y));
      }
    }
    legacy.recycle();
    optimized.recycle();
  }

  @Test
  public void preparedPlanMatchesLegacyAcrossOmittedDefaultBlankGap() {
    StyleValue styled = new StyleValue(
        TerminalColor.rgb(0xE0E0E0), TerminalColor.rgb(0x203040),
        TerminalColor.rgb(0x40FF80), 1 << 3);
    CellValue[] cells = new CellValue[] {
        new CellValue("A", (byte) 1, styled, null),
        CellValue.EMPTY,
        new CellValue("B", (byte) 1, styled, null)
    };
    RenderLine line = new RenderLine(
        new LineKey(8_002L, 1L), new LineBody(3, false, cells));
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(CELL_WIDTH, LINE_HEIGHT, 15f);
    CompiledTerminalLine compiled = renderer.compileLine(
        line, 3, TerminalPalette.defaults(), BACKGROUND);
    PreparedTerminalLine prepared = renderer.compileAndPrepareLine(
        line, 3, TerminalPalette.defaults(), BACKGROUND);

    Bitmap legacy = Bitmap.createBitmap(3 * CELL_WIDTH, LINE_HEIGHT, Bitmap.Config.ARGB_8888);
    Bitmap optimized = Bitmap.createBitmap(3 * CELL_WIDTH, LINE_HEIGHT, Bitmap.Config.ARGB_8888);
    legacy.eraseColor(BACKGROUND);
    optimized.eraseColor(BACKGROUND);
    renderer.drawCompiledLineContent(new Canvas(legacy), compiled, 0f, BACKGROUND);
    renderer.drawPreparedLineContent(new Canvas(optimized), prepared, 0f, BACKGROUND);

    for (int y = 0; y < legacy.getHeight(); y++) {
      for (int x = 0; x < legacy.getWidth(); x++) {
        assertEquals("gap pixel differs at " + x + "," + y,
            legacy.getPixel(x, y), optimized.getPixel(x, y));
      }
    }
    legacy.recycle();
    optimized.recycle();
  }

  @Test
  public void decorationRunIgnoresUnusedStrikeColorWhenStrikeIsOff() {
    StyleValue first = new StyleValue(
        TerminalColor.rgb(0xFF0000), null, TerminalColor.rgb(0x00FF00), 1 << 3);
    StyleValue second = new StyleValue(
        TerminalColor.rgb(0x0000FF), null, TerminalColor.rgb(0x00FF00), 1 << 3);
    RenderLine line = new RenderLine(new LineKey(8_003L, 1L), new LineBody(2, false,
        new CellValue[] {
            new CellValue("A", (byte) 1, first, null),
            new CellValue("B", (byte) 1, second, null)
        }));
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(CELL_WIDTH, LINE_HEIGHT, 15f);
    PreparedTerminalLine prepared = renderer.compileAndPrepareLine(
        line, 2, TerminalPalette.defaults(), BACKGROUND);

    assertEquals(1, prepared.drawPlan.staticDecorationRuns.length);
    assertEquals(0, prepared.drawPlan.staticDecorationRuns[0].leftPx);
    assertEquals(2 * CELL_WIDTH, prepared.drawPlan.staticDecorationRuns[0].rightPx);
  }

  private static Bitmap bitmap() {
    Bitmap bitmap = Bitmap.createBitmap(COLUMNS * CELL_WIDTH, LINE_HEIGHT,
        Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(BACKGROUND);
    return bitmap;
  }
}
