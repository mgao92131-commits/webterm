package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 屏幕和历史共享的唯一正文缓存。 */
public final class BodyCache {
  private final HistoryBudget budget;
  private final Map<LineKey, LineBody> bodies;
  private final HistoryResidencyIndex historyResidency;
  private final long estimatedHistoryBytes;
  private final Set<EvictionPins.CriticalEvictionReason> criticalEvictionReasons;

  public BodyCache(HistoryBudget budget) {
    this(budget, Collections.emptyMap(), new HistoryResidencyIndex(), 0,
        Collections.emptySet());
  }

  private BodyCache(
      HistoryBudget budget,
      Map<LineKey, LineBody> bodies,
      HistoryResidencyIndex historyResidency,
      long estimatedHistoryBytes,
      Set<EvictionPins.CriticalEvictionReason> criticalEvictionReasons) {
    this.budget = budget;
    this.bodies = Collections.unmodifiableMap(bodies);
    this.historyResidency = historyResidency;
    this.estimatedHistoryBytes = estimatedHistoryBytes;
    this.criticalEvictionReasons = Collections.unmodifiableSet(criticalEvictionReasons);
  }

  public LineBody body(LineKey key) { return bodies.get(key); }
  public boolean contains(LineKey key) { return bodies.containsKey(key); }
  public int bodyCount() { return bodies.size(); }
  public long loadedHistoryCount() { return historyResidency.residentCount(); }
  public long estimatedHistoryBytes() { return estimatedHistoryBytes; }
  public HistoryResidencyIndex historyResidency() { return historyResidency; }
  public Set<EvictionPins.CriticalEvictionReason> criticalEvictionReasons() {
    return criticalEvictionReasons;
  }

  public RenderLine renderLine(LineKey key) {
    LineBody body = bodies.get(key);
    return body == null ? null : new RenderLine(key, body);
  }

  public Editor edit() { return new Editor(this); }

  public static final class Editor {
    private final HistoryBudget budget;
    private final Map<LineKey, LineBody> bodies;
    private final HistoryResidencyIndex.Editor residency;
    private long historyBytes;
    private final Set<EvictionPins.CriticalEvictionReason> criticalEvictions =
        new HashSet<>();

    private Editor(BodyCache source) {
      budget = source.budget;
      bodies = new HashMap<>(source.bodies);
      residency = source.historyResidency.edit();
      historyBytes = source.estimatedHistoryBytes;
    }

    public LineBody body(LineKey key) { return bodies.get(key); }
    public LineKey residentKey(long historySeq) { return residency.key(historySeq); }

    public Editor setHistoryExtent(HistoryExtent extent) {
      residency.setExtent(extent);
      recomputeHistoryBytes();
      return this;
    }

    public Editor setAvailableExtent(HistoryExtent extent) {
      residency.setAvailableExtent(extent);
      return this;
    }

    public Editor putBody(LineKey key, LineBody body)
        throws CommitValidationException {
      if (key == null || body == null) {
        throw new CommitValidationException(CommitFailure.INVALID_LINE_DATA);
      }
      LineBody previous = bodies.get(key);
      if (previous != null && !previous.equals(body)) {
        throw new CommitValidationException(CommitFailure.LINE_CONTENT_CONFLICT);
      }
      bodies.putIfAbsent(key, body);
      return this;
    }

    public Editor putHistory(long historySeq, LineKey key, LineBody body)
        throws CommitValidationException {
      putBody(key, body);
      boolean newlyResident = residency.key(historySeq) == null;
      residency.put(historySeq, key);
      if (newlyResident) historyBytes += body.estimatedBytes;
      return this;
    }

    public Editor markHistoryResident(long historySeq, LineKey key) {
      if (!bodies.containsKey(key)) {
        throw new IllegalStateException("resident history body is missing");
      }
      boolean newlyResident = residency.key(historySeq) == null;
      residency.put(historySeq, key);
      if (newlyResident) historyBytes += bodies.get(key).estimatedBytes;
      return this;
    }

