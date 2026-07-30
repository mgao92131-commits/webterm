package com.webterm.terminal.model;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Process-local terminal rendering counters. They deliberately contain no terminal content,
 * session id, title, path, or clipboard data, so callers can expose a snapshot for diagnostics.
 */
public final class TerminalRenderMetrics {
  public static final int LATENCY_BUCKET_COUNT = 8;
  private static final long[] LATENCY_BUCKET_UPPER_BOUNDS_NANOS = {
      250_000L, 500_000L, 1_000_000L, 2_000_000L,
      4_000_000L, 8_000_000L, 16_000_000L
  };
  private static final AtomicLong MODEL_CHANGE_COUNT = new AtomicLong();
  private static final AtomicLong UI_CALLBACK_SCHEDULE_COUNT = new AtomicLong();
  private static final AtomicLong UI_CALLBACK_COALESCED_COUNT = new AtomicLong();
  private static final AtomicLong RENDER_REQUEST_COUNT = new AtomicLong();
  private static final AtomicLong VSYNC_RENDER_COUNT = new AtomicLong();
  private static final AtomicLong FULL_INVALIDATE_COUNT = new AtomicLong();
  private static final AtomicLongArray FULL_INVALIDATE_BY_REASON =
      new AtomicLongArray(FullInvalidateReason.values().length);
  private static final AtomicLong PARTIAL_INVALIDATE_COUNT = new AtomicLong();
  private static final AtomicLong DIRTY_ROW_COUNT = new AtomicLong();
  private static final AtomicLong SCREEN_REGION_INVALIDATE_COUNT = new AtomicLong();
  private static final AtomicLong PARTIAL_ROW_INVALIDATE_COUNT = new AtomicLong();
  private static final AtomicLong SCREEN_SCROLL_EVENT_COUNT = new AtomicLong();
  private static final AtomicLong SCREEN_SCROLL_ROW_TOTAL = new AtomicLong();
  private static final AtomicLong ROW_CACHE_HIT_COUNT = new AtomicLong();
  private static final AtomicLong ROW_CACHE_MISS_COUNT = new AtomicLong();
  private static final AtomicLong ROW_CACHE_STALE_FALLBACK_COUNT = new AtomicLong();
  private static final AtomicLong ROW_CACHE_PINNED_CONFLICT_COUNT = new AtomicLong();
  private static final AtomicLong ROW_NODE_RECORD_COUNT = new AtomicLong();
  private static final AtomicLong ROW_NODE_REUSE_COUNT = new AtomicLong();
  private static final AtomicLong HISTORY_ONLY_NO_DRAW_COUNT = new AtomicLong();
  private static final AtomicLong RENDER_DURATION_NANOS = new AtomicLong();
  private static final AtomicLong RENDER_DURATION_COUNT = new AtomicLong();
  private static final AtomicLong RENDER_DURATION_MAX_NANOS = new AtomicLong();
  private static final AtomicLongArray RENDER_DURATION_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLong VIEWPORT_CALCULATION_NANOS = new AtomicLong();
  private static final AtomicLong HISTORY_ROW_LOOKUP_NANOS = new AtomicLong();
  private static final AtomicLong SCREEN_ROW_LOOKUP_NANOS = new AtomicLong();
  private static final AtomicLong RENDER_NODE_DRAW_OR_RECORD_NANOS = new AtomicLong();
  private static final AtomicLong CANVAS_DRAW_NANOS = new AtomicLong();
  private static final AtomicLong PROTOBUF_PARSE_NANOS = new AtomicLong();
  private static final AtomicLong PROTOBUF_PARSE_COUNT = new AtomicLong();
  private static final AtomicLong MODEL_APPLY_NANOS = new AtomicLong();
  private static final AtomicLong MAIN_CALLBACK_DELAY_NANOS = new AtomicLong();
  private static final AtomicLong BASELINE_FRAME_COUNT = new AtomicLong();
  private static final AtomicLong BASELINE_FRAME_BYTES = new AtomicLong();
  private static final AtomicLong COMMIT_FRAME_COUNT = new AtomicLong();
  private static final AtomicLong COMMIT_FRAME_BYTES = new AtomicLong();
  private static final AtomicLong HISTORY_RANGE_FRAME_COUNT = new AtomicLong();
  private static final AtomicLong HISTORY_RANGE_FRAME_BYTES = new AtomicLong();
  private static final AtomicLong OTHER_FRAME_COUNT = new AtomicLong();
  private static final AtomicLong OTHER_FRAME_BYTES = new AtomicLong();
  private static final AtomicLong MAILBOX_RESIDENCE_NANOS = new AtomicLong();
  private static final AtomicLong MAILBOX_RESIDENCE_MAX_NANOS = new AtomicLong();
  private static final AtomicLong VIEWPORT_REDRAW_REQUEST_COUNT = new AtomicLong();
  private static final AtomicLong VIEWPORT_FULL_REDRAW_COUNT = new AtomicLong();
  private static final AtomicLongArray TERMINAL_COMMIT_APPLY_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLongArray PROTOBUF_PARSE_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLongArray MAPPER_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLongArray DICTIONARY_STAGING_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLongArray RENDER_PUBLICATION_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLongArray RENDER_NODE_RECORD_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLongArray VSYNC_DRAW_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLongArray MAILBOX_RESIDENCE_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLong HISTORY_CACHE_HIT_COUNT = new AtomicLong();
  private static final AtomicLong HISTORY_CACHE_MISS_COUNT = new AtomicLong();
  private static final AtomicLong VISIBLE_HISTORY_ROWS_DRAWN = new AtomicLong();
  private static final AtomicLong RENDER_NODE_VICTIM_SCAN_COUNT = new AtomicLong();
  private static final AtomicLong RENDER_NODE_VICTIM_SCANNED_ENTRIES = new AtomicLong();
  private static final AtomicLong RENDER_NODE_ALL_PINNED_FALLBACK_COUNT = new AtomicLong();

