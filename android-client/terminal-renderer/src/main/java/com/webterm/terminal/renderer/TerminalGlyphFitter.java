package com.webterm.terminal.renderer;

/**
 * 决定普通 grapheme 在终端 cell 内的放置方式。
 *
 * <p>这里不重新判断服务端 width；width 只由编译器传入。该类只根据 grapheme 的
 * presentation 特征决定是否居中，并保证任何缩放都是等比且不放大。</p>
 */
final class TerminalGlyphFitter {
  enum ClusterFitMode {
    GRID_START,
    CENTERED
  }

  static final class FitResult {
    float scale = 1f;
    float drawX;
    float baselineY;
  }

  static ClusterFitMode fitMode(String grapheme, int serverWidth) {
    if (serverWidth >= 2) return ClusterFitMode.CENTERED;
    if (grapheme == null || grapheme.isEmpty()) return ClusterFitMode.GRID_START;
    for (int offset = 0; offset < grapheme.length(); ) {
      int codePoint = grapheme.codePointAt(offset);
      if (isCenteredPresentation(codePoint)) return ClusterFitMode.CENTERED;
      offset += Character.charCount(codePoint);
    }
    return ClusterFitMode.GRID_START;
  }

  void fit(FitResult out, float measuredWidth, float expectedWidth,
           float gridStartX, float baselineY, ClusterFitMode mode) {
    out.scale = 1f;
    out.drawX = gridStartX;
    out.baselineY = baselineY;
    if (measuredWidth <= 0f || expectedWidth <= 0f) return;

    if (measuredWidth > expectedWidth) {
      out.scale = Math.min(1f, expectedWidth / measuredWidth);
      return;
    }
    if (mode == ClusterFitMode.CENTERED) {
      out.drawX = gridStartX + (expectedWidth - measuredWidth) / 2f;
    }
  }

  private static boolean isCenteredPresentation(int codePoint) {
    return (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
        || (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF)
        || (codePoint >= 0x2600 && codePoint <= 0x27BF)
        || (codePoint >= 0xE000 && codePoint <= 0xF8FF)
        || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
        || codePoint == 0x200D
        || codePoint == 0x20E3
        || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
        || (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF);
  }
}
