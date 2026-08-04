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

    default boolean intersectsColumns(int firstColumn, int lastColumnExclusive) {
      return firstColumn < endColumn() && lastColumnExclusive > startColumn();
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

    boolean hasBlink() {
      return blinkSlow || blinkFast;
    }

    BlinkKind blinkKind() {
      if (blinkFast) return BlinkKind.FAST;
      if (blinkSlow) return BlinkKind.SLOW;
      return BlinkKind.NONE;
    }

    boolean hasVisibleDecoration() {
      return !hidden && hasDecoration();
    }
  }

  enum BlinkKind {
    NONE,
    SLOW,
    FAST
  }

  static final class TextSpan implements Span {
    private final int startColumn;
    private final int columnCount;
    private final CompiledStyle style;
    private final TerminalFontRole fontRole;
    private final String text;
    private final int[] clusterUtf16Offsets;
    private final int[] clusterColumns;
    private final byte[] clusterWidths;
    private final byte[] fitModes;

    TextSpan(
        int startColumn,
        int columnCount,
        CompiledStyle style,
        String text,
        int[] clusterUtf16Offsets,
        int[] clusterColumns,
        byte[] clusterWidths,
        boolean[] preserveAspect) {
      this(startColumn, columnCount, style, TerminalFontRole.MAIN_TEXT, text,
          clusterUtf16Offsets, clusterColumns, clusterWidths, preserveAspect);
    }

    TextSpan(
        int startColumn,
        int columnCount,
        CompiledStyle style,
        TerminalFontRole fontRole,
        String text,
        int[] clusterUtf16Offsets,
        int[] clusterColumns,
        byte[] clusterWidths,
        boolean[] preserveAspect) {
      this(startColumn, columnCount, style, fontRole, text, clusterUtf16Offsets, clusterColumns,
          clusterWidths, toFitModes(preserveAspect));
    }

    TextSpan(
        int startColumn,
        int columnCount,
        CompiledStyle style,
        String text,
        int[] clusterUtf16Offsets,
        int[] clusterColumns,
        byte[] clusterWidths,
        byte[] fitModes) {
      this(startColumn, columnCount, style, TerminalFontRole.MAIN_TEXT, text,
          clusterUtf16Offsets, clusterColumns, clusterWidths, fitModes);
    }

    TextSpan(
        int startColumn,
        int columnCount,
        CompiledStyle style,
        TerminalFontRole fontRole,
        String text,
        int[] clusterUtf16Offsets,
        int[] clusterColumns,
        byte[] clusterWidths,
        byte[] fitModes) {
      this(startColumn, columnCount, style, fontRole, text, clusterUtf16Offsets, clusterColumns,
          clusterWidths, fitModes, true);
    }

    private TextSpan(
        int startColumn,
        int columnCount,
        CompiledStyle style,
        TerminalFontRole fontRole,
        String text,
        int[] clusterUtf16Offsets,
        int[] clusterColumns,
        byte[] clusterWidths,
        byte[] fitModes,
        boolean copyArrays) {
      if (startColumn < 0 || columnCount <= 0 || text == null || style == null
          || fontRole == null) {
        throw new IllegalArgumentException("invalid text span");
      }
      int clusterCount = clusterUtf16Offsets.length;
      if (clusterCount == 0
          || clusterColumns.length != clusterCount
          || clusterWidths.length != clusterCount
          || fitModes.length != clusterCount) {
        throw new IllegalArgumentException("invalid text cluster mapping");
      }
      this.startColumn = startColumn;
      this.columnCount = columnCount;
      this.style = style;
      this.fontRole = fontRole;
      this.text = text;
      this.clusterUtf16Offsets = copyArrays
          ? Arrays.copyOf(clusterUtf16Offsets, clusterCount) : clusterUtf16Offsets;
      this.clusterColumns = copyArrays
          ? Arrays.copyOf(clusterColumns, clusterCount) : clusterColumns;
      this.clusterWidths = copyArrays
          ? Arrays.copyOf(clusterWidths, clusterCount) : clusterWidths;
      this.fitModes = copyArrays ? Arrays.copyOf(fitModes, clusterCount) : fitModes;
    }

    /** 编译器已经放弃继续修改数组后调用；不会再做一次防御复制。 */
    static TextSpan takeOwnership(
        int startColumn,
        int columnCount,
        CompiledStyle style,
        TerminalFontRole fontRole,
        String text,
        int[] clusterUtf16Offsets,
        int[] clusterColumns,
        byte[] clusterWidths,
        byte[] fitModes) {
      return new TextSpan(startColumn, columnCount, style, fontRole, text,
          clusterUtf16Offsets, clusterColumns, clusterWidths, fitModes, false);
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

    TerminalFontRole fontRole() {
      return fontRole;
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
      return clusterFitMode(index) == TerminalGlyphFitter.ClusterFitMode.CENTERED;
    }

    TerminalGlyphFitter.ClusterFitMode clusterFitMode(int index) {
      return fitModes[index] == 0
          ? TerminalGlyphFitter.ClusterFitMode.GRID_START
          : TerminalGlyphFitter.ClusterFitMode.CENTERED;
    }

    private static byte[] toFitModes(boolean[] preserveAspect) {
      byte[] fitModes = new byte[preserveAspect.length];
      for (int i = 0; i < preserveAspect.length; i++) {
        fitModes[i] = preserveAspect[i] ? (byte) 1 : (byte) 0;
      }
      return fitModes;
    }

    int clusterIndexContainingColumn(int column) {
      for (int i = 0; i < clusterCount(); i++) {
        int start = clusterColumn(i);
        if (column >= start && column < start + clusterWidth(i)) return i;
      }
      return -1;
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
  private static final CompiledTerminalLine EMPTY =
      new CompiledTerminalLine(Collections.emptyList());

  CompiledTerminalLine(List<? extends Span> spans) {
    ArrayList<Span> copy = new ArrayList<>(spans.size());
    copy.addAll(spans);
    this.spans = Collections.unmodifiableList(copy);
  }

  private CompiledTerminalLine(ArrayList<Span> ownedSpans) {
    // 所有权已经从 compiler 转移；调用方不得继续修改 ownedSpans。
    this.spans = Collections.unmodifiableList(ownedSpans);
  }

  static CompiledTerminalLine takeOwnership(ArrayList<Span> spans) {
    return new CompiledTerminalLine(spans);
  }

  static CompiledTerminalLine empty() {
    return EMPTY;
  }

    List<Span> spans() {
    return spans;
  }

  Span spanContainingColumn(int column) {
    for (Span span : spans) {
      if (column >= span.startColumn() && column < span.endColumn()) return span;
    }
    return null;
  }

  int visibleBlinkKinds() {
    int kinds = 0;
    for (Span span : spans) {
      CompiledStyle style = span.style();
      if (!style.hasBlink() || style.hidden()) continue;
      // 编译器会保留带 decoration 的空格，但普通空格不会生成 span。
      if (span instanceof BlankStyleSpan && !style.hasDecoration()) continue;
      if (style.blinkSlow()) kinds |= TerminalLineCompiler.BLINK_SLOW;
      if (style.blinkFast()) kinds |= TerminalLineCompiler.BLINK_FAST;
    }
    return kinds;
  }
}