  private TerminalRenderMetrics() {}

  public static void modelChange() { MODEL_CHANGE_COUNT.incrementAndGet(); }
  public static void uiCallbackScheduled() { UI_CALLBACK_SCHEDULE_COUNT.incrementAndGet(); }
  public static void uiCallbackCoalesced() { UI_CALLBACK_COALESCED_COUNT.incrementAndGet(); }
  public static void renderRequested() { RENDER_REQUEST_COUNT.incrementAndGet(); }
  public static void viewportRedrawRequested() { VIEWPORT_REDRAW_REQUEST_COUNT.incrementAndGet(); }
  public static void viewportFullRedraw() { VIEWPORT_FULL_REDRAW_COUNT.incrementAndGet(); }
  public static void vsyncRender() { VSYNC_RENDER_COUNT.incrementAndGet(); }
  public enum FullInvalidateReason {
    LEGACY_SET_MODEL,
    GEOMETRY_CHANGED,
    UPSTREAM_FULL_DIRTY,
    ACTIVE_BUFFER_CHANGED,
    PALETTE_CHANGED,
    MODES_CHANGED,
    STYLES_CHANGED,
    LINKS_CHANGED,
    HISTORY_STRUCTURE_CHANGED,
    HISTORY_RANGE_UNKNOWN,
    SCREEN_AND_HISTORY_COMBINED,
    DIRTY_AREA_THRESHOLD,
    FALLBACK,
    UNKNOWN
  }

