package com.webterm.terminal.model;

/** HTTP Range 解码后的 envelope；HistorySeq 不进入 LineBody。 */
public record HistoryBodyEntry(long historySeq, LineKey key, LineBody body) {
  public HistoryBodyEntry {
    if (historySeq <= 0 || key == null || body == null) {
      throw new IllegalArgumentException("invalid history body entry");
    }
  }
}
