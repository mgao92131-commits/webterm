package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * 按绝对 HistorySeq 寻址的稀疏分页缓存。extent 决定逻辑几何，页是否驻留不会移动行。
 */
public final class PagedTerminalHistory {
  public static final int PAGE_SIZE = 128;

  private final HistoryBudget budget;
  private final ToLongFunction<TerminalLine> byteEstimator;
  private HistoryExtent extent = HistoryExtent.INITIAL_EMPTY;
  private HistoryExtent availableExtent = HistoryExtent.INITIAL_EMPTY;
  private Map<Long, HistoryPageChunk> pages = new HashMap<>();
  /** 只覆盖当前驻留行；页驱逐或 extent trim 时同步移除，绝不记录完整历史。 */
  private Map<Long, Long> loadedLineIdToSeq = new HashMap<>();
  private long loadedLineCount;
  private long estimatedByteCount;
  private long mutationVersion;
  private final Set<EvictionPins.CriticalEvictionReason> criticalEvictionReasons =
      new HashSet<>();
  private PagedTerminalHistorySnapshot snapshot =
      new PagedTerminalHistorySnapshot(extent, availableExtent, new HashMap<>(), 0, 0);

  public PagedTerminalHistory(
      HistoryBudget budget, ToLongFunction<TerminalLine> byteEstimator) {
    this.budget = budget;
    this.byteEstimator = byteEstimator;
  }

  public synchronized Editor edit() {
    return new Editor();
  }

  public synchronized HistoryExtent extent() {
    return extent;
  }

  public synchronized long logicalSize() {
    return extent.logicalSize();
  }

  public synchronized PagedTerminalHistorySnapshot snapshot() {
    return snapshot;
  }

  synchronized Long historySeqByLineId(long lineId) {
    return loadedLineIdToSeq.get(lineId);
  }

  synchronized int loadedLineIdentityCountForTest() {
    return loadedLineIdToSeq.size();
  }

  synchronized int residentPageCountForTest() {
    return pages.size();
  }

  public synchronized Set<EvictionPins.CriticalEvictionReason> criticalEvictionReasons() {
    return java.util.Collections.unmodifiableSet(new HashSet<>(criticalEvictionReasons));
  }

  /** 连续权威 extent 已完整表达 unavailable，因此没有离散 unavailable 元数据。 */
  int unavailableRangeCountForTest() {
    return 0;
  }

  public static long pageNumber(long seq) {
    if (seq < 1) throw new IllegalArgumentException("seq must be >=1");
    return (seq - 1) / PAGE_SIZE;
  }

  static long pageFirstSeq(long pageNumber) {
    if (pageNumber < 0 || pageNumber > (Long.MAX_VALUE - 1) / PAGE_SIZE) {
      throw new IllegalArgumentException("history page number overflow");
    }
    return pageNumber * PAGE_SIZE + 1;
  }

  static long pageLastSeq(long pageNumber) {
    long pageFirst = pageFirstSeq(pageNumber);
    long add = PAGE_SIZE - 1L;
    return pageFirst > Long.MAX_VALUE - add ? Long.MAX_VALUE : pageFirst + add;
  }

  public static int pageOffset(long seq) {
    if (seq < 1) throw new IllegalArgumentException("seq must be >=1");
    return (int) ((seq - 1) % PAGE_SIZE);
  }

  static final class HistoryPageChunk {
    final TerminalLine[] slots;
    final long[] lineBytes;

    HistoryPageChunk() {
      this(new TerminalLine[PAGE_SIZE], new long[PAGE_SIZE]);
    }

    HistoryPageChunk(TerminalLine[] slots, long[] lineBytes) {
      this.slots = slots;
      this.lineBytes = lineBytes;
    }

    HistoryPageChunk copy() {
      return new HistoryPageChunk(
          Arrays.copyOf(slots, slots.length),
          Arrays.copyOf(lineBytes, lineBytes.length));
    }

    boolean empty() {
      for (int i = 0; i < PAGE_SIZE; i++) {
        if (slots[i] != null) return false;
      }
      return true;
    }
  }

  public final class Editor {
    private HistoryExtent workingExtent = extent;
    private HistoryExtent workingAvailableExtent = availableExtent;
    // 页面 root 只按驻留页数浅复制；PageChunk 在首次写入时 copy-on-write。
    // 该成本是 O(resident pages)，不会按逻辑 extent 或已加载行数增长。
    private final Map<Long, HistoryPageChunk> workingPages = new HashMap<>(pages);
    /** 按本事务变化量增长的身份 overlay，绝不复制完整 loadedLineIdToSeq。 */
    private final Map<Long, Long> identityAdds = new HashMap<>();
    private final Set<Long> identityRemoves = new HashSet<>();
    private final Set<Long> copiedPages = new HashSet<>();
    private final long baseMutationVersion = mutationVersion;
    private long workingLoaded = loadedLineCount;
    private long workingBytes = estimatedByteCount;
    private final Set<EvictionPins.CriticalEvictionReason> workingCriticalEvictions =
        new HashSet<>();
    private boolean committed;

