package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.HistoryRenderView;
import com.webterm.terminal.model.SlotState;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.UnifiedContentAxis;

import java.util.List;

/**
 * Go 权威屏幕投影的 Canvas renderer。
 * 视觉规则与应用既有终端体验对齐，状态只来自 RemoteTerminalModel。
 */
public final class RemoteTerminalRenderer {

  static final int SELECTION_OVERLAY = 0x665B92F3;

  private final Paint mainTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint unicodeSymbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint nerdSymbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bgPaint = new Paint();
  private final Paint selectionPaint = new Paint();
  private final Paint placeholderPaint = new Paint();
  private final Rect clipBounds = new Rect();
  private final TerminalCellGeometry geometry = new TerminalCellGeometry();
  private final TerminalStyleResolver styleResolver = new TerminalStyleResolver();
  private final ResolvedTerminalStyle styleScratch = new ResolvedTerminalStyle();
  private final TerminalSelectionProjector selectionProjector = new TerminalSelectionProjector();
  private final TerminalSelectionProjector.Range selectionRangeScratch =
      new TerminalSelectionProjector.Range();
  private final TerminalDecorationPainter decorationPainter;
  private final TerminalFontSet fontSet;
  @Nullable private final RendererFrameWorkStats workStats;
  private final TerminalLineCompiler lineCompiler;
  private final TerminalTextPainter textPainter;
  private final TerminalSpecialGlyphPainter specialGlyphPainter;
  private int phaseMetricsFrame;

  private int textSizeSp = 14;
  @Nullable private Typeface typeface;

  public RemoteTerminalRenderer() {
    this(TerminalFontSet.mainOnly(), null);
  }

  RemoteTerminalRenderer(@NonNull TerminalFontSet fontSet) {
    this(fontSet, null);
  }

  /**
   * 只有显式性能测试才注入 workStats；生产 renderer 必须保持 null，避免在热路径计时。
   */
  RemoteTerminalRenderer(
      @NonNull TerminalFontSet fontSet,
      @Nullable RendererFrameWorkStats workStats) {
    this.fontSet = fontSet;
    this.workStats = workStats;
    this.decorationPainter = new TerminalDecorationPainter(workStats);
    this.specialGlyphPainter = new TerminalSpecialGlyphPainter(workStats);
    this.lineCompiler = new TerminalLineCompiler(fontSet.resolver, workStats);
    this.textPainter = new TerminalTextPainter(workStats);
    applyFont();
  }

  public void updateFont(float textSizePx, @Nullable Typeface tf) {
    typeface = tf;
    configurePaint(mainTextPaint, textSizePx, mainTypeface());
    configurePaint(unicodeSymbolPaint, textSizePx, fontSet.unicodeSymbols);
    configurePaint(nerdSymbolPaint, textSizePx, fontSet.nerdSymbols);
    configurePaint(emojiPaint, textSizePx, fontSet.emoji);
    // ceil 保证行高落在整数像素；与像素对齐的 topInset 一起，使相邻行
    // RenderNode 的上下边界都落在整数 Y，避免硬件合成时出现 1px 暗缝。
    float lineHeight = (float) Math.ceil(mainTextPaint.getFontSpacing());
    // rowY is the top of a terminal cell. Match Termux TerminalRenderer:
    // its baseline is the cell top minus Paint.ascent(), not the full line
    // spacing. Using lineHeight here lowered glyphs within their cells and
    // made a full-cell block cursor appear visibly too high.
    float baselineOffset = -mainTextPaint.ascent();
    float cellWidth = mainTextPaint.measureText("X");
    geometry.update(cellWidth, lineHeight, baselineOffset);
    Paint.FontMetrics fontMetrics = mainTextPaint.getFontMetrics();
    geometry.updateRowNodeBleed(new TerminalCellGeometry.PaintMetrics(
        fontMetrics.top, fontMetrics.bottom, -0.35f,
        mainTextPaint.getTextSize()));
    // 预热 fallback chain，避免首个 emoji 采用错误的测量宽度。
    emojiPaint.measureText("😀");
  }

  public void setFontMetrics(float cellWidth, float lineHeight, float baselineOffset) {
    geometry.update(cellWidth, lineHeight, baselineOffset);
  }

  public float getCellWidth() { return geometry.cellWidth(); }
  public float getLineHeight() { return geometry.lineHeightPx(); }

  /**
   * Font-metric space above the first terminal cell, matching Termux.
   *
   * <p>对原始 {@code lineHeight - baselineOffset} 做一次整像素量化。基线来自
   * {@link Paint#ascent()}，常为小数；若不量化，{@code contentTopY + row * lineHeight}
   * 整列落在亚像素 Y 上，硬件加速下相邻行级 RenderNode 边界会透出底层背景，
   * 形成周期性约 1px 暗线。绘制、命中测试、选择手柄与脏区一律走本值，
   * 保证坐标系一致。</p>
   */
  public float getTopInset() {
    return geometry.topInsetPx();
  }

  /** 基线相对行顶的偏移（仅供诊断只读快照使用）。 */
  public float getBaselineOffset() { return geometry.baselineOffset(); }

  float textOriginX(int column) { return geometry.textOriginX(column); }

  int columnAt(float x, int columns) { return geometry.columnAt(x, columns); }

  int screenRowAt(float y, int rows) { return geometry.rowAt(y, rows); }

  int screenRowAt(float y, float screenTopY, int rows) {
    return geometry.rowAt(y, screenTopY, rows);
  }

  int contentWidthPx(int columns) { return geometry.contentWidthPx(columns); }

  int columnsThatFit(int availableWidth) { return geometry.columnsThatFit(availableWidth); }

  int rowsThatFit(int availableHeight) { return geometry.rowsThatFit(availableHeight); }

  int lineHeightPx() { return geometry.lineHeightPx(); }

  int topInsetPx() { return geometry.topInsetPx(); }

  int cellLeftPx(int column) { return geometry.cellLeftPx(column); }

  int cellRightPx(int column, int columns) { return geometry.cellRightPx(column, columns); }