  public static void fullInvalidate(FullInvalidateReason reason) {
    FULL_INVALIDATE_COUNT.incrementAndGet();
    FullInvalidateReason safe = reason != null ? reason : FullInvalidateReason.UNKNOWN;
    FULL_INVALIDATE_BY_REASON.incrementAndGet(safe.ordinal());
  }
  /** 仅兼容旧调用方；新调用必须给出原因。 */
  @Deprecated
  public static void fullInvalidate() { fullInvalidate(FullInvalidateReason.UNKNOWN); }
  public static void screenRegionInvalidate() { SCREEN_REGION_INVALIDATE_COUNT.incrementAndGet(); }
  public static void partialRowInvalidate(int rows) {
    PARTIAL_ROW_INVALIDATE_COUNT.incrementAndGet();
    DIRTY_ROW_COUNT.addAndGet(Math.max(0, rows));
  }
  public static void partialInvalidate(int rows) {
    PARTIAL_INVALIDATE_COUNT.incrementAndGet();
    DIRTY_ROW_COUNT.addAndGet(Math.max(0, rows));
  }
  public static void screenScrollEvent(int rows) {
    SCREEN_SCROLL_EVENT_COUNT.incrementAndGet();
    SCREEN_SCROLL_ROW_TOTAL.addAndGet(Math.max(0, rows));
  }
  public static void rowCacheHit() { ROW_CACHE_HIT_COUNT.incrementAndGet(); }
  /** 行缓存槽位的 lineId/lineVersion 与当前行不一致、回退直接绘制的次数。 */
  public static void rowCacheStaleFallback() { ROW_CACHE_STALE_FALLBACK_COUNT.incrementAndGet(); }
  /** 本帧已绘制的节点遇到同 LineID 新 version，禁止重录并回退直接 Canvas。 */
  public static void rowCachePinnedConflict() { ROW_CACHE_PINNED_CONFLICT_COUNT.incrementAndGet(); }
  public static void rowCacheMiss() {
    ROW_CACHE_MISS_COUNT.incrementAndGet();
    ROW_NODE_RECORD_COUNT.incrementAndGet();
  }
  public static void rowCacheReuse(long reuseCount) {
    ROW_NODE_REUSE_COUNT.addAndGet(Math.max(0, reuseCount));
  }
  public static void historyOnlyNoDraw() { HISTORY_ONLY_NO_DRAW_COUNT.incrementAndGet(); }
  public static void renderDuration(long nanos) {
    long safe = Math.max(0L, nanos);
    RENDER_DURATION_COUNT.incrementAndGet();
    RENDER_DURATION_NANOS.addAndGet(safe);
    updateMax(RENDER_DURATION_MAX_NANOS, safe);
    recordLatency(RENDER_DURATION_LATENCY_BUCKETS, safe);
  }
  public static void renderFramePhases(
      long viewportCalculationNanos, long historyRowLookupNanos,
      long screenRowLookupNanos, long renderNodeDrawOrRecordNanos, long canvasDrawNanos) {
    VIEWPORT_CALCULATION_NANOS.addAndGet(Math.max(0L, viewportCalculationNanos));
    HISTORY_ROW_LOOKUP_NANOS.addAndGet(Math.max(0L, historyRowLookupNanos));
    SCREEN_ROW_LOOKUP_NANOS.addAndGet(Math.max(0L, screenRowLookupNanos));
    RENDER_NODE_DRAW_OR_RECORD_NANOS.addAndGet(
        Math.max(0L, renderNodeDrawOrRecordNanos));
    CANVAS_DRAW_NANOS.addAndGet(Math.max(0L, canvasDrawNanos));
  }
  public static void protobufParseDuration(long nanos) {
    PROTOBUF_PARSE_COUNT.incrementAndGet();
    long safe = Math.max(0L, nanos);
    PROTOBUF_PARSE_NANOS.addAndGet(safe);
    recordLatency(PROTOBUF_PARSE_LATENCY_BUCKETS, safe);
  }
  public static void terminalCommitApplyDuration(long nanos) {
    recordLatency(TERMINAL_COMMIT_APPLY_LATENCY_BUCKETS, Math.max(0L, nanos));
  }
  public static void mapperDuration(long nanos) {
    recordLatency(MAPPER_LATENCY_BUCKETS, Math.max(0L, nanos));
  }
  public static void dictionaryStagingDuration(long nanos) {
    recordLatency(DICTIONARY_STAGING_LATENCY_BUCKETS, Math.max(0L, nanos));
  }
  public static void renderPublicationDuration(long nanos) {
    recordLatency(RENDER_PUBLICATION_LATENCY_BUCKETS, Math.max(0L, nanos));
  }
  public static void renderNodeRecordDuration(long nanos) {
    recordLatency(RENDER_NODE_RECORD_LATENCY_BUCKETS, Math.max(0L, nanos));
  }
  public static void vsyncDrawDuration(long nanos) {
    recordLatency(VSYNC_DRAW_LATENCY_BUCKETS, Math.max(0L, nanos));
  }
  public static void historyCacheHit() { HISTORY_CACHE_HIT_COUNT.incrementAndGet(); }
  public static void historyCacheMiss() { HISTORY_CACHE_MISS_COUNT.incrementAndGet(); }

