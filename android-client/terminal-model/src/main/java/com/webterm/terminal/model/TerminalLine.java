package com.webterm.terminal.model;

import java.util.Arrays;

/**
 * 终端行。屏幕与历史共用稳定 lineId；version 只在内容变化时递增。
 */
public final class TerminalLine {
  public final long id;
  public final long version;
  /** Non-zero only for a scrollback entry; orders history independently of id. */
  public final long historySeq;
  public final boolean wrapped;
  public final TerminalCell[] cells;

  public TerminalLine(long id, boolean wrapped, TerminalCell[] cells) {
    this(id, 1, wrapped, cells);
  }

  public TerminalLine(long id, long version, boolean wrapped, TerminalCell[] cells) {
    this(id, version, 0, wrapped, cells);
  }

  public TerminalLine(long id, long version, long historySeq, boolean wrapped, TerminalCell[] cells) {
    this.id = id;
    this.version = version;
    this.historySeq = historySeq;
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
    return new TerminalLine(id, version, historySeq, wrapped, newCells);
  }

  public TerminalLine withWrapped(boolean wrapped) {
    return new TerminalLine(id, version, historySeq, wrapped, cells);
  }

  public TerminalLine withId(long id) {
    return new TerminalLine(id, version, historySeq, wrapped, cells);
  }

  public TerminalLine withHistorySeq(long historySeq) {
    return new TerminalLine(id, version, historySeq, wrapped, cells);
  }

  /** Legacy direct-model tests without a wire HistorySeq use the line id order. */
  public long historyOrder() {
    return historySeq != 0 ? historySeq : id;
  }

  /**
   * Compares the wire-visible line payload without treating the object identity
   * as meaningful. Same id/version data may be repeated by a recovery patch;
   * accepting only an identical replay keeps that path idempotent while still
   * detecting a broken producer that reuses a version for different cells.
   */
  public boolean sameContent(TerminalLine other) {
    return other != null && id == other.id && version == other.version
        && wrapped == other.wrapped && Arrays.equals(cells, other.cells);
  }
}
