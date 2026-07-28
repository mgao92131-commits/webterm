package com.webterm.terminal.model;

/** HistorySeq 位置绑定的行身份与正文版本。 */
public final class HistoryLineRef {
  public final long lineId;
  public final long lineVersion;

  public HistoryLineRef(long lineId, long lineVersion) {
    if (lineId <= 0 || lineVersion <= 0) {
      throw new IllegalArgumentException("invalid history line reference");
    }
    this.lineId = lineId;
    this.lineVersion = lineVersion;
  }

  @Override
  public boolean equals(Object value) {
    if (this == value) return true;
    if (!(value instanceof HistoryLineRef)) return false;
    HistoryLineRef other = (HistoryLineRef) value;
    return lineId == other.lineId && lineVersion == other.lineVersion;
  }

  @Override
  public int hashCode() {
    return 31 * Long.hashCode(lineId) + Long.hashCode(lineVersion);
  }
}
