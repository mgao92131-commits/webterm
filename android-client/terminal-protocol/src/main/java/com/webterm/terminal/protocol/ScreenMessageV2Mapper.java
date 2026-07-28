package com.webterm.terminal.protocol;

import com.webterm.terminal.model.*;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** screen.v2 wire dictionary is resolved at this boundary. */
public final class ScreenMessageV2Mapper {
  private ScreenMessageV2Mapper() {}

  public static ScreenBaseline mapBaseline(TerminalScreenV2Proto.Baseline pb) {
    Dictionary dictionary = dictionary(pb.getDictionary());
    int columns = pb.getGeometry().getCols();
    Map<Long, TerminalLine> screenLines =
        mapLines(pb.getScreenLinesList(), columns, dictionary);
    List<TerminalLine> screen = new ArrayList<>();
    for (long id : pb.getScreenLayout().getLineIdsList()) {
      TerminalLine line = screenLines.get(id);
      if (line == null) throw new IllegalArgumentException("baseline layout line missing");
      screen.add(line);
    }
    return new ScreenBaseline(
        pb.getSessionId(), pb.getInstanceId(), pb.getLayoutEpoch(),
        pb.getScreenRevision(), pb.getDictionaryGeneration(), pb.getHistoryGeneration(),
        dictionary.entries(), pb.getGeometry().getRows(), columns, buffer(pb.getActiveBuffer()),
        extent(pb.getHistoryExtent()), screen,
        cursor(pb.getCursor()), modes(pb.getModes()), palette(pb.getPalette()));
  }

  public static TerminalCommit mapTerminalCommit(
      TerminalScreenV2Proto.TerminalCommit pb, int rows, int columns) {
    ScreenMutation screen = null;
    if (pb.hasScreen()) {
      ScreenScroll scroll = pb.getScreen().hasScroll()
          ? new ScreenScroll(pb.getScreen().getScroll().getTopRow(),
              pb.getScreen().getScroll().getBottomRowExclusive(),
              pb.getScreen().getScroll().getDeltaRows()) : null;
      List<ScreenRowWrite> writes = new ArrayList<>(pb.getScreen().getWritesCount());
      boolean[] seenRows = new boolean[Math.max(0, rows)];
      for (TerminalScreenV2Proto.ScreenRowWrite write : pb.getScreen().getWritesList()) {
        if (write.getRow() < 0 || write.getRow() >= rows || seenRows[write.getRow()]) {
          throw new IllegalArgumentException("invalid or duplicate screen row write");
        }
        if (write.getLine().getPhysicalColumns() != columns) {
          throw new IllegalArgumentException("screen line physical columns mismatch");
        }
        seenRows[write.getRow()] = true;
        LineData mapped = lineData(write.getLine());
        if (mapped.historySeq != 0) {
          throw new IllegalArgumentException("screen line has history sequence");
        }
        writes.add(new ScreenRowWrite(write.getRow(), mapped));
      }
      screen = new ScreenMutation(scroll, writes);
    }
    HistoryMutation history = null;
    if (pb.hasHistory()) {
      HistoryExtent finalExtent = extent(pb.getHistory().getFinalExtent());
      List<HistoryPush> pushes = new ArrayList<>();
      long previous = 0;
      for (TerminalScreenV2Proto.HistoryPush push : pb.getHistory().getPushesList()) {
        if (push.getHistorySeq() <= previous || !finalExtent.contains(push.getHistorySeq())
            || push.getLineId() == 0 || push.getLineVersion() == 0) {
          throw new IllegalArgumentException("invalid history push");
        }
        previous = push.getHistorySeq();
        pushes.add(new HistoryPush(
            push.getHistorySeq(), push.getLineId(), push.getLineVersion()));
      }
      history = new HistoryMutation(finalExtent, pushes);
    }
    return new TerminalCommit(
        pb.getInstanceId(), pb.getLayoutEpoch(), pb.getBaseRevision(), pb.getRevision(),
        pb.getDictionaryGeneration(), pb.getHistoryGeneration(),
        dictionary(pb.getDictionaryAdditions()).entries(),
        pb.hasActiveBuffer() ? buffer(pb.getActiveBuffer()) : null, screen, history,
        pb.hasCursor() ? cursor(pb.getCursor()) : null,
        pb.hasModes() ? modes(pb.getModes()) : null,
        pb.hasPalette() ? palette(pb.getPalette()) : null);
  }

