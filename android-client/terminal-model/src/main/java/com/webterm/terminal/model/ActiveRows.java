package com.webterm.terminal.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** rowIndex → LineID 的不可变活动区域索引。 */
public final class ActiveRows {
  private final long[] lineIds;

  public ActiveRows(long[] lineIds) {
    if (lineIds == null) throw new IllegalArgumentException("lineIds missing");
    Set<Long> unique = new HashSet<>();
    for (long lineId : lineIds) {
      if (lineId <= 0 || !unique.add(lineId)) {
        throw new IllegalArgumentException("invalid or duplicate active LineID");
      }
    }
    this.lineIds = Arrays.copyOf(lineIds, lineIds.length);
  }

  public static ActiveRows empty() {
    return new ActiveRows(new long[0]);
  }

  public int size() {
    return lineIds.length;
  }

  public long lineIdAt(int row) {
    return lineIds[row];
  }

  public long[] copyLineIds() {
    return Arrays.copyOf(lineIds, lineIds.length);
  }

  public boolean contains(long lineId) {
    for (long candidate : lineIds) if (candidate == lineId) return true;
    return false;
  }
}
