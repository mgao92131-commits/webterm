package com.webterm.terminal.model;

import java.util.Objects;

/**
 * 单个终端 Cell。text 为完整字符簇；width 由 Go 权威决定。
 */
public final class TerminalCell {
  public static final TerminalCell EMPTY = new TerminalCell(" ", (byte) 1, 0, 0);
  public static final TerminalCell SPACER = new TerminalCell("", (byte) 0, 0, 0);

  public final String text;
  public final byte width;
  public final int styleId;
  public final int linkId;

  public TerminalCell(String text, byte width, int styleId, int linkId) {
    this.text = text;
    this.width = width;
    this.styleId = styleId;
    this.linkId = linkId;
  }

  public boolean isWideStart() {
    return width == 2;
  }

  public boolean isSpacer() {
    return width == 0;
  }

  public boolean isDefault() {
    return text.equals(" ") && width == 1 && styleId == 0 && linkId == 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TerminalCell)) return false;
    TerminalCell that = (TerminalCell) o;
    return width == that.width && styleId == that.styleId && linkId == that.linkId
        && Objects.equals(text, that.text);
  }

  @Override
  public int hashCode() {
    return Objects.hash(text, width, styleId, linkId);
  }
}
