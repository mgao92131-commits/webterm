package com.webterm.terminal.renderer;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.model.TerminalColor;

import java.util.Arrays;

/** API 设备性能套件使用的固定行输入；宽度直接来自 fixture 的服务端语义 cell。 */
final class RendererPerformanceFixtures {
  static final int ROWS = 40;
  static final int COLUMNS = 120;

  enum Scenario {
    BLANK_HEAVY,
    DENSE_ASCII,
    STYLED_ASCII,
    DENSE_UNICODE,
    SPECIAL_GLYPH
  }

  enum Round3Scenario {
    BOX_TUI,
    BRAILLE_TUI,
    BLOCK_TUI,
    DECORATION_SINGLE,
    DECORATION_DOUBLE,
    DECORATION_CURLY,
    DECORATION_DOTTED,
    DECORATION_DASHED,
    DECORATION_MIXED,
    BACKGROUND_TUI,
    MIXED_TUI
  }

  private RendererPerformanceFixtures() {}

  static RenderLine[] lines(Scenario scenario) {
    RenderLine[] result = new RenderLine[ROWS];
    for (int row = 0; row < ROWS; row++) {
      CellValue[] cells = new CellValue[COLUMNS];
      Arrays.fill(cells, CellValue.EMPTY);
      switch (scenario) {
        case BLANK_HEAVY:
          fillBlankHeavy(cells, row);
          break;
        case DENSE_ASCII:
          fillDenseAscii(cells, row, null);
          break;
        case STYLED_ASCII:
          fillStyledAscii(cells, row);
          break;
        case DENSE_UNICODE:
          fillDenseUnicode(cells, row);
          break;
        case SPECIAL_GLYPH:
          fillSpecialGlyphs(cells, row);
          break;
      }
      result[row] = new RenderLine(
          new LineKey(900_000L + scenario.ordinal() * 10_000L + row, 1L),
          new LineBody(COLUMNS, false, cells));
    }
    return result;
  }

  static RenderLine[] round3Lines(Round3Scenario scenario) {
    RenderLine[] result = new RenderLine[ROWS];
    for (int row = 0; row < ROWS; row++) {
      CellValue[] cells = new CellValue[COLUMNS];
      Arrays.fill(cells, CellValue.EMPTY);
      switch (scenario) {
        case BOX_TUI:
          fillBoxTui(cells, row);
          break;
        case BRAILLE_TUI:
          fillBrailleTui(cells, row);
          break;
        case BLOCK_TUI:
          fillBlockTui(cells, row);
          break;
        case DECORATION_SINGLE:
          fillDecoration(cells, row, 1 << 3);
          break;
        case DECORATION_DOUBLE:
          fillDecoration(cells, row, 1 << 4);
          break;
        case DECORATION_CURLY:
          fillDecoration(cells, row, 1 << 5);
          break;
        case DECORATION_DOTTED:
          fillDecoration(cells, row, 1 << 6);
          break;
        case DECORATION_DASHED:
          fillDecoration(cells, row, 1 << 7);
          break;
        case DECORATION_MIXED:
          fillMixedDecoration(cells, row);
          break;
        case BACKGROUND_TUI:
          fillBackgroundTui(cells, row);
          break;
        case MIXED_TUI:
          fillMixedTui(cells, row);
          break;
      }
      result[row] = new RenderLine(
          new LineKey(910_000L + scenario.ordinal() * 10_000L + row, 1L),
          new LineBody(COLUMNS, false, cells));
    }
    return result;
  }

  private static void fillBoxTui(CellValue[] cells, int row) {
    String[] lines = {
        "┌────────────────────────────────────────────────────────┐",
        "│ CPU  ████████████████  74%                              │",
        "├────────────────────────────────────────────────────────┤",
        "│ MEM  ░░░░░░░░░░        43%                              │",
        "└────────────────────────────────────────────────────────┘"
    };
    String text = lines[row % lines.length];
    for (int column = 0; column < Math.min(cells.length, text.length()); column++) {
      int attrs = (column + row) % 17 == 0 ? 1 << 3 : 0;
      cells[column] = new CellValue(String.valueOf(text.charAt(column)), (byte) 1,
          new StyleValue(TerminalColor.rgb(0x66CCFF), TerminalColor.DEFAULT_BG, null, attrs),
          null);
    }
  }

  private static void fillBrailleTui(CellValue[] cells, int row) {
    for (int column = 0; column < cells.length; column++) {
      int mask = (column * 29 + row * 17) & 0xFF;
      cells[column] = new CellValue(new String(Character.toChars(0x2800 + mask)), (byte) 1,
          null, null);
    }
  }

  private static void fillBlockTui(CellValue[] cells, int row) {
    String blocks = "█▉▊▋▌▍▎▏▀▄░▒▓";
    for (int column = 0; column < cells.length; column++) {
      cells[column] = new CellValue(
          String.valueOf(blocks.charAt((column + row) % blocks.length())), (byte) 1,
          null, null);
    }
  }

  private static void fillDecoration(CellValue[] cells, int row, int decorationAttr) {
    StyleValue style = new StyleValue(
        TerminalColor.rgb(0xDDEEFF), TerminalColor.DEFAULT_BG,
        TerminalColor.rgb(0xFFAA33), decorationAttr);
    for (int column = 0; column < cells.length; column++) {
      cells[column] = new CellValue(
          String.valueOf((char) ('a' + ((column + row) % 26))), (byte) 1, style, null);
    }
  }

