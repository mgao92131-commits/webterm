package com.webterm.terminal.model;

import java.util.Objects;

/** 同一 terminal projection 内可寻址历史的绝对序号窗口。 */
public final class HistoryExtent {
  public static final HistoryExtent INITIAL_EMPTY = new HistoryExtent(1, 0);

  public final long firstSeq;
  public final long lastSeq;

  public HistoryExtent(long firstSeq, long lastSeq) {
    if (firstSeq < 1 || lastSeq < 0 || lastSeq + 1 < firstSeq) {
      throw new IllegalArgumentException("invalid history extent " + firstSeq + ".." + lastSeq);
    }
    this.firstSeq = firstSeq;
    this.lastSeq = lastSeq;
  }

  public boolean isEmpty() {
    return lastSeq + 1 == firstSeq;
  }

  public long logicalSize() {
    return isEmpty() ? 0 : lastSeq - firstSeq + 1;
  }

  public boolean contains(long seq) {
    return !isEmpty() && seq >= firstSeq && seq <= lastSeq;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof HistoryExtent)) return false;
    HistoryExtent that = (HistoryExtent) o;
    return firstSeq == that.firstSeq && lastSeq == that.lastSeq;
  }

  @Override
  public int hashCode() {
    return Objects.hash(firstSeq, lastSeq);
  }

  @Override
  public String toString() {
    return firstSeq + ".." + lastSeq;
  }
}
