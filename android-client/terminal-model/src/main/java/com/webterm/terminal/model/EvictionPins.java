package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable history-cache pins supplied by the viewport/selection publication. */
public final class EvictionPins {
  public enum CriticalEvictionReason {
    VIEWPORT_ANCHOR_EVICTED,
    VISIBLE_HISTORY_EVICTED,
    SELECTION_HISTORY_EVICTED
  }
  public static final EvictionPins NONE =
      new EvictionPins(null, null, Collections.emptyList(), null, null);

  public static final class LongRange {
    public final long first;
    public final long last;

    public LongRange(long first, long last) {
      if (first < 1 || last < first) throw new IllegalArgumentException("invalid range");
      this.first = first;
      this.last = last;
    }

    boolean intersects(long otherFirst, long otherLast) {
      return first <= otherLast && last >= otherFirst;
    }
  }

  public final LongRange visibleRange;
  public final LongRange anchorLineHistoryRange;
  public final List<LongRange> inFlightRanges;
  public final LongRange selectionRange;
  public final LongRange prefetchRange;

  public EvictionPins(LongRange visibleRange, LongRange anchorLineHistoryRange,
                      List<LongRange> inFlightRanges, LongRange selectionRange,
                      LongRange prefetchRange) {
    this.visibleRange = visibleRange;
    this.anchorLineHistoryRange = anchorLineHistoryRange;
    this.inFlightRanges = Collections.unmodifiableList(new ArrayList<>(
        inFlightRanges == null ? Collections.emptyList() : inFlightRanges));
    this.selectionRange = selectionRange;
    this.prefetchRange = prefetchRange;
  }

  public static EvictionPins forAnchor(long historySeq) {
    return historySeq > 0
        ? new EvictionPins(null, new LongRange(historySeq, historySeq),
            Collections.emptyList(), null, null)
        : NONE;
  }

  boolean intersectsAny(long first, long last) {
    if (intersects(visibleRange, first, last)
        || intersects(anchorLineHistoryRange, first, last)
        || intersects(selectionRange, first, last)
        || intersects(prefetchRange, first, last)) {
      return true;
    }
    for (LongRange range : inFlightRanges) {
      if (range.intersects(first, last)) return true;
    }
    return false;
  }

  int protectionRank(long first, long last) {
    if (intersects(visibleRange, first, last)) return 6;
    if (intersects(anchorLineHistoryRange, first, last)) return 5;
    if (intersects(selectionRange, first, last)) return 3;
    for (LongRange range : inFlightRanges) {
      if (range.intersects(first, last)) return 2;
    }
    if (intersects(prefetchRange, first, last)) return 1;
    return 0;
  }

  CriticalEvictionReason criticalReason(long first, long last) {
    if (intersects(visibleRange, first, last)) {
      return CriticalEvictionReason.VISIBLE_HISTORY_EVICTED;
    }
    if (intersects(anchorLineHistoryRange, first, last)) {
      return CriticalEvictionReason.VIEWPORT_ANCHOR_EVICTED;
    }
    if (intersects(selectionRange, first, last)) {
      return CriticalEvictionReason.SELECTION_HISTORY_EVICTED;
    }
    return null;
  }

  private static boolean intersects(LongRange range, long first, long last) {
    return range != null && range.intersects(first, last);
  }
}
