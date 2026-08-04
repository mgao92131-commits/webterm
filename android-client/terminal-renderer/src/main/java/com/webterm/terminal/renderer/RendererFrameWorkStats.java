package com.webterm.terminal.renderer;

/**
 * 一帧 renderer 热路径工作的线程内计数器。
 *
 * <p>该对象只在 renderer 所在线程使用，字段保持 primitive，避免为了测量性能而在
 * 每个 cell 上执行原子操作或创建临时对象。调用方可以在帧边界复制快照。</p>
 */
final class RendererFrameWorkStats {
  long compiledLineCount;
  long compileNanos;
  long compileMaxNanos;
  long compiledLineDrawCount;
  long compiledLineDrawNanos;
  long inputCellCount;
  long defaultCellCount;
  long emittedSpanCount;
  long emittedTextSpanCount;
  long emittedClusterCount;
  long fontResolveCount;
  long emojiClassificationCount;
  long batchAdvanceCallCount;
  long legacyRunAdvanceCallCount;
  long clusterFallbackCount;
  long preparedSpanVisitCount;
  long backgroundRunCount;
  long backgroundRectDrawCount;
  long textForegroundOpCount;
  long specialGlyphCount;
  long specialGlyphRunCount;
  long specialGlyphFamilyDispatchCount;
  long specialGlyphCellClipCount;
  long specialGlyphRunClipCount;
  long decorationSourceSpanCount;
  long decorationRunCount;
  long decorationClipCount;
  long curlyPatternBuildCount;
  long curlyPatternCacheHitCount;
  long curlyPatternSegmentCount;
  long dottedPrimitiveCount;
  long dashedPrimitiveCount;
  long decorationPathDrawCount;
  long staticForegroundOpCount;
  long slowBlinkForegroundOpCount;
  long fastBlinkForegroundOpCount;

  void reset() {
    compiledLineCount = 0L;
    compileNanos = 0L;
    compileMaxNanos = 0L;
    compiledLineDrawCount = 0L;
    compiledLineDrawNanos = 0L;
    inputCellCount = 0L;
    defaultCellCount = 0L;
    emittedSpanCount = 0L;
    emittedTextSpanCount = 0L;
    emittedClusterCount = 0L;
    fontResolveCount = 0L;
    emojiClassificationCount = 0L;
    batchAdvanceCallCount = 0L;
    legacyRunAdvanceCallCount = 0L;
    clusterFallbackCount = 0L;
    preparedSpanVisitCount = 0L;
    backgroundRunCount = 0L;
    backgroundRectDrawCount = 0L;
    textForegroundOpCount = 0L;
    specialGlyphCount = 0L;
    specialGlyphRunCount = 0L;
    specialGlyphFamilyDispatchCount = 0L;
    specialGlyphCellClipCount = 0L;
    specialGlyphRunClipCount = 0L;
    decorationSourceSpanCount = 0L;
    decorationRunCount = 0L;
    decorationClipCount = 0L;
    curlyPatternBuildCount = 0L;
    curlyPatternCacheHitCount = 0L;
    curlyPatternSegmentCount = 0L;
    dottedPrimitiveCount = 0L;
    dashedPrimitiveCount = 0L;
    decorationPathDrawCount = 0L;
    staticForegroundOpCount = 0L;
    slowBlinkForegroundOpCount = 0L;
    fastBlinkForegroundOpCount = 0L;
  }

  Snapshot snapshot() {
    return new Snapshot(
        compiledLineCount,
        compileNanos,
        compileMaxNanos,
        compiledLineDrawCount,
        compiledLineDrawNanos,
        inputCellCount,
        defaultCellCount,
        emittedSpanCount,
        emittedTextSpanCount,
        emittedClusterCount,
        fontResolveCount,
        emojiClassificationCount,
        batchAdvanceCallCount,
        legacyRunAdvanceCallCount,
        clusterFallbackCount,
        preparedSpanVisitCount,
        backgroundRunCount,
        backgroundRectDrawCount,
        textForegroundOpCount,
        specialGlyphCount,
        specialGlyphRunCount,
        specialGlyphFamilyDispatchCount,
        specialGlyphCellClipCount,
        specialGlyphRunClipCount,
        decorationSourceSpanCount,
        decorationRunCount,
        decorationClipCount,
        curlyPatternBuildCount,
        curlyPatternCacheHitCount,
        curlyPatternSegmentCount,
        dottedPrimitiveCount,
        dashedPrimitiveCount,
        decorationPathDrawCount,
        staticForegroundOpCount,
        slowBlinkForegroundOpCount,
        fastBlinkForegroundOpCount);
  }

  record Snapshot(
      long compiledLineCount,
      long compileNanos,
      long compileMaxNanos,
      long compiledLineDrawCount,
      long compiledLineDrawNanos,
      long inputCellCount,
      long defaultCellCount,
      long emittedSpanCount,
      long emittedTextSpanCount,
      long emittedClusterCount,
      long fontResolveCount,
      long emojiClassificationCount,
      long batchAdvanceCallCount,
      long legacyRunAdvanceCallCount,
      long clusterFallbackCount,
      long preparedSpanVisitCount,
      long backgroundRunCount,
      long backgroundRectDrawCount,
      long textForegroundOpCount,
      long specialGlyphCount,
      long specialGlyphRunCount,
      long specialGlyphFamilyDispatchCount,
      long specialGlyphCellClipCount,
      long specialGlyphRunClipCount,
      long decorationSourceSpanCount,
      long decorationRunCount,
      long decorationClipCount,
      long curlyPatternBuildCount,
      long curlyPatternCacheHitCount,
      long curlyPatternSegmentCount,
      long dottedPrimitiveCount,
      long dashedPrimitiveCount,
      long decorationPathDrawCount,
      long staticForegroundOpCount,
      long slowBlinkForegroundOpCount,
      long fastBlinkForegroundOpCount) {
    public Snapshot(
        long compiledLineCount,
        long compileNanos,
        long compileMaxNanos,
        long compiledLineDrawCount,
        long compiledLineDrawNanos,
        long inputCellCount,
        long defaultCellCount,
        long emittedSpanCount,
        long emittedTextSpanCount,
        long emittedClusterCount,
        long fontResolveCount,
        long emojiClassificationCount,
        long batchAdvanceCallCount,
        long legacyRunAdvanceCallCount,
        long clusterFallbackCount) {
      this(
          compiledLineCount,
          compileNanos,
          compileMaxNanos,
          compiledLineDrawCount,
          compiledLineDrawNanos,
          inputCellCount,
          defaultCellCount,
          emittedSpanCount,
          emittedTextSpanCount,
          emittedClusterCount,
          fontResolveCount,
          emojiClassificationCount,
          batchAdvanceCallCount,
          legacyRunAdvanceCallCount,
          clusterFallbackCount,
          0L, 0L, 0L, 0L, 0L, 0L, 0L,
          0L, 0L, 0L, 0L, 0L, 0L, 0L,
          0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
  }
}
