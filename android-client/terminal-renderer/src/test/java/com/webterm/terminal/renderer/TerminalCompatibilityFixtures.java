package com.webterm.terminal.renderer;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.model.TerminalColor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 服务端终端解析结果的 Android 兼容性夹具。
 *
 * <p>这里的宽度是夹具数据的一部分，不根据 code point 或 Android 字体重新推导。
 * 例如宽字符由一个 width=2 的 grapheme 加一个显式 spacer 构成；这模拟的是
 * {@code LineBodyDecoder} 已经完成的服务端语义结果。</p>
 */
final class TerminalCompatibilityFixtures {
  enum Category {
    ASCII,
    COMBINING,
    CJK,
    ARABIC,
    INDIC,
    EMOJI,
    BOX_DRAWING,
    BLOCK_ELEMENTS,
    BRAILLE,
    POWERLINE,
    ANSI_STYLE,
    EDGE_CASE
  }

  /** 不可变的单行 CellValue 夹具；数组在入参和 accessor 两侧都做防御性复制。 */
  record Fixture(String name, int columns, CellValue[] cells, Category category) {
    Fixture {
      if (name == null || name.isEmpty() || columns < 1 || cells == null
          || cells.length != columns || category == null) {
        throw new IllegalArgumentException("invalid compatibility fixture");
      }
      cells = Arrays.copyOf(cells, cells.length);
    }

    @Override
    public CellValue[] cells() {
      return Arrays.copyOf(cells, cells.length);
    }
  }

  private static final int BOLD = 1 << 0;
  private static final int DIM = 1 << 1;
  private static final int ITALIC = 1 << 2;
  private static final int UNDERLINE = 1 << 3;
  private static final int DOUBLE_UNDERLINE = 1 << 4;
  private static final int CURLY_UNDERLINE = 1 << 5;
  private static final int DOTTED_UNDERLINE = 1 << 6;
  private static final int DASHED_UNDERLINE = 1 << 7;
  private static final int SLOW_BLINK = 1 << 8;
  private static final int FAST_BLINK = 1 << 9;
  private static final int REVERSE = 1 << 10;
  private static final int HIDDEN = 1 << 11;
  private static final int STRIKE = 1 << 12;

  private TerminalCompatibilityFixtures() {}

  static List<Fixture> all() {
    List<Fixture> fixtures = new ArrayList<>();
    fixtures.add(ascii());
    fixtures.add(asciiLigatures());
    fixtures.add(combining());
    fixtures.add(cjkChinese());
    fixtures.add(cjkJapanese());
    fixtures.add(cjkKorean());
    fixtures.add(arabic());
    fixtures.add(indic());
    fixtures.add(emoji());
    fixtures.addAll(boxDrawing());
    fixtures.add(codePointRange("box-drawing-full-range", Category.BOX_DRAWING,
        0x2500, 0x257F));
    fixtures.addAll(blockElements());
    fixtures.add(codePointRange("block-elements-full-range", Category.BLOCK_ELEMENTS,
        0x2580, 0x259F));
    fixtures.add(braille());
    fixtures.add(codePointRange("braille-full-range", Category.BRAILLE,
        0x2800, 0x28FF));
    fixtures.add(powerline());
    fixtures.addAll(ansiStyles());
    fixtures.addAll(edgeCases());
    return List.copyOf(fixtures);
  }

  /** 原文：{@code ABC xyz 012 -> != == => git status /path/to/file}；全为 U+0020..U+007E。 */
  private static Fixture ascii() {
    return row("ascii", Category.ASCII, 80,
        asciiCells("ABC xyz 012  -> != == =>  git status  /path/to/file"));
  }

  /** 原文：{@code ffi fi fl}；记录可能触发字体 ligature 的连续 ASCII run。 */
  private static Fixture asciiLigatures() {
    return row("ascii-ligature-sequences", Category.ASCII, 24,
        asciiCells("ffi fi fl == != =>"));
  }

