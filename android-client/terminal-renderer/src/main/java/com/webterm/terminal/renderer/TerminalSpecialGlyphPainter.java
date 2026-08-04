package com.webterm.terminal.renderer;

import android.graphics.Canvas;

import androidx.annotation.Nullable;

/**
 * 特殊终端字符的统一分派入口。
 *
 * <p>服务端已经完成 grapheme 合并和 cell width 决策；这里仅消费恰好一个 code point
 * 的 grapheme。多 codepoint 序列始终返回 {@code false}，由 renderer 继续使用原有字体
 * 路径。</p>
 */
final class TerminalSpecialGlyphPainter {
  // 开发期诊断开关：关闭某一族时返回 false，让现有字体 fallback 接管该 code point。
  private static final boolean DRAW_BOX = true;
  private static final boolean DRAW_BLOCK = true;
  private static final boolean DRAW_BRAILLE = true;
  private static final boolean DRAW_POWERLINE = true;

  enum Family {
    NONE,
    BOX_DRAWING,
    BLOCK_ELEMENTS,
    BRAILLE,
    POWERLINE
  }

  static Family familyFor(String grapheme) {
    int codePoint = singleCodePoint(grapheme);
    if (codePoint < 0) return Family.NONE;
    return familyForCodePoint(codePoint);
  }

  static Family familyForCodePoint(int codePoint) {
    if (codePoint >= 0x2500 && codePoint <= 0x257F) {
      return Family.BOX_DRAWING;
    }
    if (codePoint >= 0x2580 && codePoint <= 0x259F) {
      return Family.BLOCK_ELEMENTS;
    }
    if (codePoint >= 0x2800 && codePoint <= 0x28FF) {
      return Family.BRAILLE;
    }
    if (codePoint == 0xE0B0 || codePoint == 0xE0B2
        || codePoint == 0xE0B4 || codePoint == 0xE0B6) {
      return Family.POWERLINE;
    }
    return Family.NONE;
  }

  /** 只有恰好一个 Unicode code point 的 grapheme 才能进入特殊字符分派。 */
  static int singleCodePoint(String grapheme) {
    if (grapheme == null || grapheme.isEmpty()
        || grapheme.codePointCount(0, grapheme.length()) != 1) {
      return -1;
    }
    return grapheme.codePointAt(0);
  }

  private final BoxDrawingGlyphPainter boxDrawingPainter = new BoxDrawingGlyphPainter();
  private final BlockElementGlyphPainter blockElementPainter = new BlockElementGlyphPainter();
  private final BrailleGlyphPainter braillePainter = new BrailleGlyphPainter();
  private final PowerlineGlyphPainter powerlinePainter = new PowerlineGlyphPainter();
  @Nullable private final RendererFrameWorkStats workStats;

  TerminalSpecialGlyphPainter() {
    this(null);
  }

  TerminalSpecialGlyphPainter(@Nullable RendererFrameWorkStats workStats) {
    this.workStats = workStats;
  }

  boolean supports(String grapheme) {
    return supportsGrapheme(grapheme);
  }

  static boolean supportsGrapheme(String grapheme) {
    return supportsCodePoint(singleCodePoint(grapheme));
  }

  static boolean supportsCodePoint(int codePoint) {
    return isEnabled(familyForCodePoint(codePoint));
  }

  boolean drawIfSupported(Canvas canvas, String grapheme,
                          int left, int top, int right, int bottom, int foreground) {
    return drawIfSupported(canvas, grapheme, left, top, right, bottom, foreground,
        0, 0, 0, right - left);
  }

  /**
   * Draws using a stable terminal phase. X is in the terminal coordinate space; Y is relative
   * to the line being recorded so a direct Canvas fallback matches a translated RenderNode.
   */
  boolean drawIfSupported(Canvas canvas, String grapheme,
                          int left, int top, int right, int bottom, int foreground,
                          int phaseX, int phaseY) {
    return drawIfSupported(canvas, grapheme, left, top, right, bottom, foreground,
        phaseX, phaseY, 0, right - left);
  }

  /**
   * Draws with the physical phase used by pixel patterns and the logical column/cell width used
   * by dashed Box glyphs. The latter avoids deriving dash periods from rounded cell edges.
   */
  boolean drawIfSupported(Canvas canvas, String grapheme,
                          int left, int top, int right, int bottom, int foreground,
                          int phaseX, int phaseY, int column, float nominalCellWidth) {
    int codePoint = singleCodePoint(grapheme);
    return drawCodePointIfSupported(canvas, codePoint, left, top, right, bottom, foreground,
        phaseX, phaseY, column, nominalCellWidth);
  }

  /** 编译后的 SpecialGlyphSpan 已经完成单 code point 校验，直接走 painter 分派。 */
  boolean drawCodePointIfSupported(Canvas canvas, int codePoint,
                                   int left, int top, int right, int bottom, int foreground,
                                   int phaseX, int phaseY, int column,
                                   float nominalCellWidth) {
    Family family = familyForCodePoint(codePoint);
    if (!isEnabled(family) || left >= right || top >= bottom) return false;

    if (workStats != null) {
      workStats.specialGlyphCount++;
      // Round 3 baseline: the old implementation has one run and one clip per glyph.
      workStats.specialGlyphRunCount++;
      workStats.specialGlyphFamilyDispatchCount++;
      workStats.specialGlyphCellClipCount++;
      workStats.specialGlyphRunClipCount++;
    }

    int saveCount = canvas.save();
    canvas.clipRect(left, top, right, bottom);
    try {
      boolean drawn = false;
      switch (family) {
        case BOX_DRAWING:
          drawn = boxDrawingPainter.draw(canvas, codePoint, left, top, right, bottom, foreground,
              phaseX, phaseY, column, nominalCellWidth);
          break;
        case BLOCK_ELEMENTS:
          drawn = blockElementPainter.draw(canvas, codePoint, left, top, right, bottom, foreground,
              phaseX, phaseY);
          break;
        case BRAILLE:
          drawn = braillePainter.draw(canvas, codePoint, left, top, right, bottom, foreground);
          break;
        case POWERLINE:
          drawn = powerlinePainter.draw(canvas, codePoint, left, top, right, bottom, foreground);
          break;
        case NONE:
          return false;
      }
      return drawn;
    } finally {
      canvas.restoreToCount(saveCount);
    }
  }

  private static boolean isEnabled(Family family) {
    switch (family) {
      case BOX_DRAWING: return DRAW_BOX;
      case BLOCK_ELEMENTS: return DRAW_BLOCK;
      case BRAILLE: return DRAW_BRAILLE;
      case POWERLINE: return DRAW_POWERLINE;
      case NONE: return false;
    }
    return false;
  }
}
