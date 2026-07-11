package com.webterm.terminal.model;

import java.util.Objects;

/**
 * 终端颜色。保留语义类型，不预先解析为 RGB。
 */
public final class TerminalColor {
  public enum Kind {
    DEFAULT_FG,
    DEFAULT_BG,
    CURSOR,
    INDEXED,
    RGB
  }

  public static final TerminalColor DEFAULT_FG = new TerminalColor(Kind.DEFAULT_FG, 0, 0);
  public static final TerminalColor DEFAULT_BG = new TerminalColor(Kind.DEFAULT_BG, 0, 0);
  public static final TerminalColor CURSOR = new TerminalColor(Kind.CURSOR, 0, 0);

  public final Kind kind;
  public final int index;
  public final int rgb;

  public TerminalColor(Kind kind, int index, int rgb) {
    this.kind = kind;
    this.index = index;
    this.rgb = rgb;
  }

  public static TerminalColor indexed(int index) {
    return new TerminalColor(Kind.INDEXED, index, 0);
  }

  public static TerminalColor rgb(int rgb) {
    return new TerminalColor(Kind.RGB, 0, rgb);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TerminalColor)) return false;
    TerminalColor that = (TerminalColor) o;
    return kind == that.kind && index == that.index && rgb == that.rgb;
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, index, rgb);
  }
}
