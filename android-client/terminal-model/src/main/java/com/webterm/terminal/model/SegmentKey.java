package com.webterm.terminal.model;

import java.util.Objects;

/** SegmentKey = historyGeneration + segmentNumber；segmentNumber = (historySeq-1)/128。 */
public final class SegmentKey {
  public static final int SIZE = PagedTerminalHistory.PAGE_SIZE;

  public final long generation;
  public final long number;

  public SegmentKey(long generation, long number) {
    this.generation = generation;
    this.number = number;
  }

  public static long numberForSeq(long historySeq) {
    if (historySeq < 1) return 0;
    return (historySeq - 1) / SIZE;
  }

  public long firstSeq() {
    return number * SIZE + 1;
  }

  public long lastSeq() {
    return firstSeq() + SIZE - 1;
  }

  public static SegmentKey forSeq(long generation, long historySeq) {
    return new SegmentKey(generation, numberForSeq(historySeq));
  }

  @Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SegmentKey)) return false;
    SegmentKey that = (SegmentKey) o;
    return generation == that.generation && number == that.number;
  }

  @Override public int hashCode() {
    return Objects.hash(generation, number);
  }

  @Override public String toString() {
    return "SegmentKey{g=" + generation + ",n=" + number + '}';
  }
}