  /**
   * 原文：{@code é ā ñ}；cells 为 [U+0065 U+0301]、[U+0061 U+0304]、
   * [U+006E U+0303]，每个组合序列都是服务端已经合并的单个 width=1 cell。
   */
  private static Fixture combining() {
    return row("combining", Category.COMBINING, 12,
        cell("e\u0301", 1), cell("a\u0304", 1), cell("n\u0303", 1));
  }

  /** 原文：{@code 中文测试}；cells 为 U+4E2D、U+6587、U+6D4B、U+8BD5，均为 width=2。 */
  private static Fixture cjkChinese() {
    return row("cjk-chinese", Category.CJK, 16,
        wide("中"), wide("文"), wide("测"), wide("试"));
  }

  /** 原文：{@code 日本語}；cells 为 U+65E5、U+672C、U+8A9E，spacer 来自服务端 cell 语义。 */
  private static Fixture cjkJapanese() {
    return row("cjk-japanese", Category.CJK, 12,
        wide("日"), wide("本"), wide("語"));
  }

  /** 原文：{@code 한글}；cells 为 U+D55C、U+AE00，每个预组装 grapheme 占两列。 */
  private static Fixture cjkKorean() {
    return row("cjk-korean", Category.CJK, 10,
        wide("한"), wide("글"));
  }

  /**
   * 原文：{@code العربية}；cells 为 U+0627、U+0644、U+0639、U+0631、U+0628、U+064A、
   * U+0629。Arabic shaping 留给系统字体，夹具只固化服务端 cell。
   */
  private static Fixture arabic() {
    return row("arabic", Category.ARABIC, 16,
        cell("ا", 1), cell("ل", 1), cell("ع", 1), cell("ر", 1),
        cell("ب", 1), cell("ي", 1), cell("ة", 1));
  }

  /**
   * 原文：{@code हिन्दी}；按服务端 grapheme 结果固化为 [U+0939 U+093F]、
   * [U+0928 U+094D]、[U+0926 U+0940]。
   */
  private static Fixture indic() {
    return row("indic", Category.INDIC, 12,
        cell("हि", 1), cell("न्", 1), cell("दी", 1));
  }

  /**
   * 原文依次为 {@code 😀 ❤️ 👨‍👩‍👧‍👦 1️⃣ 🇨🇳 👍🏽}；覆盖单码点、VS、ZWJ、keycap、
   * regional indicator 和 skin-tone modifier。对应 code point 依次为 U+1F600、
   * [U+2764 U+FE0F]、[U+1F468 U+200D U+1F469 U+200D U+1F467 U+200D U+1F466]、
   * [U+0031 U+FE0F U+20E3]、[U+1F1E8 U+1F1F3]、[U+1F44D U+1F3FD]；每个 grapheme
   * 的服务端宽度均显式为 2。
   */
  private static Fixture emoji() {
    return row("emoji", Category.EMOJI, 12,
        wide("😀"), wide("❤️"), wide("👨‍👩‍👧‍👦"), wide("1️⃣"),
        wide("🇨🇳"), wide("👍🏽"));
  }

  /**
   * Box Drawing 原文为上下边框行；例如 U+250C/U+2500/U+2510、U+2502、U+251C/U+2524、
   * U+2514/U+2518，以及双线 U+2554/U+2550/U+2557、U+2551、U+2560/U+2563、
   * U+255A/U+255D；字符均为服务端 width=1 cell。
   */
  private static List<Fixture> boxDrawing() {
    return List.of(
        bmpSymbols("box-top", Category.BOX_DRAWING, "┌────┐"),
        bmpSymbols("box-middle", Category.BOX_DRAWING, "│    │"),
        bmpSymbols("box-divider", Category.BOX_DRAWING, "├────┤"),
        bmpSymbols("box-bottom", Category.BOX_DRAWING, "└────┘"),
        bmpSymbols("box-double-top", Category.BOX_DRAWING, "╔════╗"),
        bmpSymbols("box-double-middle", Category.BOX_DRAWING, "║    ║"),
        bmpSymbols("box-double-bottom", Category.BOX_DRAWING, "╚════╝"));
  }