    public Editor setExtent(long firstSeq, long lastSeq) {
      ensureOpen();
      HistoryExtent next = new HistoryExtent(firstSeq, lastSeq);
      workingExtent = next;
      // 通用调用默认把显示和权威可用窗口一起推进；冻结补页可只调用 setAvailableExtent。
      workingAvailableExtent = next;
      List<Long> outside = new ArrayList<>();
      for (Map.Entry<Long, HistoryPageChunk> entry : workingPages.entrySet()) {
        long pageFirst = pageFirstSeq(entry.getKey());
        long pageLast = pageLastSeq(entry.getKey());
        if (pageLast < next.firstSeq || pageFirst > next.lastSeq || next.isEmpty()) {
          outside.add(entry.getKey());
        }
      }
      for (long page : outside) removePage(page);
      if (!next.isEmpty()) {
        clearOutsideExtent(next.firstSeq, next.lastSeq);
      }
      return this;
    }

    public Editor setAvailableExtent(long firstSeq, long lastSeq) {
      ensureOpen();
      workingAvailableExtent = new HistoryExtent(firstSeq, lastSeq);
      return this;
    }

    public Editor put(long historySeq, TerminalLine line) {
      ensureOpen();
      if (!workingExtent.contains(historySeq)) {
        throw new IllegalArgumentException(
            "seq " + historySeq + " outside extent " + workingExtent);
      }
      if (line == null) throw new IllegalArgumentException("history line is missing");
      if (line.cells == null) {
        throw new IllegalArgumentException("history line cells are missing");
      }
      for (TerminalCell cell : line.cells) {
        if (cell == null) {
          throw new IllegalArgumentException("history line contains null cell");
        }
      }
      Long ownedSeq = historySeqByLineId(line.id);
      if (ownedSeq != null && ownedSeq != historySeq) {
        throw new IllegalStateException(
            "history LineID already loaded at seq " + ownedSeq);
      }
      HistoryPageChunk page = mutablePage(pageNumber(historySeq));
      int offset = pageOffset(historySeq);
      TerminalLine old = page.slots[offset];
      if (old != null) {
        if (!sameLine(old, line)) {
          throw new IllegalStateException("history is immutable at seq " + historySeq);
        }
        return this;
      }
      long bytes = Math.max(0, byteEstimator.applyAsLong(line));
      page.slots[offset] = line;
      page.lineBytes[offset] = bytes;
      putIdentity(line.id, historySeq);
      workingLoaded++;
      workingBytes += bytes;
      return this;
    }

    public Editor putAll(List<TerminalLine> lines) {
      ensureOpen();
      for (TerminalLine line : lines) put(line.historySeq, line);
      return this;
    }

    Long historySeqByLineId(long lineId) {
      if (identityRemoves.contains(lineId)) return null;
      Long added = identityAdds.get(lineId);
      return added != null ? added : loadedLineIdToSeq.get(lineId);
    }

    public Editor evictIfNeeded(long anchorSeq) {
      return evictIfNeeded(EvictionPins.forAnchor(anchorSeq));
    }

    /**
     * Soft-budget eviction never selects a page intersecting a viewport,
     * anchor, request, selection, or prefetch pin.
     */
    public Editor evictIfNeeded(EvictionPins pins) {
      ensureOpen();
      boolean overLines = budget.hardLines > 0 && workingLoaded > budget.hardLines;
      boolean overBytes = budget.hardBytes > 0 && workingBytes > budget.hardBytes;
      if (!overLines && !overBytes) return this;

      EvictionPins safePins = pins == null ? EvictionPins.NONE : pins;
      long anchorSeq = safePins.anchorLineHistoryRange != null
          ? safePins.anchorLineHistoryRange.first
          : (workingExtent.isEmpty() ? 1 : workingExtent.lastSeq);
      long anchorPage = pageNumber(Math.max(1, anchorSeq));
      List<Long> candidates = new ArrayList<>(workingPages.keySet());
      candidates.sort(Comparator
          .comparingLong((Long page) -> Math.abs(page - anchorPage))
          .reversed()
          .thenComparingLong(Long::longValue));
      for (long pageNumber : candidates) {
        boolean targetLinesReached = budget.softLines <= 0 || workingLoaded <= budget.softLines;
        boolean targetBytesReached = budget.softBytes <= 0 || workingBytes <= budget.softBytes;
        if (targetLinesReached && targetBytesReached) break;
        long pageFirst = pageFirstSeq(pageNumber);
        long pageLast = pageLastSeq(pageNumber);
        if (safePins.intersectsAny(pageFirst, pageLast)) continue;
        HistoryPageChunk page = mutablePage(pageNumber);
        for (int i = 0; i < PAGE_SIZE; i++) {
          if (page.slots[i] == null) continue;
          removeIdentity(page.slots[i].id);
          page.slots[i] = null;
          workingLoaded--;
          workingBytes -= page.lineBytes[i];
          page.lineBytes[i] = 0;
        }
        if (page.empty()) workingPages.remove(pageNumber);
      }
      boolean stillOverHard =
          (budget.hardLines > 0 && workingLoaded > budget.hardLines)
              || (budget.hardBytes > 0 && workingBytes > budget.hardBytes);
      if (stillOverHard) {
        candidates = new ArrayList<>(workingPages.keySet());
        candidates.sort(Comparator
            .comparingInt((Long page) -> safePins.protectionRank(
                pageFirstSeq(page), pageLastSeq(page)))
            .thenComparing(Comparator
                .comparingLong((Long page) -> Math.abs(page - anchorPage)).reversed()));
        for (long pageNumber : candidates) {
          boolean underHard =
              (budget.hardLines <= 0 || workingLoaded <= budget.hardLines)
                  && (budget.hardBytes <= 0 || workingBytes <= budget.hardBytes);
          if (underHard) break;
          long pageFirst = pageFirstSeq(pageNumber);
          long pageLast = pageLastSeq(pageNumber);
          EvictionPins.CriticalEvictionReason reason =
              safePins.criticalReason(pageFirst, pageLast);
          if (reason != null) workingCriticalEvictions.add(reason);
          removePage(pageNumber);
        }
      }
      return this;
    }