  /** 将 HTTP Range 中的单行映射为 TerminalLine。 */
  public static TerminalLine mapHistoryLine(
      TerminalScreenV2Proto.LineData pb,
      TerminalScreenV2Proto.Dictionary dictionaryPb) {
    return line(pb, pb.getPhysicalColumns(), dictionary(dictionaryPb));
  }

  private static Map<Long, TerminalLine> mapLines(
      List<TerminalScreenV2Proto.LineData> lines, int columns, Dictionary dictionary) {
    Map<Long, TerminalLine> result = new HashMap<>();
    for (TerminalScreenV2Proto.LineData line : lines) {
      if (line.getPhysicalColumns() != columns) {
        throw new IllegalArgumentException("screen line physical columns mismatch");
      }
      TerminalLine mapped = line(line, columns, dictionary);
      if (result.put(mapped.id, mapped) != null) {
        throw new IllegalArgumentException("duplicate line id " + mapped.id);
      }
    }
    return result;
  }

  private static List<TerminalLine> mapLineList(
      List<TerminalScreenV2Proto.LineData> lines, int columns, Dictionary dictionary) {
    List<TerminalLine> result = new ArrayList<>(lines.size());
    for (TerminalScreenV2Proto.LineData line : lines) {
      result.add(line(line, columns, dictionary));
    }
    return result;
  }

  private static TerminalLine line(
      TerminalScreenV2Proto.LineData pb, int requestedColumns, Dictionary dictionary) {
    if (requestedColumns < 1 || requestedColumns > 500) {
      throw new IllegalArgumentException("invalid line geometry");
    }
    int columns = requestedColumns;
    TerminalCell[] cells = new TerminalCell[columns];
    java.util.Arrays.fill(cells, TerminalCell.EMPTY);
    byte[] textBytes = pb.getUtf8Text().toByteArray();
    byte[] meta = pb.getGlyphMeta().toByteArray();
    int textOffset = 0, metaOffset = 0, col = 0;
    while (metaOffset < meta.length) {
      long value = 0; int shift = 0;
      while (true) {
        if (metaOffset >= meta.length || shift >= 64) throw new IllegalArgumentException("invalid glyph metadata");
        int b = meta[metaOffset++] & 0xff;
        value |= (long) (b & 0x7f) << shift;
        if ((b & 0x80) == 0) break;
        shift += 7;
      }
      int length = (int) (value >>> 1);
      int width = (value & 1L) == 0 ? 1 : 2;
      if (length <= 0 || textOffset + length > textBytes.length || col + width > columns) {
        throw new IllegalArgumentException("invalid glyph metadata");
      }
      String text = new String(textBytes, textOffset, length, java.nio.charset.StandardCharsets.UTF_8);
      textOffset += length;
      int styleId = 0, linkId = 0;
      for (TerminalScreenV2Proto.StyleSpan span : pb.getStyleSpansList()) {
        if (col >= span.getStartCol() && col < span.getEndCol()) { styleId = span.getStyleId(); linkId = span.getLinkId(); break; }
      }
      TerminalStyle style = dictionary.style(styleId);
      Hyperlink link = dictionary.link(linkId);
      cells[col] = width == 1 && " ".equals(text) && style == null && link == null
          ? TerminalCell.EMPTY : new TerminalCell(text, (byte) width, style, link);
      if (width == 2) cells[col + 1] = TerminalCell.SPACER;
      col += width;
    }
    if (textOffset != textBytes.length) throw new IllegalArgumentException("glyph metadata/text mismatch");
    return new TerminalLine(
        pb.getLineId(), pb.getLineVersion(), pb.getHistorySeq(), pb.getWrapped(), cells);
  }

