package com.webterm.terminal.model;

/** 与 wire styleId 无关的纯样式值。 */
public record StyleValue(
    TerminalColor fg,
    TerminalColor bg,
    TerminalColor underlineColor,
    int attrs
) {
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
}
