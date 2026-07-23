package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 小型不可变历史视图；正式分页投影使用 {@link PagedTerminalHistorySnapshot}。 */
public final class TerminalHistorySnapshot implements TerminalHistoryView {
  private static final TerminalHistorySnapshot EMPTY =
      new TerminalHistorySnapshot(Collections.emptyList());

  private final List<TerminalLine> lines;

  public TerminalHistorySnapshot(List<TerminalLine> lines) {
    this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
  }

  public static TerminalHistorySnapshot empty() {
    return EMPTY;
  }

  @Override
  public int size() {
    return lines.size();
  }

  @Override
  public TerminalLine lineAt(int index) {
    return lines.get(index);
  }

  @Override
  public int findSeqIndex(long seq) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).historyOrder() == seq) return i;
    }
    return -1;
  }

  @Override
  public long firstSeq() {
    return lines.isEmpty() ? 0 : lines.get(0).historyOrder();
  }

  @Override
  public long lastSeq() {
    return lines.isEmpty() ? 0 : lines.get(lines.size() - 1).historyOrder();
  }
}
