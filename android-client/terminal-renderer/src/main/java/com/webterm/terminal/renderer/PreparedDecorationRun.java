package com.webterm.terminal.renderer;

/** 一段可以共享一次 decoration clip 的连续物理列范围。 */
final class PreparedDecorationRun {
  final int startSpanIndex;
  int endSpanIndexExclusive;
  final int leftPx;
  int rightPx;
  final ResolvedTerminalStyle.UnderlineKind underlineKind;
  final int underlineColor;
  final boolean strike;
  final int strikeColor;
  final CompiledTerminalLine.BlinkKind blinkKind;

  PreparedDecorationRun(
      int startSpanIndex,
      int endSpanIndexExclusive,
      int leftPx,
      int rightPx,
      ResolvedTerminalStyle.UnderlineKind underlineKind,
      int underlineColor,
      boolean strike,
      int strikeColor,
      CompiledTerminalLine.BlinkKind blinkKind) {
    this.startSpanIndex = startSpanIndex;
    this.endSpanIndexExclusive = endSpanIndexExclusive;
    this.leftPx = leftPx;
    this.rightPx = rightPx;
    this.underlineKind = underlineKind;
    this.underlineColor = underlineColor;
    this.strike = strike;
    this.strikeColor = strikeColor;
    this.blinkKind = blinkKind;
  }

  int sourceSpanCount() {
    return endSpanIndexExclusive - startSpanIndex;
  }

  long estimatedBytes() {
    return 64L;
  }
}
