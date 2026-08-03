package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;

/** 将 U+2800..U+28FF 的低八位直接绘制为稳定的 2×4 Braille 点阵。 */
final class BrailleGlyphPainter {
  private static final int FIRST = 0x2800;
  private static final int LAST = 0x28FF;
  // Visual order is left/right by row, while Unicode assigns dots 1..8 as
  // left 1,2,3,7 and right 4,5,6,8.
  private static final int[] DOT_BITS = {0, 3, 1, 4, 2, 5, 6, 7};

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  BrailleGlyphPainter() {
    paint.setStyle(Paint.Style.FILL);
  }

  static boolean supports(int codePoint) {
    return codePoint >= FIRST && codePoint <= LAST;
  }

  boolean draw(Canvas canvas, int codePoint, int left, int top, int right, int bottom,
               int foreground) {
    if (!supports(codePoint) || left >= right || top >= bottom) return false;
    paint.setColor(foreground);
    int mask = codePoint - FIRST;
    float width = right - left;
    float height = bottom - top;
    float radius = Math.max(1f, Math.min(width / 2f, height / 4f) * 0.28f);
    float leftX = left + width * 0.25f;
    float rightX = left + width * 0.75f;
    for (int dot = 0; dot < DOT_BITS.length; dot++) {
      if ((mask & (1 << DOT_BITS[dot])) == 0) continue;
      float x = (dot % 2 == 0) ? leftX : rightX;
      int row = dot / 2;
      float y = top + height * ((row * 2 + 1) / 8f);
      canvas.drawCircle(x, y, radius, paint);
    }
    return true;
  }
}
