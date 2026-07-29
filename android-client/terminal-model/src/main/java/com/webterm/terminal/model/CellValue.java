package com.webterm.terminal.model;

/** 一个已解析的字符簇；style/link 均为语义值。 */
public record CellValue(
    String text,
    byte width,
    StyleValue style,
    LinkValue link
) {
  public static final CellValue EMPTY = new CellValue(" ", (byte) 1, null, null);
  public static final CellValue SPACER = new CellValue("", (byte) 0, null, null);

  public CellValue {
    if (text == null || width < 0 || width > 2) {
      throw new IllegalArgumentException("invalid cell value");
    }
  }

  public boolean isWideStart() { return width == 2; }
  public boolean isSpacer() { return width == 0; }
  public boolean isDefault() {
    return " ".equals(text) && width == 1 && style == null && link == null;
  }
}
