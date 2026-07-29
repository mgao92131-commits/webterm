package com.webterm.terminal.model;

/** Renderer 使用的只读身份+正文视图；行位置由外部 row/HistorySeq 决定。 */
public record RenderLine(LineKey key, LineBody body) {
  public RenderLine {
    if (key == null || body == null) {
      throw new IllegalArgumentException("render line is incomplete");
    }
  }

  public int length() { return body.length(); }
  public CellValue at(int column) { return body.at(column); }
}
