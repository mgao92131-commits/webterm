package com.webterm.terminal.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local terminal rendering counters. They deliberately contain no terminal content,
 * session id, title, path, or clipboard data, so callers can expose a snapshot for diagnostics.
 */
public final class TerminalRenderMetrics {
  private static final AtomicLong MODEL_CHANGE_COUNT = new AtomicLong();
  private static final AtomicLong UI_CALLBACK_SCHEDULE_COUNT = new AtomicLong();
  private static final AtomicLong UI_CALLBACK_COALESCED_COUNT = new AtomicLong();
  private static final AtomicLong RENDER_REQUEST_COUNT = new AtomicLong();
  private static final AtomicLong VSYNC_RENDER_COUNT = new AtomicLong();
  private static final AtomicLong FULL_INVALIDATE_COUNT = new AtomicLong();
  private static final AtomicLong PARTIAL_INVALIDATE_COUNT = new AtomicLong();
  private static final AtomicLong DIRTY_ROW_COUNT = new AtomicLong();
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

  private TerminalRenderMetrics() {}

  public static void modelChange() { MODEL_CHANGE_COUNT.incrementAndGet(); }
  public static void uiCallbackScheduled() { UI_CALLBACK_SCHEDULE_COUNT.incrementAndGet(); }
  public static void uiCallbackCoalesced() { UI_CALLBACK_COALESCED_COUNT.incrementAndGet(); }
  public static void renderRequested() { RENDER_REQUEST_COUNT.incrementAndGet(); }
  public static void vsyncRender() { VSYNC_RENDER_COUNT.incrementAndGet(); }
  public static void fullInvalidate() { FULL_INVALIDATE_COUNT.incrementAndGet(); }
  public static void partialInvalidate(int rows) {
    PARTIAL_INVALIDATE_COUNT.incrementAndGet();
    DIRTY_ROW_COUNT.addAndGet(Math.max(0, rows));
  }
  public static void renderDuration(long nanos) {
    long safe = Math.max(0L, nanos);
    RENDER_DURATION_NANOS.addAndGet(safe);
    updateMax(RENDER_DURATION_MAX_NANOS, safe);
  }
  public static void protobufParseDuration(long nanos) {
    PROTOBUF_PARSE_COUNT.incrementAndGet();
    PROTOBUF_PARSE_NANOS.addAndGet(Math.max(0L, nanos));
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
  }

  public static Snapshot snapshot() {
    return new Snapshot(MODEL_CHANGE_COUNT.get(), UI_CALLBACK_SCHEDULE_COUNT.get(),
        UI_CALLBACK_COALESCED_COUNT.get(), RENDER_REQUEST_COUNT.get(), VSYNC_RENDER_COUNT.get(),
        FULL_INVALIDATE_COUNT.get(), PARTIAL_INVALIDATE_COUNT.get(), DIRTY_ROW_COUNT.get(),
        RENDER_DURATION_NANOS.get(), RENDER_DURATION_MAX_NANOS.get(), PROTOBUF_PARSE_NANOS.get(),
        PROTOBUF_PARSE_COUNT.get(), MODEL_APPLY_NANOS.get(), MAIN_CALLBACK_DELAY_NANOS.get(),
        BASELINE_FRAME_COUNT.get(), BASELINE_FRAME_BYTES.get(), PATCH_FRAME_COUNT.get(),
        PATCH_FRAME_BYTES.get(), HISTORY_RANGE_FRAME_COUNT.get(), HISTORY_RANGE_FRAME_BYTES.get(),
        HISTORY_DELTA_FRAME_COUNT.get(), HISTORY_DELTA_FRAME_BYTES.get(), OTHER_FRAME_COUNT.get(),
        OTHER_FRAME_BYTES.get(), MAILBOX_RESIDENCE_NANOS.get(), MAILBOX_RESIDENCE_MAX_NANOS.get());
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

    Snapshot(long modelChangeCount, long uiCallbackScheduleCount, long uiCallbackCoalescedCount,
             long renderRequestCount, long vsyncRenderCount, long fullInvalidateCount,
             long partialInvalidateCount, long dirtyRowCount, long renderDurationNanos,
             long renderDurationMaxNanos, long protobufParseNanos, long protobufParseCount,
             long modelApplyNanos, long mainThreadCallbackDelayNanos, long baselineFrameCount,
             long baselineFrameBytes, long patchFrameCount, long patchFrameBytes,
             long historyRangeFrameCount, long historyRangeFrameBytes,
             long historyDeltaFrameCount, long historyDeltaFrameBytes,
             long otherFrameCount, long otherFrameBytes, long mailboxResidenceNanos,
             long mailboxResidenceMaxNanos) {
      this.modelChangeCount = modelChangeCount;
      this.uiCallbackScheduleCount = uiCallbackScheduleCount;
      this.uiCallbackCoalescedCount = uiCallbackCoalescedCount;
      this.renderRequestCount = renderRequestCount;
      this.vsyncRenderCount = vsyncRenderCount;
      this.fullInvalidateCount = fullInvalidateCount;
      this.partialInvalidateCount = partialInvalidateCount;
      this.dirtyRowCount = dirtyRowCount;
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
    }
  }
}
