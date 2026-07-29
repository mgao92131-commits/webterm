package com.webterm.terminal.protocol;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.LinkValue;
import com.webterm.terminal.model.StyleValue;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** WS canonical dictionary 与 HTTP message-local dictionary 共用的唯一正文解码器。 */
public final class LineBodyDecoder {
  public DecodedLine decode(WireLineData line, WireDictionary dictionary) {
    if (line == null || dictionary == null
        || line.lineId <= 0 || line.lineVersion <= 0
        || line.physicalColumns < 1 || line.physicalColumns > 500) {
      throw new IllegalArgumentException("invalid wire line");
    }
    CellValue[] cells = new CellValue[line.physicalColumns];
    Arrays.fill(cells, CellValue.EMPTY);
    int textOffset = 0;
    int metaOffset = 0;
    int column = 0;
    while (metaOffset < line.glyphMeta.length) {
      long value = 0;
      int shift = 0;
      while (true) {
        if (metaOffset >= line.glyphMeta.length || shift >= 64) {
          throw new IllegalArgumentException("invalid glyph metadata");
        }
        int b = line.glyphMeta[metaOffset++] & 0xff;
        value |= (long) (b & 0x7f) << shift;
        if ((b & 0x80) == 0) break;
        shift += 7;
      }
      int length = (int) (value >>> 1);
      int width = (value & 1L) == 0 ? 1 : 2;
      if (length <= 0 || textOffset + length > line.utf8Text.length
          || column + width > line.physicalColumns) {
        throw new IllegalArgumentException("invalid glyph metadata");
      }
      String text = new String(
          line.utf8Text, textOffset, length, StandardCharsets.UTF_8);
      textOffset += length;
      int styleId = 0;
      int linkId = 0;
      for (WireLineData.Span span : line.styleSpans) {
        if (column >= span.startCol() && column < span.endCol()) {
          styleId = span.styleId();
          linkId = span.linkId();
          break;
        }
      }
      StyleValue style = dictionary.style(styleId);
      LinkValue link = dictionary.link(linkId);
      cells[column] = width == 1 && " ".equals(text) && style == null && link == null
          ? CellValue.EMPTY : new CellValue(text, (byte) width, style, link);
      if (width == 2) cells[column + 1] = CellValue.SPACER;
      column += width;
    }
    if (textOffset != line.utf8Text.length) {
      throw new IllegalArgumentException("glyph metadata/text mismatch");
    }
    return new DecodedLine(
        new LineKey(line.lineId, line.lineVersion),
        line.historySeq,
        new LineBody(line.physicalColumns, line.wrapped, cells));
  }
}
