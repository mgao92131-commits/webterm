package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.util.LongSparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalRenderMetrics;

/**
 * screen 与 history 共用的有界 RenderNode 行缓存。
 *
 * <p>缓存身份由 lineId、lineVersion 和当前视觉 generation 共同决定。节点只在
 * onDraw 确认该行进入 Canvas clip 后才录制，不再在模型更新时预录整个 screen。</p>
 */
public final class TerminalLineRenderNodeCache {
  enum LineDrawResult { HIT, RECORDED, UNAVAILABLE }

  private static final int MIN_CAPACITY = 96;
  private static final int MAX_CAPACITY = 384;

  private final TerminalRowNodeFactory nodeFactory;
  private final LongSparseArray<CachedLine> lines = new LongSparseArray<>();

  private int capacity = MIN_CAPACITY;
  private int columns;
  private int widthPx;
  private int heightPx;
  private int fontGeneration;
  private int paletteGeneration;
  private int styleGeneration;
  private long layoutEpoch = Long.MIN_VALUE;
  private long frameNumber;
  private int nextNodeId;
  @Nullable private String instanceId;
  @Nullable private RemoteTerminalRenderer frameRenderer;
  @Nullable private TerminalPalette framePalette;
  private int frameCanvasBackground;
  private long frameRowHits;
  private long frameRowMisses;
  private long frameHistoryHits;
  private long frameHistoryMisses;
  private long frameStaleFallbacks;
  private long framePinnedConflicts;
  private long victimScanCount;
  private long victimScannedEntries;
  private long allPinnedFallbackCount;

  TerminalLineRenderNodeCache() {
    this(name -> new RenderNodeTerminalRowNode(name));
  }

  TerminalLineRenderNodeCache(@NonNull TerminalRowNodeFactory nodeFactory) {
    this.nodeFactory = nodeFactory;
  }

  /** 建立本帧视觉上下文；generation 改变时一次性清空旧节点。 */
  void beginFrame(@NonNull RemoteTerminalModel.RenderSnapshot snapshot,
                  @NonNull RemoteTerminalRenderer renderer,
                  @NonNull TerminalPalette palette, int canvasBackground,
                  int fontGeneration, int paletteGeneration, int styleGeneration) {
    int rows = snapshot.screenView.size();
    int nextCapacity = clamp(rows * 4, MIN_CAPACITY, MAX_CAPACITY);
    int nextColumns = snapshot.columns;
    // 行节点高度必须与整数 lineHeight 一致，保证录制内容铺满单元格且与
    // 像素对齐的 translate Y 无缝衔接。
    int nextHeightPx = Math.max(1, Math.round(renderer.getLineHeight()));
    int nextWidthPx = Math.max(1, Math.round(nextColumns * renderer.getCellWidth()));
    boolean generationChanged = instanceId == null
        || !instanceId.equals(snapshot.instanceId)
        || layoutEpoch != snapshot.layoutEpoch
        || columns != nextColumns
        || widthPx != nextWidthPx
        || heightPx != nextHeightPx
        || this.fontGeneration != fontGeneration
        || this.paletteGeneration != paletteGeneration
        || this.styleGeneration != styleGeneration;
    if (generationChanged) lines.clear();

    capacity = nextCapacity;
    columns = nextColumns;
    widthPx = nextWidthPx;
    heightPx = nextHeightPx;
    this.fontGeneration = fontGeneration;
    this.paletteGeneration = paletteGeneration;
    this.styleGeneration = styleGeneration;
    layoutEpoch = snapshot.layoutEpoch;
    instanceId = snapshot.instanceId;
    frameRenderer = renderer;
    framePalette = palette;
    frameCanvasBackground = canvasBackground;
    frameNumber++;
    frameRowHits = 0;
    frameRowMisses = 0;
    frameHistoryHits = 0;
    frameHistoryMisses = 0;
    frameStaleFallbacks = 0;
    framePinnedConflicts = 0;
    trimToCapacity();
  }

  LineDrawResult drawOrRecord(@NonNull Canvas canvas, @Nullable RenderLine line,
                              float rowTop, boolean historyLine) {
    if (line == null || frameRenderer == null || framePalette == null
        || widthPx <= 0 || heightPx <= 0) {
      return LineDrawResult.UNAVAILABLE;
    }

    CachedLine cached = lines.get(line.key().lineId());
    if (cached != null && cached.node.hasDisplayList() && matchesRecordedLine(cached, line)) {
      cached.lastUsedFrame = frameNumber;
      cached.lastDrawnFrame = frameNumber;
      frameRowHits++;
      if (historyLine) frameHistoryHits++;
      cached.node.draw(canvas, rowTop);
      return LineDrawResult.HIT;
    }

    if (cached != null) {
      frameStaleFallbacks++;
      if (cached.lastDrawnFrame == frameNumber) {
        // draw() 已把该 display list 提交给本帧 Canvas。此时重录同一节点会让前面
        // 已绘制的逻辑行看到新 version；安全回退由调用方直接 Canvas 绘制。
        framePinnedConflicts++;
        return LineDrawResult.UNAVAILABLE;
      }
    } else {
      cached = obtainEntry(line.key().lineId());
      if (cached == null) return LineDrawResult.UNAVAILABLE;
    }
    if (!record(cached, line)) return LineDrawResult.UNAVAILABLE;
    cached.lastUsedFrame = frameNumber;
    cached.lastDrawnFrame = frameNumber;
    if (historyLine) frameHistoryMisses++;
    cached.node.draw(canvas, rowTop);
    return LineDrawResult.RECORDED;
  }

