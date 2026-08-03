package com.webterm.terminal.renderer;

import androidx.annotation.NonNull;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalPalette;

import java.util.ArrayList;
import java.util.List;

/** 把服务端语义 cell 编译成有序的行内绘制 Span。 */
final class TerminalLineCompiler {
  static final int BLINK_SLOW = 1;
  static final int BLINK_FAST = 1 << 1;

  private final TerminalStyleResolver styleResolver = new TerminalStyleResolver();
  private final ResolvedTerminalStyle styleScratch = new ResolvedTerminalStyle();
  private final TerminalFontResolver fontResolver;

  TerminalLineCompiler() {
    this(TerminalFontResolver.mainOnly());
  }

  TerminalLineCompiler(@NonNull TerminalFontResolver fontResolver) {
    this.fontResolver = fontResolver;
  }

  @NonNull
  CompiledTerminalLine compile(
      @NonNull RenderLine line,
      int columns,
      @NonNull TerminalPalette palette,
      int canvasBackground) {
    if (columns <= 0 || line.length() == 0) return CompiledTerminalLine.empty();

    int lineLength = Math.min(line.length(), columns);
    ArrayList<CompiledTerminalLine.Span> spans = new ArrayList<>();
    TextSpanBuilder textBuilder = null;
    BlankSpanBuilder blankBuilder = null;
    CompiledTerminalLine.CompiledStyle currentStyle = null;

    for (int column = 0; column < lineLength; ) {
      CellValue cell = line.at(column);
      if (cell == null || cell.isSpacer()) {
        textBuilder = flushText(textBuilder, spans);
        blankBuilder = flushBlank(blankBuilder, spans);
        column++;
        continue;
      }

      int width = Math.min(Math.max(1, cell.width()), columns - column);
      if (width <= 0) break;

      styleResolver.resolveInto(palette, cell.style(), false, styleScratch);
      if (currentStyle == null || !currentStyle.matches(styleScratch)) {
        textBuilder = flushText(textBuilder, spans);
        blankBuilder = flushBlank(blankBuilder, spans);
        currentStyle = CompiledTerminalLine.CompiledStyle.from(styleScratch);
      }

      String text = cell.text().isEmpty() ? " " : cell.text();
      int codePoint = TerminalSpecialGlyphPainter.singleCodePoint(text);
      boolean special = !styleScratch.hidden
          && TerminalSpecialGlyphPainter.supportsGrapheme(text);
      if (special) {
        textBuilder = flushText(textBuilder, spans);
        blankBuilder = flushBlank(blankBuilder, spans);
        spans.add(new CompiledTerminalLine.SpecialGlyphSpan(
            column, width, currentStyle, codePoint));
        column += width;
        continue;
      }

      boolean defaultVisualBlank = isDefaultVisualBlank(
          text, styleScratch, canvasBackground);
      if (styleScratch.hidden || (!defaultVisualBlank && " ".equals(text))) {
        textBuilder = flushText(textBuilder, spans);
        if (blankBuilder == null
            || blankBuilder.startColumn + blankBuilder.columnCount != column
            || blankBuilder.style != currentStyle) {
          blankBuilder = flushBlank(blankBuilder, spans);
          blankBuilder = new BlankSpanBuilder(column, currentStyle);
        }
        blankBuilder.columnCount += width;
        column += width;
        continue;
      }

      blankBuilder = flushBlank(blankBuilder, spans);
      TerminalFontRole fontRole = fontResolver.resolve(text);
      if (textBuilder == null
          || textBuilder.style != currentStyle
          || textBuilder.fontRole != fontRole
          || textBuilder.endColumn != column) {
        textBuilder = flushText(textBuilder, spans);
        textBuilder = new TextSpanBuilder(column, currentStyle, fontRole);
      }
      int graphemeStart = textBuilder.text.length();
      textBuilder.text.append(text);
      textBuilder.appendCluster(
          graphemeStart,
          column,
          (byte) width,
          (byte) (TerminalGlyphFitter.fitMode(fontRole)
              == TerminalGlyphFitter.ClusterFitMode.CENTERED ? 1 : 0),
          defaultVisualBlank);
      textBuilder.endColumn = column + width;
      column += width;
    }

    textBuilder = flushText(textBuilder, spans);
    blankBuilder = flushBlank(blankBuilder, spans);
    return new CompiledTerminalLine(spans);
  }

  /**
   * 只扫描样式和正文形态，不创建编译 Span。用于决定是否需要动态 blink 覆盖层。
   */
  boolean hasVisibleBlink(
      @NonNull RenderLine line,
      int columns,
      @NonNull TerminalPalette palette,
      int canvasBackground) {
    return visibleBlinkKinds(line, columns, palette, canvasBackground) != 0;
  }