  private static final AtomicLong HISTORY_AXIS_UPDATE_COUNT = new AtomicLong();
  private static final AtomicLong HISTORY_AXIS_FULL_REBUILD_COUNT = new AtomicLong();
  private static final AtomicLong HISTORY_AXIS_PAGES_REBUILT = new AtomicLong();
  private static final AtomicLong HISTORY_AXIS_PAGES_REUSED = new AtomicLong();
  private static final AtomicLong HISTORY_AXIS_ROWS_SCANNED = new AtomicLong();
  private static final AtomicLong HISTORY_AXIS_UPDATE_NANOS = new AtomicLong();

  public static void historyAxisUpdate(
      long durationNanos, int pagesRebuilt, int pagesReused, int rowsScanned, boolean fullRebuild) {
    HISTORY_AXIS_UPDATE_COUNT.incrementAndGet();
    HISTORY_AXIS_PAGES_REBUILT.addAndGet(Math.max(0, pagesRebuilt));
    HISTORY_AXIS_PAGES_REUSED.addAndGet(Math.max(0, pagesReused));
    HISTORY_AXIS_ROWS_SCANNED.addAndGet(Math.max(0, rowsScanned));
    HISTORY_AXIS_UPDATE_NANOS.addAndGet(Math.max(0L, durationNanos));
    if (fullRebuild) HISTORY_AXIS_FULL_REBUILD_COUNT.incrementAndGet();
  }

  public static void historyAxisFullRebuild(String reason) {
    // 原因由调用方传入供后续诊断扩展；计数由 historyAxisUpdate(fullRebuild=true) 统一累加。
  }

  public static long historyAxisUpdateCount() { return HISTORY_AXIS_UPDATE_COUNT.get(); }
  public static long historyAxisFullRebuildCount() { return HISTORY_AXIS_FULL_REBUILD_COUNT.get(); }
  public static long historyAxisPagesRebuilt() { return HISTORY_AXIS_PAGES_REBUILT.get(); }
  public static long historyAxisPagesReused() { return HISTORY_AXIS_PAGES_REUSED.get(); }
  public static long historyAxisRowsScanned() { return HISTORY_AXIS_ROWS_SCANNED.get(); }
  /** 每帧结束批量提交行级热路径指标，每个非零项最多一次原子 add。 */
  public static void addRowCacheStats(long rowHits, long rowMisses,
                                      long historyHits, long historyMisses,
                                      long staleFallbacks, long pinnedConflicts) {
    if (rowHits > 0) ROW_CACHE_HIT_COUNT.addAndGet(rowHits);
    if (rowMisses > 0) {
      ROW_CACHE_MISS_COUNT.addAndGet(rowMisses);
      ROW_NODE_RECORD_COUNT.addAndGet(rowMisses);
    }
    if (historyHits > 0) HISTORY_CACHE_HIT_COUNT.addAndGet(historyHits);
    if (historyMisses > 0) HISTORY_CACHE_MISS_COUNT.addAndGet(historyMisses);
    if (staleFallbacks > 0) ROW_CACHE_STALE_FALLBACK_COUNT.addAndGet(staleFallbacks);
    if (pinnedConflicts > 0) ROW_CACHE_PINNED_CONFLICT_COUNT.addAndGet(pinnedConflicts);
  }
  /** 淘汰只发生在 miss/容量收缩，允许低频直接提交。 */
  public static void renderNodeVictimScan(int scannedEntries, boolean allPinned) {
    RENDER_NODE_VICTIM_SCAN_COUNT.incrementAndGet();
    RENDER_NODE_VICTIM_SCANNED_ENTRIES.addAndGet(Math.max(0, scannedEntries));
    if (allPinned) RENDER_NODE_ALL_PINNED_FALLBACK_COUNT.incrementAndGet();
  }
  public static void visibleHistoryRowsDrawn(int rows) {
    VISIBLE_HISTORY_ROWS_DRAWN.addAndGet(Math.max(0, rows));
  }
  public static void modelApplyDuration(long nanos) {
    MODEL_APPLY_NANOS.addAndGet(Math.max(0L, nanos));
  }
  public static void mainThreadCallbackDelay(long nanos) {
    MAIN_CALLBACK_DELAY_NANOS.addAndGet(Math.max(0L, nanos));
  }
  /** 屏幕协议消息的分类标记；不依赖其他模块枚举的 ordinal。 */
  public enum ScreenTrafficKind {
    BASELINE, COMMIT, HISTORY_RANGE, OTHER
  }

