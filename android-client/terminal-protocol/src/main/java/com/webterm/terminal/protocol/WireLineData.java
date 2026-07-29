package com.webterm.terminal.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 未解析字典 ID 的 LineData。 */
public final class WireLineData {
  public record Span(int startCol, int endCol, int styleId, int linkId) {}

  public final long lineId;
  public final long lineVersion;
  public final long historySeq;
  public final int physicalColumns;
  public final boolean wrapped;
  public final byte[] utf8Text;
  public final byte[] glyphMeta;
  public final List<Span> styleSpans;

  public WireLineData(
      long lineId, long lineVersion, long historySeq, int physicalColumns, boolean wrapped,
      byte[] utf8Text, byte[] glyphMeta, List<Span> styleSpans) {
    this.lineId = lineId;
    this.lineVersion = lineVersion;
    this.historySeq = historySeq;
    this.physicalColumns = physicalColumns;
    this.wrapped = wrapped;
    this.utf8Text = utf8Text == null ? new byte[0] : utf8Text.clone();
    this.glyphMeta = glyphMeta == null ? new byte[0] : glyphMeta.clone();
    this.styleSpans = Collections.unmodifiableList(new ArrayList<>(
        styleSpans == null ? Collections.emptyList() : styleSpans));
  }
}
