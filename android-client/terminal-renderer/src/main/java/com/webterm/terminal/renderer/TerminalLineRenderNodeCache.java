package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.util.LongSparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.TerminalLine;
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
    int rows = snapshot.screen != null ? snapshot.screen.length : 0;
    int nextCapacity = clamp(rows * 4, MIN_CAPACITY, MAX_CAPACITY);
    int nextColumns = snapshot.columns;
    int nextHeightPx = Math.round(renderer.getLineHeight());
    int nextWidthPx = Math.round(nextColumns * renderer.getCellWidth());
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
    trimToCapacity();
  }

  LineDrawResult drawOrRecord(@NonNull Canvas canvas, @Nullable TerminalLine line,
                              float rowTop, boolean historyLine) {
    if (line == null || frameRenderer == null || framePalette == null
        || widthPx <= 0 || heightPx <= 0) {
      return LineDrawResult.UNAVAILABLE;
    }

    CachedLine cached = lines.get(line.id);
    if (cached != null && cached.lineVersion == line.version && cached.node.hasDisplayList()) {
      cached.lastUsedFrame = frameNumber;
      cached.pinnedThisFrame = true;
      TerminalRenderMetrics.rowCacheHit();
      if (historyLine) TerminalRenderMetrics.historyCacheHit();
      cached.node.draw(canvas, rowTop);
      return LineDrawResult.HIT;
    }

    if (cached != null) {
      TerminalRenderMetrics.rowCacheStaleFallback();
    } else {
      cached = obtainEntry(line.id);
      if (cached == null) return LineDrawResult.UNAVAILABLE;
    }
    if (!record(cached, line)) return LineDrawResult.UNAVAILABLE;
    cached.lastUsedFrame = frameNumber;
    cached.pinnedThisFrame = true;
    if (historyLine) TerminalRenderMetrics.historyCacheMiss();
    cached.node.draw(canvas, rowTop);
    return LineDrawResult.RECORDED;
  }

  void endFrame() {
    for (int i = 0; i < lines.size(); i++) lines.valueAt(i).pinnedThisFrame = false;
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
    cached.pinnedThisFrame = false;
    lines.put(lineId, cached);
    return cached;
  }

  private int findVictim(boolean includePinned) {
    int victim = -1;
    long oldestFrame = Long.MAX_VALUE;
    for (int i = 0; i < lines.size(); i++) {
      CachedLine candidate = lines.valueAt(i);
      if (!includePinned && candidate.pinnedThisFrame) continue;
      if (candidate.lastUsedFrame < oldestFrame) {
        oldestFrame = candidate.lastUsedFrame;
        victim = i;
      }
    }
    return victim;
  }

  private boolean record(@NonNull CachedLine cached, @NonNull TerminalLine line) {
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
    cached.lineId = line.id;
    cached.lineVersion = line.version;
    TerminalRenderMetrics.rowCacheMiss();
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

  @Nullable
  TerminalRowNode nodeForLineForTest(long lineId) {
    CachedLine cached = lines.get(lineId);
    return cached != null ? cached.node : null;
  }

  private static final class CachedLine {
    final TerminalRowNode node;
    long lineId;
    long lineVersion;
    long lastUsedFrame;
    boolean pinnedThisFrame;

    CachedLine(@NonNull TerminalRowNode node) {
      this.node = node;
    }
  }
}
