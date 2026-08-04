package com.webterm.terminal.renderer;

import android.util.LongSparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalPalette;

/**
 * 独立于 RenderNode 生命周期的有界 CPU 行缓存。
 *
 * <p>RenderNode 被淘汰或本帧因 pinned conflict 无法重录时，编译结果和批量文字布局仍可
 * 复用。缓存只在 UI 线程访问，使用 second-chance clock，避免热路径维护链表。</p>
 */
final class TerminalPreparedLineCache {
  static final int MIN_ENTRY_LIMIT = 512;
  static final int MAX_ENTRY_LIMIT = 2048;
  static final long DEFAULT_BYTE_LIMIT = 12L * 1024L * 1024L;

  private final LongSparseArray<Entry> entries = new LongSparseArray<>();
  private final int configuredEntryLimit;
  private final long byteLimit;
  private int entryLimit = MIN_ENTRY_LIMIT;
  private int clockHand;
  private long estimatedBytes;

  @Nullable private String instanceId;
  private long layoutEpoch = Long.MIN_VALUE;
  private int columns;
  private int canvasBackground;
  private int fontGeneration;
  private int paletteGeneration;
  private int styleGeneration;
  private int geometryWidthBits;
  private int geometryHeightBits;
  private float frameCellWidth;
  private float frameLineHeight;
  private boolean contextReady;

  private long frameHits;
  private long frameMisses;
  private long frameEvictions;
  private long frameMetadataHits;
  private long frameMetadataMisses;

  TerminalPreparedLineCache() {
    this(MAX_ENTRY_LIMIT, DEFAULT_BYTE_LIMIT);
  }

  TerminalPreparedLineCache(int entryLimit, long byteLimit) {
    configuredEntryLimit = clamp(entryLimit, 1, MAX_ENTRY_LIMIT);
    this.byteLimit = Math.max(1L, byteLimit);
  }

  void beginFrame(
      @NonNull RemoteTerminalModel.RenderSnapshot snapshot,
      @NonNull RemoteTerminalRenderer renderer,
      int canvasBackground,
      int fontGeneration,
      int paletteGeneration,
      int styleGeneration) {
    int minimum = Math.min(MIN_ENTRY_LIMIT, configuredEntryLimit);
    int nextEntryLimit = clamp(
        snapshot.screenView.size() * 16, minimum, configuredEntryLimit);
    int nextColumns = snapshot.columns;
    int nextWidthBits = Float.floatToIntBits(renderer.getCellWidth());
    int nextHeightBits = Float.floatToIntBits(renderer.getLineHeight());
    boolean changed = !contextReady
        || !same(instanceId, snapshot.instanceId)
        || layoutEpoch != snapshot.layoutEpoch
        || columns != nextColumns
        || canvasBackground != this.canvasBackground
        || this.fontGeneration != fontGeneration
        || this.paletteGeneration != paletteGeneration
        || this.styleGeneration != styleGeneration
        || geometryWidthBits != nextWidthBits
        || geometryHeightBits != nextHeightBits;
    if (changed) clear();

    contextReady = true;
    instanceId = snapshot.instanceId;
    layoutEpoch = snapshot.layoutEpoch;
    columns = nextColumns;
    this.canvasBackground = canvasBackground;
    this.fontGeneration = fontGeneration;
    this.paletteGeneration = paletteGeneration;
    this.styleGeneration = styleGeneration;
    geometryWidthBits = nextWidthBits;
    geometryHeightBits = nextHeightBits;
    frameCellWidth = renderer.getCellWidth();
    frameLineHeight = renderer.getLineHeight();
    entryLimit = nextEntryLimit;
    frameHits = frameMisses = frameEvictions = 0L;
    frameMetadataHits = frameMetadataMisses = 0L;
    trimToLimits();
  }

  @Nullable
  PreparedTerminalLine get(@Nullable RenderLine line) {
    Entry entry = find(line);
    if (entry == null || entry.prepared == null) {
      frameMisses++;
      return null;
    }
    entry.recentlyUsed = true;
    frameHits++;
    return entry.prepared;
  }

