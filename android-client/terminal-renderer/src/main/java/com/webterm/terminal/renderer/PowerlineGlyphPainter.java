package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** 仅自绘已经由兼容性 fixture 明确覆盖的四个 Powerline separator。 */
final class PowerlineGlyphPainter {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();

  PowerlineGlyphPainter() {
    paint.setStyle(Paint.Style.FILL);
  }

  static boolean supports(int codePoint) {
    return codePoint == 0xE0B0 || codePoint == 0xE0B2
        || codePoint == 0xE0B4 || codePoint == 0xE0B6;
  }

  boolean draw(Canvas canvas, int codePoint, int left, int top, int right, int bottom,
               int foreground) {
    if (!supports(codePoint) || left >= right || top >= bottom) return false;
    paint.setColor(foreground);
    float middleY = top + (bottom - top) / 2f;
    path.reset();
    switch (codePoint) {
      case 0xE0B0: // right triangular separator
        path.moveTo(left, top);
        path.lineTo(right, middleY);
        path.lineTo(left, bottom);
        path.close();
        break;
      case 0xE0B2: // left triangular separator
        path.moveTo(right, top);
        path.lineTo(left, middleY);
        path.lineTo(right, bottom);
        path.close();
        break;
      case 0xE0B4: // right rounded separator
        path.moveTo(left, top);
        path.cubicTo(right, top, right, bottom, left, bottom);
        path.close();
        break;
      case 0xE0B6: // left rounded separator
        path.moveTo(right, top);
        path.cubicTo(left, top, left, bottom, right, bottom);
        path.close();
        break;
      default:
        return false;
    }
    canvas.drawPath(path, paint);
    return true;
  }
}
