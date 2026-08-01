package com.webterm.terminal.model;

import java.util.Set;

/** 发起 HTTP 正文 batch 时冻结的投影身份与请求 key 集合。 */
public record BodyBatchRequestContext(
    ProjectionIdentity identity,
    Set<LineKey> requestedKeys) {
  public BodyBatchRequestContext {
    if (identity == null || requestedKeys == null || requestedKeys.isEmpty()) {
      throw new IllegalArgumentException("invalid body batch request context");
    }
  }
}