  void endFrame() {
    TerminalRenderMetrics.addRowCacheStats(
        frameRowHits, frameRowMisses, frameHistoryHits, frameHistoryMisses,
        frameStaleFallbacks, framePinnedConflicts);
    frameRowHits = frameRowMisses = 0;
    frameHistoryHits = frameHistoryMisses = 0;
    frameStaleFallbacks = framePinnedConflicts = 0;
    frameRenderer = null;
    framePalette = null;
  }

  @Nullable
  private CachedLine obtainEntry(long lineId) {
    CachedLine cached;
    if (lines.size() >= capacity) {
      int victim = findVictim(false);
      // 不能在同一帧内重录已经 draw 过的 RenderNode，否则早先提交
      // 的行可能在硬件渲染时看到被改写的 display list。本帧全部
      // pinned 时让超出容量的行直接 Canvas 绘制。
      if (victim < 0) return null;
      cached = lines.valueAt(victim);
      lines.removeAt(victim);
    } else {
      cached = new CachedLine(nodeFactory.create("terminal-line-" + (++nextNodeId)));
    }
    cached.lineId = lineId;
    cached.lineVersion = Long.MIN_VALUE;
    cached.recordedLine = null;
    cached.lastDrawnFrame = Long.MIN_VALUE;
    lines.put(lineId, cached);
    return cached;
  }

  private int findVictim(boolean includePinned) {
    int victim = -1;
    long oldestFrame = Long.MAX_VALUE;
    int scanned = 0;
    for (int i = 0; i < lines.size(); i++) {
      CachedLine candidate = lines.valueAt(i);
      scanned++;
      if (!includePinned && candidate.lastDrawnFrame == frameNumber) continue;
      if (candidate.lastUsedFrame < oldestFrame) {
        oldestFrame = candidate.lastUsedFrame;
        victim = i;
      }
    }
    boolean allPinned = !includePinned && victim < 0;
    victimScanCount++;
    victimScannedEntries += scanned;
    if (allPinned) allPinnedFallbackCount++;
    TerminalRenderMetrics.renderNodeVictimScan(scanned, allPinned);
    return victim;
  }

  private boolean record(@NonNull CachedLine cached, @NonNull RenderLine line) {
    cached.node.setPosition(0, 0, widthPx, heightPx);
    long startedNanos = System.nanoTime();
    Canvas recordingCanvas = cached.node.beginRecording(widthPx, heightPx);
    try {
      frameRenderer.drawTerminalLineContent(recordingCanvas, columns, framePalette, line, 0f,
          frameCanvasBackground);
    } finally {
      cached.node.endRecording();
      TerminalRenderMetrics.renderNodeRecordDuration(System.nanoTime() - startedNanos);
    }
    if (!cached.node.hasDisplayList()) return false;
    cached.lineId = line.key().lineId();
    cached.lineVersion = line.key().lineVersion();
    cached.recordedLine = line;
    frameRowMisses++;
    return true;
  }

  private void trimToCapacity() {
    while (lines.size() > capacity) {
      int victim = findVictim(true);
      if (victim < 0) return;
      lines.removeAt(victim);
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  int sizeForTest() { return lines.size(); }
  int capacityForTest() { return capacity; }
  long victimScanCountForTest() { return victimScanCount; }
  long victimScannedEntriesForTest() { return victimScannedEntries; }
  long allPinnedFallbackCountForTest() { return allPinnedFallbackCount; }

  @Nullable
  RenderLine recordedLineForTest(long lineId) {
    CachedLine cached = lines.get(lineId);
    return cached != null ? cached.recordedLine : null;
  }

  @Nullable
  TerminalRowNode nodeForLineForTest(long lineId) {
    CachedLine cached = lines.get(lineId);
    return cached != null ? cached.node : null;
  }

  /** 同一不可变行对象是热路径；只有恢复/分页等重建对象时才比较 cells。 */
  private static boolean matchesRecordedLine(
      @NonNull CachedLine cached, @NonNull RenderLine incoming) {
    RenderLine recorded = cached.recordedLine;
    return recorded != null
        && cached.lineId == incoming.key().lineId()
        && cached.lineVersion == incoming.key().lineVersion()
        && (recorded == incoming || recorded.body().equals(incoming.body()));
  }

  private static final class CachedLine {
    final TerminalRowNode node;
    long lineId;
    long lineVersion;
    @Nullable RenderLine recordedLine;
    long lastUsedFrame;
    long lastDrawnFrame = Long.MIN_VALUE;

    CachedLine(@NonNull TerminalRowNode node) {
      this.node = node;
    }
  }
}
