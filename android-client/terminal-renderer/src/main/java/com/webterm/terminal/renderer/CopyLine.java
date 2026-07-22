package com.webterm.terminal.renderer;

/** 选区中的一条物理终端行，供复制排版器处理。 */
final class CopyLine {
  final String text;
  /** 当前物理行是否会在屏幕右边界继续到下一行。 */
  final boolean wrapped;
  /** 当前物理行是否有行首空白。 */
  final boolean indented;

  CopyLine(String text, boolean wrapped, boolean indented) {
    this.text = text;
    this.wrapped = wrapped;
    this.indented = indented;
  }
}
