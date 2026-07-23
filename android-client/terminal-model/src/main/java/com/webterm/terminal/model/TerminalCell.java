package com.webterm.terminal.model;

import java.util.Objects;

/**
 * 单个终端 Cell。text 为完整字符簇；width 由 Go 权威决定。
 */
public final class TerminalCell {
  public static final TerminalCell EMPTY = new TerminalCell(" ", (byte) 1, null, null);
  public static final TerminalCell SPACER = new TerminalCell("", (byte) 0, null, null);

  public final String text;
  public final byte width;
  public final TerminalStyle style;
  public final Hyperlink link;

  /** wire 字典编号在 mapper 边界被解析，不进入长期缓存。 */
  public TerminalCell(String text, byte width, TerminalStyle style, Hyperlink link) {
    this.text = text;
    this.width = width;
    this.style = style;
    this.link = link;
  }

  public boolean isWideStart() {
    return width == 2;
  }

  public boolean isSpacer() {
    return width == 0;
  }

  public boolean isDefault() {
    return text.equals(" ") && width == 1 && style == null && link == null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TerminalCell)) return false;
    TerminalCell that = (TerminalCell) o;
    return width == that.width && Objects.equals(text, that.text)
        && Objects.equals(style, that.style)
        && Objects.equals(link, that.link);
  }

  @Override
  public int hashCode() {
    return Objects.hash(text, width, style, link);
  }
}