  private static void fillMixedDecoration(CellValue[] cells, int row) {
    int[] attrs = {1 << 3, 1 << 4, 1 << 5, 1 << 6, 1 << 7, 1 << 12};
    for (int column = 0; column < cells.length; column++) {
      StyleValue style = new StyleValue(
          TerminalColor.rgb(0x80C0FF + ((column / 4) & 0x1F) * 0x010000),
          TerminalColor.DEFAULT_BG, TerminalColor.rgb(0xFFAA33),
          attrs[(column / 4 + row) % attrs.length]);
      cells[column] = new CellValue("x", (byte) 1, style, null);
    }
  }

  private static void fillBackgroundTui(CellValue[] cells, int row) {
    String[] glyphs = {"A", "─", "█", "界", "⣿", "B", "░", "C"};
    int column = 0;
    int index = row % glyphs.length;
    while (column < cells.length) {
      String text = glyphs[index++ % glyphs.length];
      int width = ("界".equals(text)) ? 2 : 1;
      if (column + width > cells.length) break;
      int block = column / 16;
      StyleValue style = new StyleValue(
          TerminalColor.rgb(0xDDEEFF),
          TerminalColor.rgb((block & 1) == 0 ? 0x102030 : 0x203010),
          null, 0);
      cells[column] = new CellValue(text, (byte) width, style, null);
      if (width == 2) cells[column + 1] = CellValue.SPACER;
      column += width;
    }
  }

  private static void fillMixedTui(CellValue[] cells, int row) {
    String[] graphemes = {"A", "界", "─", "█", "⣿", "", "e\u0301", "😀"};
    int column = 0;
    int index = row % graphemes.length;
    while (column < cells.length) {
      String text = graphemes[index++ % graphemes.length];
      byte width = (byte) (("界".equals(text) || "😀".equals(text)) ? 2 : 1);
      if (column + width > cells.length) break;
      int attrs = (column / 8 + row) % 5 == 0 ? (1 << 3) : 0;
      cells[column] = new CellValue(text, width,
          new StyleValue(TerminalColor.rgb(0xBBDDFF), TerminalColor.DEFAULT_BG,
              TerminalColor.rgb(0xFFAA33), attrs), null);
      if (width == 2) cells[column + 1] = CellValue.SPACER;
      column += width;
    }
  }

  private static void fillBlankHeavy(CellValue[] cells, int row) {
    String[] commands = {"pwd", "git status", "ls -la /data/local/tmp", "npm run build"};
    String command = commands[row % commands.length];
    for (int column = 0; column < command.length(); column++) {
      cells[column] = new CellValue(String.valueOf(command.charAt(column)), (byte) 1, null, null);
    }
    // 保留少量内部空格，验证空格只能在 run 内物化，尾部空格不能进入 cluster。
    if (command.length() + 3 < COLUMNS) {
      cells[command.length() + 1] = CellValue.EMPTY;
      cells[command.length() + 2] = new CellValue("x", (byte) 1, null, null);
    }
  }

  private static void fillDenseAscii(CellValue[] cells, int row, StyleValue style) {
    for (int column = 0; column < cells.length; column++) {
      char value = (char) ('A' + ((column + row) % 26));
      cells[column] = new CellValue(String.valueOf(value), (byte) 1, style, null);
    }
  }

  private static void fillStyledAscii(CellValue[] cells, int row) {
    StyleValue[] styles = {
        new StyleValue(TerminalColor.rgb(0xDDEEFF), TerminalColor.DEFAULT_BG, null, 0),
        new StyleValue(TerminalColor.rgb(0xFFCC66), TerminalColor.DEFAULT_BG, null, 1),
        new StyleValue(TerminalColor.rgb(0x99EE99), TerminalColor.DEFAULT_BG, null, 1 << 2),
        new StyleValue(TerminalColor.rgb(0xFF9999), TerminalColor.DEFAULT_BG, null, 1 << 0 | 1 << 2)
    };
    for (int column = 0; column < cells.length; column++) {
      char value = (char) ('a' + ((column + row) % 26));
      cells[column] = new CellValue(
          String.valueOf(value), (byte) 1, styles[(column / 8) % styles.length], null);
    }
  }

  private static void fillDenseUnicode(CellValue[] cells, int row) {
    String[] graphemes = {"A", "界", "😀", "e\u0301", "हि", "ع"};
    int column = 0;
    int index = row % graphemes.length;
    while (column < cells.length) {
      String text = graphemes[index++ % graphemes.length];
      byte width = (byte) (("界".equals(text) || "😀".equals(text)) ? 2 : 1);
      if (column + width > cells.length) break;
      cells[column] = new CellValue(text, width, null, null);
      if (width == 2) cells[column + 1] = CellValue.SPACER;
      column += width;
    }
  }

  private static void fillSpecialGlyphs(CellValue[] cells, int row) {
    String[] glyphs = {"─", "│", "┌", "█", "▒", "⣿", "", ""};
    for (int column = 0; column < cells.length; column++) {
      cells[column] = new CellValue(glyphs[(column + row) % glyphs.length], (byte) 1, null, null);
    }
  }
}
