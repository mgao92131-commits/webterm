package com.webterm.terminal.renderer;

/** 选区中的一条物理终端行，供复制排版器处理。 */
final class CopyLine {
  final String text;
  /** 当前物理行是否在终端中继续到下一物理行。 */
  final boolean wrapped;

  CopyLine(String text, boolean wrapped) {
    this.text = text;
    this.wrapped = wrapped;
  }
}