  private static LineData lineData(TerminalScreenV2Proto.LineData pb) {
    List<LineData.Span> spans = new ArrayList<>(pb.getStyleSpansCount());
    for (TerminalScreenV2Proto.StyleSpan span : pb.getStyleSpansList()) {
      spans.add(new LineData.Span(span.getStartCol(), span.getEndCol(),
          span.getStyleId(), span.getLinkId()));
    }
    return new LineData(pb.getLineId(), pb.getLineVersion(), pb.getWrapped(),
        pb.getHistorySeq(), pb.getUtf8Text().toByteArray(), pb.getGlyphMeta().toByteArray(), spans);
  }

  private static Dictionary dictionary(TerminalScreenV2Proto.Dictionary pb) {
    Map<Integer, TerminalStyle> styles = new HashMap<>();
    if (pb.getStylesCount() > 4096 || pb.getLinksCount() > 4096) {
      throw new IllegalArgumentException("dictionary exceeds limit");
    }
    for (TerminalScreenV2Proto.TerminalStyle style : pb.getStylesList()) {
      if (style.getId() == 0) continue;
      TerminalStyle previous = styles.put(style.getId(), new TerminalStyle(
          style.getId(), color(style.getFg()), color(style.getBg()), color(style.getUnderlineColor()),
          attrs(style.getAttrs())));
      if (previous != null) throw new IllegalArgumentException("duplicate style id");
    }
    Map<Integer, Hyperlink> links = new HashMap<>();
    for (TerminalScreenV2Proto.Hyperlink link : pb.getLinksList()) {
      if (link.getId() == 0) continue;
      Hyperlink previous = links.put(link.getId(), new Hyperlink(link.getId(), link.getUri()));
      if (previous != null) throw new IllegalArgumentException("duplicate link id");
    }
    return new Dictionary(styles, links);
  }

  private static HistoryExtent extent(TerminalScreenV2Proto.HistoryExtent pb) {
    if (pb.getFirstSeq() == 0 && pb.getLastSeq() == 0) return HistoryExtent.INITIAL_EMPTY;
    return new HistoryExtent(pb.getFirstSeq(), pb.getLastSeq());
  }

  private static TerminalBufferKind buffer(TerminalScreenV2Proto.BufferKind kind) {
    return kind == TerminalScreenV2Proto.BufferKind.BUFFER_KIND_ALTERNATE
        ? TerminalBufferKind.ALTERNATE : TerminalBufferKind.MAIN;
  }

  private static TerminalCursor cursor(TerminalScreenV2Proto.Cursor pb) {
    TerminalCursor.Shape shape = TerminalCursor.Shape.BLOCK;
    if (pb.getShape() == TerminalScreenV2Proto.CursorShape.CURSOR_SHAPE_BAR) {
      shape = TerminalCursor.Shape.BAR;
    } else if (pb.getShape() == TerminalScreenV2Proto.CursorShape.CURSOR_SHAPE_UNDERLINE) {
      shape = TerminalCursor.Shape.UNDERLINE;
    }
    return new TerminalCursor(pb.getRow(), pb.getCol(), pb.getVisible(), shape, pb.getBlink());
  }

  private static TerminalModes modes(TerminalScreenV2Proto.Modes pb) {
    TerminalModes.MouseTracking tracking;
    switch (pb.getMouseTracking()) {
      case MOUSE_TRACKING_X10: tracking = TerminalModes.MouseTracking.X10; break;
      case MOUSE_TRACKING_VT200: tracking = TerminalModes.MouseTracking.VT200; break;
      case MOUSE_TRACKING_VT200_HIGHLIGHT: tracking = TerminalModes.MouseTracking.VT200_HIGHLIGHT; break;
      case MOUSE_TRACKING_BUTTON_EVENT: tracking = TerminalModes.MouseTracking.BUTTON_EVENT; break;
      case MOUSE_TRACKING_ANY_EVENT: tracking = TerminalModes.MouseTracking.ANY_EVENT; break;
      case MOUSE_TRACKING_SGR_PIXELS: tracking = TerminalModes.MouseTracking.SGR_PIXELS; break;
      default: tracking = TerminalModes.MouseTracking.NONE;
    }
    TerminalModes.MouseEncoding encoding;
    switch (pb.getMouseEncoding()) {
      case MOUSE_ENCODING_UTF8: encoding = TerminalModes.MouseEncoding.UTF8; break;
      case MOUSE_ENCODING_SGR: encoding = TerminalModes.MouseEncoding.SGR; break;
      case MOUSE_ENCODING_URXVT: encoding = TerminalModes.MouseEncoding.URXVT; break;
      default: encoding = TerminalModes.MouseEncoding.X10;
    }
    return new TerminalModes(
        pb.getApplicationCursor(), pb.getApplicationKeypad(), pb.getBracketedPaste(),
        tracking, encoding, pb.getFocusReporting());
  }