  int cellRightPx(int column, int columnSpan, int columns) {
    return geometry.spanRightPx(column, columnSpan, columns);
  }

  int cellWidthPx(int column, int columns) { return geometry.cellWidthPx(column, columns); }
  int rowNodeLeftBleedPx() { return geometry.rowNodeLeftBleedPx(); }
  int rowNodeRightBleedPx() { return geometry.rowNodeRightBleedPx(); }
  int rowNodeTopBleedPx() { return geometry.rowNodeTopBleedPx(); }
  int rowNodeBottomBleedPx() { return geometry.rowNodeBottomBleedPx(); }

  /** 返回当前 viewport 内需要动态 blink 覆盖层的类型位图。 */
  int visibleBlinkKinds(@NonNull RemoteTerminalModel.RenderSnapshot model,
                        @NonNull TerminalViewportState viewport,
                        int viewportHeight) {
    float lineHeight = geometry.lineHeightPx();
    if (lineHeight <= 0f || viewportHeight <= 0) return 0;
    UnifiedContentAxis axis = model.contentAxis;
    long historyRowsLong = axis.historyRowCount();
    int historyRows = (int) Math.min(Integer.MAX_VALUE, historyRowsLong);
    int screenRows = model.screenView.size();
    int maxScrollOffset = Math.round(historyRows * lineHeight);
    float scrollOffset = viewport.derivedScrollOffsetPixels(
        model, lineHeight, maxScrollOffset);
    float contentTop = contentTopY(viewportHeight, historyRows, screenRows,
        lineHeight, getTopInset(), scrollOffset);
    long first = Math.max(0L, (long) Math.floor(-contentTop / lineHeight) - 1L);
    long last = Math.min(axis.rowCount(),
        (long) Math.ceil((viewportHeight - contentTop) / lineHeight) + 1L);
    int kinds = 0;
    for (long row = first; row < last; row++) {
      UnifiedContentAxis.Item item = axis.itemAtRow(row);
      if (item.kind == UnifiedContentAxis.Kind.MISSING_HISTORY_RANGE || item.line == null) continue;
      kinds |= lineCompiler.visibleBlinkKinds(
          item.line, model.columns, model.palette,
          resolveColor(model.palette,
              model.palette.reverseVideo ? model.palette.defaultFg : model.palette.defaultBg));
      if (kinds == (TerminalLineCompiler.BLINK_SLOW | TerminalLineCompiler.BLINK_FAST)) {
        return kinds;
      }
    }
    return kinds;
  }

  int visibleBlinkKinds(@NonNull RenderLine line, int columns,
                        @NonNull TerminalPalette palette, int canvasBackground) {
    return lineCompiler.visibleBlinkKinds(line, columns, palette, canvasBackground);
  }

  static int liveScreenExitOffsetPixels(int viewportHeight, float topInset) {
    return Math.max(
        0, (int) Math.ceil(Math.max(0f, viewportHeight - topInset)));
  }

  public void setTextSize(int textSizeSp) {
    this.textSizeSp = textSizeSp;
    applyFont();
  }

  public void setTypeface(@Nullable Typeface typeface) {
    this.typeface = typeface;
    applyFont();
  }

  private void applyFont() {
    configurePaint(mainTextPaint, textSizeSp, mainTypeface());
    configurePaint(unicodeSymbolPaint, textSizeSp, fontSet.unicodeSymbols);
    configurePaint(nerdSymbolPaint, textSizeSp, fontSet.nerdSymbols);
    configurePaint(emojiPaint, textSizeSp, fontSet.emoji);
  }

  private Typeface mainTypeface() {
    return typeface != null ? typeface : fontSet.mainText;
  }

  private void configurePaint(Paint paint, float textSizePx, Typeface typeface) {
    paint.setTextSize(textSizePx);
    paint.setTypeface(typeface);
    textPainter.configureFont(paint);
  }

  private Paint paintFor(TerminalFontRole role) {
    switch (role) {
      case UNICODE_SYMBOL:
        return unicodeSymbolPaint;
      case NERD_SYMBOL:
        return nerdSymbolPaint;
      case EMOJI:
        return emojiPaint;
      case MAIN_TEXT:
      default:
        return mainTextPaint;
    }
  }

  public void render(@NonNull Canvas canvas, @NonNull RemoteTerminalModel.RenderSnapshot model,
                     @NonNull TerminalViewportState viewport, boolean cursorBlinkOn) {
    render(canvas, model, viewport, TerminalAnimationState.cursorOnly(cursorBlinkOn), null);
  }

  /**
   * 绘制完整终端帧。screen/history 行优先通过 {@link TerminalLineRenderNodeCache}
   * 绘制；缓存不可用时回退到直接 {@link #drawLine}。光标和选择作为动态覆盖层
   * 在静态正文之后绘制，避免光标闪烁或选择变化触发整行重录。
   */
  public void render(@NonNull Canvas canvas, @NonNull RemoteTerminalModel.RenderSnapshot model,
                     @NonNull TerminalViewportState viewport, boolean cursorBlinkOn,
                     @Nullable TerminalLineRenderNodeCache lineCache) {
    render(canvas, model, viewport, TerminalAnimationState.cursorOnly(cursorBlinkOn), lineCache,
        null);
  }

  void render(@NonNull Canvas canvas, @NonNull RemoteTerminalModel.RenderSnapshot model,
              @NonNull TerminalViewportState viewport,
              @NonNull TerminalAnimationState animationState,
              @Nullable TerminalLineRenderNodeCache lineCache) {
    render(canvas, model, viewport, animationState, lineCache, null);
  }

