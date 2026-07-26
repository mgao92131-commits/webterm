package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** 唯一 LineData 生产编码；样式 ID 在 RemoteTerminalModel staged dictionary 中解析。 */
public final class LineData {
  public final long lineId;
  public final long lineVersion;
  public final boolean wrapped;
  public final long historySeq;
  public final byte[] utf8Text;
  public final byte[] glyphMeta;
  public final List<Span> styleSpans;

  public LineData(long lineId, long lineVersion, boolean wrapped, long historySeq,
                  byte[] utf8Text, byte[] glyphMeta, List<Span> styleSpans) {
    this.lineId = lineId;
    this.lineVersion = lineVersion;
    this.wrapped = wrapped;
    this.historySeq = historySeq;
    this.utf8Text = utf8Text == null ? new byte[0] : utf8Text.clone();
    this.glyphMeta = glyphMeta == null ? new byte[0] : glyphMeta.clone();
    this.styleSpans = styleSpans == null ? Collections.emptyList() : Collections.unmodifiableList(styleSpans);
  }

  /**
   * Test/domain producers use the same LineData representation as protobuf.
   * This is an encoder, not a second decoded Commit path.
   */
  public static LineData fromTerminalLine(TerminalLine line) {
    if (line == null) throw new IllegalArgumentException("line is missing");
    int lastColumn = line.cells.length;
    while (lastColumn > 0 && line.cells[lastColumn - 1].isDefault()) lastColumn--;
    ByteArrayOutputStream text = new ByteArrayOutputStream();
    ByteArrayOutputStream meta = new ByteArrayOutputStream();
    List<Span> spans = new ArrayList<>();
    int openStart = -1, openEnd = -1, openStyle = 0, openLink = 0;
    for (int col = 0; col < lastColumn; ) {
      TerminalCell cell = line.cells[col];
      if (cell == null || cell.isSpacer()) {
        col++;
        continue;
      }
      byte[] glyph = cell.text.getBytes(StandardCharsets.UTF_8);
      text.write(glyph, 0, glyph.length);
      writeUnsignedVarint(meta, ((long) glyph.length << 1) | (cell.width == 2 ? 1 : 0));
      int styleId = cell.style == null ? 0 : cell.style.id;
      int linkId = cell.link == null ? 0 : cell.link.id;
      int end = col + Math.max(1, cell.width);
      if (styleId == 0 && linkId == 0) {
        if (openStart >= 0) {
          spans.add(new Span(openStart, openEnd, openStyle, openLink));
          openStart = -1;
        }
      } else if (openStart >= 0 && openEnd == col
          && openStyle == styleId && openLink == linkId) {
        openEnd = end;
      } else {
        if (openStart >= 0) spans.add(new Span(openStart, openEnd, openStyle, openLink));
        openStart = col;
        openEnd = end;
        openStyle = styleId;
        openLink = linkId;
      }
      col = end;
    }
    if (openStart >= 0) spans.add(new Span(openStart, openEnd, openStyle, openLink));
    return new LineData(line.id, line.version, line.wrapped, line.historySeq,
        text.toByteArray(), meta.toByteArray(), spans);
  }

  private static void writeUnsignedVarint(ByteArrayOutputStream target, long value) {
    while ((value & ~0x7fL) != 0) {
      target.write((int) ((value & 0x7fL) | 0x80L));
      value >>>= 7;
    }
    target.write((int) value);
  }

  public static final class Span {
    public final int startCol, endCol, styleId, linkId;
    public Span(int startCol, int endCol, int styleId, int linkId) {
      this.startCol = startCol; this.endCol = endCol; this.styleId = styleId; this.linkId = linkId;
    }
  }
}
