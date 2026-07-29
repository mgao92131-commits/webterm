package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 稀疏分页驻留目录。槽位只保存 LineKey，不保存正文或反向身份索引。 */
public final class HistoryResidencyIndex {
  public static final int PAGE_SIZE = 128;

  static final class HistoryPage {
    final LineKey[] slots;

    HistoryPage() { this(new LineKey[PAGE_SIZE]); }
    HistoryPage(LineKey[] slots) { this.slots = slots; }
    HistoryPage copy() { return new HistoryPage(Arrays.copyOf(slots, slots.length)); }
    boolean empty() {
      for (LineKey key : slots) if (key != null) return false;
      return true;
    }
  }

  public record ResidentEntry(long historySeq, LineKey key) {}

  private final HistoryExtent extent;
  private final HistoryExtent availableExtent;
  private final Map<Long, HistoryPage> pages;
  private final long residentCount;

  public HistoryResidencyIndex() {
    this(HistoryExtent.INITIAL_EMPTY, HistoryExtent.INITIAL_EMPTY,
        Collections.emptyMap(), 0);
  }

  private HistoryResidencyIndex(
      HistoryExtent extent, HistoryExtent availableExtent,
      Map<Long, HistoryPage> pages, long residentCount) {
    this.extent = extent;
    this.availableExtent = availableExtent;
    this.pages = Collections.unmodifiableMap(pages);
    this.residentCount = residentCount;
  }

  public HistoryExtent extent() { return extent; }
  public HistoryExtent availableExtent() { return availableExtent; }
  public long residentCount() { return residentCount; }

  public LineKey key(long historySeq) {
    if (!extent.contains(historySeq)) return null;
    HistoryPage page = pages.get(pageNumber(historySeq));
    return page == null ? null : page.slots[pageOffset(historySeq)];
  }

  public SlotState slotState(long historySeq) {
    if (!extent.contains(historySeq)) return SlotState.UNAVAILABLE;
    if (key(historySeq) != null) return SlotState.LOADED;
    return availableExtent.contains(historySeq) ? SlotState.UNLOADED : SlotState.UNAVAILABLE;
  }

  public Set<LineKey> residentKeys() {
    Set<LineKey> result = new HashSet<>();
    for (HistoryPage page : pages.values()) {
      for (LineKey key : page.slots) if (key != null) result.add(key);
    }
    return result;
  }

  public List<ResidentEntry> residentEntries() {
    List<ResidentEntry> result = new ArrayList<>();
    for (Map.Entry<Long, HistoryPage> pageEntry : pages.entrySet()) {
      long first = pageFirstSeq(pageEntry.getKey());
      for (int offset = 0; offset < PAGE_SIZE; offset++) {
        LineKey key = pageEntry.getValue().slots[offset];
        if (key == null) continue;
        long seq = first + offset;
        if (extent.contains(seq)) result.add(new ResidentEntry(seq, key));
      }
    }
    result.sort(java.util.Comparator.comparingLong(ResidentEntry::historySeq));
    return result;
  }

  public Set<Long> residentPages() { return pages.keySet(); }
  public Editor edit() { return new Editor(this); }

  public static long pageNumber(long seq) {
    if (seq < 1) throw new IllegalArgumentException("seq must be >= 1");
    return (seq - 1) / PAGE_SIZE;
  }

  public static long pageFirstSeq(long pageNumber) {
    if (pageNumber < 0 || pageNumber > (Long.MAX_VALUE - 1) / PAGE_SIZE) {
      throw new IllegalArgumentException("history page overflow");
    }
    return pageNumber * PAGE_SIZE + 1;
  }

  public static long pageLastSeq(long pageNumber) {
    long first = pageFirstSeq(pageNumber);
    return first > Long.MAX_VALUE - (PAGE_SIZE - 1L)
        ? Long.MAX_VALUE : first + PAGE_SIZE - 1L;
  }

  public static int pageOffset(long seq) {
    if (seq < 1) throw new IllegalArgumentException("seq must be >= 1");
    return (int) ((seq - 1) % PAGE_SIZE);
  }

  public static final class Editor {
    private HistoryExtent extent;
    private HistoryExtent availableExtent;
    private final Map<Long, HistoryPage> pages;
    private final Set<Long> copiedPages = new HashSet<>();
    private final Set<LineKey> removedKeys = new HashSet<>();
    private long residentCount;

    private Editor(HistoryResidencyIndex source) {
      extent = source.extent;
      availableExtent = source.availableExtent;
      pages = new HashMap<>(source.pages);
      residentCount = source.residentCount;
    }

