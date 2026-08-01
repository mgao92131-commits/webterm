package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 屏幕和历史共享的唯一正文缓存。 */
public final class BodyCache {
  private static final AtomicLong EVICTION_DURATION_NANOS = new AtomicLong();
  private static final AtomicLong EVICTION_PAGES_CONSIDERED = new AtomicLong();
  private static final AtomicLong EVICTION_PAGES_REMOVED = new AtomicLong();
  private static final AtomicLong EVICTION_CRITICAL_COUNT = new AtomicLong();

  private final HistoryBudget budget;
  private final PersistentShardedMap<LineKey, LineBody> bodies;
  private final HistoryResidencyIndex historyResidency;
  private final long estimatedHistoryBytes;
  private final Set<EvictionPins.CriticalEvictionReason> criticalEvictionReasons;

  public BodyCache(HistoryBudget budget) {
    this(budget, new PersistentShardedMap<>(), new HistoryResidencyIndex(), 0,
        Collections.emptySet());
  }

  private BodyCache(
      HistoryBudget budget,
      PersistentShardedMap<LineKey, LineBody> bodies,
      HistoryResidencyIndex historyResidency,
      long estimatedHistoryBytes,
      Set<EvictionPins.CriticalEvictionReason> criticalEvictionReasons) {
    this.budget = budget;
    this.bodies = bodies;
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

  public static Map<String, Long> evictionMetricsSnapshot() {
    Map<String, Long> out = new LinkedHashMap<>();
    out.put("bodyCacheEvictionDurationNanos", EVICTION_DURATION_NANOS.get());
    out.put("bodyCacheEvictionPagesConsidered", EVICTION_PAGES_CONSIDERED.get());
    out.put("bodyCacheEvictionPagesRemoved", EVICTION_PAGES_REMOVED.get());
    out.put("bodyCacheCriticalEvictionCount", EVICTION_CRITICAL_COUNT.get());
    return out;
  }

  public RenderLine renderLine(LineKey key) {
    LineBody body = bodies.get(key);
    return body == null ? null : new RenderLine(key, body);
  }

  public Editor edit() { return new Editor(this); }

  public static final class Editor {
    private final HistoryBudget budget;
    private final PersistentShardedMap.Editor<LineKey, LineBody> bodies;
    private final HistoryResidencyIndex.Editor residency;
    private long historyBytes;
    private final Set<EvictionPins.CriticalEvictionReason> criticalEvictions =
        new HashSet<>();

    private Editor(BodyCache source) {
      budget = source.budget;
      bodies = source.bodies.edit();
      residency = source.historyResidency.edit();
      historyBytes = source.estimatedHistoryBytes;
    }

    public LineBody body(LineKey key) { return bodies.get(key); }
    public LineKey residentKey(long historySeq) { return residency.key(historySeq); }

    public Editor setHistoryExtent(HistoryExtent extent) {
      residency.setExtent(extent);
      if (!residency.removedKeys().isEmpty()) recomputeHistoryBytes();
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
      long startedNanos = System.nanoTime();
      EvictionPins safePins = pins == null ? EvictionPins.NONE : pins;
      long loaded = residentCount();
      long bytes = historyBytes();
      if (!overHard(loaded, bytes)) return this;
      int pagesConsidered = 0;
      int pagesRemoved = 0;
      int criticalCount = 0;

      long anchorSeq = safePins.anchorLineHistoryRange != null
          ? safePins.anchorLineHistoryRange.first : 1;
      long anchorPage = HistoryResidencyIndex.pageNumber(Math.max(1, anchorSeq));
      List<Long> candidates = new ArrayList<>(residency.residentPages());
      candidates.sort(Comparator
          .comparingLong((Long page) -> Math.abs(page - anchorPage))
          .reversed()
          .thenComparingLong(Long::longValue));
      for (long page : candidates) {
        pagesConsidered++;
        if (underSoft(residentCount(), historyBytes())) break;
        long first = HistoryResidencyIndex.pageFirstSeq(page);
        long last = HistoryResidencyIndex.pageLastSeq(page);
        if (safePins.intersectsAny(first, last)) continue;
        removePage(page);
        pagesRemoved++;
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
          pagesConsidered++;
          if (!overHard(residentCount(), historyBytes())) break;
          long first = HistoryResidencyIndex.pageFirstSeq(page);
          long last = HistoryResidencyIndex.pageLastSeq(page);
          EvictionPins.CriticalEvictionReason reason =
              safePins.criticalReason(first, last);
          if (reason != null) criticalEvictions.add(reason);
          removePage(page);
          pagesRemoved++;
          if (reason != null) criticalCount++;
        }
      }
      EVICTION_DURATION_NANOS.addAndGet(System.nanoTime() - startedNanos);
      EVICTION_PAGES_CONSIDERED.addAndGet(pagesConsidered);
      EVICTION_PAGES_REMOVED.addAndGet(pagesRemoved);
      EVICTION_CRITICAL_COUNT.addAndGet(criticalCount);
      return this;
    }

    /** 只检查本事务失去引用的 key，避免每次 Commit 扫描完整正文缓存。 */
    public Editor removeUnreferenced(
        Set<LineKey> activeKeys,
        HistoryCatalog catalog,
        Set<LineKey> additionalCandidates) {
      Set<LineKey> candidates = residency.removedKeys();
      if (additionalCandidates != null) candidates.addAll(additionalCandidates);
      Set<LineKey> active = activeKeys == null
          ? Collections.emptySet() : activeKeys;
      for (LineKey key : candidates) {
        if (active.contains(key)) continue;
        Long seq = catalog.historySeq(key);
        if (seq != null && key.equals(residency.key(seq))) continue;
        bodies.remove(key);
      }
      return this;
    }

    public BodyCache commit() {
      HistoryResidencyIndex nextResidency = residency.commit();
      return new BodyCache(
          budget, bodies.commit(), nextResidency, historyBytes,
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
