package com.webterm.terminal.model;

import java.util.Arrays;

/**
 * 终端行。屏幕与历史共用稳定 lineId；version 只在内容变化时递增。
 */
public final class TerminalLine {
  public final long id;
  public final long version;
  public final boolean wrapped;
  public final TerminalCell[] cells;

  public TerminalLine(long id, boolean wrapped, TerminalCell[] cells) {
    this(id, 1, wrapped, cells);
  }

  public TerminalLine(long id, long version, boolean wrapped, TerminalCell[] cells) {
    this.id = id;
    this.version = version;
    this.wrapped = wrapped;
    this.cells = cells;
  }

  public static TerminalLine empty(long id, int cols) {
    TerminalCell[] cells = new TerminalCell[cols];
    Arrays.fill(cells, TerminalCell.EMPTY);
    return new TerminalLine(id, 1, false, cells);
  }

  public int length() {
    return cells.length;
  }

  public TerminalCell at(int col) {
    return cells[col];
  }

  public TerminalLine withCells(TerminalCell[] newCells) {
    return new TerminalLine(id, version, wrapped, newCells);
  }

  public TerminalLine withWrapped(boolean wrapped) {
    return new TerminalLine(id, version, wrapped, cells);
  }

  public TerminalLine withId(long id) {
    return new TerminalLine(id, version, wrapped, cells);
  }
}
