package com.webterm.terminal.renderer;

import android.graphics.Canvas;

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

  boolean supports(String grapheme) {
    return isEnabled(familyFor(grapheme));
  }

  boolean drawIfSupported(Canvas canvas, String grapheme,
                          int left, int top, int right, int bottom, int foreground) {
    int codePoint = singleCodePoint(grapheme);
    Family family = familyForCodePoint(codePoint);
    if (!isEnabled(family)) return false;

    int saveCount = canvas.save();
    canvas.clipRect(left, top, right, bottom);
    try {
      switch (family) {
        case BOX_DRAWING:
          boxDrawingPainter.draw(canvas, codePoint, left, top, right, bottom, foreground);
          break;
        case BLOCK_ELEMENTS:
          blockElementPainter.draw(canvas, codePoint, left, top, right, bottom, foreground);
          break;
        case BRAILLE:
          braillePainter.draw(canvas, codePoint, left, top, right, bottom, foreground);
          break;
        case POWERLINE:
          powerlinePainter.draw(canvas, codePoint, left, top, right, bottom, foreground);
          break;
        case NONE:
          return false;
      }
      return true;
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
