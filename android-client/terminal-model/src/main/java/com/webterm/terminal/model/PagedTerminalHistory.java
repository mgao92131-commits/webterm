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
  private Map<Long, HistoryPageChunk> pages = new HashMap<>();
  private long loadedLineCount;
  private long estimatedByteCount;
  private PagedTerminalHistorySnapshot snapshot =
      new PagedTerminalHistorySnapshot(extent, new HashMap<>(), 0, 0);

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

  public static long pageNumber(long seq) {
    if (seq < 1) throw new IllegalArgumentException("seq must be >=1");
    return (seq - 1) / PAGE_SIZE;
  }

  public static int pageOffset(long seq) {
    if (seq < 1) throw new IllegalArgumentException("seq must be >=1");
    return (int) ((seq - 1) % PAGE_SIZE);
  }

  static final class HistoryPageChunk {
    final TerminalLine[] slots;
    final long[] lineBytes;
    final boolean[] unavailable;

    HistoryPageChunk() {
      this(new TerminalLine[PAGE_SIZE], new long[PAGE_SIZE], new boolean[PAGE_SIZE]);
    }

    HistoryPageChunk(TerminalLine[] slots, long[] lineBytes, boolean[] unavailable) {
      this.slots = slots;
      this.lineBytes = lineBytes;
      this.unavailable = unavailable;
    }

    HistoryPageChunk copy() {
      return new HistoryPageChunk(
          Arrays.copyOf(slots, slots.length),
          Arrays.copyOf(lineBytes, lineBytes.length),
          Arrays.copyOf(unavailable, unavailable.length));
    }

    boolean empty() {
      for (int i = 0; i < PAGE_SIZE; i++) {
        if (slots[i] != null || unavailable[i]) return false;
      }
      return true;
    }
  }

  public final class Editor {
    private HistoryExtent workingExtent = extent;
    private final Map<Long, HistoryPageChunk> workingPages = new HashMap<>(pages);
    private final Set<Long> copiedPages = new HashSet<>();
    private long workingLoaded = loadedLineCount;
    private long workingBytes = estimatedByteCount;
    private boolean committed;

    public Editor setExtent(long firstSeq, long lastSeq) {
      ensureOpen();
      HistoryExtent next = new HistoryExtent(firstSeq, lastSeq);
      workingExtent = next;
      List<Long> outside = new ArrayList<>();
      for (Map.Entry<Long, HistoryPageChunk> entry : workingPages.entrySet()) {
        long pageFirst = entry.getKey() * PAGE_SIZE + 1;
        long pageLast = pageFirst + PAGE_SIZE - 1;
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

    public Editor put(long historySeq, TerminalLine line) {
      ensureOpen();
      if (!workingExtent.contains(historySeq)) {
        throw new IllegalArgumentException(
            "seq " + historySeq + " outside extent " + workingExtent);
      }
      if (line == null || line.historySeq != historySeq) {
        throw new IllegalArgumentException("line historySeq does not match target");
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
      page.unavailable[offset] = false;
      workingLoaded++;
      workingBytes += bytes;
      return this;
    }

    public Editor putAll(List<TerminalLine> lines) {
      ensureOpen();
      for (TerminalLine line : lines) put(line.historySeq, line);
      return this;
    }

    public Editor markUnavailable(long fromSeq, long toSeq) {
      ensureOpen();
      if (fromSeq > toSeq || workingExtent.isEmpty()) return this;
      long from = Math.max(fromSeq, workingExtent.firstSeq);
      long to = Math.min(toSeq, workingExtent.lastSeq);
      for (long seq = from; seq <= to; seq++) {
        HistoryPageChunk page = mutablePage(pageNumber(seq));
        int offset = pageOffset(seq);
        if (page.slots[offset] == null) page.unavailable[offset] = true;
        if (seq == Long.MAX_VALUE) break;
      }
      return this;
    }

    public Editor evictIfNeeded(long anchorSeq) {
      ensureOpen();
      boolean overLines = budget.hardLines > 0 && workingLoaded > budget.hardLines;
      boolean overBytes = budget.hardBytes > 0 && workingBytes > budget.hardBytes;
      if (!overLines && !overBytes) return this;

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
        HistoryPageChunk page = mutablePage(pageNumber);
        for (int i = 0; i < PAGE_SIZE; i++) {
          if (page.slots[i] == null) continue;
          page.slots[i] = null;
          workingLoaded--;
          workingBytes -= page.lineBytes[i];
          page.lineBytes[i] = 0;
        }
        if (page.empty()) workingPages.remove(pageNumber);
      }
      return this;
    }

    public PagedTerminalHistorySnapshot commit() {
      synchronized (PagedTerminalHistory.this) {
        ensureOpen();
        committed = true;
        PagedTerminalHistory.this.extent = workingExtent;
        PagedTerminalHistory.this.pages = workingPages;
        PagedTerminalHistory.this.loadedLineCount = workingLoaded;
        PagedTerminalHistory.this.estimatedByteCount = workingBytes;
        PagedTerminalHistory.this.snapshot = new PagedTerminalHistorySnapshot(
            workingExtent, new HashMap<>(workingPages), workingLoaded, workingBytes);
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
        workingLoaded--;
        workingBytes -= page.lineBytes[offset];
      }
      page.slots[offset] = null;
      page.lineBytes[offset] = 0;
      page.unavailable[offset] = false;
    }

    private void removePage(long pageNumber) {
      HistoryPageChunk page = workingPages.remove(pageNumber);
      if (page == null) return;
      for (int i = 0; i < PAGE_SIZE; i++) {
        if (page.slots[i] != null) {
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
