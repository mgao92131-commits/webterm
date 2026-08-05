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
import java.util.Random;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** 随机比较第二轮逐 Span 路径和第三轮 prepared 路径，覆盖省略空白与宽字符边界。 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PreparedLineRandomParityTest {
  private static final int BACKGROUND = 0xFF000000;
  private static final int CELL_WIDTH = 10;
  private static final int LINE_HEIGHT = 20;
  private static final int SAMPLE_COUNT = 400;

  @Test
  public void preparedPlanMatchesLegacyForRandomizedRows() {
    Random random = new Random(0x3A77_2026L);
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(CELL_WIDTH, LINE_HEIGHT, 15f);
    TerminalPalette palette = TerminalPalette.defaults();

    for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
      int columns = 1 + random.nextInt(24);
      CellValue[] cells = randomCells(random, columns);
      RenderLine line = new RenderLine(
          new LineKey(90_000L + sample, 1L), new LineBody(columns, false, cells));
      CompiledTerminalLine compiled = renderer.compileLine(line, columns, palette, BACKGROUND);
      PreparedTerminalLine prepared = renderer.compileAndPrepareLine(
          line, columns, palette, BACKGROUND);
      Bitmap legacy = bitmap(columns);
      Bitmap optimized = bitmap(columns);
      renderer.drawCompiledLineContent(new Canvas(legacy), compiled, 0f, BACKGROUND);
      renderer.drawPreparedLineContent(new Canvas(optimized), prepared, 0f, BACKGROUND);
      try {
        assertSamePixels(sample, legacy, optimized);
      } finally {
        legacy.recycle();
        optimized.recycle();
      }
    }
  }

  private static CellValue[] randomCells(Random random, int columns) {
    CellValue[] cells = new CellValue[columns];
    Arrays.fill(cells, CellValue.EMPTY);
    for (int column = 0; column < columns; ) {
      if (random.nextInt(5) == 0) {
        column++;
        continue;
      }
      boolean wide = column + 1 < columns && random.nextInt(7) == 0;
      StyleValue style = randomStyle(random);
      String text;
      if (wide) {
        text = random.nextBoolean() ? "界" : "😀";
        cells[column] = new CellValue(text, (byte) 2, style, null);
        cells[column + 1] = CellValue.SPACER;
        column += 2;
        continue;
      }
      switch (random.nextInt(7)) {
        case 0: text = "A"; break;
        case 1: text = "e\u0301"; break;
        case 2: text = "▀"; break;
        case 3: text = "⣿"; break;
        case 4: text = "─"; break;
        case 5: text = new String(Character.toChars(0xE0B0)); break;
        default: text = "B";
      }
      cells[column] = new CellValue(text, (byte) 1, style, null);
      column++;
    }
    return cells;
  }

  private static StyleValue randomStyle(Random random) {
    switch (random.nextInt(7)) {
      case 0:
        return null;
      case 1:
        return new StyleValue(
            TerminalColor.rgb(0xE0E0E0), TerminalColor.rgb(0x203040), null, 0);
      case 2:
        return new StyleValue(
            TerminalColor.rgb(0xE0E0E0), null, TerminalColor.rgb(0x40FF80), 1 << 3);
      case 3:
        return new StyleValue(
            TerminalColor.rgb(0xFF8040), null, null, 1 << 12);
      case 4:
        return new StyleValue(
            TerminalColor.rgb(0xFFFFFF), TerminalColor.rgb(0x301020), null,
            (1 << 3) | (1 << 12));
      case 5:
        return new StyleValue(
            TerminalColor.rgb(0xE0E0E0), null, null, 1 << 8);
      default:
        return new StyleValue(
            TerminalColor.rgb(0xE0E0E0), null, null, 1 << 9);
    }
  }

  private static Bitmap bitmap(int columns) {
    Bitmap bitmap = Bitmap.createBitmap(columns * CELL_WIDTH, LINE_HEIGHT,
        Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(BACKGROUND);
    return bitmap;
  }

  private static void assertSamePixels(int sample, Bitmap expected, Bitmap actual) {
    for (int y = 0; y < expected.getHeight(); y++) {
      for (int x = 0; x < expected.getWidth(); x++) {
        assertEquals("sample=" + sample + " pixel=" + x + "," + y,
            expected.getPixel(x, y), actual.getPixel(x, y));
      }
    }
  }
}
