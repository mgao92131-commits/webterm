package com.webterm.terminal.renderer;

import android.graphics.Canvas;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalRenderMetrics;

/**
 * 实时终端屏幕行的 RenderNode 行级缓存。
 *
 * <p>每个实时屏幕行对应一个缓存槽位。屏幕滚动时通过调整逻辑索引到物理槽位的映射
 * 复用未变化行的 RenderNode，只重新录制新暴露或内容变化的行。光标闪烁和选择变化
 * 不会触发正文重新录制。</p>
 */
public final class TerminalRowRenderNodeCache {
  private static final int MAX_ROWS = 512;

  private final TerminalRowNodeFactory nodeFactory;
  private CachedRow[] slots = new CachedRow[0];
  private int firstPhysicalSlot;
  private int rowCount;
  private int columns;
  private int widthPx;
  private int heightPx;
  private int fontGeneration;
  private int paletteGeneration;
  private int styleGeneration;

  private long lastLayoutEpoch;
  @Nullable private String lastInstanceId;

  TerminalRowRenderNodeCache() {
    this(name -> new RenderNodeTerminalRowNode(name));
  }

  TerminalRowRenderNodeCache(@NonNull TerminalRowNodeFactory nodeFactory) {
    this.nodeFactory = nodeFactory;
  }

  /**
   * 在绘制前根据当前快照和脏区更新缓存。
   *
   * @return 本次重新录制的行数
   */
  int prepareFrame(@NonNull RemoteTerminalModel.RenderSnapshot snapshot,
                   @NonNull RenderDirtyState dirty,
                   @NonNull RemoteTerminalRenderer renderer,
                   @NonNull TerminalPalette palette, int canvasBackground,
                   int fontGeneration, int paletteGeneration, int styleGeneration) {
    TerminalLine[] screen = snapshot.screen;
    int rows = screen != null ? screen.length : 0;
    int cols = snapshot.columns;
    int lineH = Math.round(renderer.getLineHeight());
    int rowW = Math.round(cols * renderer.getCellWidth());

    boolean structuralInvalidation = dirty.fullInvalidate || dirty.geometryChanged
        || dirty.activeBufferChanged || rows <= 0 || cols <= 0
        || rows != rowCount || cols != columns
        || lineH != heightPx || rowW != widthPx
        || snapshot.layoutEpoch != lastLayoutEpoch
        || lastInstanceId == null || !lastInstanceId.equals(snapshot.instanceId)
        || this.fontGeneration != fontGeneration
        || this.paletteGeneration != paletteGeneration
        || this.styleGeneration != styleGeneration;

    if (structuralInvalidation) {
      configure(rows, cols, rowW, lineH, fontGeneration, paletteGeneration, styleGeneration);
      lastLayoutEpoch = snapshot.layoutEpoch;
      lastInstanceId = snapshot.instanceId;
      recordAll(screen, renderer, palette, canvasBackground);
      return rows;
    }

    if (dirty.screenScrollRows != 0) {
      int shift = dirty.screenScrollRows;
      firstPhysicalSlot = (firstPhysicalSlot + shift) % rowCount;
      if (firstPhysicalSlot < 0) firstPhysicalSlot += rowCount;
      // 滚动后需要重录的是暴露行与同 Patch 内 version 升高的保留行的并集；
      // 只录暴露行会让保留行复用旧录制，显示陈旧内容。
      java.util.BitSet rowsToRecord = (java.util.BitSet) dirty.exposedScreenRows.clone();
      rowsToRecord.or(dirty.changedScreenRows);
      int recorded = 0;
      for (int row = rowsToRecord.nextSetBit(0); row >= 0 && row < rows;
           row = rowsToRecord.nextSetBit(row + 1)) {
        recordRow(slotAt(row), screen[row], renderer, palette, canvasBackground,
            fontGeneration, paletteGeneration, styleGeneration);
        recorded++;
      }
      TerminalRenderMetrics.rowCacheReuse(rowCount - recorded);
      return recorded;
    }

    int recorded = 0;
    for (int row = dirty.changedScreenRows.nextSetBit(0); row >= 0;
         row = dirty.changedScreenRows.nextSetBit(row + 1)) {
      if (row < 0 || row >= rows) continue;
      recordRow(slotAt(row), screen[row], renderer, palette, canvasBackground,
          fontGeneration, paletteGeneration, styleGeneration);
      recorded++;
    }
    return recorded;
  }

