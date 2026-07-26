package com.webterm.terminal.model;

public final class ScreenRowWrite {
  public final int row;
  public final TerminalLine line;

  public ScreenRowWrite(int row, TerminalLine line) {
    this.row = row;
    this.line = line;
  }
}
