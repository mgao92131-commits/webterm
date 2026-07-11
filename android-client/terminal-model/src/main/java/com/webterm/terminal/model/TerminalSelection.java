package com.webterm.terminal.model;

/**
 * 纯数据：选择范围锚点。
 * 锚点可以位于历史行（用 lineId）或活动屏幕行（用 row）。
 */
public final class TerminalSelection {

  public final Anchor start;
  public final Anchor end;

  public TerminalSelection(Anchor start, Anchor end) {
    this.start = start;
    this.end = end;
  }

  public boolean isEmpty() {
    return start.equals(end);
  }

  public TerminalSelection normalized() {
    if (start.compareTo(end) <= 0) return this;
    return new TerminalSelection(end, start);
  }

  public static final class Anchor implements Comparable<Anchor> {
    public final long historyLineId; // >0 表示在历史中；0 表示在活动屏幕
    public final int screenRow;      // 活动屏幕行索引（historyLineId==0 时有效）
    public final int col;

    public Anchor(long historyLineId, int screenRow, int col) {
      this.historyLineId = historyLineId;
      this.screenRow = screenRow;
      this.col = col;
    }

    @Override
    public int compareTo(Anchor other) {
      if (this.historyLineId != 0 && other.historyLineId != 0) {
        int cmp = Long.compare(this.historyLineId, other.historyLineId);
        if (cmp != 0) return cmp;
        return Integer.compare(this.col, other.col);
      }
      if (this.historyLineId != 0) return -1; // 历史行总在屏幕上方
      if (other.historyLineId != 0) return 1;
      int cmp = Integer.compare(this.screenRow, other.screenRow);
      if (cmp != 0) return cmp;
      return Integer.compare(this.col, other.col);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Anchor)) return false;
      Anchor that = (Anchor) o;
      return historyLineId == that.historyLineId
          && screenRow == that.screenRow
          && col == that.col;
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(historyLineId, screenRow, col);
    }
  }
}