  /** Records only wire class and length; terminal contents never enter diagnostics. */
  public static void inboundScreenFrame(ScreenTrafficKind kind, int bytes) {
    AtomicLong count;
    AtomicLong totalBytes;
    if (kind == ScreenTrafficKind.BASELINE) {
      count = BASELINE_FRAME_COUNT;
      totalBytes = BASELINE_FRAME_BYTES;
    } else if (kind == ScreenTrafficKind.COMMIT) {
      count = COMMIT_FRAME_COUNT;
      totalBytes = COMMIT_FRAME_BYTES;
    } else if (kind == ScreenTrafficKind.HISTORY_RANGE) {
      count = HISTORY_RANGE_FRAME_COUNT;
      totalBytes = HISTORY_RANGE_FRAME_BYTES;
    } else {
      count = OTHER_FRAME_COUNT;
      totalBytes = OTHER_FRAME_BYTES;
    }
    count.incrementAndGet();
    totalBytes.addAndGet(Math.max(0, bytes));
  }
  public static void mailboxResidenceDuration(long nanos) {
    long safe = Math.max(0L, nanos);
    MAILBOX_RESIDENCE_NANOS.addAndGet(safe);
    updateMax(MAILBOX_RESIDENCE_MAX_NANOS, safe);
    recordLatency(MAILBOX_RESIDENCE_LATENCY_BUCKETS, safe);
  }

