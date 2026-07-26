package com.webterm.terminal.model;

public final class ScreenRowWrite {
  public final int row;
  public final LineData lineData;

  public ScreenRowWrite(int row, TerminalLine line) {
    this.row = row;
    this.lineData = LineData.fromTerminalLine(line);
  }

  public ScreenRowWrite(int row, LineData line) {
    this.row = row;
    this.lineData = line;
  }
}
