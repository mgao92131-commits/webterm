package com.webterm.terminal.model;

/** 一个 Buffer 的不可变 root；位置目录与正文缓存通过事务整体替换。 */
public final class TerminalSurfaceState {
  public final ActiveRowLayout activeRows;
  public final HistoryCatalog historyCatalog;
  public final BodyCache bodyCache;

  public TerminalSurfaceState(HistoryBudget budget) {
    this(ActiveRowLayout.empty(), new HistoryCatalog(), new BodyCache(budget));
  }

  TerminalSurfaceState(
      ActiveRowLayout activeRows, HistoryCatalog historyCatalog, BodyCache bodyCache) {
    this.activeRows = activeRows;
    this.historyCatalog = historyCatalog;
    this.bodyCache = bodyCache;
  }

  public TerminalSurfaceTransaction beginTransaction() {
    return new TerminalSurfaceTransaction(this);
  }
}
