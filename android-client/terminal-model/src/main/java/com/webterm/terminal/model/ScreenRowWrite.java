package com.webterm.terminal.model;

public final class ScreenRowWrite {
  public final int row;
  public final LineKey key;

  public ScreenRowWrite(int row, LineKey key) {
    this.row = row;
    this.key = key;
  }

  /** 测试用：从完整行内容提取 key。 */
  public static ScreenRowWrite fromLine(int row, ScreenLineContent line) {
    return new ScreenRowWrite(row, line.key());
  }
}
