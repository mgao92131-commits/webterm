package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 小型不可变历史视图；正式分页投影使用 {@link PagedTerminalHistorySnapshot}。 */
public final class TerminalHistorySnapshot implements HistoryRenderView {
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

  @Override
  public long seqAt(int index) {
    if (index < 0 || index >= lines.size()) {
      throw new IndexOutOfBoundsException("index=" + index + " size=" + lines.size());
    }
    return lines.get(index).historyOrder();
  }

  @Override
  public HistoryExtent extent() {
    return lines.isEmpty()
        ? HistoryExtent.INITIAL_EMPTY : new HistoryExtent(firstSeq(), lastSeq());
  }

  @Override
  public HistoryExtent availableExtent() {
    return extent();
  }

  @Override
  public long logicalSize() {
    return lines.size();
  }

  @Override
  public long loadedLineCount() {
    return lines.size();
  }

  @Override
  public long estimatedByteCount() {
    long bytes = 0;
    for (TerminalLine line : lines) {
      if (line != null) bytes += line.estimatedBytes;
    }
    return bytes;
  }

  @Override
  public long firstLoadedSeq() {
    return lines.isEmpty() ? -1 : firstSeq();
  }

  @Override
  public SlotState slotStateAt(long logicalIndex) {
    if (logicalIndex < 0 || logicalIndex >= lines.size()) {
      throw new IndexOutOfBoundsException(
          "logicalIndex=" + logicalIndex + " size=" + lines.size());
    }
    return lines.get((int) logicalIndex) == null ? SlotState.UNLOADED : SlotState.LOADED;
  }
}
