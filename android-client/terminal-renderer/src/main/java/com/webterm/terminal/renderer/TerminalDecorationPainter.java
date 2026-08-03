package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** 绘制 ANSI decoration；Paint/Path 在 renderer 线程内复用，不进入逐 cell 分配热路径。 */
final class TerminalDecorationPainter {
  private static final float CURLY_WAVELENGTH_PX = 8f;
  private static final float DOTTED_PHASE_PX = 0.75f;
  private static final float DOTTED_PERIOD_PX = 3f;
  private static final int DASHED_PERIOD_PX = 7;
  private static final int DASHED_LENGTH_PX = 4;
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

    int saveCount = canvas.save();
    canvas.clipRect(left, rowTop, right, rowBottom);
    try {
      paint.setColor(style.underlineColor);
      switch (style.underlineKind) {
        case SINGLE:
          drawLine(canvas, left - 1, right + 1, rowBottom - 2);
          break;
        case DOUBLE:
          drawLine(canvas, left - 1, right + 1, rowBottom - 2);
          drawLine(canvas, left - 1, right + 1, rowBottom - 5);
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
        drawLine(canvas, left - 1, right + 1, strikeY);
      }
    } finally {
      canvas.restoreToCount(saveCount);
    }
  }

  private void drawLine(Canvas canvas, int left, int right, float y) {
    canvas.drawLine(left, y, right, y, paint);
  }

  private void drawCurly(Canvas canvas, int left, int right, float baseY) {
    int start = left - 2;
    int end = right + 2;
    float amplitude = 1f;
    curlyPath.reset();
    // Use one-pixel subpaths rather than one long contour. Each segment is aligned to the
    // same absolute X phase, so clipping a run into multiple spans cannot change a join or
    // the anti-aliased pixels at the split boundary.
    for (int x = start; x < end; x++) {
      curlyPath.moveTo(x, curlyY(x, baseY, amplitude));
      curlyPath.lineTo(x + 1, curlyY(x + 1, baseY, amplitude));
    }
    canvas.drawPath(curlyPath, paint);
  }

  private static float curlyY(int x, float baseY, float amplitude) {
    float phase = (float) (x * Math.PI * 2d / CURLY_WAVELENGTH_PX);
    return baseY + (float) Math.sin(phase) * amplitude;
  }

  private void drawDotted(Canvas canvas, int left, int right, float y) {
    paint.setStyle(Paint.Style.FILL);
    paint.setStrokeCap(Paint.Cap.ROUND);
    float radius = 0.75f;
    int firstIndex = (int) Math.floor(
        (left - radius - DOTTED_PHASE_PX) / DOTTED_PERIOD_PX) - 1;
    float x = DOTTED_PHASE_PX + firstIndex * DOTTED_PERIOD_PX;
    float last = right + radius;
    for (; x <= last; x += DOTTED_PERIOD_PX) {
      canvas.drawCircle(x, y, radius, paint);
    }
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.BUTT);
  }

  private void drawDashed(Canvas canvas, int left, int right, float y) {
    int firstIndex = (int) Math.floor((left - DASHED_LENGTH_PX) / (float) DASHED_PERIOD_PX) - 1;
    int start = firstIndex * DASHED_PERIOD_PX;
    int last = right + DASHED_LENGTH_PX;
    for (; start <= last; start += DASHED_PERIOD_PX) {
      canvas.drawLine(start, y, start + DASHED_LENGTH_PX, y, paint);
    }
  }
}
