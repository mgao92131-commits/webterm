package com.webterm.terminal.model;

import java.util.Objects;

/**
 * 终端光标。位置由 Go 决定，Android 只负责绘制与闪烁动画。
 */
public final class TerminalCursor {
  public enum Shape {
    BLOCK,
    BAR,
    UNDERLINE
  }

  public final int row;
  public final int col;
  public final boolean visible;
  public final Shape shape;
  public final boolean blink;

  public TerminalCursor(int row, int col, boolean visible, Shape shape, boolean blink) {
    this.row = row;
    this.col = col;
    this.visible = visible;
    this.shape = shape;
    this.blink = blink;
  }

  public static TerminalCursor hidden() {
    return new TerminalCursor(0, 0, false, Shape.BLOCK, false);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TerminalCursor)) return false;
    TerminalCursor that = (TerminalCursor) o;
    return row == that.row && col == that.col && visible == that.visible
        && blink == that.blink && shape == that.shape;
  }

  @Override
  public int hashCode() {
    return Objects.hash(row, col, visible, shape, blink);
  }
}