  @NonNull
  PreparedTerminalLine getOrPrepare(
      @NonNull RenderLine line,
      @NonNull RemoteTerminalRenderer renderer,
      int columns,
      @NonNull TerminalPalette palette,
      int canvasBackground) {
    Entry entry = find(line);
    if (entry != null && entry.prepared != null) {
      entry.recentlyUsed = true;
      frameHits++;
      return entry.prepared;
    }

    frameMisses++;
    PreparedTerminalLine prepared = renderer.compileAndPrepareLine(
        line, columns, palette, canvasBackground);
    if (entry == null) {
      entry = new Entry(line.key().lineId(), line.key().lineVersion(), line, prepared);
      addEntry(entry);
    } else {
      estimatedBytes -= entry.estimatedBytes;
      entry.lineVersion = line.key().lineVersion();
      entry.source = line;
      entry.prepared = prepared;
      entry.visibleBlinkKinds = prepared.visibleBlinkKinds;
      entry.metadataKnown = true;
      entry.estimatedBytes = estimateEntryBytes(entry);
      estimatedBytes += entry.estimatedBytes;
      entry.recentlyUsed = true;
      trimToLimits();
    }
    return prepared;
  }

  /** 返回行的 blink 元数据；首次 metadata miss 最多扫描一次。 */
  int visibleBlinkKinds(
      @NonNull RenderLine line,
      @NonNull RemoteTerminalRenderer renderer,
      int columns,
      @NonNull TerminalPalette palette,
      int canvasBackground) {
    Entry entry = find(line);
    if (entry != null && entry.metadataKnown) {
      entry.recentlyUsed = true;
      frameMetadataHits++;
      return entry.visibleBlinkKinds;
    }

    frameMetadataMisses++;
    int kinds = renderer.visibleBlinkKinds(line, columns, palette, canvasBackground);
    if (entry == null) {
      entry = new Entry(line.key().lineId(), line.key().lineVersion(), line, null);
      entry.visibleBlinkKinds = kinds;
      entry.metadataKnown = true;
      addEntry(entry);
    } else {
      entry.visibleBlinkKinds = kinds;
      entry.metadataKnown = true;
      entry.recentlyUsed = true;
    }
    return kinds;
  }

  /**
   * 为动态覆盖层按需恢复 CPU 行结果。
   *
   * <p>RenderNode 的 display list 可以仍然命中，但对应的 PreparedTerminalLine 可能已经
   * 被独立的 CPU 缓存预算淘汰。普通静态命中不需要重新 prepare；只有 block cursor 或当前
   * blink phase 确实需要重放前景时，才恢复完整的 prepared line。</p>
   */
  @Nullable
  PreparedTerminalLine getForDynamicOverlay(
      @NonNull RenderLine line,
      @NonNull RemoteTerminalRenderer renderer,
      int columns,
      @NonNull TerminalPalette palette,
      int canvasBackground,
      @NonNull TerminalAnimationState animationState,
      boolean blockCursorNeedsLine) {
    PreparedTerminalLine prepared = get(line);
    if (prepared != null) {
      return blockCursorNeedsLine
          || blinkVisible(prepared.visibleBlinkKinds, animationState)
          ? prepared : null;
    }

    if (blockCursorNeedsLine) {
      return getOrPrepare(line, renderer, columns, palette, canvasBackground);
    }

    int blinkKinds = visibleBlinkKinds(
        line, renderer, columns, palette, canvasBackground);
    if (!blockCursorNeedsLine && !blinkVisible(blinkKinds, animationState)) {
      return null;
    }
    return getOrPrepare(line, renderer, columns, palette, canvasBackground);
  }

  void clear() {
    entries.clear();
    clockHand = 0;
    estimatedBytes = 0L;
  }

  int sizeForTest() { return entries.size(); }
  long estimatedBytesForTest() { return estimatedBytes; }
  long hitCountForTest() { return frameHits; }
  long missCountForTest() { return frameMisses; }
  long evictionCountForTest() { return frameEvictions; }
  long metadataHitCountForTest() { return frameMetadataHits; }
  long metadataMissCountForTest() { return frameMetadataMisses; }
  int entryLimitForTest() { return entryLimit; }
  float frameCellWidthForTest() { return frameCellWidth; }
  float frameLineHeightForTest() { return frameLineHeight; }

