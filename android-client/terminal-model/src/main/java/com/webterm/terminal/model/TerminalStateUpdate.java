package com.webterm.terminal.model;

/**
 * 与一个 {@link RenderUpdate} 同步交换的非 Canvas 页面状态。
 *
 * <p>它不参与脏区决策；仅供 Controller 处理历史视口补偿、分页 loading 状态以及实例/几何
 * 锚点重置。和 RenderDirtyState 一样，实例在模型锁内累计，交换出去后不再修改。</p>
 */
public final class TerminalStateUpdate {
  public boolean geometryChanged;
  public boolean historyChanged;
  public boolean titleChanged;
  public boolean workingDirectoryChanged;
  public int tailAppendedLines;
  public int historyPrependedLines;

  public boolean isEmpty() {
    return !geometryChanged && !historyChanged && !titleChanged && !workingDirectoryChanged
        && tailAppendedLines == 0 && historyPrependedLines == 0;
  }

  void merge(boolean geometryChanged, boolean historyChanged, boolean titleChanged,
             boolean workingDirectoryChanged, int tailAppendedLines,
             int historyPrependedLines) {
    this.geometryChanged |= geometryChanged;
    this.historyChanged |= historyChanged;
    this.titleChanged |= titleChanged;
    this.workingDirectoryChanged |= workingDirectoryChanged;
    this.tailAppendedLines = saturatingAdd(this.tailAppendedLines, tailAppendedLines);
    this.historyPrependedLines = saturatingAdd(this.historyPrependedLines, historyPrependedLines);
  }

  private static int saturatingAdd(int first, int second) {
    long result = (long) first + Math.max(0, second);
    return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
  }
}
