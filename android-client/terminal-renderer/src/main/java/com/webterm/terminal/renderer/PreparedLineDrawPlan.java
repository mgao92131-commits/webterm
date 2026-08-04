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
  final int[] backgroundEndSpanIndexes;
  final int[] backgroundStartPx;
  final int[] backgroundEndPx;
  final int[] backgroundColors;
  final int[] decorationSpanIndexes;
  final int[] specialGlyphSpanIndexes;
  final PreparedDecorationRun[] staticDecorationRuns;
  final PreparedDecorationRun[] slowBlinkDecorationRuns;
  final PreparedDecorationRun[] fastBlinkDecorationRuns;
  final PreparedSpecialGlyphRun[] staticSpecialGlyphRuns;
  final PreparedSpecialGlyphRun[] slowBlinkSpecialGlyphRuns;
  final PreparedSpecialGlyphRun[] fastBlinkSpecialGlyphRuns;

  private final long estimatedBytes;

  private PreparedLineDrawPlan(
      int[] spanLeftPx,
      int[] spanRightPx,
      int[] staticForegroundSpanIndexes,
      int[] slowBlinkSpanIndexes,
      int[] fastBlinkSpanIndexes,
      int[] backgroundSpanIndexes,
      int[] backgroundEndSpanIndexes,
      int[] backgroundStartPx,
      int[] backgroundEndPx,
      int[] backgroundColors,
      int[] decorationSpanIndexes,
      int[] specialGlyphSpanIndexes,
      PreparedDecorationRun[] staticDecorationRuns,
      PreparedDecorationRun[] slowBlinkDecorationRuns,
      PreparedDecorationRun[] fastBlinkDecorationRuns,
      PreparedSpecialGlyphRun[] staticSpecialGlyphRuns,
      PreparedSpecialGlyphRun[] slowBlinkSpecialGlyphRuns,
      PreparedSpecialGlyphRun[] fastBlinkSpecialGlyphRuns) {
    this.spanLeftPx = spanLeftPx;
    this.spanRightPx = spanRightPx;
    this.staticForegroundSpanIndexes = staticForegroundSpanIndexes;
    this.slowBlinkSpanIndexes = slowBlinkSpanIndexes;
    this.fastBlinkSpanIndexes = fastBlinkSpanIndexes;
    this.backgroundSpanIndexes = backgroundSpanIndexes;
    this.backgroundEndSpanIndexes = backgroundEndSpanIndexes;
    this.backgroundStartPx = backgroundStartPx;
    this.backgroundEndPx = backgroundEndPx;
    this.backgroundColors = backgroundColors;
    this.decorationSpanIndexes = decorationSpanIndexes;
    this.specialGlyphSpanIndexes = specialGlyphSpanIndexes;
    this.staticDecorationRuns = staticDecorationRuns;
    this.slowBlinkDecorationRuns = slowBlinkDecorationRuns;
    this.fastBlinkDecorationRuns = fastBlinkDecorationRuns;
    this.staticSpecialGlyphRuns = staticSpecialGlyphRuns;
    this.slowBlinkSpecialGlyphRuns = slowBlinkSpecialGlyphRuns;
    this.fastBlinkSpecialGlyphRuns = fastBlinkSpecialGlyphRuns;
    estimatedBytes = 64L
        + arrayBytes(spanLeftPx)
        + arrayBytes(spanRightPx)
        + arrayBytes(staticForegroundSpanIndexes)
        + arrayBytes(slowBlinkSpanIndexes)
        + arrayBytes(fastBlinkSpanIndexes)
        + arrayBytes(backgroundSpanIndexes)
        + arrayBytes(backgroundEndSpanIndexes)
        + arrayBytes(backgroundStartPx)
        + arrayBytes(backgroundEndPx)
        + arrayBytes(backgroundColors)
        + arrayBytes(decorationSpanIndexes)
        + arrayBytes(specialGlyphSpanIndexes)
        + objectArrayBytes(staticDecorationRuns)
        + objectArrayBytes(slowBlinkDecorationRuns)
        + objectArrayBytes(fastBlinkDecorationRuns)
        + objectArrayBytes(staticSpecialGlyphRuns)
        + objectArrayBytes(slowBlinkSpecialGlyphRuns)
        + objectArrayBytes(fastBlinkSpecialGlyphRuns);
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
    int staticDecorationRunCount = 0;
    int slowDecorationRunCount = 0;
    int fastDecorationRunCount = 0;
    int staticSpecialRunCount = 0;
    int slowSpecialRunCount = 0;
    int fastSpecialRunCount = 0;
    int previousBackgroundSpan = -1;
    int previousBackgroundRight = 0;
    int previousBackgroundColor = 0;
    int previousDecorationSpan = -1;
    ResolvedTerminalStyle.UnderlineKind previousUnderlineKind = null;
    int previousUnderlineColor = 0;
    boolean previousStrike = false;
    int previousStrikeColor = 0;
    CompiledTerminalLine.BlinkKind previousBlinkKind = CompiledTerminalLine.BlinkKind.NONE;
    for (int i = 0; i < spans.size(); i++) {
      CompiledTerminalLine.Span span = spans.get(i);
      CompiledTerminalLine.CompiledStyle style = span.style();
      if (!style.hidden()) {
        if (style.blinkFast()) fastCount++;
        else if (style.blinkSlow()) slowCount++;
        else staticCount++;
      }
      if (style.background() != canvasBackground) {
        int left = geometry.columnEdgePx(span.startColumn());
        if (previousBackgroundSpan < 0
            || previousBackgroundSpan + 1 != i
            || previousBackgroundRight != left
            || previousBackgroundColor != style.background()) {
          backgroundCount++;
        }
        previousBackgroundSpan = i;
        previousBackgroundRight = geometry.columnEdgePx(span.endColumn());
        previousBackgroundColor = style.background();
      } else {
        previousBackgroundSpan = -1;
      }
      if (style.hasVisibleDecoration()) decorationCount++;
      if (style.hasVisibleDecoration()) {
        CompiledTerminalLine.BlinkKind blinkKind = style.blinkKind();
        boolean sameRun = previousDecorationSpan + 1 == i
            && previousUnderlineKind == style.underlineKind()
            && previousUnderlineColor == style.underlineColor()
            && previousStrike == style.strike()
            && previousStrikeColor == style.foreground()
            && previousBlinkKind == blinkKind;
        if (!sameRun) {
          if (blinkKind == CompiledTerminalLine.BlinkKind.FAST) fastDecorationRunCount++;
          else if (blinkKind == CompiledTerminalLine.BlinkKind.SLOW) slowDecorationRunCount++;
          else staticDecorationRunCount++;
        }
        previousDecorationSpan = i;
        previousUnderlineKind = style.underlineKind();
        previousUnderlineColor = style.underlineColor();
        previousStrike = style.strike();
        previousStrikeColor = style.foreground();
        previousBlinkKind = blinkKind;
      } else {
        previousDecorationSpan = -1;
        previousUnderlineKind = null;
        previousBlinkKind = CompiledTerminalLine.BlinkKind.NONE;
      }
      if (span instanceof CompiledTerminalLine.SpecialGlyphSpan) specialCount++;
      if (span instanceof CompiledTerminalLine.SpecialGlyphSpan) {
        boolean sameRun = i > 0
            && spans.get(i - 1) instanceof CompiledTerminalLine.SpecialGlyphSpan
            && spans.get(i - 1).endColumn() == span.startColumn()
            && spans.get(i - 1).style().equals(style)
            && TerminalSpecialGlyphPainter.clipPolicy(
                TerminalSpecialGlyphPainter.familyForCodePoint(
                    ((CompiledTerminalLine.SpecialGlyphSpan) spans.get(i - 1)).codePoint()))
                == TerminalSpecialGlyphPainter.clipPolicy(
                TerminalSpecialGlyphPainter.familyForCodePoint(
                    ((CompiledTerminalLine.SpecialGlyphSpan) span).codePoint()));
        if (!sameRun) {
          if (style.blinkKind() == CompiledTerminalLine.BlinkKind.FAST) fastSpecialRunCount++;
          else if (style.blinkKind() == CompiledTerminalLine.BlinkKind.SLOW) slowSpecialRunCount++;
          else staticSpecialRunCount++;
        }
      }
    }

    int[] left = new int[spanCount];
    int[] right = new int[spanCount];
    int[] staticIndexes = new int[staticCount];
    int[] slowIndexes = new int[slowCount];
    int[] fastIndexes = new int[fastCount];
    int[] backgroundIndexes = new int[backgroundCount];
    int[] backgroundEndIndexes = new int[backgroundCount];
    int[] backgroundStart = new int[backgroundCount];
    int[] backgroundEnd = new int[backgroundCount];
    int[] backgroundColors = new int[backgroundCount];
    int[] decorationIndexes = new int[decorationCount];
    int[] specialIndexes = new int[specialCount];
    PreparedDecorationRun[] staticDecorationRuns =
        new PreparedDecorationRun[staticDecorationRunCount];
    PreparedDecorationRun[] slowDecorationRuns =
        new PreparedDecorationRun[slowDecorationRunCount];
    PreparedDecorationRun[] fastDecorationRuns =
        new PreparedDecorationRun[fastDecorationRunCount];
    PreparedSpecialGlyphRun[] staticSpecialRuns =
        new PreparedSpecialGlyphRun[staticSpecialRunCount];
    PreparedSpecialGlyphRun[] slowSpecialRuns =
        new PreparedSpecialGlyphRun[slowSpecialRunCount];
    PreparedSpecialGlyphRun[] fastSpecialRuns =
        new PreparedSpecialGlyphRun[fastSpecialRunCount];
    int staticIndex = 0;
    int slowIndex = 0;
    int fastIndex = 0;
    int backgroundIndex = 0;
    int decorationIndex = 0;
    int specialIndex = 0;
    int decorationSourceIndex = 0;
    int staticDecorationIndex = 0;
    int slowDecorationIndex = 0;
    int fastDecorationIndex = 0;
    for (int i = 0; i < spanCount; i++) {
      CompiledTerminalLine.Span span = spans.get(i);
      CompiledTerminalLine.CompiledStyle style = span.style();
      left[i] = geometry.columnEdgePx(span.startColumn());
      right[i] = geometry.columnEdgePx(span.endColumn());
      if (!style.hidden()) {
        if (style.blinkFast()) fastIndexes[fastIndex++] = i;
        else if (style.blinkSlow()) slowIndexes[slowIndex++] = i;
        else staticIndexes[staticIndex++] = i;
      }
      if (style.background() != canvasBackground) {
        boolean startsRun = backgroundIndex == 0
            || backgroundEndIndexes[backgroundIndex - 1] != i
            || backgroundColors[backgroundIndex - 1] != style.background();
        if (startsRun) {
          backgroundIndexes[backgroundIndex] = i;
          backgroundStart[backgroundIndex] = left[i];
          backgroundColors[backgroundIndex] = style.background();
          backgroundIndex++;
        }
        backgroundEndIndexes[backgroundIndex - 1] = i + 1;
        backgroundEnd[backgroundIndex - 1] = right[i];
      }
      if (style.hasVisibleDecoration()) {
        decorationIndexes[decorationIndex++] = i;
        CompiledTerminalLine.BlinkKind blinkKind = style.blinkKind();
        PreparedDecorationRun[] target;
        int targetIndex;
        if (blinkKind == CompiledTerminalLine.BlinkKind.FAST) {
          target = fastDecorationRuns;
          targetIndex = fastDecorationIndex;
        } else if (blinkKind == CompiledTerminalLine.BlinkKind.SLOW) {
          target = slowDecorationRuns;
          targetIndex = slowDecorationIndex;
        } else {
          target = staticDecorationRuns;
          targetIndex = staticDecorationIndex;
        }
        PreparedDecorationRun run = targetIndex == 0 ? null : target[targetIndex - 1];
        boolean canExtend = run != null
            && run.endSpanIndexExclusive == i
            && run.underlineKind == style.underlineKind()
            && run.underlineColor == style.underlineColor()
            && run.strike == style.strike()
            && run.strikeColor == style.foreground();
        if (!canExtend) {
          run = new PreparedDecorationRun(
              i, i + 1, left[i], right[i], style.underlineKind(), style.underlineColor(),
              style.strike(), style.foreground(), blinkKind);
          target[targetIndex] = run;
          if (blinkKind == CompiledTerminalLine.BlinkKind.FAST) fastDecorationIndex++;
          else if (blinkKind == CompiledTerminalLine.BlinkKind.SLOW) slowDecorationIndex++;
          else staticDecorationIndex++;
        } else {
          run.endSpanIndexExclusive = i + 1;
          run.rightPx = right[i];
        }
        decorationSourceIndex++;
      }
      if (span instanceof CompiledTerminalLine.SpecialGlyphSpan) {
        specialIndexes[specialIndex++] = i;
      }
    }
    int staticSpecialIndex = 0;
    int slowSpecialIndex = 0;
    int fastSpecialIndex = 0;
    for (int start = 0; start < spanCount; ) {
      if (!(spans.get(start) instanceof CompiledTerminalLine.SpecialGlyphSpan)) {
        start++;
        continue;
      }
      CompiledTerminalLine.Span first = spans.get(start);
      int end = start + 1;
      while (end < spanCount
          && spans.get(end) instanceof CompiledTerminalLine.SpecialGlyphSpan
          && spans.get(end - 1).endColumn() == spans.get(end).startColumn()
          && first.style().equals(spans.get(end).style())
          && TerminalSpecialGlyphPainter.clipPolicy(
              TerminalSpecialGlyphPainter.familyForCodePoint(
                  ((CompiledTerminalLine.SpecialGlyphSpan) spans.get(end - 1)).codePoint()))
              == TerminalSpecialGlyphPainter.clipPolicy(
              TerminalSpecialGlyphPainter.familyForCodePoint(
                  ((CompiledTerminalLine.SpecialGlyphSpan) spans.get(end)).codePoint()))) {
        end++;
      }
      PreparedSpecialGlyphRun run = PreparedSpecialGlyphRun.build(
          spans, start, end, left, right);
      if (first.style().blinkKind() == CompiledTerminalLine.BlinkKind.FAST) {
        fastSpecialRuns[fastSpecialIndex++] = run;
      } else if (first.style().blinkKind() == CompiledTerminalLine.BlinkKind.SLOW) {
        slowSpecialRuns[slowSpecialIndex++] = run;
      } else {
        staticSpecialRuns[staticSpecialIndex++] = run;
      }
      start = end;
    }
    return new PreparedLineDrawPlan(
        left, right, staticIndexes, slowIndexes, fastIndexes, backgroundIndexes,
        backgroundEndIndexes,
        backgroundStart, backgroundEnd, backgroundColors, decorationIndexes, specialIndexes,
        staticDecorationRuns, slowDecorationRuns, fastDecorationRuns,
        staticSpecialRuns, slowSpecialRuns, fastSpecialRuns);
  }

  long estimatedBytes() {
    return estimatedBytes;
  }

  private static long arrayBytes(int[] array) {
    return 16L + (long) array.length * Integer.BYTES;
  }

  private static long objectArrayBytes(Object[] array) {
    long bytes = 16L + (long) array.length * 8L;
    for (Object value : array) {
      if (value instanceof PreparedDecorationRun) {
        bytes += ((PreparedDecorationRun) value).estimatedBytes();
      } else if (value instanceof PreparedSpecialGlyphRun) {
        bytes += ((PreparedSpecialGlyphRun) value).estimatedBytes();
      }
    }
    return bytes;
  }
}