  void render(@NonNull Canvas canvas, @NonNull RemoteTerminalModel.RenderSnapshot model,
              @NonNull TerminalViewportState viewport,
              @NonNull TerminalAnimationState animationState,
              @Nullable TerminalLineRenderNodeCache lineCache,
              @Nullable TerminalPreparedLineCache preparedLineCache) {
    if (workStats != null) workStats.reset();
    long renderStartedNanos = System.nanoTime();
    boolean samplePhases = (++phaseMetricsFrame & 63) == 1;
    long viewportCalculationNanos = 0L;
    long historyRowLookupNanos = 0L;
    long screenRowLookupNanos = 0L;
    long renderNodeDrawOrRecordNanos = 0L;
    long canvasDrawNanos = 0L;
    try {
    float cellWidth = geometry.cellWidth();
    float lineHeight = geometry.lineHeightPx();
    if (lineHeight <= 0 || cellWidth <= 0) return;

    long viewportStartedNanos = samplePhases ? System.nanoTime() : 0L;
    UnifiedContentAxis axis = model.contentAxis;
    // The content axis is the only vertical coordinate space. History and
    // ActiveRows remain semantic item kinds, not independent layout systems.
    HistoryRenderView history = model.history;
    int screenRows = model.screenView.size();
    long historyRowsLong = axis.historyRowCount();
    int historyRows = (int) Math.min(Integer.MAX_VALUE, historyRowsLong);
    int maxScrollOffset = Math.round(historyRows * lineHeight);
    float scrollOffset = viewport.derivedScrollOffsetPixels(
        model, lineHeight, maxScrollOffset);
    float contentTopY = contentTopY(canvas.getHeight(), historyRows, screenRows, lineHeight,
        getTopInset(), scrollOffset);
    float screenTopY = contentTopY + historyRows * lineHeight;

    Rect clip = clipBounds;
    if (!canvas.getClipBounds(clip)) clip.set(0, 0, canvas.getWidth(), canvas.getHeight());

    boolean useCache = canvas.isHardwareAccelerated() && lineCache != null;
    float topInset = getTopInset();
    int visibleHistoryRows = 0;
    long axisRows = axis.rowCount();
    long firstAxisRow = Math.max(0L,
        (long) Math.floor((clip.top - contentTopY) / lineHeight) - 1L);
    long lastAxisRow = Math.min(axisRows,
        (long) Math.ceil((clip.bottom - contentTopY) / lineHeight) + 1L);
    if (samplePhases) {
      viewportCalculationNanos = System.nanoTime() - viewportStartedNanos;
    }

    TerminalPalette palette = model.palette;
    int canvasBackground = resolveColor(palette,
        palette.reverseVideo ? palette.defaultFg : palette.defaultBg);
    TerminalSelection selection = viewport.selection;
    TerminalSelection normalizedSelection = selection != null ? selection.normalized() : null;
    TerminalCursor cursor = model.cursor;
    boolean cursorVisible = shouldDrawCursor(
        viewport, model.activeBuffer, cursor, animationState.cursorOn());
    long backgroundDrawStartedNanos = samplePhases ? System.nanoTime() : 0L;
    canvas.drawColor(canvasBackground);
    if (samplePhases) {
      canvasDrawNanos += System.nanoTime() - backgroundDrawStartedNanos;
    }
    int historyClipSaveCount = -1;
    try {
      for (long axisRow = firstAxisRow; axisRow < lastAxisRow; axisRow++) {
      long rowLookupStartedNanos = samplePhases ? System.nanoTime() : 0L;
      UnifiedContentAxis.Item item = axis.itemAtRow(axisRow);
      if (samplePhases) {
        long rowLookupNanos = System.nanoTime() - rowLookupStartedNanos;
        if (item.kind == UnifiedContentAxis.Kind.ACTIVE_LINE) {
          screenRowLookupNanos += rowLookupNanos;
        } else {
          historyRowLookupNanos += rowLookupNanos;
        }
      }
      float y = contentTopY + axisRow * lineHeight;
      boolean active = item.kind == UnifiedContentAxis.Kind.ACTIVE_LINE;
      if (!active && (y + lineHeight <= topInset + 0.001f || y >= screenTopY)) continue;
      if (!active && historyClipSaveCount < 0) {
        historyClipSaveCount = canvas.save();
        canvas.clipRect(0f, topInset, canvas.getWidth(), screenTopY);
      } else if (active && historyClipSaveCount >= 0) {
        canvas.restoreToCount(historyClipSaveCount);
        historyClipSaveCount = -1;
      }

      if (item.kind == UnifiedContentAxis.Kind.MISSING_HISTORY_RANGE) {
        long historySeq = item.fromHistorySeq + (axisRow - item.startRow);
        long historyIndex = historySeq - history.firstSeq();
        if (historyIndex >= 0 && historyIndex <= Integer.MAX_VALUE) {
          long drawStartedNanos = samplePhases ? System.nanoTime() : 0L;
          drawHistoryPlaceholder(canvas, model.columns, history, (int) historyIndex, y,
              canvasBackground);
          if (samplePhases) {
            canvasDrawNanos += System.nanoTime() - drawStartedNanos;
          }
        }
        continue;
      }

      RenderLine line = item.line;
      int screenRow = active ? (int) (axisRow - historyRowsLong) : -1;
      long historySeq = active ? 0 : item.fromHistorySeq;
      if (!active) {
        visibleHistoryRows++;
      }
      TerminalLineRenderNodeCache.LineDrawResult cacheResult;
      PreparedTerminalLine preparedLine = null;
      CompiledTerminalLine compiledLine = null;
      if (useCache) {
        long nodeStartedNanos = samplePhases ? System.nanoTime() : 0L;
        cacheResult = lineCache.drawOrRecord(
            canvas, line, y, !active, preparedLineCache);
        if (samplePhases) {
          renderNodeDrawOrRecordNanos += System.nanoTime() - nodeStartedNanos;
        }
      } else {
        cacheResult = TerminalLineRenderNodeCache.LineDrawResult.UNAVAILABLE;
      }
      if (cacheResult == TerminalLineRenderNodeCache.LineDrawResult.UNAVAILABLE) {
        if (preparedLineCache != null) {
          preparedLine = preparedLineCache.getOrPrepare(
              line, this, model.columns, palette, canvasBackground);
          compiledLine = preparedLine.compiledLine;
        } else {
          compiledLine = compileLine(line, model.columns, palette, canvasBackground);
        }
        long drawStartedNanos = samplePhases ? System.nanoTime() : 0L;
        if (preparedLine != null) {
          drawPreparedLineContent(canvas, preparedLine, y, canvasBackground);
        } else {
          drawCompiledLineContent(canvas, compiledLine, y, canvasBackground);
        }
        if (samplePhases) {
          canvasDrawNanos += System.nanoTime() - drawStartedNanos;
        }
      }
      if (compiledLine == null && lineCache != null) {
        compiledLine = lineCache.compiledLineForLine(line);
      }
      if (preparedLine == null && compiledLine == null && preparedLineCache != null) {
        boolean blockCursorNeedsLine = active
            && cursorVisible
            && cursor.row == screenRow
            && cursor.shape == TerminalCursor.Shape.BLOCK;
        // RenderNode entry 只保存 display list；Prepared entry 可能先被独立预算淘汰。
        // 只有动态层确实需要 CPU layout 时才重新 prepare，普通静态 HIT 不制造抖动。
        preparedLine = preparedLineCache.getForDynamicOverlay(
            line, this, model.columns, palette, canvasBackground,
            animationState, blockCursorNeedsLine);
        if (preparedLine != null) {
          compiledLine = preparedLine.compiledLine;
        }
      }
      if (preparedLine != null && preparedLine.visibleBlinkKinds != 0) {
        long blinkStartedNanos = samplePhases ? System.nanoTime() : 0L;
        drawBlinkOverlayForLine(canvas, preparedLine, y, animationState);
        if (samplePhases) {
          canvasDrawNanos += System.nanoTime() - blinkStartedNanos;
        }
      } else if (compiledLine != null && compiledLine.visibleBlinkKinds() != 0) {
        long blinkStartedNanos = samplePhases ? System.nanoTime() : 0L;
        drawBlinkOverlayForLine(canvas, compiledLine, y, animationState);
        if (samplePhases) {
          canvasDrawNanos += System.nanoTime() - blinkStartedNanos;
        }
      }
      long overlayStartedNanos = samplePhases ? System.nanoTime() : 0L;
      drawSelectionOverlayForRow(canvas, model.columns, palette, line, y,
          historySeq, screenRow, normalizedSelection, canvasBackground);
      if (active && cursorVisible && cursor.row == screenRow) {
        drawCursorOverlayForRow(canvas, model.columns, palette, line, preparedLine, compiledLine, y,
            screenRow, cursor, canvasBackground, animationState);
      }
      if (samplePhases) {
        canvasDrawNanos += System.nanoTime() - overlayStartedNanos;
      }
      }
    } finally {
      if (historyClipSaveCount >= 0) {
        canvas.restoreToCount(historyClipSaveCount);
      }
    }
    TerminalRenderMetrics.visibleHistoryRowsDrawn(visibleHistoryRows);
    } finally {
      if (samplePhases) {
        TerminalRenderMetrics.renderFramePhases(
            scalePhaseSample(viewportCalculationNanos),
            scalePhaseSample(historyRowLookupNanos),
            scalePhaseSample(screenRowLookupNanos),
            scalePhaseSample(renderNodeDrawOrRecordNanos),
            scalePhaseSample(canvasDrawNanos));
      }
      TerminalRenderMetrics.renderDuration(System.nanoTime() - renderStartedNanos);
    }
  }

