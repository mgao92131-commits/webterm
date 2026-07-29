package com.webterm.terminal.model;

/** 正文缓存与位置目录共同所属的投影身份。 */
public record ProjectionIdentity(
    String instanceId,
    long layoutEpoch,
    long historyGeneration
) {
  public ProjectionIdentity {
    if (instanceId == null || instanceId.isEmpty()
        || layoutEpoch <= 0 || historyGeneration <= 0) {
      throw new IllegalArgumentException("invalid projection identity");
    }
  }
}
