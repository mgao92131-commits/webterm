package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;

/** 使用服务端 grapheme 边界绘制带上下文的普通终端文字。 */
final class TerminalTextPainter {
  static final float RUN_ALIGNMENT_TOLERANCE_PX = 0.25f;
  private static final String DISABLED_LIGATURE_FEATURES = "'liga' 0, 'clig' 0, 'calt' 0";
  private final TerminalGlyphFitter glyphFitter = new TerminalGlyphFitter();
  private final TerminalGlyphFitter.FitResult fitScratch = new TerminalGlyphFitter.FitResult();

  void configureFont(@androidx.annotation.NonNull Paint paint) {
    // 保留 rlig/ccmp/mark/mkmk 等 shaping 特性，只关闭会跨终端 cell 合并的常见
    // discretionary/contextual ligature 特性。
    paint.setFontFeatureSettings(DISABLED_LIGATURE_FEATURES);
  }

  void draw(
      @androidx.annotation.NonNull Canvas canvas,
      @androidx.annotation.NonNull CompiledTerminalLine.TextSpan span,
      @androidx.annotation.NonNull TerminalCellGeometry geometry,
      float rowY,
      @androidx.annotation.NonNull Paint textPaint) {
    draw(canvas, span, geometry, rowY, textPaint, span.style().foreground(), false);
  }

  void draw(
      @androidx.annotation.NonNull Canvas canvas,
      @androidx.annotation.NonNull CompiledTerminalLine.TextSpan span,
      @androidx.annotation.NonNull TerminalCellGeometry geometry,
      float rowY,
      @androidx.annotation.NonNull Paint textPaint,
      int foregroundOverride,
      boolean useForegroundOverride) {
    CompiledTerminalLine.CompiledStyle style = span.style();
    applyStyle(textPaint, style, foregroundOverride, useForegroundOverride);
    if (style.hidden()) return;

    if (canDrawWholeRun(span, geometry, textPaint)) {
      canvas.drawTextRun(
          span.text(),
          0,
          span.text().length(),
          0,
          span.text().length(),
          geometry.textOriginX(span.startColumn()),
          rowY + geometry.baselineOffset(),
          false,
          textPaint);
      return;
    }

    drawClusters(canvas, span, geometry, rowY, textPaint);
  }

  private static void applyStyle(Paint paint, CompiledTerminalLine.CompiledStyle style,
                                 int foregroundOverride, boolean useForegroundOverride) {
    paint.setColor(useForegroundOverride ? foregroundOverride : style.foreground());
    paint.setFakeBoldText(style.bold());
    paint.setTextSkewX(style.italic() ? -0.35f : 0f);
  }

  private static boolean canDrawWholeRun(
      CompiledTerminalLine.TextSpan span,
      TerminalCellGeometry geometry,
      Paint paint) {
    String text = span.text();
    float runStartX = geometry.textOriginX(span.startColumn());
    for (int cluster = 0; cluster < span.clusterCount(); cluster++) {
      if (span.clusterFitMode(cluster) != TerminalGlyphFitter.ClusterFitMode.GRID_START) {
        return false;
      }
      int end = span.clusterUtf16End(cluster);
      float actualAdvance = prefixAdvance(paint, text, end);
      if (actualAdvance <= 0f) {
        actualAdvance = paint.measureText(text, 0, end);
      }
      // Robolectric and a few old platform shadows can report no advance. In that case the
      // renderer keeps the natural run path; the hardware/device path still performs the
      // strict boundary check with the real Paint implementation.
      if (actualAdvance <= 0f) continue;
      float expectedAdvance = geometry.textOriginX(
          span.clusterColumn(cluster) + span.clusterWidth(cluster)) - runStartX;
      if (Math.abs(actualAdvance - expectedAdvance) > RUN_ALIGNMENT_TOLERANCE_PX) {
        return false;
      }
    }
    return true;
  }

  private void drawClusters(
      Canvas canvas,
      CompiledTerminalLine.TextSpan span,
      TerminalCellGeometry geometry,
      float rowY,
      Paint paint) {
    String text = span.text();
    int contextStart = 0;
    int contextEnd = text.length();
    for (int cluster = 0; cluster < span.clusterCount(); cluster++) {
      int start = span.clusterUtf16Start(cluster);
      int end = span.clusterUtf16End(cluster);
      float x = geometry.textOriginX(span.clusterColumn(cluster));
      float expectedWidth = geometry.columnEdgePx(
          span.clusterColumn(cluster) + span.clusterWidth(cluster))
          - geometry.columnEdgePx(span.clusterColumn(cluster));
      float measuredWidth = clusterAdvance(paint, text, start, end, contextStart, contextEnd);
      if (measuredWidth <= 0f) {
        measuredWidth = paint.measureText(text, start, end);
      }

      glyphFitter.fit(fitScratch, measuredWidth, expectedWidth, x,
          rowY + geometry.baselineOffset(), span.clusterFitMode(cluster));
      float drawX = fitScratch.drawX;
      boolean savedMatrix = fitScratch.scale < 0.999999f;
      if (savedMatrix) {
        canvas.save();
        canvas.translate(drawX, fitScratch.baselineY);
        canvas.scale(fitScratch.scale, fitScratch.scale);
        canvas.translate(-drawX, -fitScratch.baselineY);
      }
      canvas.drawTextRun(
          text,
          start,
          end,
          contextStart,
          contextEnd,
          drawX,
          fitScratch.baselineY,
          false,
          paint);
      if (savedMatrix) canvas.restore();
    }
  }

  private static float prefixAdvance(Paint paint, CharSequence text, int offset) {
    return paint.getRunAdvance(
        text, 0, text.length(), 0, text.length(), false, offset);
  }

  private static float clusterAdvance(
      Paint paint, CharSequence text, int start, int end, int contextStart, int contextEnd) {
    return paint.getRunAdvance(text, start, end, contextStart, contextEnd, false, end);
  }
}
