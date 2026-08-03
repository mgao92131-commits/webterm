package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** 使用整数 cell 边界绘制 U+2580..U+259F Block Elements。 */
final class BlockElementGlyphPainter {
  private static final int FIRST = 0x2580;
  private static final int LAST = 0x259F;

  private final Paint paint = new Paint();
  private final Path shadePath = new Path();

  static boolean supports(int codePoint) {
    return codePoint >= FIRST && codePoint <= LAST;
  }

  void draw(Canvas canvas, int codePoint, int left, int top, int right, int bottom,
            int foreground) {
    if (!supports(codePoint) || left >= right || top >= bottom) return;
    paint.setColor(foreground);
    paint.setStyle(Paint.Style.FILL);
    switch (codePoint) {
      case 0x2580: // upper half
        drawRect(canvas, left, top, right, edge(top, bottom, 4, 8));
        return;
      case 0x2581: // lower one eighth through lower seven eighths
      case 0x2582:
      case 0x2583:
      case 0x2584:
      case 0x2585:
      case 0x2586:
      case 0x2587:
      case 0x2588:
        drawRect(canvas, left, edge(top, bottom, 8 - (codePoint - 0x2580), 8),
            right, bottom);
        return;
      case 0x2589: // left seven eighths through left one eighth
      case 0x258A:
      case 0x258B:
      case 0x258C:
      case 0x258D:
      case 0x258E:
      case 0x258F:
        drawRect(canvas, left, top,
            edge(left, right, 8 - (codePoint - 0x2588), 8), bottom);
        return;
      case 0x2590: // right half
        drawRect(canvas, edge(left, right, 4, 8), top, right, bottom);
        return;
      case 0x2591:
      case 0x2592:
      case 0x2593:
        drawShade(canvas, codePoint - 0x2590, left, top, right, bottom);
        return;
      case 0x2594: // upper one eighth
        drawRect(canvas, left, top, right, edge(top, bottom, 1, 8));
        return;
      case 0x2595: // right one eighth
        drawRect(canvas, edge(left, right, 7, 8), top, right, bottom);
        return;
      default:
        drawQuadrant(canvas, codePoint, left, top, right, bottom);
    }
  }

  private void drawQuadrant(Canvas canvas, int codePoint,
                            int left, int top, int right, int bottom) {
    int middleX = edge(left, right, 4, 8);
    int middleY = edge(top, bottom, 4, 8);
    boolean upperLeft = codePoint == 0x2598 || codePoint == 0x2599
        || codePoint == 0x259A || codePoint == 0x259B || codePoint == 0x259C;
    boolean upperRight = codePoint == 0x259C
        || codePoint == 0x259D || codePoint == 0x259B;
    boolean lowerLeft = codePoint == 0x2596 || codePoint == 0x2599
        || codePoint == 0x259B || codePoint == 0x259E || codePoint == 0x259F;
    boolean lowerRight = codePoint == 0x2597 || codePoint == 0x2599
        || codePoint == 0x259A || codePoint == 0x259C || codePoint == 0x259F;
    if (upperLeft) drawRect(canvas, left, top, middleX, middleY);
    if (upperRight) drawRect(canvas, middleX, top, right, middleY);
    if (lowerLeft) drawRect(canvas, left, middleY, middleX, bottom);
    if (lowerRight) drawRect(canvas, middleX, middleY, right, bottom);
  }

  /** Shade pattern is anchored to absolute Canvas pixels, not restarted per cell. */
  private void drawShade(Canvas canvas, int shade, int left, int top, int right, int bottom) {
    shadePath.reset();
    for (int y = top; y < bottom; y++) {
      for (int x = left; x < right; x++) {
        boolean filled;
        if (shade == 1) {
          filled = (x & 1) == 0 && (y & 1) == 0;
        } else if (shade == 2) {
          filled = ((x + y) & 1) == 0;
        } else {
          filled = !((x & 1) == 1 && (y & 1) == 1);
        }
        if (filled) shadePath.addRect(x, y, x + 1, y + 1, Path.Direction.CW);
      }
    }
    canvas.drawPath(shadePath, paint);
  }

  private void drawRect(Canvas canvas, int left, int top, int right, int bottom) {
    if (left < right && top < bottom) canvas.drawRect(left, top, right, bottom, paint);
  }

  private static int edge(int start, int end, int numerator, int denominator) {
    return start + Math.round((end - start) * numerator / (float) denominator);
  }
}
