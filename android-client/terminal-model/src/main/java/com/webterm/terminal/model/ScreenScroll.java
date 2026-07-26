package com.webterm.terminal.model;

public final class ScreenScroll {
  public final int topRow;
  public final int bottomRowExclusive;
  public final int deltaRows;

  public ScreenScroll(int topRow, int bottomRowExclusive, int deltaRows) {
    this.topRow = topRow;
    this.bottomRowExclusive = bottomRowExclusive;
    this.deltaRows = deltaRows;
  }
}
