package com.webterm.terminal.model;

/** LineKey 与正文的不可变绑定。 */
public record LineBodyRecord(LineKey key, LineBody body) {
  public LineBodyRecord {
    if (key == null || body == null) {
      throw new IllegalArgumentException("invalid line body record");
    }
  }
}