  int visibleBlinkKinds(
      @NonNull RenderLine line,
      int columns,
      @NonNull TerminalPalette palette,
      int canvasBackground) {
    int lineLength = Math.min(line.length(), Math.max(0, columns));
    int kinds = 0;
    for (int column = 0; column < lineLength; ) {
      CellValue cell = line.at(column);
      if (cell == null || cell.isSpacer()) {
        column++;
        continue;
      }
      int width = Math.min(Math.max(1, cell.width()), lineLength - column);
      styleResolver.resolveInto(palette, cell.style(), false, styleScratch);
      boolean hasDecoration = styleScratch.strike
          || styleScratch.underlineKind != ResolvedTerminalStyle.UnderlineKind.NONE;
      String text = cell.text().isEmpty() ? " " : cell.text();
      boolean visible = !styleScratch.hidden
          && (!" ".equals(text) || hasDecoration);
      if (visible && styleScratch.blinkSlow) kinds |= BLINK_SLOW;
      if (visible && styleScratch.blinkFast) kinds |= BLINK_FAST;
      column += Math.max(1, width);
    }
    return kinds;
  }

  private static boolean isDefaultVisualBlank(
        String text, ResolvedTerminalStyle style, int canvasBackground) {
    return " ".equals(text)
        && !style.hidden
        && !style.strike
        && style.underlineKind == ResolvedTerminalStyle.UnderlineKind.NONE
        && style.background == canvasBackground;
  }

  private static TextSpanBuilder flushText(
      TextSpanBuilder builder, ArrayList<CompiledTerminalLine.Span> spans) {
    if (builder == null) return null;
    CompiledTerminalLine.TextSpan span = builder.build();
    if (span != null) spans.add(span);
    return null;
  }

  private static BlankSpanBuilder flushBlank(
      BlankSpanBuilder builder, ArrayList<CompiledTerminalLine.Span> spans) {
    if (builder == null) return null;
    spans.add(new CompiledTerminalLine.BlankStyleSpan(
        builder.startColumn, builder.columnCount, builder.style));
    return null;
  }

  private static final class BlankSpanBuilder {
    final int startColumn;
    final CompiledTerminalLine.CompiledStyle style;
    int columnCount;

    BlankSpanBuilder(int startColumn, CompiledTerminalLine.CompiledStyle style) {
      this.startColumn = startColumn;
      this.style = style;
    }
  }

  private static final class TextSpanBuilder {
    final int startColumn;
    final CompiledTerminalLine.CompiledStyle style;
    final TerminalFontRole fontRole;
    final StringBuilder text = new StringBuilder();
    int[] clusterUtf16Offsets = new int[8];
    int[] clusterColumns = new int[8];
    byte[] clusterWidths = new byte[8];
    byte[] fitModes = new byte[8];
    boolean[] trimEligible = new boolean[8];
    int clusterCount;
    int endColumn;

    TextSpanBuilder(
        int startColumn,
        CompiledTerminalLine.CompiledStyle style,
        TerminalFontRole fontRole) {
      this.startColumn = startColumn;
      this.style = style;
      this.fontRole = fontRole;
      this.endColumn = startColumn;
    }

    void appendCluster(
        int utf16Offset,
        int column,
        byte width,
        byte fitMode,
        boolean trim) {
      ensureCapacity(clusterCount + 1);
      clusterUtf16Offsets[clusterCount] = utf16Offset;
      clusterColumns[clusterCount] = column;
      clusterWidths[clusterCount] = width;
      fitModes[clusterCount] = fitMode;
      trimEligible[clusterCount] = trim;
      clusterCount++;
    }

    CompiledTerminalLine.TextSpan build() {
      int first = 0;
      while (first < clusterCount && trimEligible[first]) first++;
      int last = clusterCount - 1;
      while (last >= first && trimEligible[last]) last--;
      if (first > last) return null;

      int textStart = clusterUtf16Offsets[first];
      int textEnd = last + 1 < clusterCount
          ? clusterUtf16Offsets[last + 1] : text.length();
      int keptCount = last - first + 1;
      int[] keptOffsets = new int[keptCount];
      int[] keptColumns = new int[keptCount];
      byte[] keptWidths = new byte[keptCount];
      byte[] keptFitModes = new byte[keptCount];
      for (int i = 0; i < keptCount; i++) {
        int source = first + i;
        keptOffsets[i] = clusterUtf16Offsets[source] - textStart;
        keptColumns[i] = clusterColumns[source];
        keptWidths[i] = clusterWidths[source];
        keptFitModes[i] = fitModes[source];
      }
      return new CompiledTerminalLine.TextSpan(
          clusterColumns[first],
          clusterColumns[last] + clusterWidths[last] - clusterColumns[first],
          style,
          fontRole,
          text.substring(textStart, textEnd),
          keptOffsets,
          keptColumns,
          keptWidths,
          keptFitModes);
    }

    private void ensureCapacity(int required) {
      if (required <= clusterUtf16Offsets.length) return;
      int next = Math.max(required, clusterUtf16Offsets.length * 2);
      clusterUtf16Offsets = java.util.Arrays.copyOf(clusterUtf16Offsets, next);
      clusterColumns = java.util.Arrays.copyOf(clusterColumns, next);
      clusterWidths = java.util.Arrays.copyOf(clusterWidths, next);
      fitModes = java.util.Arrays.copyOf(fitModes, next);
      trimEligible = java.util.Arrays.copyOf(trimEligible, next);
    }
  }
}
