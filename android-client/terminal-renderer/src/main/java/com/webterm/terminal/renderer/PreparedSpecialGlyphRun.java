package com.webterm.terminal.renderer;

import androidx.annotation.NonNull;

import java.util.List;

/** 连续、同样式特殊 glyph 的预分类绘制单元。第一版仍保留每 cell clip。 */
final class PreparedSpecialGlyphRun {
  final int startSpanIndex;
  final int endSpanIndexExclusive;
  final int leftPx;
  final int rightPx;
  final CompiledTerminalLine.CompiledStyle style;
  final int[] codePoints;
  final byte[] families;
  final int[] columns;
  final int[] glyphLeftPx;
  final int[] glyphRightPx;

  private PreparedSpecialGlyphRun(
      int startSpanIndex,
      int endSpanIndexExclusive,
      int leftPx,
      int rightPx,
      CompiledTerminalLine.CompiledStyle style,
      int[] codePoints,
      byte[] families,
      int[] columns,
      int[] glyphLeftPx,
      int[] glyphRightPx) {
    this.startSpanIndex = startSpanIndex;
    this.endSpanIndexExclusive = endSpanIndexExclusive;
    this.leftPx = leftPx;
    this.rightPx = rightPx;
    this.style = style;
    this.codePoints = codePoints;
    this.families = families;
    this.columns = columns;
    this.glyphLeftPx = glyphLeftPx;
    this.glyphRightPx = glyphRightPx;
  }

  static PreparedSpecialGlyphRun build(
      @NonNull List<CompiledTerminalLine.Span> spans,
      int start,
      int end,
      @NonNull int[] spanLeftPx,
      @NonNull int[] spanRightPx) {
    int count = end - start;
    int[] codePoints = new int[count];
    byte[] families = new byte[count];
    int[] columns = new int[count];
    int[] left = new int[count];
    int[] right = new int[count];
    for (int i = 0; i < count; i++) {
      CompiledTerminalLine.SpecialGlyphSpan span =
          (CompiledTerminalLine.SpecialGlyphSpan) spans.get(start + i);
      codePoints[i] = span.codePoint();
      families[i] = (byte) TerminalSpecialGlyphPainter
          .familyForCodePoint(span.codePoint()).ordinal();
      columns[i] = span.startColumn();
      left[i] = spanLeftPx[start + i];
      right[i] = spanRightPx[start + i];
    }
    return new PreparedSpecialGlyphRun(
        start, end, spanLeftPx[start], spanRightPx[end - 1], spans.get(start).style(),
        codePoints, families, columns, left, right);
  }

  int glyphCount() {
    return codePoints.length;
  }

  long estimatedBytes() {
    return 80L
        + (long) codePoints.length * Integer.BYTES * 3L
        + (long) columns.length * Integer.BYTES
        + families.length;
  }
}
