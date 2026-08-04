package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;

/** 使用服务端 grapheme 边界绘制带上下文的普通终端文字。 */
final class TerminalTextPainter {
  static final float RUN_ALIGNMENT_TOLERANCE_PX = 0.25f;
  private static final String DISABLED_LIGATURE_FEATURES = "'liga' 0, 'clig' 0, 'calt' 0";
  private final TerminalGlyphFitter glyphFitter = new TerminalGlyphFitter();
  private final TerminalGlyphFitter.FitResult fitScratch = new TerminalGlyphFitter.FitResult();
  @androidx.annotation.Nullable private final RendererFrameWorkStats workStats;
  private char[] textChars = new char[32];
  private float[] utf16Advances = new float[32];
  private float[] prefixAdvances = new float[33];

  TerminalTextPainter() {
    this(null);
  }

  TerminalTextPainter(@androidx.annotation.Nullable RendererFrameWorkStats workStats) {
    this.workStats = workStats;
  }

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
    PreparedTextLayout prepared = prepare(span, geometry, textPaint);
    drawPrepared(canvas, span, prepared, geometry, rowY, textPaint,
        foregroundOverride, useForegroundOverride);
  }

  /** 在当前 Paint/geometry 下计算一次 TextSpan 的布局，供多个绘制后端复用。 */
  PreparedTextLayout prepare(
      @androidx.annotation.NonNull CompiledTerminalLine.TextSpan span,
      @androidx.annotation.NonNull TerminalCellGeometry geometry,
      @androidx.annotation.NonNull Paint textPaint) {
    applyStyle(textPaint, span.style(), span.style().foreground(), false);
    if (span.style().hidden()) {
      return new PreparedTextLayout(true, null, null);
    }

    boolean batchAvailable = prepareBatchAdvances(span.text(), textPaint);
    if (batchAvailable && canDrawWholeRun(span, geometry, prefixAdvances)) {
      return new PreparedTextLayout(true, null, null);
    }
    if (!batchAvailable && canDrawWholeRunLegacy(span, geometry, textPaint)) {
      return new PreparedTextLayout(true, null, null);
    }

    if (workStats != null) workStats.clusterFallbackCount++;
    int clusterCount = span.clusterCount();
    float[] drawX = new float[clusterCount];
    float[] scales = new float[clusterCount];
    for (int cluster = 0; cluster < clusterCount; cluster++) {
      int start = span.clusterUtf16Start(cluster);
      int end = span.clusterUtf16End(cluster);
      float x = geometry.textOriginX(span.clusterColumn(cluster));
      float expectedWidth = geometry.columnEdgePx(
          span.clusterColumn(cluster) + span.clusterWidth(cluster))
          - geometry.columnEdgePx(span.clusterColumn(cluster));
      float measuredWidth = batchAvailable
          ? prefixAdvances[end] - prefixAdvances[start]
          : clusterAdvance(textPaint, span.text(), start, end, 0, span.text().length());
      if (measuredWidth <= 0f) measuredWidth = textPaint.measureText(span.text(), start, end);
      glyphFitter.fit(fitScratch, measuredWidth, expectedWidth, x,
          geometry.baselineOffset(), span.clusterFitMode(cluster));
      drawX[cluster] = fitScratch.drawX;
      scales[cluster] = fitScratch.scale;
    }
    return new PreparedTextLayout(false, drawX, scales);
  }

  void drawPrepared(
      @androidx.annotation.NonNull Canvas canvas,
      @androidx.annotation.NonNull CompiledTerminalLine.TextSpan span,
      @androidx.annotation.NonNull PreparedTextLayout prepared,
      @androidx.annotation.NonNull TerminalCellGeometry geometry,
      float rowY,
      @androidx.annotation.NonNull Paint textPaint,
      int foregroundOverride,
      boolean useForegroundOverride) {
    CompiledTerminalLine.CompiledStyle style = span.style();
    applyStyle(textPaint, style, foregroundOverride, useForegroundOverride);
    if (style.hidden()) return;

    if (prepared.drawWholeRun || !prepared.hasClusterLayout()) {
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

    for (int cluster = 0; cluster < span.clusterCount(); cluster++) {
      int start = span.clusterUtf16Start(cluster);
      int end = span.clusterUtf16End(cluster);
      float drawX = prepared.clusterDrawX[cluster];
      float scale = prepared.clusterScale[cluster];
      float baselineY = rowY + geometry.baselineOffset();
      boolean savedMatrix = scale < 0.999999f;
      if (savedMatrix) {
        canvas.save();
        canvas.translate(drawX, baselineY);
        canvas.scale(scale, scale);
        canvas.translate(-drawX, -baselineY);
      }
      canvas.drawTextRun(
          span.text(), start, end, 0, span.text().length(), drawX, baselineY, false, textPaint);
      if (savedMatrix) canvas.restore();
    }
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
      float[] prefixes) {
    String text = span.text();
    float runStartX = geometry.textOriginX(span.startColumn());
    for (int cluster = 0; cluster < span.clusterCount(); cluster++) {
      if (span.clusterFitMode(cluster) != TerminalGlyphFitter.ClusterFitMode.GRID_START) {
        return false;
      }
      int end = span.clusterUtf16End(cluster);
      float actualAdvance = prefixes[end];
      float expectedAdvance = geometry.textOriginX(
          span.clusterColumn(cluster) + span.clusterWidth(cluster)) - runStartX;
      if (Math.abs(actualAdvance - expectedAdvance) > RUN_ALIGNMENT_TOLERANCE_PX) {
        return false;
      }
    }
    return true;
  }

  private boolean canDrawWholeRunLegacy(
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
      if (actualAdvance <= 0f) continue;
      float expectedAdvance = geometry.textOriginX(
          span.clusterColumn(cluster) + span.clusterWidth(cluster)) - runStartX;
      if (Math.abs(actualAdvance - expectedAdvance) > RUN_ALIGNMENT_TOLERANCE_PX) {
        return false;
      }
    }
    return true;
  }


  private boolean prepareBatchAdvances(CharSequence text, Paint paint) {
    int textLength = text.length();
    ensureAdvanceCapacity(textLength);
    text.toString().getChars(0, textLength, textChars, 0);
    if (workStats != null) workStats.batchAdvanceCallCount++;
    float totalAdvance = paint.getTextRunAdvances(
        textChars, 0, textLength, 0, textLength, false, utf16Advances, 0);
    prefixAdvances[0] = 0f;
    boolean hasUsableAdvance = Float.isFinite(totalAdvance) && totalAdvance > 0f;
    for (int i = 0; i < textLength; i++) {
      float advance = utf16Advances[i];
      if (!Float.isFinite(advance)) return false;
      prefixAdvances[i + 1] = prefixAdvances[i] + advance;
      if (advance != 0f) hasUsableAdvance = true;
    }
    return hasUsableAdvance;
  }

  private void ensureAdvanceCapacity(int textLength) {
    if (textChars.length < textLength) {
      int next = Math.max(textLength, textChars.length * 2);
      textChars = new char[next];
    }
    if (utf16Advances.length < textLength) {
      int next = Math.max(textLength, utf16Advances.length * 2);
      utf16Advances = new float[next];
    }
    if (prefixAdvances.length < textLength + 1) {
      int next = Math.max(textLength + 1, prefixAdvances.length * 2);
      prefixAdvances = new float[next];
    }
  }

  private float prefixAdvance(Paint paint, CharSequence text, int offset) {
    if (workStats != null) workStats.legacyRunAdvanceCallCount++;
    return paint.getRunAdvance(
        text, 0, text.length(), 0, text.length(), false, offset);
  }

  private float clusterAdvance(
      Paint paint, CharSequence text, int start, int end, int contextStart, int contextEnd) {
    if (workStats != null) workStats.legacyRunAdvanceCallCount++;
    return paint.getRunAdvance(text, start, end, contextStart, contextEnd, false, end);
  }
}