    public Editor invalidateHistory(long historySeq) {
      LineKey previous = residency.key(historySeq);
      residency.invalidate(historySeq);
      if (previous != null) historyBytes -= bodies.get(previous).estimatedBytes;
      return this;
    }

    public Editor evictIfNeeded(EvictionPins pins) {
      EvictionPins safePins = pins == null ? EvictionPins.NONE : pins;
      long loaded = residentCount();
      long bytes = historyBytes();
      if (!overHard(loaded, bytes)) return this;

      long anchorSeq = safePins.anchorLineHistoryRange != null
          ? safePins.anchorLineHistoryRange.first : 1;
      long anchorPage = HistoryResidencyIndex.pageNumber(Math.max(1, anchorSeq));
      List<Long> candidates = new ArrayList<>(residency.residentPages());
      candidates.sort(Comparator
          .comparingLong((Long page) -> Math.abs(page - anchorPage))
          .reversed()
          .thenComparingLong(Long::longValue));
      for (long page : candidates) {
        if (underSoft(residentCount(), historyBytes())) break;
        long first = HistoryResidencyIndex.pageFirstSeq(page);
        long last = HistoryResidencyIndex.pageLastSeq(page);
        if (safePins.intersectsAny(first, last)) continue;
        removePage(page);
      }

      if (overHard(residentCount(), historyBytes())) {
        candidates = new ArrayList<>(residency.residentPages());
        candidates.sort(Comparator
            .comparingInt((Long page) -> safePins.protectionRank(
                HistoryResidencyIndex.pageFirstSeq(page),
                HistoryResidencyIndex.pageLastSeq(page)))
            .thenComparing(Comparator
                .comparingLong((Long page) -> Math.abs(page - anchorPage)).reversed()));
        for (long page : candidates) {
          if (!overHard(residentCount(), historyBytes())) break;
          long first = HistoryResidencyIndex.pageFirstSeq(page);
          long last = HistoryResidencyIndex.pageLastSeq(page);
          EvictionPins.CriticalEvictionReason reason =
              safePins.criticalReason(first, last);
          if (reason != null) criticalEvictions.add(reason);
          removePage(page);
        }
      }
      return this;
    }

    /** 仅保留活动屏或仍驻留历史引用的正文。 */
    public Editor retainOnlyActiveAndResident(Set<LineKey> activeKeys) {
      Set<LineKey> retained = new HashSet<>(
          activeKeys == null ? Collections.emptySet() : activeKeys);
      retained.addAll(residency.residentKeys());
      bodies.keySet().retainAll(retained);
      return this;
    }

    public BodyCache commit() {
      HistoryResidencyIndex nextResidency = residency.commit();
      Set<LineKey> residentKeys = nextResidency.residentKeys();
      for (LineKey key : residentKeys) {
        LineBody body = bodies.get(key);
        if (body == null) {
          throw new IllegalStateException("resident key has no body");
        }
      }
      return new BodyCache(
          budget, new HashMap<>(bodies), nextResidency, historyBytes,
          new HashSet<>(criticalEvictions));
    }

    private long residentCount() {
      return residency.residentCount();
    }

    private long historyBytes() {
      return historyBytes;
    }

    private void removePage(long page) {
      for (HistoryResidencyIndex.ResidentEntry entry : residency.pageEntries(page)) {
        LineBody body = bodies.get(entry.key());
        if (body != null) historyBytes -= body.estimatedBytes;
      }
      residency.removePage(page);
    }

    private void recomputeHistoryBytes() {
      historyBytes = 0;
      for (HistoryResidencyIndex.ResidentEntry entry : residency.residentEntries()) {
        LineBody body = bodies.get(entry.key());
        if (body != null) historyBytes += body.estimatedBytes;
      }
    }

    private boolean overHard(long lines, long bytes) {
      return (budget.hardLines > 0 && lines > budget.hardLines)
          || (budget.hardBytes > 0 && bytes > budget.hardBytes);
    }

    private boolean underSoft(long lines, long bytes) {
      return (budget.softLines <= 0 || lines <= budget.softLines)
          && (budget.softBytes <= 0 || bytes <= budget.softBytes);
    }
  }
}
