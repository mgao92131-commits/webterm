package com.webterm.terminal.model;

public final class ScreenRowWrite {
  public final int row;
  public final ScreenLineContent line;

  public ScreenRowWrite(int row, ScreenLineContent line) {
    this.row = row;
    this.line = line;
  }

  /** 迁移期测试构造器；Renderer 迁移完成后与旧混合行类型一起删除。 */
  @Deprecated
  public ScreenRowWrite(int row, TerminalLine line) {
    this(row, SemanticLineAdapter.screenContent(line));
  }
}
