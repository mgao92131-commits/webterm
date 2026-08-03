package com.webterm.terminal.renderer;

/** UI 线程复用的最终终端样式 scratch。 */
final class ResolvedTerminalStyle {
  enum UnderlineKind {
    NONE,
    SINGLE,
    DOUBLE,
    CURLY,
    DOTTED,
    DASHED
  }

  int foreground;
  int background;
  int underlineColor;

  boolean bold;
  boolean dim;
  boolean italic;
  boolean hidden;
  boolean strike;
  boolean blinkSlow;
  boolean blinkFast;

  UnderlineKind underlineKind = UnderlineKind.NONE;
}
