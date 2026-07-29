package com.webterm.terminal.model;

/** 终端正文的唯一身份。位置和传输字典世代均不属于该 key。 */
public record LineKey(long lineId, long lineVersion) {
  public LineKey {
    if (lineId <= 0 || lineVersion <= 0) {
      throw new IllegalArgumentException("invalid line key");
    }
  }
}