  /** Block Elements 原文覆盖 U+2588、U+2580、U+2584、U+258C、U+2590、阴影 U+2591..U+2593 和渐变块 U+2581..U+2588。 */
  private static List<Fixture> blockElements() {
    return List.of(
        bmpSymbols("block-basic", Category.BLOCK_ELEMENTS, "█ ▀ ▄ ▌ ▐"),
        bmpSymbols("block-shades", Category.BLOCK_ELEMENTS, "░ ▒ ▓"),
        bmpSymbols("block-lower-gradient", Category.BLOCK_ELEMENTS, "▁▂▃▄▅▆▇█"));
  }

  /** Braille 原文：U+2801、U+2808、U+2840、U+2880、U+28FF、U+28F7、U+28E4、U+2800；字体 fallback 行为只做记录。 */
  private static Fixture braille() {
    return new Fixture("braille", 20,
        rowCells(20, bmpCells("⠁ ⠈ ⡀ ⢀ ⣿⣷⣤⡀")), Category.BRAILLE);
  }

  /** Powerline 原文 code point：U+E0B0、U+E0B2、U+E0B6、U+E0B4，均作为已识别范围的 width=1 cell。 */
  private static Fixture powerline() {
    return row("powerline", Category.POWERLINE, 12,
        cell("\uE0B0", 1), cell("\uE0B2", 1), cell("\uE0B6", 1), cell("\uE0B4", 1));
  }

  /** ANSI 样式每行单独记录，第一阶段只刻画当前输出，不把未实现装饰当正确 Golden。 */
  private static List<Fixture> ansiStyles() {
    return List.of(
        styled("ansi-bold", BOLD),
        styled("ansi-dim", DIM),
        styled("ansi-italic", ITALIC),
        styled("ansi-underline", UNDERLINE),
        styled("ansi-double-underline", DOUBLE_UNDERLINE),
        styled("ansi-curly-underline", CURLY_UNDERLINE),
        styled("ansi-dotted-underline", DOTTED_UNDERLINE),
        styled("ansi-dashed-underline", DASHED_UNDERLINE),
        styled("ansi-strike", STRIKE),
        styled("ansi-hidden", HIDDEN),
        styled("ansi-slow-blink", SLOW_BLINK),
        styled("ansi-fast-blink", FAST_BLINK),
        styled("ansi-reverse", REVERSE),
        new Fixture("ansi-underline-color", 16,
            rowCells(16, styledCell("A", UNDERLINE,
                TerminalColor.rgb(0xFFAA00))), Category.ANSI_STYLE));
  }

  /** 边界夹具按使用场景命名，避免把 Android 测试里的宽度判断混进来。 */
  private static List<Fixture> edgeCases() {
    List<Fixture> fixtures = new ArrayList<>();
    fixtures.add(row("edge-italic-first-column", Category.EDGE_CASE, 8,
        styledCell("A", ITALIC)));
    fixtures.add(atColumn("edge-italic-last-column", Category.EDGE_CASE, 8, 7,
        styledCell("Z", ITALIC)));
    fixtures.add(atColumn("edge-wide-last-two-columns", Category.EDGE_CASE, 8, 6,
        wide("界")));
    // 该 fixture 作为屏幕第一行使用，专门记录顶部 emoji 的裁切风险。
    fixtures.add(row("edge-top-emoji", Category.EDGE_CASE, 8, wide("😀")));
    fixtures.add(row("edge-bottom-descenders", Category.EDGE_CASE, 8,
        asciiCells("gjy")));
    fixtures.add(fullBackgroundRow());
    fixtures.add(row("edge-all-blank", Category.EDGE_CASE, 80));
    fixtures.add(row("edge-continuous-ascii-80", Category.EDGE_CASE, 80,
        asciiCells(continuousAscii(80))));
    return fixtures;
  }

  private static Fixture styled(String name, int attrs) {
    return row(name, Category.ANSI_STYLE, 16, styledCell("A", attrs));
  }

