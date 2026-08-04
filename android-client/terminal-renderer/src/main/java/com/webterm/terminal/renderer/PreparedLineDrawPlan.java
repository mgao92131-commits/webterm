package com.webterm.terminal.renderer;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * 当前字体和 cell 几何下的一行绘制计划。
 *
 * <p>第一版只把每个 Span 的物理边界和分类索引预计算出来；绘制顺序和 Canvas 操作仍
 * 与第二轮逐 Span 路径相同。后续提交再逐项消费这些索引，避免把多个优化混成一个不可
 * 回滚的行为变化。</p>
 */
final class PreparedLineDrawPlan {
  final int[] spanLeftPx;
  final int[] spanRightPx;
  final int[] staticForegroundSpanIndexes;
  final int[] slowBlinkSpanIndexes;
  final int[] fastBlinkSpanIndexes;
  final int[] backgroundSpanIndexes;
  final int[] backgroundStartPx;
  final int[] backgroundEndPx;
  final int[] backgroundColors;
  final int[] decorationSpanIndexes;
  final int[] specialGlyphSpanIndexes;

  private final long estimatedBytes;

  private PreparedLineDrawPlan(
      int[] spanLeftPx,
      int[] spanRightPx,
      int[] staticForegroundSpanIndexes,
      int[] slowBlinkSpanIndexes,
      int[] fastBlinkSpanIndexes,
      int[] backgroundSpanIndexes,
      int[] backgroundStartPx,
      int[] backgroundEndPx,
      int[] backgroundColors,
      int[] decorationSpanIndexes,
      int[] specialGlyphSpanIndexes) {
    this.spanLeftPx = spanLeftPx;
    this.spanRightPx = spanRightPx;
    this.staticForegroundSpanIndexes = staticForegroundSpanIndexes;
    this.slowBlinkSpanIndexes = slowBlinkSpanIndexes;
    this.fastBlinkSpanIndexes = fastBlinkSpanIndexes;
    this.backgroundSpanIndexes = backgroundSpanIndexes;
    this.backgroundStartPx = backgroundStartPx;
    this.backgroundEndPx = backgroundEndPx;
    this.backgroundColors = backgroundColors;
    this.decorationSpanIndexes = decorationSpanIndexes;
    this.specialGlyphSpanIndexes = specialGlyphSpanIndexes;
    estimatedBytes = 64L
        + arrayBytes(spanLeftPx)
        + arrayBytes(spanRightPx)
        + arrayBytes(staticForegroundSpanIndexes)
        + arrayBytes(slowBlinkSpanIndexes)
        + arrayBytes(fastBlinkSpanIndexes)
        + arrayBytes(backgroundSpanIndexes)
        + arrayBytes(backgroundStartPx)
        + arrayBytes(backgroundEndPx)
        + arrayBytes(backgroundColors)
        + arrayBytes(decorationSpanIndexes)
        + arrayBytes(specialGlyphSpanIndexes);
  }

  static PreparedLineDrawPlan build(
      @NonNull CompiledTerminalLine line,
      @NonNull TerminalCellGeometry geometry,
      int canvasBackground) {
    List<CompiledTerminalLine.Span> spans = line.spans();
    int spanCount = spans.size();
    int staticCount = 0;
    int slowCount = 0;
    int fastCount = 0;
    int backgroundCount = 0;
    int decorationCount = 0;
    int specialCount = 0;
    for (CompiledTerminalLine.Span span : spans) {
      CompiledTerminalLine.CompiledStyle style = span.style();
      if (!style.hidden() && !style.hasBlink()) staticCount++;
      if (!style.hidden() && style.blinkSlow()) slowCount++;
      if (!style.hidden() && style.blinkFast()) fastCount++;
      if (style.background() != canvasBackground) backgroundCount++;
      if (style.hasVisibleDecoration()) decorationCount++;
      if (span instanceof CompiledTerminalLine.SpecialGlyphSpan) specialCount++;
    }

    int[] left = new int[spanCount];
    int[] right = new int[spanCount];
    int[] staticIndexes = new int[staticCount];
    int[] slowIndexes = new int[slowCount];
    int[] fastIndexes = new int[fastCount];
    int[] backgroundIndexes = new int[backgroundCount];
    int[] backgroundStart = new int[backgroundCount];
    int[] backgroundEnd = new int[backgroundCount];
    int[] backgroundColors = new int[backgroundCount];
    int[] decorationIndexes = new int[decorationCount];
    int[] specialIndexes = new int[specialCount];
    int staticIndex = 0;
    int slowIndex = 0;
    int fastIndex = 0;
    int backgroundIndex = 0;
    int decorationIndex = 0;
    int specialIndex = 0;
    for (int i = 0; i < spanCount; i++) {
      CompiledTerminalLine.Span span = spans.get(i);
      CompiledTerminalLine.CompiledStyle style = span.style();
      left[i] = geometry.columnEdgePx(span.startColumn());
      right[i] = geometry.columnEdgePx(span.endColumn());
      if (!style.hidden() && !style.hasBlink()) staticIndexes[staticIndex++] = i;
      if (!style.hidden() && style.blinkSlow()) slowIndexes[slowIndex++] = i;
      if (!style.hidden() && style.blinkFast()) fastIndexes[fastIndex++] = i;
      if (style.background() != canvasBackground) {
        backgroundIndexes[backgroundIndex] = i;
        backgroundStart[backgroundIndex] = left[i];
        backgroundEnd[backgroundIndex] = right[i];
        backgroundColors[backgroundIndex] = style.background();
        backgroundIndex++;
      }
      if (style.hasVisibleDecoration()) decorationIndexes[decorationIndex++] = i;
      if (span instanceof CompiledTerminalLine.SpecialGlyphSpan) {
        specialIndexes[specialIndex++] = i;
      }
    }
    return new PreparedLineDrawPlan(
        left, right, staticIndexes, slowIndexes, fastIndexes, backgroundIndexes,
        backgroundStart, backgroundEnd, backgroundColors, decorationIndexes, specialIndexes);
  }

  long estimatedBytes() {
    return estimatedBytes;
  }

  private static long arrayBytes(int[] array) {
    return 16L + (long) array.length * Integer.BYTES;
  }
}
