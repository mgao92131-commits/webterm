package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** 绘制 ANSI decoration；Paint/Path 在 renderer 线程内复用，不进入逐 cell 分配热路径。 */
final class TerminalDecorationPainter {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path curlyPath = new Path();

  TerminalDecorationPainter() {
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1f);
    paint.setStrokeCap(Paint.Cap.BUTT);
    paint.setStrokeJoin(Paint.Join.ROUND);
  }

  void draw(Canvas canvas, ResolvedTerminalStyle style,
            int left, int right, int rowTop, int rowBottom) {
    if (style == null || style.hidden || left >= right || rowBottom <= rowTop) return;

    paint.setColor(style.underlineColor);
    switch (style.underlineKind) {
      case SINGLE:
        drawLine(canvas, left, right, rowBottom - 2);
        break;
      case DOUBLE:
        drawLine(canvas, left, right, rowBottom - 2);
        drawLine(canvas, left, right, rowBottom - 5);
        break;
      case CURLY:
        drawCurly(canvas, left, right, rowBottom - 2);
        break;
      case DOTTED:
        drawDotted(canvas, left, right, rowBottom - 2);
        break;
      case DASHED:
        drawDashed(canvas, left, right, rowBottom - 2);
        break;
      case NONE:
        break;
    }

    if (style.strike) {
      paint.setColor(style.foreground);
      float strikeY = rowTop + (rowBottom - rowTop) * 0.52f;
      drawLine(canvas, left, right, strikeY);
    }
  }

  private void drawLine(Canvas canvas, int left, int right, float y) {
    canvas.drawLine(left, y, right, y, paint);
  }

  private void drawCurly(Canvas canvas, int left, int right, float baseY) {
    float width = right - left;
    float wavelength = Math.max(4f, Math.min(10f, width / 2f));
    float amplitude = 1f;
    curlyPath.reset();
    curlyPath.moveTo(left, baseY);
    for (int x = left + 1; x <= right; x++) {
      float phase = (float) ((x - left) * Math.PI * 2d / wavelength);
      curlyPath.lineTo(x, baseY + (float) Math.sin(phase) * amplitude);
    }
    canvas.drawPath(curlyPath, paint);
  }

  private void drawDotted(Canvas canvas, int left, int right, float y) {
    paint.setStyle(Paint.Style.FILL);
    paint.setStrokeCap(Paint.Cap.ROUND);
    float radius = 0.75f;
    for (float x = left + radius; x < right; x += 3f) {
      canvas.drawCircle(x, y, radius, paint);
    }
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.BUTT);
  }

  private void drawDashed(Canvas canvas, int left, int right, float y) {
    for (int start = left; start < right; start += 7) {
      canvas.drawLine(start, y, Math.min(right, start + 4), y, paint);
    }
  }
}
