package com.webterm.terminal.protocol;

import com.google.protobuf.ByteString;
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
  public final ByteString utf8Text;
  public final ByteString glyphMeta;
  public final List<Span> styleSpans;

  public WireLineData(
      long lineId, long lineVersion, long historySeq, int physicalColumns, boolean wrapped,
      byte[] utf8Text, byte[] glyphMeta, List<Span> styleSpans) {
    this(lineId, lineVersion, historySeq, physicalColumns, wrapped,
        utf8Text == null ? ByteString.EMPTY : ByteString.copyFrom(utf8Text),
        glyphMeta == null ? ByteString.EMPTY : ByteString.copyFrom(glyphMeta),
        styleSpans);
  }

  public WireLineData(
      long lineId, long lineVersion, long historySeq, int physicalColumns, boolean wrapped,
      ByteString utf8Text, ByteString glyphMeta, List<Span> styleSpans) {
    this.lineId = lineId;
    this.lineVersion = lineVersion;
    this.historySeq = historySeq;
    this.physicalColumns = physicalColumns;
    this.wrapped = wrapped;
    this.utf8Text = utf8Text == null ? ByteString.EMPTY : utf8Text;
    this.glyphMeta = glyphMeta == null ? ByteString.EMPTY : glyphMeta;
    this.styleSpans = Collections.unmodifiableList(new ArrayList<>(
        styleSpans == null ? Collections.emptyList() : styleSpans));
    int previousEnd = 0;
    for (Span span : this.styleSpans) {
      if (span == null || span.startCol() < previousEnd || span.startCol() < 0
          || span.endCol() <= span.startCol() || span.endCol() > physicalColumns
          || span.styleId() < 0 || span.linkId() < 0) {
        throw new IllegalArgumentException("invalid wire style span");
      }
      previousEnd = span.endCol();
    }
  }
}
