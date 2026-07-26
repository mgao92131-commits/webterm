package com.webterm.terminal.model;

/** Main/Alternate 各自独立持有正文、历史位置和活动行位置。 */
final class TerminalSurface {
  final LineStore lineStore = new LineStore();
  final HistoryIndex historyIndex = new HistoryIndex();
  final PagedTerminalHistory history;
  ActiveRows activeRows = ActiveRows.empty();

  TerminalSurface(HistoryBudget budget) {
    history = new PagedTerminalHistory(budget, RemoteTerminalModel::estimateHistoryLineBytesForStore);
  }
}
