package com.webterm.terminal.model;

/** WebSocket Commit 中的 HistorySeq 位置绑定，不携带正文。 */
public final class HistoryPush {
  public final long historySeq;
  public final long lineId;
  public final long lineVersion;

  public HistoryPush(long historySeq, long lineId, long lineVersion) {
    this.historySeq = historySeq;
    this.lineId = lineId;
    this.lineVersion = lineVersion;
  }
}
