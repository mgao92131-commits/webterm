package com.webterm.terminal.model;

import java.util.Objects;

/**
 * 终端样式。styleId 在 layout epoch 内只增不改。
 */
public final class TerminalStyle {
  public final int id;
  public final TerminalColor fg;
  public final TerminalColor bg;
  public final TerminalColor underlineColor;
  public final int attrs;

  public TerminalStyle(int id, TerminalColor fg, TerminalColor bg, TerminalColor underlineColor, int attrs) {
    this.id = id;
    this.fg = fg;
    this.bg = bg;
    this.underlineColor = underlineColor;
    this.attrs = attrs;
  }

  public boolean bold()            { return (attrs & (1 << 0)) != 0; }
  public boolean dim()             { return (attrs & (1 << 1)) != 0; }
  public boolean italic()          { return (attrs & (1 << 2)) != 0; }
  public boolean underline()       { return (attrs & (1 << 3)) != 0; }
  public boolean doubleUnderline() { return (attrs & (1 << 4)) != 0; }
  public boolean curlyUnderline()  { return (attrs & (1 << 5)) != 0; }
  public boolean dottedUnderline() { return (attrs & (1 << 6)) != 0; }
  public boolean dashedUnderline() { return (attrs & (1 << 7)) != 0; }
  public boolean blinkSlow()       { return (attrs & (1 << 8)) != 0; }
  public boolean blinkFast()       { return (attrs & (1 << 9)) != 0; }
  public boolean reverse()         { return (attrs & (1 << 10)) != 0; }
  public boolean hidden()          { return (attrs & (1 << 11)) != 0; }
  public boolean strike()          { return (attrs & (1 << 12)) != 0; }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TerminalStyle)) return false;
    TerminalStyle that = (TerminalStyle) o;
    return id == that.id && attrs == that.attrs
        && Objects.equals(fg, that.fg)
        && Objects.equals(bg, that.bg)
        && Objects.equals(underlineColor, that.underlineColor);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, fg, bg, underlineColor, attrs);
  }
}