  public static Snapshot snapshot() {
    return new Snapshot(MODEL_CHANGE_COUNT.get(), UI_CALLBACK_SCHEDULE_COUNT.get(),
        UI_CALLBACK_COALESCED_COUNT.get(), RENDER_REQUEST_COUNT.get(), VSYNC_RENDER_COUNT.get(),
        FULL_INVALIDATE_COUNT.get(), PARTIAL_INVALIDATE_COUNT.get(), DIRTY_ROW_COUNT.get(),
        SCREEN_REGION_INVALIDATE_COUNT.get(), PARTIAL_ROW_INVALIDATE_COUNT.get(),
        SCREEN_SCROLL_EVENT_COUNT.get(), SCREEN_SCROLL_ROW_TOTAL.get(),
        ROW_CACHE_HIT_COUNT.get(), ROW_CACHE_MISS_COUNT.get(),
        ROW_CACHE_STALE_FALLBACK_COUNT.get(), ROW_CACHE_PINNED_CONFLICT_COUNT.get(),
        ROW_NODE_RECORD_COUNT.get(), ROW_NODE_REUSE_COUNT.get(),
        HISTORY_ONLY_NO_DRAW_COUNT.get(),
        RENDER_DURATION_COUNT.get(), RENDER_DURATION_NANOS.get(), RENDER_DURATION_MAX_NANOS.get(),
        copyBuckets(RENDER_DURATION_LATENCY_BUCKETS),
        VIEWPORT_CALCULATION_NANOS.get(), HISTORY_ROW_LOOKUP_NANOS.get(),
        SCREEN_ROW_LOOKUP_NANOS.get(), RENDER_NODE_DRAW_OR_RECORD_NANOS.get(),
        CANVAS_DRAW_NANOS.get(), copyBuckets(FULL_INVALIDATE_BY_REASON),
        PROTOBUF_PARSE_NANOS.get(),
        PROTOBUF_PARSE_COUNT.get(), MODEL_APPLY_NANOS.get(), MAIN_CALLBACK_DELAY_NANOS.get(),
        BASELINE_FRAME_COUNT.get(), BASELINE_FRAME_BYTES.get(), COMMIT_FRAME_COUNT.get(),
        COMMIT_FRAME_BYTES.get(), HISTORY_RANGE_FRAME_COUNT.get(), HISTORY_RANGE_FRAME_BYTES.get(),
        OTHER_FRAME_COUNT.get(),
        OTHER_FRAME_BYTES.get(), MAILBOX_RESIDENCE_NANOS.get(), MAILBOX_RESIDENCE_MAX_NANOS.get(),
        VIEWPORT_REDRAW_REQUEST_COUNT.get(), VIEWPORT_FULL_REDRAW_COUNT.get(),
        copyBuckets(TERMINAL_COMMIT_APPLY_LATENCY_BUCKETS),
        copyBuckets(PROTOBUF_PARSE_LATENCY_BUCKETS),
        copyBuckets(MAPPER_LATENCY_BUCKETS),
        copyBuckets(DICTIONARY_STAGING_LATENCY_BUCKETS),
        copyBuckets(RENDER_PUBLICATION_LATENCY_BUCKETS),
        copyBuckets(RENDER_NODE_RECORD_LATENCY_BUCKETS),
        copyBuckets(VSYNC_DRAW_LATENCY_BUCKETS),
        copyBuckets(MAILBOX_RESIDENCE_LATENCY_BUCKETS),
        HISTORY_CACHE_HIT_COUNT.get(), HISTORY_CACHE_MISS_COUNT.get(),
        VISIBLE_HISTORY_ROWS_DRAWN.get(), RENDER_NODE_VICTIM_SCAN_COUNT.get(),
        RENDER_NODE_VICTIM_SCANNED_ENTRIES.get(),
        RENDER_NODE_ALL_PINNED_FALLBACK_COUNT.get());
  }

  private static void recordLatency(AtomicLongArray buckets, long nanos) {
    int bucket = LATENCY_BUCKET_UPPER_BOUNDS_NANOS.length;
    for (int i = 0; i < LATENCY_BUCKET_UPPER_BOUNDS_NANOS.length; i++) {
      if (nanos < LATENCY_BUCKET_UPPER_BOUNDS_NANOS[i]) {
        bucket = i;
        break;
      }
    }
    buckets.incrementAndGet(bucket);
  }

  private static long[] copyBuckets(AtomicLongArray source) {
    long[] copy = new long[source.length()];
    for (int i = 0; i < copy.length; i++) copy[i] = source.get(i);
    return copy;
  }

  private static void updateMax(AtomicLong counter, long value) {
    long current = counter.get();
    while (value > current && !counter.compareAndSet(current, value)) current = counter.get();
  }

