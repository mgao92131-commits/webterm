package com.webterm.terminal.model;

import java.util.Arrays;

/**
 * 终端行。历史行使用稳定 lineId；活动屏幕行 lineId 为 0。
 */
public final class TerminalLine {
  public final long id;
  public final boolean wrapped;
  public final TerminalCell[] cells;

  public TerminalLine(long id, boolean wrapped, TerminalCell[] cells) {
    this.id = id;
    this.wrapped = wrapped;
    this.cells = cells;
  }

  public static TerminalLine empty(long id, int cols) {
    TerminalCell[] cells = new TerminalCell[cols];
    Arrays.fill(cells, TerminalCell.EMPTY);
    return new TerminalLine(id, false, cells);
  }

  public int length() {
    return cells.length;
  }

  public TerminalCell at(int col) {
    return cells[col];
  }

  public TerminalLine withCells(TerminalCell[] newCells) {
    return new TerminalLine(id, wrapped, newCells);
  }

  public TerminalLine withWrapped(boolean wrapped) {
    return new TerminalLine(id, wrapped, cells);
  }

  public TerminalLine withId(long id) {
    return new TerminalLine(id, wrapped, cells);
  }
}