  private static Fixture fullBackgroundRow() {
    StyleValue style = new StyleValue(
        TerminalColor.rgb(0xFFFFFF), TerminalColor.rgb(0x182030), null, 0);
    CellValue[] cells = new CellValue[32];
    Arrays.fill(cells, new CellValue(" ", (byte) 1, style, null));
    return new Fixture("edge-full-background", cells.length, cells, Category.EDGE_CASE);
  }

  private static CellValue styledCell(String text, int attrs) {
    return styledCell(text, attrs, null);
  }

  private static CellValue styledCell(String text, int attrs, TerminalColor underlineColor) {
    return new CellValue(text, (byte) 1,
        new StyleValue(TerminalColor.rgb(0xF5F5F5), TerminalColor.DEFAULT_BG,
            underlineColor, attrs), null);
  }

  private static CellValue cell(String text, int width) {
    return new CellValue(text, (byte) width, null, null);
  }

  private static CellValue wide(String text) {
    return cell(text, 2);
  }

  private static Fixture row(String name, Category category, int columns, CellValue... content) {
    return new Fixture(name, columns, rowCells(columns, content), category);
  }

  private static Fixture atColumn(String name, Category category, int columns, int startColumn,
                                  CellValue... content) {
    CellValue[] cells = blankCells(columns);
    int column = startColumn;
    for (CellValue cell : content) {
      if (cell == null || column < 0 || column + cell.width() > columns) {
        throw new IllegalArgumentException("fixture content exceeds columns");
      }
      cells[column] = cell;
      if (cell.width() == 2) {
        cells[column + 1] = CellValue.SPACER;
      }
      column += Math.max(1, cell.width());
    }
    return new Fixture(name, columns, cells, category);
  }

  private static CellValue[] rowCells(int columns, CellValue... content) {
    CellValue[] cells = blankCells(columns);
    int column = 0;
    for (CellValue cell : content) {
      if (cell == null || column + cell.width() > columns) {
        throw new IllegalArgumentException("fixture content exceeds columns");
      }
      cells[column] = cell;
      if (cell.width() == 2) {
        cells[column + 1] = CellValue.SPACER;
      }
      column += Math.max(1, cell.width());
    }
    return cells;
  }

  /** Each element is explicitly declared as width=1; this helper never decides Unicode width. */
  private static CellValue[] asciiCells(String text) {
    CellValue[] cells = new CellValue[text.length()];
    for (int i = 0; i < text.length(); i++) {
      cells[i] = cell(String.valueOf(text.charAt(i)), 1);
    }
    return cells;
  }

  /** All input symbols in these samples are BMP and explicitly declared as width=1. */
  private static Fixture bmpSymbols(String name, Category category, String text) {
    return row(name, category,
        Math.max(1, text.length()), bmpCells(text));
  }

  private static CellValue[] bmpCells(String text) {
    CellValue[] cells = new CellValue[text.length()];
    for (int i = 0; i < text.length(); i++) {
      cells[i] = cell(String.valueOf(text.charAt(i)), 1);
    }
    return cells;
  }

  /** 固化一个服务端已经决定为 width=1 的 BMP code point 范围。 */
  private static Fixture codePointRange(String name, Category category,
                                        int firstCodePoint, int lastCodePoint) {
    int count = lastCodePoint - firstCodePoint + 1;
    CellValue[] cells = new CellValue[count];
    for (int index = 0; index < count; index++) {
      cells[index] = cell(String.valueOf((char) (firstCodePoint + index)), 1);
    }
    return new Fixture(name, count, cells, category);
  }

  private static CellValue[] blankCells(int columns) {
    CellValue[] cells = new CellValue[columns];
    Arrays.fill(cells, CellValue.EMPTY);
    return cells;
  }

  private static String continuousAscii(int length) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    StringBuilder text = new StringBuilder(length);
    for (int i = 0; i < length; i++) text.append(alphabet.charAt(i % alphabet.length()));
    return text.toString();
  }
}
