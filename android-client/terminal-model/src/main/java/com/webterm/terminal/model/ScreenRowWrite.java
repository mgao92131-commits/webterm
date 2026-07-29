package com.webterm.terminal.model;

public final class ScreenRowWrite {
  public final int row;
  public final ScreenLineContent line;

  public ScreenRowWrite(int row, ScreenLineContent line) {
    this.row = row;
    this.line = line;
  }
}
