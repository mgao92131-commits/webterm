package com.webterm.terminal.model;

/** 已解码的屏幕正文命令，不携带 rowIndex。 */
public record ScreenLineContent(LineKey key, LineBody body) {
  public ScreenLineContent {
    if (key == null || body == null) {
      throw new IllegalArgumentException("screen line content is incomplete");
    }
  }
}