    public PagedTerminalHistorySnapshot commit() {
      synchronized (PagedTerminalHistory.this) {
        ensureOpen();
        if (baseMutationVersion != mutationVersion) {
          throw new IllegalStateException("stale history editor");
        }
        committed = true;
        for (long lineId : identityRemoves) loadedLineIdToSeq.remove(lineId);
        loadedLineIdToSeq.putAll(identityAdds);
        PagedTerminalHistory.this.extent = workingExtent;
        PagedTerminalHistory.this.availableExtent = workingAvailableExtent;
        PagedTerminalHistory.this.pages = workingPages;
        PagedTerminalHistory.this.loadedLineCount = workingLoaded;
        PagedTerminalHistory.this.estimatedByteCount = workingBytes;
        PagedTerminalHistory.this.criticalEvictionReasons.clear();
        PagedTerminalHistory.this.criticalEvictionReasons.addAll(workingCriticalEvictions);
        PagedTerminalHistory.this.mutationVersion++;
        PagedTerminalHistory.this.snapshot = new PagedTerminalHistorySnapshot(
            workingExtent, workingAvailableExtent, workingPages, workingLoaded, workingBytes);
        return PagedTerminalHistory.this.snapshot;
      }
    }

    private void clearOutsideExtent(long firstSeq, long lastSeq) {
      long firstPage = pageNumber(firstSeq);
      long lastPage = pageNumber(lastSeq);
      HistoryPageChunk first = workingPages.get(firstPage);
      if (first != null) {
        first = mutablePage(firstPage);
        for (int i = 0; i < pageOffset(firstSeq); i++) clearSlot(first, i);
      }
      HistoryPageChunk last = workingPages.get(lastPage);
      if (last != null) {
        last = mutablePage(lastPage);
        for (int i = pageOffset(lastSeq) + 1; i < PAGE_SIZE; i++) clearSlot(last, i);
      }
    }

    private void clearSlot(HistoryPageChunk page, int offset) {
      if (page.slots[offset] != null) {
        removeIdentity(page.slots[offset].id);
        workingLoaded--;
        workingBytes -= page.lineBytes[offset];
      }
      page.slots[offset] = null;
      page.lineBytes[offset] = 0;
    }

    private void removePage(long pageNumber) {
      HistoryPageChunk page = workingPages.remove(pageNumber);
      if (page == null) return;
      for (int i = 0; i < PAGE_SIZE; i++) {
        if (page.slots[i] != null) {
          removeIdentity(page.slots[i].id);
          workingLoaded--;
          workingBytes -= page.lineBytes[i];
        }
      }
    }

    private HistoryPageChunk mutablePage(long pageNumber) {
      HistoryPageChunk page = workingPages.get(pageNumber);
      if (page == null) {
        page = new HistoryPageChunk();
        workingPages.put(pageNumber, page);
        copiedPages.add(pageNumber);
      } else if (copiedPages.add(pageNumber)) {
        page = page.copy();
        workingPages.put(pageNumber, page);
      }
      return page;
    }

    private void putIdentity(long lineId, long historySeq) {
      identityRemoves.remove(lineId);
      identityAdds.put(lineId, historySeq);
    }

    private void removeIdentity(long lineId) {
      identityAdds.remove(lineId);
      if (loadedLineIdToSeq.containsKey(lineId)) {
        identityRemoves.add(lineId);
      }
    }

    int identityEntriesCopiedForTest() {
      return 0;
    }

    private void ensureOpen() {
      if (committed) throw new IllegalStateException("editor already committed");
    }
  }

  private static boolean sameLine(TerminalLine a, TerminalLine b) {
    return a.id == b.id
        && a.version == b.version
        && a.historySeq == b.historySeq
        && a.wrapped == b.wrapped
        && Arrays.equals(a.cells, b.cells);
  }
}
