package com.webterm.terminal.model;

import java.util.Arrays;

/** 不含身份和位置的不可变终端行正文。 */
public final class LineBody {
  public final int physicalColumns;
  public final boolean wrapped;
  private final CellValue[] cells;
  public final long estimatedBytes;

  public LineBody(int physicalColumns, boolean wrapped, CellValue[] cells) {
    if (physicalColumns < 1 || physicalColumns > 500
        || cells == null || cells.length != physicalColumns) {
      throw new IllegalArgumentException("invalid line body geometry");
    }
    for (CellValue cell : cells) {
      if (cell == null) throw new IllegalArgumentException("line body contains null cell");
    }
    this.physicalColumns = physicalColumns;
    this.wrapped = wrapped;
    this.cells = Arrays.copyOf(cells, cells.length);
    this.estimatedBytes = estimateBytes(this.cells);
  }

  public int length() { return cells.length; }
  public CellValue at(int column) { return cells[column]; }
  public CellValue[] copyCells() { return Arrays.copyOf(cells, cells.length); }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof LineBody)) return false;
    LineBody that = (LineBody) other;
    return physicalColumns == that.physicalColumns
        && wrapped == that.wrapped
        && Arrays.equals(cells, that.cells);
  }

  @Override
  public int hashCode() {
    int result = 31 * physicalColumns + Boolean.hashCode(wrapped);
    return 31 * result + Arrays.hashCode(cells);
  }

  private static long estimateBytes(CellValue[] cells) {
    long bytes = 48 + cells.length * 4L;
    for (CellValue cell : cells) {
      bytes += 64;
      bytes += cell.text().length() * 2L;
    }
    return bytes;
  }
}
