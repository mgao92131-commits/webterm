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
        clusterFallbackCount);
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
      long clusterFallbackCount) {}
}
