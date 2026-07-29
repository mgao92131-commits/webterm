package com.webterm.terminal.model;

/** 发起 HTTP 请求时冻结的投影身份与闭区间。 */
public record HistoryRequestContext(
    ProjectionIdentity identity,
    long fromSeq,
    long toSeq,
    long anchorSeq
) {
  public HistoryRequestContext {
    if (identity == null || fromSeq < 1 || toSeq < fromSeq) {
      throw new IllegalArgumentException("invalid history request context");
    }
  }
}
