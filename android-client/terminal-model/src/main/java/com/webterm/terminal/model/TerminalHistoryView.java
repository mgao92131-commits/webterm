package com.webterm.terminal.model;

/** Renderer/selection shared read-only history contract. */
public interface TerminalHistoryView {
  int size();
  default boolean isEmpty() { return size() == 0; }
  TerminalLine lineAt(int index);
  int findSeqIndex(long seq);
  long firstSeq();
  long lastSeq();
  /** 逻辑下标对应的 HistorySeq；不得从 TerminalLine 正文推导。 */
  long seqAt(int index);
}