  private void configure(int rows, int columns, int widthPx, int heightPx,
                         int fontGeneration, int paletteGeneration, int styleGeneration) {
    // 超过槽位上限时直接禁用缓存（rowCount 置 0），消费方因 rowCount 与屏幕行数
    // 不等而走直接绘制回退；不要截断到 MAX_ROWS 空转——那会导致每帧结构性失效、
    // 全量重录的结果永不使用。
    int safeRows = rows > MAX_ROWS ? 0 : Math.max(0, rows);
    if (safeRows != rowCount || slots.length != safeRows) {
      CachedRow[] newSlots = new CachedRow[safeRows];
      for (int i = 0; i < safeRows; i++) {
        newSlots[i] = new CachedRow(nodeFactory.create("terminal-row-" + i));
      }
      this.slots = newSlots;
      this.firstPhysicalSlot = 0;
    }
    this.rowCount = safeRows;
    this.columns = columns;
    this.widthPx = widthPx;
    this.heightPx = heightPx;
    this.fontGeneration = fontGeneration;
    this.paletteGeneration = paletteGeneration;
    this.styleGeneration = styleGeneration;
    invalidateAll();
  }

  private void invalidateAll() {
    for (CachedRow slot : slots) {
      if (slot != null) slot.valid = false;
    }
  }

  private void recordAll(@Nullable TerminalLine[] screen,
                         @NonNull RemoteTerminalRenderer renderer,
                         @NonNull TerminalPalette palette, int canvasBackground) {
    if (screen == null) return;
    for (int row = 0; row < screen.length && row < rowCount; row++) {
      recordRow(slotAt(row), screen[row], renderer, palette, canvasBackground,
          fontGeneration, paletteGeneration, styleGeneration);
    }
  }

  private void recordRow(@NonNull CachedRow cached, @Nullable TerminalLine line,
                         @NonNull RemoteTerminalRenderer renderer,
                         @NonNull TerminalPalette palette, int canvasBackground,
                         int fontGeneration, int paletteGeneration, int styleGeneration) {
    if (line == null || widthPx <= 0 || heightPx <= 0) {
      cached.valid = false;
      return;
    }
    cached.node.setPosition(0, 0, widthPx, heightPx);
    Canvas canvas = cached.node.beginRecording(widthPx, heightPx);
    try {
      renderer.drawTerminalLineContent(canvas, columns, palette, line, 0f, canvasBackground);
    } finally {
      cached.node.endRecording();
    }
    cached.lineId = line.id;
    cached.lineVersion = line.version;
    cached.columns = columns;
    cached.widthPx = widthPx;
    cached.heightPx = heightPx;
    cached.fontGeneration = fontGeneration;
    cached.paletteGeneration = paletteGeneration;
    cached.styleGeneration = styleGeneration;
    cached.valid = true;
    TerminalRenderMetrics.rowCacheMiss();
  }

  /**
   * 绘制指定逻辑行；调用前必须先执行 {@link #prepareFrame}。
   *
   * <p>兜底校验槽位录制的 lineId/lineVersion 与当前行一致：不一致说明模型层
   * 漏标脏，返回 false 由调用方回退直接绘制，避免显示陈旧内容。</p>
   *
   * @return true 表示已用缓存录制绘制；false 表示槽位无效或校验失败，调用方须自行绘制
   */
  boolean drawRow(@NonNull Canvas canvas, int logicalRow, float rowTop,
                  @Nullable TerminalLine line) {
    if (logicalRow < 0 || logicalRow >= rowCount) return false;
    CachedRow cached = slotAt(logicalRow);
    if (!cached.valid) return false;
    if (line == null || cached.lineId != line.id || cached.lineVersion != line.version) {
      TerminalRenderMetrics.rowCacheStaleFallback();
      return false;
    }
    TerminalRenderMetrics.rowCacheHit();
    cached.node.draw(canvas, rowTop);
    return true;
  }

  private CachedRow slotAt(int logicalRow) {
    int physical = firstPhysicalSlot + logicalRow;
    physical %= rowCount;
    if (physical < 0) physical += rowCount;
    return slots[physical];
  }

  int rowCount() {
    return rowCount;
  }

  /** 供测试检查指定逻辑行当前使用的 node 身份。 */
  @NonNull
  TerminalRowNode nodeForTest(int logicalRow) {
    return slotAt(logicalRow).node;
  }

  private static final class CachedRow {
    final TerminalRowNode node;
    long lineId;
    long lineVersion;
    int columns;
    int widthPx;
    int heightPx;
    int fontGeneration;
    int paletteGeneration;
    int styleGeneration;
    boolean valid;

    CachedRow(@NonNull TerminalRowNode node) {
      this.node = node;
    }
  }
}
