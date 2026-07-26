package com.webterm.terminal.model;

public final class HistoryPromotion {
  public final long lineId, lineVersion, historySeq;
  public HistoryPromotion(long lineId, long lineVersion, long historySeq) {
    this.lineId = lineId; this.lineVersion = lineVersion; this.historySeq = historySeq;
  }
}