  private void addEntry(@NonNull Entry entry) {
    // 同一 lineId 的旧版本可能仍在表中。LongSparseArray.put() 会覆盖它，但不会
    // 自动扣除旧 entry 的预算；先移除，避免版本更新后 estimatedBytes 虚高。
    int existingIndex = entries.indexOfKey(entry.lineId);
    if (existingIndex >= 0) {
      estimatedBytes -= entries.valueAt(existingIndex).estimatedBytes;
      entries.removeAt(existingIndex);
      if (entries.size() == 0) {
        clockHand = 0;
      } else if (clockHand > existingIndex) {
        clockHand--;
      } else if (clockHand >= entries.size()) {
        clockHand = 0;
      }
    }
    evictFor(entry.estimatedBytes);
    entries.put(entry.lineId, entry);
    estimatedBytes += entry.estimatedBytes;
  }

  @Nullable
  private Entry find(@Nullable RenderLine line) {
    if (!contextReady || line == null) return null;
    Entry entry = entries.get(line.key().lineId());
    if (entry == null || entry.lineVersion != line.key().lineVersion()) return null;
    if (entry.source != line && !entry.source.body().equals(line.body())) return null;
    return entry;
  }

  private void evictFor(long incomingBytes) {
    while (entries.size() >= entryLimit
        || (estimatedBytes + incomingBytes > byteLimit && entries.size() > 0)) {
      int victim = findVictim();
      if (victim < 0) break;
      Entry entry = entries.valueAt(victim);
      estimatedBytes -= entry.estimatedBytes;
      entries.removeAt(victim);
      frameEvictions++;
      clockHand = entries.size() == 0 ? 0 : victim % entries.size();
    }
  }

  private int findVictim() {
    int size = entries.size();
    if (size == 0) return -1;
    for (int step = 0; step < size * 2; step++) {
      int index = (clockHand + step) % size;
      Entry entry = entries.valueAt(index);
      if (entry.recentlyUsed) {
        entry.recentlyUsed = false;
      } else {
        return index;
      }
    }
    return 0;
  }

  private void trimToLimits() {
    while (entries.size() > entryLimit || estimatedBytes > byteLimit) {
      int victim = findVictim();
      if (victim < 0) return;
      Entry entry = entries.valueAt(victim);
      // 保留一个超预算的大 entry，避免刚编译的单行结果立即被清掉。
      if (entries.size() == 1) return;
      estimatedBytes -= entry.estimatedBytes;
      entries.removeAt(victim);
      frameEvictions++;
      clockHand = entries.size() == 0 ? 0 : victim % entries.size();
    }
  }

  private static long estimateEntryBytes(@NonNull Entry entry) {
    long bytes = 96L;
    if (entry.source != null) {
      bytes += entry.source.body().estimatedBytes;
    }
    if (entry.prepared != null) bytes += entry.prepared.estimatedBytes;
    return bytes;
  }

  private static boolean same(@Nullable String first, @Nullable String second) {
    return first == null ? second == null : first.equals(second);
  }

  private static boolean blinkVisible(
      int blinkKinds, @NonNull TerminalAnimationState animationState) {
    return ((blinkKinds & TerminalLineCompiler.BLINK_SLOW) != 0
            && animationState.slowBlinkOn())
        || ((blinkKinds & TerminalLineCompiler.BLINK_FAST) != 0
            && animationState.fastBlinkOn());
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static final class Entry {
    final long lineId;
    long lineVersion;
    @NonNull RenderLine source;
    @Nullable PreparedTerminalLine prepared;
    long estimatedBytes;
    int visibleBlinkKinds;
    boolean metadataKnown;
    boolean recentlyUsed;

    Entry(long lineId, long lineVersion, @NonNull RenderLine source,
          @Nullable PreparedTerminalLine prepared) {
      this.lineId = lineId;
      this.lineVersion = lineVersion;
      this.source = source;
      this.prepared = prepared;
      this.visibleBlinkKinds = prepared == null ? 0 : prepared.visibleBlinkKinds;
      this.metadataKnown = prepared != null;
      this.estimatedBytes = estimateEntryBytes(this);
      this.recentlyUsed = true;
    }
  }
}
