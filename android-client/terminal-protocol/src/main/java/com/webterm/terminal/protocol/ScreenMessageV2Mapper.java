package com.webterm.terminal.protocol;

import com.webterm.terminal.model.*;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** screen.v2 wire dictionary is resolved at this boundary. */
public final class ScreenMessageV2Mapper {
  private static final LineBodyDecoder LINE_DECODER = new LineBodyDecoder();

  private ScreenMessageV2Mapper() {}

  public static ScreenBaseline mapBaseline(TerminalScreenV2Proto.Baseline pb) {
    WireDictionary dictionary = wireDictionary(pb.getDictionary());
    int columns = pb.getGeometry().getCols();
    Map<Long, ScreenLineContent> screenLines = new HashMap<>();
    for (TerminalScreenV2Proto.LineData line : pb.getScreenLinesList()) {
      if (line.getPhysicalColumns() != columns || line.getHistorySeq() != 0) {
        throw new IllegalArgumentException("screen line geometry or position mismatch");
      }
      DecodedLine decoded = LINE_DECODER.decode(wireLine(line), dictionary);
      ScreenLineContent content = new ScreenLineContent(decoded.key(), decoded.body());
      if (screenLines.put(decoded.key().lineId(), content) != null) {
        throw new IllegalArgumentException("duplicate screen LineID");
      }
    }
    List<ScreenLineContent> screen = new ArrayList<>();
    for (long id : pb.getScreenLayout().getLineIdsList()) {
      ScreenLineContent line = screenLines.get(id);
      if (line == null) throw new IllegalArgumentException("baseline layout line missing");
      screen.add(line);
    }
    HistoryExtent historyExtent = extent(pb.getHistoryExtent());
    List<HistoryPush> bindings = mapHistoryBindings(
        pb.getHistoryBindingsList(), historyExtent);
    return new ScreenBaseline(
        pb.getSessionId(), pb.getInstanceId(), pb.getLayoutEpoch(),
        pb.getScreenRevision(), pb.getDictionaryGeneration(), pb.getHistoryGeneration(),
        pb.getGeometry().getRows(), columns, buffer(pb.getActiveBuffer()),
        historyExtent, bindings, screen,
        cursor(pb.getCursor()), modes(pb.getModes()), palette(pb.getPalette()));
  }

  public static TerminalCommit mapTerminalCommit(
      TerminalScreenV2Proto.TerminalCommit pb, int rows, int columns,
      WireDictionary canonicalDictionary) {
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
        DecodedLine mapped = LINE_DECODER.decode(
            wireLine(write.getLine()), canonicalDictionary);
        if (mapped.historySeq() != 0) {
          throw new IllegalArgumentException("screen line has history sequence");
        }
        writes.add(new ScreenRowWrite(
            write.getRow(), new ScreenLineContent(mapped.key(), mapped.body())));
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
            push.getHistorySeq(), new LineKey(push.getLineId(), push.getLineVersion())));
      }
      history = new HistoryMutation(finalExtent, pushes);
    }
    return new TerminalCommit(
        pb.getInstanceId(), pb.getLayoutEpoch(), pb.getBaseRevision(), pb.getRevision(),
        pb.getDictionaryGeneration(), pb.getHistoryGeneration(),
        pb.hasActiveBuffer() ? buffer(pb.getActiveBuffer()) : null, screen, history,
        pb.hasCursor() ? cursor(pb.getCursor()) : null,
        pb.hasModes() ? modes(pb.getModes()) : null,
        pb.hasPalette() ? palette(pb.getPalette()) : null);
  }

  /** 将 HTTP Range 中的单行解码成与位置分离的纯正文。 */
  public static HistoryBodyEntry mapHistoryLine(
      TerminalScreenV2Proto.LineData pb,
      WireDictionary dictionary) {
    DecodedLine decoded = LINE_DECODER.decode(
        wireLine(pb), dictionary);
    return new HistoryBodyEntry(decoded.historySeq(), decoded.key(), decoded.body());
  }

  public static WireDictionary mapDictionary(TerminalScreenV2Proto.Dictionary pb) {
    return wireDictionary(pb);
  }

  private static List<HistoryPush> mapHistoryBindings(
      List<TerminalScreenV2Proto.HistoryPush> wireBindings,
      HistoryExtent extent) {
    List<HistoryPush> bindings = new ArrayList<>(wireBindings.size());
    long previousSeq = 0;
    for (TerminalScreenV2Proto.HistoryPush binding : wireBindings) {
      if (binding.getHistorySeq() <= previousSeq
          || !extent.contains(binding.getHistorySeq())
          || binding.getLineId() <= 0 || binding.getLineVersion() <= 0) {
        throw new IllegalArgumentException("invalid baseline history binding");
      }
      bindings.add(new HistoryPush(
          binding.getHistorySeq(),
          new LineKey(binding.getLineId(), binding.getLineVersion())));
      previousSeq = binding.getHistorySeq();
    }
    if (extent.logicalSize() != bindings.size()) {
      throw new IllegalArgumentException("baseline history catalog is incomplete");
    }
    return bindings;
  }

  private static WireLineData wireLine(TerminalScreenV2Proto.LineData pb) {
    List<WireLineData.Span> spans = new ArrayList<>(pb.getStyleSpansCount());
    for (TerminalScreenV2Proto.StyleSpan span : pb.getStyleSpansList()) {
      spans.add(new WireLineData.Span(
          span.getStartCol(), span.getEndCol(), span.getStyleId(), span.getLinkId()));
    }
    return new WireLineData(
        pb.getLineId(), pb.getLineVersion(), pb.getHistorySeq(),
        pb.getPhysicalColumns(), pb.getWrapped(),
        pb.getUtf8Text().toByteArray(), pb.getGlyphMeta().toByteArray(), spans);
  }

  private static WireDictionary wireDictionary(TerminalScreenV2Proto.Dictionary pb) {
    Map<Integer, StyleValue> styles = new HashMap<>();
    if (pb.getStylesCount() > 4096 || pb.getLinksCount() > 4096) {
      throw new IllegalArgumentException("dictionary exceeds limit");
    }
    for (TerminalScreenV2Proto.TerminalStyle style : pb.getStylesList()) {
      if (style.getId() == 0) continue;
      StyleValue previous = styles.put(style.getId(), new StyleValue(
          color(style.getFg()), color(style.getBg()), color(style.getUnderlineColor()),
          attrs(style.getAttrs())));
      if (previous != null) throw new IllegalArgumentException("duplicate style id");
    }
    Map<Integer, LinkValue> links = new HashMap<>();
    for (TerminalScreenV2Proto.Hyperlink link : pb.getLinksList()) {
      if (link.getId() == 0) continue;
      LinkValue previous = links.put(link.getId(), new LinkValue(link.getUri()));
      if (previous != null) throw new IllegalArgumentException("duplicate link id");
    }
    return new WireDictionary(styles, links);
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

}