  public static final class Snapshot {
    public final long modelChangeCount;
    public final long uiCallbackScheduleCount;
    public final long uiCallbackCoalescedCount;
    public final long renderRequestCount;
    public final long vsyncRenderCount;
    public final long fullInvalidateCount;
    public final long partialInvalidateCount;
    public final long dirtyRowCount;
    public final long screenRegionInvalidateCount;
    public final long partialRowInvalidateCount;
    public final long screenScrollEventCount;
    public final long screenScrollRowTotal;
    public final long rowCacheHitCount;
    public final long rowCacheMissCount;
    public final long rowCacheStaleFallbackCount;
    public final long rowCachePinnedConflictCount;
    public final long rowNodeRecordCount;
    public final long rowNodeReuseCount;
    public final long historyOnlyNoDrawCount;
    public final long renderDurationCount;
    public final long renderDurationNanos;
    public final long renderDurationMaxNanos;
    public final long[] renderDurationLatencyBuckets;
    public final long viewportCalculationNanos;
    public final long historyRowLookupNanos;
    public final long screenRowLookupNanos;
    public final long renderNodeDrawOrRecordNanos;
    public final long canvasDrawNanos;
    public final long[] fullInvalidateByReason;
    public final long protobufParseNanos;
    public final long protobufParseCount;
    public final long modelApplyNanos;
    public final long mainThreadCallbackDelayNanos;
    public final long baselineFrameCount;
    public final long baselineFrameBytes;
    public final long commitFrameCount;
    public final long commitFrameBytes;
    public final long historyRangeFrameCount;
    public final long historyRangeFrameBytes;
    public final long otherFrameCount;
    public final long otherFrameBytes;
    public final long mailboxResidenceNanos;
    public final long mailboxResidenceMaxNanos;
    public final long viewportRedrawRequestCount;
    public final long viewportFullRedrawCount;
    public final long[] terminalCommitApplyLatencyBuckets;
    public final long[] protobufParseLatencyBuckets;
    public final long[] mapperLatencyBuckets;
    public final long[] dictionaryStagingLatencyBuckets;
    public final long[] renderPublicationLatencyBuckets;
    public final long[] renderNodeRecordLatencyBuckets;
    public final long[] vsyncDrawLatencyBuckets;
    public final long[] mailboxResidenceLatencyBuckets;
    public final long historyCacheHitCount;
    public final long historyCacheMissCount;
    public final long visibleHistoryRowsDrawn;
    public final long renderNodeVictimScanCount;
    public final long renderNodeVictimScannedEntries;
    public final long renderNodeAllPinnedFallbackCount;

