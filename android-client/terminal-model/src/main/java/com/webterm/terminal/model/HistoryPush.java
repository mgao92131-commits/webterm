package com.webterm.terminal.model;

/** WebSocket Commit 中的 HistorySeq 位置绑定，不携带正文。 */
public final class HistoryPush {
  public final long historySeq;
  public final LineKey key;

  public HistoryPush(long historySeq, LineKey key) {
    if (historySeq <= 0 || key == null) {
      throw new IllegalArgumentException("invalid history push");
    }
    this.historySeq = historySeq;
    this.key = key;
  }

  /** 迁移期测试构造器；生产路径必须直接传递 LineKey。 */
  @Deprecated
  public HistoryPush(long historySeq, long lineId, long lineVersion) {
    this(historySeq, new LineKey(lineId, lineVersion));
  }
}
