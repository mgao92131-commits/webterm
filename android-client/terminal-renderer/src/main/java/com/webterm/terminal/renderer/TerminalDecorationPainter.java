package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.annotation.Nullable;

/** 绘制 ANSI decoration；Paint/Path 在 renderer 线程内复用，不进入逐 cell 分配热路径。 */
final class TerminalDecorationPainter {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final TerminalDecorationPatternCache patternCache =
      new TerminalDecorationPatternCache();
  @Nullable private final RendererFrameWorkStats workStats;

  TerminalDecorationPainter() {
    this(null);
  }

  TerminalDecorationPainter(@Nullable RendererFrameWorkStats workStats) {
    this.workStats = workStats;
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1f);
    paint.setStrokeCap(Paint.Cap.BUTT);
    paint.setStrokeJoin(Paint.Join.ROUND);
  }

  void draw(Canvas canvas, ResolvedTerminalStyle style,
            int left, int right, int rowTop, int rowBottom) {
    if (!hasDecoration(style) || left >= right || rowBottom <= rowTop) return;

    if (workStats != null) {
      workStats.decorationSourceSpanCount++;
      workStats.decorationRunCount++;
      workStats.decorationClipCount++;
    }

    drawInternal(canvas, style.underlineKind, style.underlineColor, style.strike,
        style.foreground, left, right, rowTop, rowBottom);
  }

  void drawRun(Canvas canvas, PreparedDecorationRun run, int rowTop, int rowBottom) {
    if (run == null || run.leftPx >= run.rightPx || rowBottom <= rowTop) return;
    if (workStats != null) {
      workStats.decorationSourceSpanCount += run.sourceSpanCount();
      workStats.decorationRunCount++;
      workStats.decorationClipCount++;
    }
    drawInternal(canvas, run.underlineKind, run.underlineColor, run.strike,
        run.strikeColor, run.leftPx, run.rightPx, rowTop, rowBottom);
  }

  private void drawInternal(Canvas canvas,
                            ResolvedTerminalStyle.UnderlineKind underlineKind,
                            int underlineColor,
                            boolean strike,
                            int strikeColor,
                            int left,
                            int right,
                            int rowTop,
                            int rowBottom) {

    int saveCount = canvas.save();
    canvas.clipRect(left, rowTop, right, rowBottom);
    try {
      paint.setColor(underlineColor);
      switch (underlineKind) {
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

      if (strike) {
        paint.setColor(strikeColor);
        float strikeY = rowTop + (rowBottom - rowTop) * 0.52f;
        drawLine(canvas, left - 1, right + 1, strikeY);
      }
    } finally {
      canvas.restoreToCount(saveCount);
    }
  }

  static boolean hasDecoration(ResolvedTerminalStyle style) {
    return style != null && !style.hidden
        && (style.underlineKind != ResolvedTerminalStyle.UnderlineKind.NONE || style.strike);
  }

  private void drawLine(Canvas canvas, int left, int right, float y) {
    if (workStats != null) workStats.decorationPathDrawCount++;
    canvas.drawLine(left, y, right, y, paint);
  }

  private void drawCurly(Canvas canvas, int left, int right, float baseY) {
    Path path = patternCache.curly(right - left, left);
    if (workStats != null) {
      if (patternCache.wasLastLookupHit()) {
        workStats.curlyPatternCacheHitCount++;
      } else {
        workStats.curlyPatternBuildCount++;
        workStats.curlyPatternSegmentCount += patternCache.lastBuildSegmentCount();
      }
    }
    if (workStats != null) workStats.decorationPathDrawCount++;
    canvas.save();
    canvas.translate(left, baseY);
    canvas.drawPath(path, paint);
    canvas.restore();
  }

  private void drawDotted(Canvas canvas, int left, int right, float y) {
    paint.setStyle(Paint.Style.FILL);
    paint.setStrokeCap(Paint.Cap.ROUND);
    Path path = patternCache.dotted(right - left, left);
    if (workStats != null) {
      workStats.dottedPrimitiveCount++;
      workStats.decorationPathDrawCount++;
    }
    canvas.save();
    canvas.translate(left, y);
    canvas.drawPath(path, paint);
    canvas.restore();
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.BUTT);
  }

  private void drawDashed(Canvas canvas, int left, int right, float y) {
    Path path = patternCache.dashed(right - left, left);
    if (workStats != null) {
      workStats.dashedPrimitiveCount++;
      workStats.decorationPathDrawCount++;
    }
    canvas.save();
    canvas.translate(left, y);
    canvas.drawPath(path, paint);
    canvas.restore();
  }
}
