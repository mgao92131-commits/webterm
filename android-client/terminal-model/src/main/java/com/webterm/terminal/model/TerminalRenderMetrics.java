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
  private static final AtomicLong RENDER_DURATION_MAX_NANOS = new AtomicLong();
  private static final AtomicLong PROTOBUF_PARSE_NANOS = new AtomicLong();
  private static final AtomicLong PROTOBUF_PARSE_COUNT = new AtomicLong();
  private static final AtomicLong MODEL_APPLY_NANOS = new AtomicLong();
  private static final AtomicLong MAIN_CALLBACK_DELAY_NANOS = new AtomicLong();
  private static final AtomicLong BASELINE_FRAME_COUNT = new AtomicLong();
  private static final AtomicLong BASELINE_FRAME_BYTES = new AtomicLong();
  private static final AtomicLong PATCH_FRAME_COUNT = new AtomicLong();
  private static final AtomicLong PATCH_FRAME_BYTES = new AtomicLong();
  private static final AtomicLong HISTORY_RANGE_FRAME_COUNT = new AtomicLong();
  private static final AtomicLong HISTORY_RANGE_FRAME_BYTES = new AtomicLong();
  private static final AtomicLong HISTORY_DELTA_FRAME_COUNT = new AtomicLong();
  private static final AtomicLong HISTORY_DELTA_FRAME_BYTES = new AtomicLong();
  private static final AtomicLong OTHER_FRAME_COUNT = new AtomicLong();
  private static final AtomicLong OTHER_FRAME_BYTES = new AtomicLong();
  private static final AtomicLong MAILBOX_RESIDENCE_NANOS = new AtomicLong();
  private static final AtomicLong MAILBOX_RESIDENCE_MAX_NANOS = new AtomicLong();
  private static final AtomicLong VIEWPORT_REDRAW_REQUEST_COUNT = new AtomicLong();
  private static final AtomicLong VIEWPORT_FULL_REDRAW_COUNT = new AtomicLong();
  private static final AtomicLongArray SCREEN_PATCH_APPLY_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLongArray PROTOBUF_PARSE_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLongArray MAPPER_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLongArray RENDER_NODE_RECORD_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLongArray MAILBOX_RESIDENCE_LATENCY_BUCKETS =
      new AtomicLongArray(LATENCY_BUCKET_COUNT);
  private static final AtomicLong HISTORY_CACHE_HIT_COUNT = new AtomicLong();
  private static final AtomicLong HISTORY_CACHE_MISS_COUNT = new AtomicLong();
  private static final AtomicLong BACKGROUND_PATCH_DROPPED_COUNT = new AtomicLong();
  private static final AtomicLong SCREEN_LINE_STORE_MAX_SIZE = new AtomicLong();
  private static final AtomicLong VISIBLE_HISTORY_ROWS_DRAWN = new AtomicLong();

  private TerminalRenderMetrics() {}

  public static void modelChange() { MODEL_CHANGE_COUNT.incrementAndGet(); }
  public static void uiCallbackScheduled() { UI_CALLBACK_SCHEDULE_COUNT.incrementAndGet(); }
  public static void uiCallbackCoalesced() { UI_CALLBACK_COALESCED_COUNT.incrementAndGet(); }
  public static void renderRequested() { RENDER_REQUEST_COUNT.incrementAndGet(); }
  public static void viewportRedrawRequested() { VIEWPORT_REDRAW_REQUEST_COUNT.incrementAndGet(); }
  public static void viewportFullRedraw() { VIEWPORT_FULL_REDRAW_COUNT.incrementAndGet(); }
  public static void vsyncRender() { VSYNC_RENDER_COUNT.incrementAndGet(); }
  public static void fullInvalidate() { FULL_INVALIDATE_COUNT.incrementAndGet(); }
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
    RENDER_DURATION_NANOS.addAndGet(safe);
    updateMax(RENDER_DURATION_MAX_NANOS, safe);
  }
  public static void protobufParseDuration(long nanos) {
    PROTOBUF_PARSE_COUNT.incrementAndGet();
    long safe = Math.max(0L, nanos);
    PROTOBUF_PARSE_NANOS.addAndGet(safe);
    recordLatency(PROTOBUF_PARSE_LATENCY_BUCKETS, safe);
  }
  public static void screenPatchApplyDuration(long nanos) {
    recordLatency(SCREEN_PATCH_APPLY_LATENCY_BUCKETS, Math.max(0L, nanos));
  }
  public static void mapperDuration(long nanos) {
    recordLatency(MAPPER_LATENCY_BUCKETS, Math.max(0L, nanos));
  }
  public static void renderNodeRecordDuration(long nanos) {
    recordLatency(RENDER_NODE_RECORD_LATENCY_BUCKETS, Math.max(0L, nanos));
  }
  public static void historyCacheHit() { HISTORY_CACHE_HIT_COUNT.incrementAndGet(); }
  public static void historyCacheMiss() { HISTORY_CACHE_MISS_COUNT.incrementAndGet(); }
  public static void backgroundPatchDropped() { BACKGROUND_PATCH_DROPPED_COUNT.incrementAndGet(); }
  public static void screenLineStoreSize(long size) {
    updateMax(SCREEN_LINE_STORE_MAX_SIZE, Math.max(0L, size));
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
    BASELINE, PATCH, HISTORY_RANGE, HISTORY_DELTA, OTHER
  }

  /** Records only wire class and length; terminal contents never enter diagnostics. */
  public static void inboundScreenFrame(ScreenTrafficKind kind, int bytes) {
    AtomicLong count;
    AtomicLong totalBytes;
    if (kind == ScreenTrafficKind.BASELINE) {
      count = BASELINE_FRAME_COUNT;
      totalBytes = BASELINE_FRAME_BYTES;
    } else if (kind == ScreenTrafficKind.PATCH) {
      count = PATCH_FRAME_COUNT;
      totalBytes = PATCH_FRAME_BYTES;
    } else if (kind == ScreenTrafficKind.HISTORY_RANGE) {
      count = HISTORY_RANGE_FRAME_COUNT;
      totalBytes = HISTORY_RANGE_FRAME_BYTES;
    } else if (kind == ScreenTrafficKind.HISTORY_DELTA) {
      count = HISTORY_DELTA_FRAME_COUNT;
      totalBytes = HISTORY_DELTA_FRAME_BYTES;
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
        RENDER_DURATION_NANOS.get(), RENDER_DURATION_MAX_NANOS.get(), PROTOBUF_PARSE_NANOS.get(),
        PROTOBUF_PARSE_COUNT.get(), MODEL_APPLY_NANOS.get(), MAIN_CALLBACK_DELAY_NANOS.get(),
        BASELINE_FRAME_COUNT.get(), BASELINE_FRAME_BYTES.get(), PATCH_FRAME_COUNT.get(),
        PATCH_FRAME_BYTES.get(), HISTORY_RANGE_FRAME_COUNT.get(), HISTORY_RANGE_FRAME_BYTES.get(),
        HISTORY_DELTA_FRAME_COUNT.get(), HISTORY_DELTA_FRAME_BYTES.get(), OTHER_FRAME_COUNT.get(),
        OTHER_FRAME_BYTES.get(), MAILBOX_RESIDENCE_NANOS.get(), MAILBOX_RESIDENCE_MAX_NANOS.get(),
        VIEWPORT_REDRAW_REQUEST_COUNT.get(), VIEWPORT_FULL_REDRAW_COUNT.get(),
        copyBuckets(SCREEN_PATCH_APPLY_LATENCY_BUCKETS),
        copyBuckets(PROTOBUF_PARSE_LATENCY_BUCKETS),
        copyBuckets(MAPPER_LATENCY_BUCKETS),
        copyBuckets(RENDER_NODE_RECORD_LATENCY_BUCKETS),
        copyBuckets(MAILBOX_RESIDENCE_LATENCY_BUCKETS),
        HISTORY_CACHE_HIT_COUNT.get(), HISTORY_CACHE_MISS_COUNT.get(),
        BACKGROUND_PATCH_DROPPED_COUNT.get(), SCREEN_LINE_STORE_MAX_SIZE.get(),
        VISIBLE_HISTORY_ROWS_DRAWN.get());
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
    public final long renderDurationNanos;
    public final long renderDurationMaxNanos;
    public final long protobufParseNanos;
    public final long protobufParseCount;
    public final long modelApplyNanos;
    public final long mainThreadCallbackDelayNanos;
    public final long baselineFrameCount;
    public final long baselineFrameBytes;
    public final long patchFrameCount;
    public final long patchFrameBytes;
    public final long historyRangeFrameCount;
    public final long historyRangeFrameBytes;
    public final long historyDeltaFrameCount;
    public final long historyDeltaFrameBytes;
    public final long otherFrameCount;
    public final long otherFrameBytes;
    public final long mailboxResidenceNanos;
    public final long mailboxResidenceMaxNanos;
    public final long viewportRedrawRequestCount;
    public final long viewportFullRedrawCount;
    public final long[] screenPatchApplyLatencyBuckets;
    public final long[] protobufParseLatencyBuckets;
    public final long[] mapperLatencyBuckets;
    public final long[] renderNodeRecordLatencyBuckets;
    public final long[] mailboxResidenceLatencyBuckets;
    public final long historyCacheHitCount;
    public final long historyCacheMissCount;
    public final long backgroundPatchDroppedCount;
    public final long screenLineStoreMaxSize;
    public final long visibleHistoryRowsDrawn;

    Snapshot(long modelChangeCount, long uiCallbackScheduleCount, long uiCallbackCoalescedCount,
             long renderRequestCount, long vsyncRenderCount, long fullInvalidateCount,
             long partialInvalidateCount, long dirtyRowCount, long screenRegionInvalidateCount,
             long partialRowInvalidateCount, long screenScrollEventCount, long screenScrollRowTotal,
             long rowCacheHitCount, long rowCacheMissCount, long rowCacheStaleFallbackCount,
             long rowCachePinnedConflictCount,
             long rowNodeRecordCount, long rowNodeReuseCount, long historyOnlyNoDrawCount, long renderDurationNanos,
             long renderDurationMaxNanos, long protobufParseNanos, long protobufParseCount,
             long modelApplyNanos, long mainThreadCallbackDelayNanos, long baselineFrameCount,
             long baselineFrameBytes, long patchFrameCount, long patchFrameBytes,
             long historyRangeFrameCount, long historyRangeFrameBytes,
             long historyDeltaFrameCount, long historyDeltaFrameBytes,
             long otherFrameCount, long otherFrameBytes, long mailboxResidenceNanos,
             long mailboxResidenceMaxNanos, long viewportRedrawRequestCount,
             long viewportFullRedrawCount, long[] screenPatchApplyLatencyBuckets,
             long[] protobufParseLatencyBuckets, long[] mapperLatencyBuckets,
             long[] renderNodeRecordLatencyBuckets,
             long[] mailboxResidenceLatencyBuckets, long historyCacheHitCount,
             long historyCacheMissCount, long backgroundPatchDroppedCount,
             long screenLineStoreMaxSize, long visibleHistoryRowsDrawn) {
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
      this.renderDurationNanos = renderDurationNanos;
      this.renderDurationMaxNanos = renderDurationMaxNanos;
      this.protobufParseNanos = protobufParseNanos;
      this.protobufParseCount = protobufParseCount;
      this.modelApplyNanos = modelApplyNanos;
      this.mainThreadCallbackDelayNanos = mainThreadCallbackDelayNanos;
      this.baselineFrameCount = baselineFrameCount;
      this.baselineFrameBytes = baselineFrameBytes;
      this.patchFrameCount = patchFrameCount;
      this.patchFrameBytes = patchFrameBytes;
      this.historyRangeFrameCount = historyRangeFrameCount;
      this.historyRangeFrameBytes = historyRangeFrameBytes;
      this.historyDeltaFrameCount = historyDeltaFrameCount;
      this.historyDeltaFrameBytes = historyDeltaFrameBytes;
      this.otherFrameCount = otherFrameCount;
      this.otherFrameBytes = otherFrameBytes;
      this.mailboxResidenceNanos = mailboxResidenceNanos;
      this.mailboxResidenceMaxNanos = mailboxResidenceMaxNanos;
      this.viewportRedrawRequestCount = viewportRedrawRequestCount;
      this.viewportFullRedrawCount = viewportFullRedrawCount;
      this.screenPatchApplyLatencyBuckets = screenPatchApplyLatencyBuckets;
      this.protobufParseLatencyBuckets = protobufParseLatencyBuckets;
      this.mapperLatencyBuckets = mapperLatencyBuckets;
      this.renderNodeRecordLatencyBuckets = renderNodeRecordLatencyBuckets;
      this.mailboxResidenceLatencyBuckets = mailboxResidenceLatencyBuckets;
      this.historyCacheHitCount = historyCacheHitCount;
      this.historyCacheMissCount = historyCacheMissCount;
      this.backgroundPatchDroppedCount = backgroundPatchDroppedCount;
      this.screenLineStoreMaxSize = screenLineStoreMaxSize;
      this.visibleHistoryRowsDrawn = visibleHistoryRowsDrawn;
    }
  }
}