  private static long scalePhaseSample(long nanos) {
    return nanos > Long.MAX_VALUE / 64L ? Long.MAX_VALUE : nanos * 64L;
  }

  static boolean shouldDrawCursor(
      @NonNull TerminalViewportState viewport,
      @NonNull TerminalCursor cursor,
      boolean cursorBlinkOn) {
    return shouldDrawCursor(
        viewport, TerminalBufferKind.MAIN, cursor, cursorBlinkOn);
  }

  static boolean shouldDrawCursor(
      @NonNull TerminalViewportState viewport,
      @NonNull TerminalBufferKind buffer,
      @NonNull TerminalCursor cursor,
      boolean cursorBlinkOn) {
    return viewport.isFollowTail(buffer) && cursor.visible
        && (!cursor.blink || cursorBlinkOn);
  }

  private void drawHistoryPlaceholder(Canvas canvas, int columns, HistoryRenderView history,
                                      int historyIndex, float y, int canvasBackground) {
    SlotState state = history.slotStateAt(historyIndex);
    int alpha = state == SlotState.UNAVAILABLE ? 18 : 10;
    placeholderPaint.setColor((canvasBackground & 0x00ffffff) | (alpha << 24));
    canvas.drawRect(0f, y, geometry.contentWidthPx(columns),
        y + geometry.lineHeightPx(), placeholderPaint);
  }

  /** Half-open row range whose cells can affect a Canvas clip, including one anti-aliasing guard. */
  static int[] rowRangeIntersecting(int clipTop, int clipBottom, float rowsTop,
                                    float rowHeight, int rowCount) {
    long packed = rowRangeIntersectingPacked(
        clipTop, clipBottom, rowsTop, rowHeight, rowCount);
    return new int[] {(int) (packed >> 32), (int) packed};
  }

  /** 热路径用一个 long 携带两个非负 int，避免每帧创建范围数组。 */
  static long rowRangeIntersectingPacked(int clipTop, int clipBottom, float rowsTop,
                                         float rowHeight, int rowCount) {
    if (rowCount <= 0 || rowHeight <= 0f || clipBottom <= clipTop) return 0L;
    double firstRaw = Math.floor((clipTop - rowsTop) / rowHeight);
    double lastRaw = Math.ceil((clipBottom - rowsTop) / rowHeight);
    int first = clampRow(firstRaw, rowCount);
    int last = clampRow(lastRaw, rowCount);
    first = Math.max(0, first - 1);
    last = Math.min(rowCount, last + 1);
    int rangeFirst = Math.min(first, last);
    int rangeLast = Math.max(first, last);
    return ((long) rangeFirst << 32) | (rangeLast & 0xffffffffL);
  }

  private static int clampRow(double value, int rowCount) {
    if (value <= 0d) return 0;
    if (value >= rowCount) return rowCount;
    return (int) value;
  }