  private static TerminalPalette palette(TerminalScreenV2Proto.TerminalPalette pb) {
    Map<Integer, Integer> indexed = new HashMap<>();
    for (TerminalScreenV2Proto.IndexedPaletteColor entry : pb.getIndexedColorsList()) {
      if (entry.getIndex() >= 0 && entry.getIndex() < 256) {
        indexed.put(entry.getIndex(), entry.getRgb());
      }
    }
    return new TerminalPalette(
        color(pb.getDefaultFg()), color(pb.getDefaultBg()), color(pb.getCursorColor()),
        pb.getReverseVideo(), indexed, pb.getGeneration());
  }

  private static TerminalColor color(TerminalScreenV2Proto.Color pb) {
    switch (pb.getKind()) {
      case COLOR_KIND_DEFAULT_BG: return TerminalColor.DEFAULT_BG;
      case COLOR_KIND_CURSOR: return TerminalColor.CURSOR;
      case COLOR_KIND_INDEXED: return TerminalColor.indexed(pb.getIndex());
      case COLOR_KIND_RGB: return TerminalColor.rgb(pb.getRgb());
      default: return TerminalColor.DEFAULT_FG;
    }
  }

  private static int attrs(TerminalScreenV2Proto.CellAttrs pb) {
    int bits = 0;
    if (pb.getBold()) bits |= 1;
    if (pb.getDim()) bits |= 1 << 1;
    if (pb.getItalic()) bits |= 1 << 2;
    if (pb.getUnderline()) bits |= 1 << 3;
    if (pb.getDoubleUnderline()) bits |= 1 << 4;
    if (pb.getCurlyUnderline()) bits |= 1 << 5;
    if (pb.getDottedUnderline()) bits |= 1 << 6;
    if (pb.getDashedUnderline()) bits |= 1 << 7;
    if (pb.getBlinkSlow()) bits |= 1 << 8;
    if (pb.getBlinkFast()) bits |= 1 << 9;
    if (pb.getReverse()) bits |= 1 << 10;
    if (pb.getHidden()) bits |= 1 << 11;
    if (pb.getStrike()) bits |= 1 << 12;
    return bits;
  }

  private static final class Dictionary {
    private final Map<Integer, TerminalStyle> styles;
    private final Map<Integer, Hyperlink> links;

    Dictionary(Map<Integer, TerminalStyle> styles, Map<Integer, Hyperlink> links) {
      this.styles = styles;
      this.links = links;
    }

    TerminalStyle style(int id) {
      if (id == 0) return null;
      TerminalStyle value = styles.get(id);
      if (value == null) throw new IllegalArgumentException("unknown style id " + id);
      return value;
    }

    Hyperlink link(int id) {
      if (id == 0) return null;
      Hyperlink value = links.get(id);
      if (value == null) throw new IllegalArgumentException("unknown link id " + id);
      return value;
    }

    DictionaryEntries entries() {
      List<TerminalStyle> styleList = new ArrayList<>(styles.values());
      styleList.sort(java.util.Comparator.comparingInt(value -> value.id));
      List<Hyperlink> linkList = new ArrayList<>(links.values());
      linkList.sort(java.util.Comparator.comparingInt(value -> value.id));
      return new DictionaryEntries(styleList, linkList);
    }
  }
}