    public Editor setExtent(HistoryExtent next) {
      if (next == null) throw new IllegalArgumentException("history extent missing");
      HistoryExtent previous = extent;
      extent = next;
      availableExtent = next;
      if (previous.isEmpty()
          || (!next.isEmpty()
              && next.firstSeq <= previous.firstSeq
              && next.lastSeq >= previous.lastSeq)) {
        return this;
      }
      List<Long> outside = new ArrayList<>();
      for (long page : pages.keySet()) {
        if (next.isEmpty() || pageLastSeq(page) < next.firstSeq
            || pageFirstSeq(page) > next.lastSeq) {
          outside.add(page);
        }
      }
      for (long page : outside) removePage(page);
      if (!next.isEmpty()) clearOutside(next.firstSeq, next.lastSeq);
      return this;
    }

    public Editor setAvailableExtent(HistoryExtent next) {
      if (next == null) throw new IllegalArgumentException("available extent missing");
      availableExtent = next;
      return this;
    }

    public LineKey key(long historySeq) {
      if (!extent.contains(historySeq)) return null;
      HistoryPage page = pages.get(pageNumber(historySeq));
      return page == null ? null : page.slots[pageOffset(historySeq)];
    }

    public Editor put(long historySeq, LineKey key) {
      if (!extent.contains(historySeq) || key == null) {
        throw new IllegalArgumentException("resident key outside extent");
      }
      HistoryPage page = mutablePage(pageNumber(historySeq));
      int offset = pageOffset(historySeq);
      LineKey previous = page.slots[offset];
      if (previous != null && !previous.equals(key)) {
        throw new IllegalStateException("history resident key conflict");
      }
      if (previous == null) {
        page.slots[offset] = key;
        residentCount++;
      }
      return this;
    }

    public Editor invalidate(long historySeq) {
      if (!extent.contains(historySeq)) return this;
      HistoryPage page = pages.get(pageNumber(historySeq));
      if (page == null) return this;
      page = mutablePage(pageNumber(historySeq));
      int offset = pageOffset(historySeq);
      if (page.slots[offset] != null) {
        removedKeys.add(page.slots[offset]);
        page.slots[offset] = null;
        residentCount--;
      }
      if (page.empty()) pages.remove(pageNumber(historySeq));
      return this;
    }

    public Set<Long> residentPages() { return new HashSet<>(pages.keySet()); }
    public long residentCount() { return residentCount; }

    public Set<LineKey> residentKeys() {
      Set<LineKey> result = new HashSet<>();
      for (HistoryPage page : pages.values()) {
        for (LineKey key : page.slots) if (key != null) result.add(key);
      }
      return result;
    }

    public Set<LineKey> removedKeys() {
      return new HashSet<>(removedKeys);
    }

    public List<ResidentEntry> residentEntries() {
      List<ResidentEntry> result = new ArrayList<>();
      for (long page : pages.keySet()) result.addAll(pageEntries(page));
      return result;
    }

    public List<ResidentEntry> pageEntries(long pageNumber) {
      List<ResidentEntry> result = new ArrayList<>();
      HistoryPage page = pages.get(pageNumber);
      if (page == null) return result;
      long first = pageFirstSeq(pageNumber);
      for (int offset = 0; offset < PAGE_SIZE; offset++) {
        if (page.slots[offset] != null) {
          result.add(new ResidentEntry(first + offset, page.slots[offset]));
        }
      }
      return result;
    }

    public Editor removePage(long pageNumber) {
      HistoryPage page = pages.remove(pageNumber);
      if (page != null) {
        for (LineKey key : page.slots) {
          if (key != null) {
            removedKeys.add(key);
            residentCount--;
          }
        }
      }
      return this;
    }

    public HistoryResidencyIndex commit() {
      return new HistoryResidencyIndex(
          extent, availableExtent, new HashMap<>(pages), residentCount);
    }

    private HistoryPage mutablePage(long pageNumber) {
      HistoryPage page = pages.get(pageNumber);
      if (page == null) {
        page = new HistoryPage();
        pages.put(pageNumber, page);
        copiedPages.add(pageNumber);
      } else if (copiedPages.add(pageNumber)) {
        page = page.copy();
        pages.put(pageNumber, page);
      }
      return page;
    }

    private void clearOutside(long firstSeq, long lastSeq) {
      long firstPage = pageNumber(firstSeq);
      long lastPage = pageNumber(lastSeq);
      HistoryPage first = pages.get(firstPage);
      if (first != null) {
        first = mutablePage(firstPage);
        for (int i = 0; i < pageOffset(firstSeq); i++) {
          if (first.slots[i] != null) {
            removedKeys.add(first.slots[i]);
            first.slots[i] = null;
            residentCount--;
          }
        }
        if (first.empty()) pages.remove(firstPage);
      }
      HistoryPage last = pages.get(lastPage);
      if (last != null) {
        last = mutablePage(lastPage);
        for (int i = pageOffset(lastSeq) + 1; i < PAGE_SIZE; i++) {
          if (last.slots[i] != null) {
            removedKeys.add(last.slots[i]);
            last.slots[i] = null;
            residentCount--;
          }
        }
        if (last.empty()) pages.remove(lastPage);
      }
    }
  }
}
