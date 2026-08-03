package com.webterm.terminal.renderer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一行终端正文的不可变编译结果。
 *
 * <p>Span 按终端物理列顺序排列。TextSpan 保存服务端提供的 grapheme 边界，不能把
 * grapheme 当作 code point 或 Java {@code char} 拆开；SpecialGlyphSpan 则只保存已经
 * 通过 Phase 3 分派规则确认的单一 code point。</p>
 */
final class CompiledTerminalLine {
  interface Span {
    int startColumn();

    int columnCount();

    CompiledStyle style();

    default int endColumn() {
      return startColumn() + columnCount();
    }
  }

  record CompiledStyle(
      int foreground,
      int background,
      int underlineColor,
      boolean bold,
      boolean dim,
      boolean italic,
      boolean hidden,
      boolean strike,
      boolean blinkSlow,
      boolean blinkFast,
      ResolvedTerminalStyle.UnderlineKind underlineKind
  ) {
    CompiledStyle {
      Objects.requireNonNull(underlineKind, "underlineKind");
    }

    static CompiledStyle from(ResolvedTerminalStyle source) {
      return new CompiledStyle(
          source.foreground,
          source.background,
          source.underlineColor,
          source.bold,
          source.dim,
          source.italic,
          source.hidden,
          source.strike,
          source.blinkSlow,
          source.blinkFast,
          source.underlineKind);
    }

    boolean matches(ResolvedTerminalStyle source) {
      return foreground == source.foreground
          && background == source.background
          && underlineColor == source.underlineColor
          && bold == source.bold
          && dim == source.dim
          && italic == source.italic
          && hidden == source.hidden
          && strike == source.strike
          && blinkSlow == source.blinkSlow
          && blinkFast == source.blinkFast
          && underlineKind == source.underlineKind;
    }

    boolean hasDecoration() {
      return strike || underlineKind != ResolvedTerminalStyle.UnderlineKind.NONE;
    }
  }

  static final class TextSpan implements Span {
    private final int startColumn;
    private final int columnCount;
    private final CompiledStyle style;
    private final String text;
    private final int[] clusterUtf16Offsets;
    private final int[] clusterColumns;
    private final byte[] clusterWidths;
    private final boolean[] preserveAspect;

    TextSpan(
        int startColumn,
        int columnCount,
        CompiledStyle style,
        String text,
        int[] clusterUtf16Offsets,
        int[] clusterColumns,
        byte[] clusterWidths,
        boolean[] preserveAspect) {
      if (startColumn < 0 || columnCount <= 0 || text == null || style == null) {
        throw new IllegalArgumentException("invalid text span");
      }
      int clusterCount = clusterUtf16Offsets.length;
      if (clusterCount == 0
          || clusterColumns.length != clusterCount
          || clusterWidths.length != clusterCount
          || preserveAspect.length != clusterCount) {
        throw new IllegalArgumentException("invalid text cluster mapping");
      }
      this.startColumn = startColumn;
      this.columnCount = columnCount;
      this.style = style;
      this.text = text;
      this.clusterUtf16Offsets = Arrays.copyOf(clusterUtf16Offsets, clusterCount);
      this.clusterColumns = Arrays.copyOf(clusterColumns, clusterCount);
      this.clusterWidths = Arrays.copyOf(clusterWidths, clusterCount);
      this.preserveAspect = Arrays.copyOf(preserveAspect, clusterCount);
    }

    @Override
    public int startColumn() {
      return startColumn;
    }

    @Override
    public int columnCount() {
      return columnCount;
    }

    @Override
    public CompiledStyle style() {
      return style;
    }

    String text() {
      return text;
    }

    int clusterCount() {
      return clusterUtf16Offsets.length;
    }

    int clusterUtf16Start(int index) {
      return clusterUtf16Offsets[index];
    }

    int clusterUtf16End(int index) {
      return index + 1 < clusterUtf16Offsets.length
          ? clusterUtf16Offsets[index + 1] : text.length();
    }

    int clusterColumn(int index) {
      return clusterColumns[index];
    }

    int clusterWidth(int index) {
      return clusterWidths[index];
    }

    boolean clusterPreserveAspect(int index) {
      return preserveAspect[index];
    }
  }

  static final class SpecialGlyphSpan implements Span {
    private final int startColumn;
    private final int columnCount;
    private final CompiledStyle style;
    private final int codePoint;

    SpecialGlyphSpan(int startColumn, int columnCount, CompiledStyle style, int codePoint) {
      if (startColumn < 0 || columnCount <= 0 || style == null || codePoint < 0) {
        throw new IllegalArgumentException("invalid special glyph span");
      }
      this.startColumn = startColumn;
      this.columnCount = columnCount;
      this.style = style;
      this.codePoint = codePoint;
    }

    @Override
    public int startColumn() {
      return startColumn;
    }

    @Override
    public int columnCount() {
      return columnCount;
    }

    @Override
    public CompiledStyle style() {
      return style;
    }

    int codePoint() {
      return codePoint;
    }
  }

  static final class BlankStyleSpan implements Span {
    private final int startColumn;
    private final int columnCount;
    private final CompiledStyle style;

    BlankStyleSpan(int startColumn, int columnCount, CompiledStyle style) {
      if (startColumn < 0 || columnCount <= 0 || style == null) {
        throw new IllegalArgumentException("invalid blank style span");
      }
      this.startColumn = startColumn;
      this.columnCount = columnCount;
      this.style = style;
    }

    @Override
    public int startColumn() {
      return startColumn;
    }

    @Override
    public int columnCount() {
      return columnCount;
    }

    @Override
    public CompiledStyle style() {
      return style;
    }
  }

  private final List<Span> spans;

  CompiledTerminalLine(List<? extends Span> spans) {
    ArrayList<Span> copy = new ArrayList<>(spans.size());
    copy.addAll(spans);
    this.spans = Collections.unmodifiableList(copy);
  }

  static CompiledTerminalLine empty() {
    return new CompiledTerminalLine(Collections.emptyList());
  }

  List<Span> spans() {
    return spans;
  }
}
