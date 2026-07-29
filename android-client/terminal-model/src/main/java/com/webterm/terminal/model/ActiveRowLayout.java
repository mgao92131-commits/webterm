package com.webterm.terminal.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** rowIndex → LineKey 的不可变活动屏位置目录。 */
public final class ActiveRowLayout {
  private final LineKey[] rows;

  public ActiveRowLayout(LineKey[] rows) {
    if (rows == null) throw new IllegalArgumentException("active rows missing");
    Set<LineKey> unique = new HashSet<>();
    for (LineKey key : rows) {
      if (key == null || !unique.add(key)) {
        throw new IllegalArgumentException("invalid or duplicate active LineKey");
      }
    }
    this.rows = Arrays.copyOf(rows, rows.length);
  }

  public static ActiveRowLayout empty() {
    return new ActiveRowLayout(new LineKey[0]);
  }

  public int size() { return rows.length; }
  public LineKey keyAt(int row) { return rows[row]; }
  public LineKey[] copyKeys() { return Arrays.copyOf(rows, rows.length); }

  public boolean contains(LineKey key) {
    for (LineKey candidate : rows) if (candidate.equals(key)) return true;
    return false;
  }

  public Set<LineKey> keys() {
    return Set.copyOf(Arrays.asList(rows));
  }
}