    Snapshot(long modelChangeCount, long uiCallbackScheduleCount, long uiCallbackCoalescedCount,
             long renderRequestCount, long vsyncRenderCount, long fullInvalidateCount,
             long partialInvalidateCount, long dirtyRowCount, long screenRegionInvalidateCount,
             long partialRowInvalidateCount, long screenScrollEventCount, long screenScrollRowTotal,
             long rowCacheHitCount, long rowCacheMissCount, long rowCacheStaleFallbackCount,
             long rowCachePinnedConflictCount,
             long rowNodeRecordCount, long rowNodeReuseCount, long historyOnlyNoDrawCount,
             long renderDurationCount, long renderDurationNanos,
             long renderDurationMaxNanos, long[] renderDurationLatencyBuckets,
             long viewportCalculationNanos, long historyRowLookupNanos,
             long screenRowLookupNanos, long renderNodeDrawOrRecordNanos, long canvasDrawNanos,
             long[] fullInvalidateByReason,
             long protobufParseNanos, long protobufParseCount,
             long modelApplyNanos, long mainThreadCallbackDelayNanos, long baselineFrameCount,
             long baselineFrameBytes, long commitFrameCount, long commitFrameBytes,
             long historyRangeFrameCount, long historyRangeFrameBytes,
             long otherFrameCount, long otherFrameBytes, long mailboxResidenceNanos,
             long mailboxResidenceMaxNanos, long viewportRedrawRequestCount,
             long viewportFullRedrawCount, long[] terminalCommitApplyLatencyBuckets,
             long[] protobufParseLatencyBuckets, long[] mapperLatencyBuckets,
             long[] dictionaryStagingLatencyBuckets,
             long[] renderPublicationLatencyBuckets,
             long[] renderNodeRecordLatencyBuckets,
             long[] vsyncDrawLatencyBuckets,
             long[] mailboxResidenceLatencyBuckets, long historyCacheHitCount,
             long historyCacheMissCount,
             long visibleHistoryRowsDrawn,
             long renderNodeVictimScanCount, long renderNodeVictimScannedEntries,
             long renderNodeAllPinnedFallbackCount) {
      this.modelChangeCount = modelChangeCount;
      this.uiCallbackScheduleCount = uiCallbackScheduleCount;
      this.uiCallbackCoalescedCount = uiCallbackCoalescedCount;
      this.renderRequestCount = renderRequestCount;
      this.vsyncRenderCount = vsyncRenderCount;
      this.fullInvalidateCount = fullInvalidateCount;
      this.partialInvalidateCount = partialInvalidateCount;
      this.dirtyRowCount = dirtyRowCount;
      this.screenRegionInvalidateCount = screenRegionInvalidateCount;
      this.partialRowInvalidateCount = partialRowInvalidateCount;
      this.screenScrollEventCount = screenScrollEventCount;
      this.screenScrollRowTotal = screenScrollRowTotal;
      this.rowCacheHitCount = rowCacheHitCount;
      this.rowCacheMissCount = rowCacheMissCount;
      this.rowCacheStaleFallbackCount = rowCacheStaleFallbackCount;
      this.rowCachePinnedConflictCount = rowCachePinnedConflictCount;
      this.rowNodeRecordCount = rowNodeRecordCount;
      this.rowNodeReuseCount = rowNodeReuseCount;
      this.historyOnlyNoDrawCount = historyOnlyNoDrawCount;
      this.renderDurationCount = renderDurationCount;
      this.renderDurationNanos = renderDurationNanos;
      this.renderDurationMaxNanos = renderDurationMaxNanos;
      this.renderDurationLatencyBuckets = renderDurationLatencyBuckets;
      this.viewportCalculationNanos = viewportCalculationNanos;
      this.historyRowLookupNanos = historyRowLookupNanos;
      this.screenRowLookupNanos = screenRowLookupNanos;
      this.renderNodeDrawOrRecordNanos = renderNodeDrawOrRecordNanos;
      this.canvasDrawNanos = canvasDrawNanos;
      this.fullInvalidateByReason = fullInvalidateByReason;
      this.protobufParseNanos = protobufParseNanos;
      this.protobufParseCount = protobufParseCount;
      this.modelApplyNanos = modelApplyNanos;
      this.mainThreadCallbackDelayNanos = mainThreadCallbackDelayNanos;
      this.baselineFrameCount = baselineFrameCount;
      this.baselineFrameBytes = baselineFrameBytes;
      this.commitFrameCount = commitFrameCount;
      this.commitFrameBytes = commitFrameBytes;
      this.historyRangeFrameCount = historyRangeFrameCount;
      this.historyRangeFrameBytes = historyRangeFrameBytes;
      this.otherFrameCount = otherFrameCount;
      this.otherFrameBytes = otherFrameBytes;
      this.mailboxResidenceNanos = mailboxResidenceNanos;
      this.mailboxResidenceMaxNanos = mailboxResidenceMaxNanos;
      this.viewportRedrawRequestCount = viewportRedrawRequestCount;
      this.viewportFullRedrawCount = viewportFullRedrawCount;
      this.terminalCommitApplyLatencyBuckets = terminalCommitApplyLatencyBuckets;
      this.protobufParseLatencyBuckets = protobufParseLatencyBuckets;
      this.mapperLatencyBuckets = mapperLatencyBuckets;
      this.dictionaryStagingLatencyBuckets = dictionaryStagingLatencyBuckets;
      this.renderPublicationLatencyBuckets = renderPublicationLatencyBuckets;
      this.renderNodeRecordLatencyBuckets = renderNodeRecordLatencyBuckets;
      this.vsyncDrawLatencyBuckets = vsyncDrawLatencyBuckets;
      this.mailboxResidenceLatencyBuckets = mailboxResidenceLatencyBuckets;
      this.historyCacheHitCount = historyCacheHitCount;
      this.historyCacheMissCount = historyCacheMissCount;
      this.visibleHistoryRowsDrawn = visibleHistoryRowsDrawn;
      this.renderNodeVictimScanCount = renderNodeVictimScanCount;
      this.renderNodeVictimScannedEntries = renderNodeVictimScannedEntries;
      this.renderNodeAllPinnedFallbackCount = renderNodeAllPinnedFallbackCount;
    }
  }
}