  /**
   * 仅绘制终端行的静态正文：背景、字符、样式装饰，不包含光标和选择。
   * 用于 RenderNode 行缓存录制和 Direct Canvas fallback，保证两条路径共享同一份
   * 编译结果和文字绘制实现。
   */
  void drawTerminalLineContent(Canvas canvas, int columns, TerminalPalette palette,
                               RenderLine line, float y, int canvasBackground) {
    if (line == null) return;
    CompiledTerminalLine compiled = compileLine(line, columns, palette, canvasBackground);
    drawCompiledLineContent(canvas, compiled, y, canvasBackground);
  }

  CompiledTerminalLine compileLine(RenderLine line, int columns, TerminalPalette palette,
                                   int canvasBackground) {
    if (workStats == null) {
      return lineCompiler.compile(line, columns, palette, canvasBackground);
    }
    long startedNanos = System.nanoTime();
    CompiledTerminalLine compiled = lineCompiler.compile(line, columns, palette, canvasBackground);
    long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);
    workStats.compileNanos += elapsedNanos;
    workStats.compileMaxNanos = Math.max(workStats.compileMaxNanos, elapsedNanos);
    return compiled;
  }

  PreparedTerminalLine compileAndPrepareLine(
      @NonNull RenderLine line,
      int columns,
      @NonNull TerminalPalette palette,
      int canvasBackground) {
    CompiledTerminalLine compiled = compileLine(line, columns, palette, canvasBackground);
    List<CompiledTerminalLine.Span> spans = compiled.spans();
    PreparedTextLayout[] layouts = new PreparedTextLayout[spans.size()];
    for (int i = 0; i < spans.size(); i++) {
      CompiledTerminalLine.Span span = spans.get(i);
      if (span instanceof CompiledTerminalLine.TextSpan) {
        CompiledTerminalLine.TextSpan textSpan =
            (CompiledTerminalLine.TextSpan) span;
        layouts[i] = textPainter.prepare(textSpan, geometry, paintFor(textSpan.fontRole()));
      }
    }
    return new PreparedTerminalLine(
        compiled, layouts, PreparedLineDrawPlan.build(compiled, geometry, canvasBackground));
  }

  void drawCompiledLineContent(Canvas canvas, CompiledTerminalLine line,
                               float rowY, int canvasBackground) {
    if (workStats == null) {
      drawCompiledLineContentMeasured(canvas, line, rowY, canvasBackground);
      return;
    }
    long startedNanos = System.nanoTime();
    try {
      drawCompiledLineContentMeasured(canvas, line, rowY, canvasBackground);
    } finally {
      workStats.compiledLineDrawCount++;
      workStats.compiledLineDrawNanos += Math.max(0L, System.nanoTime() - startedNanos);
    }
  }

  void drawPreparedLineContent(
      @NonNull Canvas canvas,
      @NonNull PreparedTerminalLine prepared,
      float rowY,
      int canvasBackground) {
    if (workStats == null) {
      drawPreparedLineContentMeasured(canvas, prepared, rowY, canvasBackground);
      return;
    }
    long startedNanos = System.nanoTime();
    try {
      drawPreparedLineContentMeasured(canvas, prepared, rowY, canvasBackground);
    } finally {
      workStats.compiledLineDrawCount++;
      workStats.compiledLineDrawNanos += Math.max(0L, System.nanoTime() - startedNanos);
    }
  }

  private void drawCompiledLineContentMeasured(
      Canvas canvas, CompiledTerminalLine line, float rowY, int canvasBackground) {
    for (int i = 0; i < line.spans().size(); i++) {
      if (workStats != null) workStats.preparedSpanVisitCount++;
      CompiledTerminalLine.Span span = line.spans().get(i);
      CompiledTerminalLine.CompiledStyle style = span.style();
      int left = geometry.columnEdgePx(span.startColumn());
      int right = geometry.columnEdgePx(span.endColumn());
      if (style.background() != canvasBackground) {
        bgPaint.setColor(style.background());
        if (workStats != null) {
          workStats.backgroundRunCount++;
          workStats.backgroundRectDrawCount++;
        }
        canvas.drawRect(left, rowY, right, rowY + geometry.lineHeightPx(), bgPaint);
      }

      if (!style.hidden() && !style.hasBlink()) {
        if (workStats != null) {
          workStats.staticForegroundOpCount++;
          if (span instanceof CompiledTerminalLine.TextSpan) {
            workStats.textForegroundOpCount++;
          }
        }
        drawSpanForeground(canvas, span, rowY, style.foreground(),
            style.underlineColor(), false);
      }
    }
  }

  private void drawPreparedLineContentMeasured(
      @NonNull Canvas canvas,
      @NonNull PreparedTerminalLine prepared,
      float rowY,
      int canvasBackground) {
    CompiledTerminalLine line = prepared.compiledLine;
    PreparedLineDrawPlan plan = prepared.drawPlan;
    for (int backgroundRunIndex = 0;
         backgroundRunIndex < plan.backgroundSpanIndexes.length;
         backgroundRunIndex++) {
      bgPaint.setColor(plan.backgroundColors[backgroundRunIndex]);
      if (workStats != null) {
        workStats.backgroundRunCount++;
        workStats.backgroundRectDrawCount++;
      }
      canvas.drawRect(
          plan.backgroundStartPx[backgroundRunIndex], rowY,
          plan.backgroundEndPx[backgroundRunIndex], rowY + geometry.lineHeightPx(), bgPaint);
    }

    int staticSpecialRunIndex = 0;
    int[] staticIndexes = plan.staticForegroundSpanIndexes;
    for (int staticIndex = 0; staticIndex < staticIndexes.length; staticIndex++) {
      int i = staticIndexes[staticIndex];
      if (workStats != null) workStats.preparedSpanVisitCount++;
      CompiledTerminalLine.Span span = line.spans().get(i);
      CompiledTerminalLine.CompiledStyle style = span.style();
      int left = plan.spanLeftPx[i];
      int right = plan.spanRightPx[i];
      if (staticSpecialRunIndex < plan.staticSpecialGlyphRuns.length
          && plan.staticSpecialGlyphRuns[staticSpecialRunIndex].startSpanIndex == i) {
        PreparedSpecialGlyphRun run = plan.staticSpecialGlyphRuns[staticSpecialRunIndex++];
        if (workStats != null) {
          workStats.preparedSpanVisitCount += run.glyphCount() - 1L;
          workStats.staticForegroundOpCount += run.glyphCount();
        }
        drawPreparedSpecialGlyphRun(canvas, run, rowY);
        while (staticIndex + 1 < staticIndexes.length
            && staticIndexes[staticIndex + 1] < run.endSpanIndexExclusive) {
          staticIndex++;
        }
        continue;
      }
      if (workStats != null) {
        workStats.staticForegroundOpCount++;
        if (span instanceof CompiledTerminalLine.TextSpan) {
          workStats.textForegroundOpCount++;
        }
      }
      drawSpanForeground(canvas, span, rowY, style.foreground(),
          style.underlineColor(), false, prepared.layoutAt(i), left, right, false);
    }
    drawPreparedDecorationRuns(canvas, plan.staticDecorationRuns, rowY);
  }

  @Nullable RendererFrameWorkStats.Snapshot workStatsForTest() {
    return workStats == null ? null : workStats.snapshot();
  }

  void resetWorkStatsForTest() {
    if (workStats != null) workStats.reset();
  }

  private void drawBlinkOverlayForLine(
      Canvas canvas, CompiledTerminalLine compiled, float rowY,
      TerminalAnimationState animationState) {
    for (CompiledTerminalLine.Span span : compiled.spans()) {
      CompiledTerminalLine.CompiledStyle style = span.style();
      if (style.hasBlink() && animationState.blinkForegroundVisible(style)) {
        if (workStats != null) {
          if (style.blinkFast()) workStats.fastBlinkForegroundOpCount++;
          else workStats.slowBlinkForegroundOpCount++;
          if (span instanceof CompiledTerminalLine.TextSpan) {
            workStats.textForegroundOpCount++;
          }
        }
        drawSpanForeground(canvas, span, rowY, style.foreground(),
            style.underlineColor(), false);
      }
    }
  }

  private void drawBlinkOverlayForLine(
      @NonNull Canvas canvas,
      @NonNull PreparedTerminalLine prepared,
      float rowY,
      @NonNull TerminalAnimationState animationState) {
    PreparedLineDrawPlan plan = prepared.drawPlan;
    if (animationState.slowBlinkOn()) {
      drawPreparedBlinkTextOps(canvas, prepared, plan.slowBlinkSpanIndexes, rowY, false);
      drawPreparedSpecialGlyphRuns(canvas, plan.slowBlinkSpecialGlyphRuns, rowY);
      drawPreparedDecorationRuns(canvas, plan.slowBlinkDecorationRuns, rowY);
    }
    if (animationState.fastBlinkOn()) {
      drawPreparedBlinkTextOps(canvas, prepared, plan.fastBlinkSpanIndexes, rowY, true);
      drawPreparedSpecialGlyphRuns(canvas, plan.fastBlinkSpecialGlyphRuns, rowY);
      drawPreparedDecorationRuns(canvas, plan.fastBlinkDecorationRuns, rowY);
    }
  }

  private void drawPreparedBlinkTextOps(
      @NonNull Canvas canvas,
      @NonNull PreparedTerminalLine prepared,
      @NonNull int[] spanIndexes,
      float rowY,
      boolean fast) {
    CompiledTerminalLine compiled = prepared.compiledLine;
    PreparedLineDrawPlan plan = prepared.drawPlan;
    for (int index : spanIndexes) {
      CompiledTerminalLine.Span span = compiled.spans().get(index);
      if (span instanceof CompiledTerminalLine.SpecialGlyphSpan) continue;
      if (workStats != null) {
        workStats.preparedSpanVisitCount++;
        if (fast) workStats.fastBlinkForegroundOpCount++;
        else workStats.slowBlinkForegroundOpCount++;
        if (span instanceof CompiledTerminalLine.TextSpan) {
          workStats.textForegroundOpCount++;
        }
      }
      CompiledTerminalLine.CompiledStyle style = span.style();
      drawSpanForeground(canvas, span, rowY, style.foreground(),
          style.underlineColor(), false, prepared.layoutAt(index),
          plan.spanLeftPx[index], plan.spanRightPx[index], false);
    }
  }

  private void drawPreparedSpecialGlyphRun(
      Canvas canvas, PreparedSpecialGlyphRun run, float rowY) {
    if (workStats != null) {
      workStats.specialGlyphRunCount++;
    }
    boolean runClip = run.clipPolicy == TerminalSpecialGlyphPainter.ClipPolicy.RUN_CLIP_SAFE;
    int saveCount = -1;
    if (runClip) {
      saveCount = canvas.save();
      canvas.clipRect(run.leftPx, Math.round(rowY), run.rightPx,
          Math.round(rowY) + geometry.lineHeightPx());
      if (workStats != null) workStats.specialGlyphRunClipCount++;
    }
    try {
      for (int i = 0; i < run.glyphCount(); i++) {
        specialGlyphPainter.drawCodePointWithFamily(
            canvas, run.codePoints[i],
            TerminalSpecialGlyphPainter.familyFromPreparedCode(run.families[i]),
            run.glyphLeftPx[i], Math.round(rowY), run.glyphRightPx[i],
            Math.round(rowY) + geometry.lineHeightPx(), run.style.foreground(),
            0, 0, run.columns[i], geometry.cellWidth(), !runClip);
      }
    } finally {
      if (runClip) canvas.restoreToCount(saveCount);
    }
  }

  private void drawPreparedSpecialGlyphRuns(
      Canvas canvas, PreparedSpecialGlyphRun[] runs, float rowY) {
    for (PreparedSpecialGlyphRun run : runs) {
      drawPreparedSpecialGlyphRun(canvas, run, rowY);
    }
  }

  private void drawPreparedDecorationRuns(
      Canvas canvas, PreparedDecorationRun[] runs, float rowY) {
    int rowTop = Math.round(rowY);
    int rowBottom = rowTop + geometry.lineHeightPx();
    for (PreparedDecorationRun run : runs) {
      decorationPainter.drawRun(canvas, run, rowTop, rowBottom);
    }
  }

  private void drawSpanForeground(
      Canvas canvas,
      CompiledTerminalLine.Span span,
      float rowY,
      int foreground,
      int underlineColor,
      boolean overrideForeground) {
    drawSpanForeground(canvas, span, rowY, foreground, underlineColor,
        overrideForeground, null);
  }

  private void drawSpanForeground(
      Canvas canvas,
      CompiledTerminalLine.Span span,
      float rowY,
      int foreground,
      int underlineColor,
      boolean overrideForeground,
      @Nullable PreparedTextLayout preparedLayout) {
    CompiledTerminalLine.CompiledStyle style = span.style();
    int left = geometry.columnEdgePx(span.startColumn());
    int right = geometry.columnEdgePx(span.endColumn());
    drawSpanForeground(canvas, span, rowY, foreground, underlineColor, overrideForeground,
        preparedLayout, left, right);
  }

  private void drawSpanForeground(
      Canvas canvas,
      CompiledTerminalLine.Span span,
      float rowY,
      int foreground,
      int underlineColor,
      boolean overrideForeground,
      @Nullable PreparedTextLayout preparedLayout,
      int left,
      int right) {
    drawSpanForeground(canvas, span, rowY, foreground, underlineColor, overrideForeground,
        preparedLayout, left, right, true);
  }

  private void drawSpanForeground(
      Canvas canvas,
      CompiledTerminalLine.Span span,
      float rowY,
      int foreground,
      int underlineColor,
      boolean overrideForeground,
      @Nullable PreparedTextLayout preparedLayout,
      int left,
      int right,
      boolean drawDecoration) {
    CompiledTerminalLine.CompiledStyle style = span.style();
    if (span instanceof CompiledTerminalLine.TextSpan) {
      CompiledTerminalLine.TextSpan textSpan =
          (CompiledTerminalLine.TextSpan) span;
      Paint textPaint = paintFor(textSpan.fontRole());
      if (preparedLayout == null) {
        textPainter.draw(canvas, textSpan, geometry, rowY, textPaint,
            foreground, overrideForeground);
      } else {
        textPainter.drawPrepared(canvas, textSpan, preparedLayout, geometry, rowY,
            textPaint, foreground, overrideForeground);
      }
    } else if (span instanceof CompiledTerminalLine.SpecialGlyphSpan) {
      CompiledTerminalLine.SpecialGlyphSpan special =
          (CompiledTerminalLine.SpecialGlyphSpan) span;
      if (workStats != null) {
        workStats.specialGlyphRunCount++;
        workStats.specialGlyphRunClipCount++;
      }
      specialGlyphPainter.drawCodePointIfSupported(
          canvas,
          special.codePoint(),
          left,
          Math.round(rowY),
          right,
          Math.round(rowY) + geometry.lineHeightPx(),
          overrideForeground ? foreground : style.foreground(),
          0,
          0,
          special.startColumn(),
          geometry.cellWidth());
    }

    if (drawDecoration) {
      copyCompiledStyle(style, styleScratch);
      if (overrideForeground) {
        styleScratch.foreground = foreground;
        styleScratch.underlineColor = underlineColor;
      }
      int rowTop = Math.round(rowY);
      decorationPainter.draw(canvas, styleScratch, left, right,
          rowTop, rowTop + geometry.lineHeightPx());
    }
  }

  /** 在已绘制的行上追加选择高亮覆盖层。 */
  private void drawSelectionOverlayForRow(Canvas canvas, int columns, TerminalPalette palette,
                                          RenderLine line, float y, long historySeq, int screenRow,
                                          TerminalSelection selection, int canvasBackground) {
    if (line == null || selection == null) return;
    selectionProjector.project(selection, line, historySeq, screenRow, columns,
        selectionRangeScratch);
    if (selectionRangeScratch.isEmpty()) return;
    selectionPaint.setColor(SELECTION_OVERLAY);
    int left = geometry.columnEdgePx(selectionRangeScratch.startColumn);
    int right = geometry.columnEdgePx(selectionRangeScratch.endColumnExclusive);
    canvas.drawRect(left, y, right, y + geometry.lineHeightPx(), selectionPaint);
  }

  /** 在已绘制的行上追加光标覆盖层。 */
  private void drawCursorOverlayForRow(Canvas canvas, int columns, TerminalPalette palette,
                                       RenderLine line,
                                       @Nullable PreparedTerminalLine preparedLine,
                                       @Nullable CompiledTerminalLine compiledLine,
                                       float y, int screenRow,
                                       TerminalCursor cursor, int canvasBackground,
                                       TerminalAnimationState animationState) {
    if (line == null || cursor == null || !cursor.visible || screenRow != cursor.row
        || cursor.col < 0 || cursor.col >= columns) {
      return;
    }
    int col = cursor.col;
    CellValue cell = col < line.length() ? line.at(col) : null;
    // 光标落在宽字符右半（spacer 列）时归一到宽字符起始格：整格 2 列高亮，
    // 与 legacy block-cursor 路径 `cursor.col == col + 1` 的行为一致。
    if (cell != null && cell.isSpacer() && col > 0) {
      CellValue left = line.at(col - 1);
      if (left != null && left.isWideStart()) {
        col--;
        cell = left;
      }
    }
    int columnWidth = cell != null && cell.isWideStart() ? 2 : 1;
    int cursorColor = resolveColor(palette, palette.cursorColor);
    int left = geometry.columnEdgePx(col);
    int right = geometry.columnEdgePx(col + columnWidth);
    float lineHeight = geometry.lineHeightPx();
    bgPaint.setColor(cursorColor);
    if (cursor.shape == TerminalCursor.Shape.BAR) {
      canvas.drawRect(left, y, barCursorRight(left, right), y + lineHeight, bgPaint);
    } else if (cursor.shape == TerminalCursor.Shape.UNDERLINE) {
      canvas.drawRect(left, y + lineHeight * 3f / 4f, right, y + lineHeight, bgPaint);
    } else if (cell == null || cell.isSpacer()) {
      // 空 cell（或左侧无宽字符起始格的异常 spacer）没有字形可重绘，只画光标矩形。
      canvas.drawRect(left, y, right, y + lineHeight, bgPaint);
    } else {
      // BLOCK 光标：只重放与 cursor cell 相交的已编译 Span。文字仍使用原 TextSpan
      // 的完整 shaping context，特殊字符也继续走 Phase 3 painter。
      canvas.drawRect(left, y, right, y + lineHeight, bgPaint);
      TerminalStyleResolver cursorResolver = styleResolver;
      cursorResolver.resolveInto(palette, cell.style(), true, styleScratch);
      int cursorForeground = styleScratch.foreground;
      int cursorUnderlineColor = styleScratch.underlineColor;
      int saveCount = canvas.save();
      canvas.clipRect(left, Math.round(y), right,
          Math.round(y) + geometry.lineHeightPx());
      try {
        if (preparedLine != null) compiledLine = preparedLine.compiledLine;
        if (compiledLine == null) return;
        for (int i = 0; i < compiledLine.spans().size(); i++) {
          CompiledTerminalLine.Span span = compiledLine.spans().get(i);
          if (!span.intersectsColumns(col, col + columnWidth)) continue;
          if (!animationState.foregroundVisible(span.style())) continue;
          drawSpanForeground(canvas, span, y, cursorForeground,
              cursorUnderlineColor, true,
              preparedLine == null ? null : preparedLine.layoutAt(i));
        }
      } finally {
        canvas.restoreToCount(saveCount);
      }
    }
  }

  private static void copyCompiledStyle(
      CompiledTerminalLine.CompiledStyle source, ResolvedTerminalStyle target) {
    target.foreground = source.foreground();
    target.background = source.background();
    target.underlineColor = source.underlineColor();
    target.bold = source.bold();
    target.dim = source.dim();
    target.italic = source.italic();
    target.hidden = source.hidden();
    target.strike = source.strike();
    target.blinkSlow = source.blinkSlow();
    target.blinkFast = source.blinkFast();
    target.underlineKind = source.underlineKind();
  }

  private static int barCursorRight(int left, int right) {
    return Math.min(right, left + Math.max(1, Math.round((right - left) / 4f)));
  }

  private static boolean isCellSelected(TerminalSelection selection, long historySeq, int screenRow,
                                        int col, int columnWidth) {
    if (selection == null) return false;
    return compareSelectionPosition(historySeq, screenRow, col, selection.end) < 0
        && compareSelectionPosition(historySeq, screenRow, col + Math.max(1, columnWidth),
            selection.start) > 0;
  }

  private static int compareSelectionPosition(long historySeq, int screenRow, int col,
                                              TerminalSelection.Anchor other) {
    if (historySeq != 0 && other.historySeq != 0) {
      int cmp = Long.compare(historySeq, other.historySeq);
      return cmp != 0 ? cmp : Integer.compare(col, other.col);
    }
    if (historySeq != 0) return -1;
    if (other.historySeq != 0) return 1;
    int cmp = Integer.compare(screenRow, other.screenRow);
    return cmp != 0 ? cmp : Integer.compare(col, other.col);
  }

  static int resolveColor(TerminalColor color) {
    return resolveColor(TerminalPalette.defaults(), color);
  }

  static int resolveColor(TerminalPalette palette, TerminalColor color) {
    if (color == null) return 0xFF000000;
    switch (color.kind) {
      case RGB: return 0xFF000000 | color.rgb;
      case DEFAULT_FG:
        return palette.defaultFg != null && palette.defaultFg.kind != TerminalColor.Kind.DEFAULT_FG
            ? resolveColor(palette, palette.defaultFg) : 0xFFFFFFFF;
      case DEFAULT_BG:
        return palette.defaultBg != null && palette.defaultBg.kind != TerminalColor.Kind.DEFAULT_BG
            ? resolveColor(palette, palette.defaultBg) : 0xFF000000;
      case CURSOR:
        return palette.cursorColor != null && palette.cursorColor.kind != TerminalColor.Kind.CURSOR
            ? resolveColor(palette, palette.cursorColor) : 0xFFFFFFFF;
      case INDEXED: return resolveIndexedColor(palette, color.index);
      default: return 0xFF000000;
    }
  }

  static int resolveIndexedColor(TerminalPalette palette, int index) {
    Integer override = palette.indexedColors.get(index);
    return override != null ? 0xFF000000 | override : TerminalVisualRules.ansiColor(index);
  }

  /** Shared geometry for drawing, hit-testing and selection handles. */
  static float contentTopY(int viewportHeight, int historyRows, int screenRows,
                           float lineHeight, float topInset, float scrollOffsetPixels) {
    float usableHeight = Math.max(0, viewportHeight - topInset);
    float contentHeight = (historyRows + screenRows) * lineHeight;
    // 上界按"首条历史行贴顶"锚定：滚到顶时 contentTopY == topInset，首行完整可见。
    // 旧公式 contentHeight - usableHeight 是底部锚定，行数 floor 取整的余数会让
    // 首行停在视口顶边之外，被裁掉半行。内容不足一屏时不允许滚动。
    float maxOffset = contentHeight > usableHeight ? historyRows * lineHeight : 0f;
    float offset = Math.max(0, Math.min(scrollOffsetPixels, maxOffset));
    return topInset + offset - historyRows * lineHeight;
  }

  static float screenTopY(int viewportHeight, int historyRows, int screenRows,
                          float lineHeight, float topInset, float scrollOffsetPixels) {
    return contentTopY(viewportHeight, historyRows, screenRows, lineHeight, topInset, scrollOffsetPixels)
        + historyRows * lineHeight;
  }

  static float contentTopY(int viewportHeight, int historyRows, int screenRows,
                           float lineHeight, float scrollOffsetPixels) {
    return contentTopY(viewportHeight, historyRows, screenRows, lineHeight, 0f, scrollOffsetPixels);
  }

  static float screenTopY(int viewportHeight, int historyRows, int screenRows,
                          float lineHeight, float scrollOffsetPixels) {
    return screenTopY(viewportHeight, historyRows, screenRows, lineHeight, 0f, scrollOffsetPixels);
  }
}
