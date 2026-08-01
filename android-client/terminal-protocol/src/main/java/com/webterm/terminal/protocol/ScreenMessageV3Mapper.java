package com.webterm.terminal.protocol;

import com.webterm.terminal.model.*;
import com.webterm.terminal.protocol.generated.TerminalScreenV3Proto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** screen.v3 wire dictionary is resolved at this boundary. */
public final class ScreenMessageV3Mapper {
  private static final LineBodyDecoder LINE_DECODER = new LineBodyDecoder();

  private ScreenMessageV3Mapper() {}

  public static ScreenBaseline mapBaseline(TerminalScreenV3Proto.Baseline pb) {
    WireDictionary dictionary = wireDictionary(pb.getDictionary());
    int columns = pb.getGeometry().getCols();
    List<LineBodyRecord> bodies = new ArrayList<>(pb.getScreenBodiesCount());
    for (TerminalScreenV3Proto.LineBodyRecord record : pb.getScreenBodiesList()) {
      DecodedLine decoded = decodeRecord(record, dictionary);
      bodies.add(new LineBodyRecord(decoded.key(), decoded.body()));
    }
    List<LineKey> screenRows = new ArrayList<>(pb.getScreenRowsCount());
    for (TerminalScreenV3Proto.LineKey key : pb.getScreenRowsList()) {
      screenRows.add(mapLineKey(key));
    }
    HistoryExtent historyExtent = extent(pb.getHistoryExtent());
    List<HistoryPush> bindings = mapHistoryBindings(
        pb.getHistoryBindingsList(), historyExtent);
    return new ScreenBaseline(
        pb.getSessionId(), pb.getInstanceId(), pb.getLayoutEpoch(),
        pb.getScreenRevision(), pb.getHistoryGeneration(),
        pb.getGeometry().getRows(), columns, buffer(pb.getActiveBuffer()),
        historyExtent, bindings, screenRows, bodies,
        cursor(pb.getCursor()), modes(pb.getModes()), palette(pb.getPalette()));
  }

  public static TerminalCommit mapTerminalCommit(
      TerminalScreenV3Proto.TerminalCommit pb, int rows, int columns,
      WireDictionary dictionary) {
    List<LineBodyRecord> upserts = new ArrayList<>(pb.getBodyUpsertsCount());
    for (TerminalScreenV3Proto.LineBodyRecord record : pb.getBodyUpsertsList()) {
      DecodedLine decoded = decodeRecord(record, dictionary);
      if (decoded.body().physicalColumns != columns) {
        throw new IllegalArgumentException("body upsert column mismatch");
      }
      upserts.add(new LineBodyRecord(decoded.key(), decoded.body()));
    }
    ScreenMutation screen = null;
    if (pb.hasScreen()) {
      ScreenScroll scroll = pb.getScreen().hasScroll()
          ? new ScreenScroll(pb.getScreen().getScroll().getTopRow(),
              pb.getScreen().getScroll().getBottomRowExclusive(),
              pb.getScreen().getScroll().getDeltaRows()) : null;
      List<ScreenRowWrite> writes = new ArrayList<>(pb.getScreen().getWritesCount());
      boolean[] seenRows = new boolean[Math.max(0, rows)];
      for (TerminalScreenV3Proto.ScreenRowWrite write : pb.getScreen().getWritesList()) {
        if (write.getRow() < 0 || write.getRow() >= rows || seenRows[write.getRow()]) {
          throw new IllegalArgumentException("invalid or duplicate screen row write");
        }
        seenRows[write.getRow()] = true;
        writes.add(new ScreenRowWrite(write.getRow(), mapLineKey(write.getKey())));
      }
      screen = new ScreenMutation(scroll, writes);
    }
    HistoryMutation history = null;
    if (pb.hasHistory()) {
      HistoryExtent finalExtent = extent(pb.getHistory().getFinalExtent());
      List<HistoryPush> pushes = new ArrayList<>();
      long previous = 0;
      for (TerminalScreenV3Proto.HistoryBinding push : pb.getHistory().getPushesList()) {
        LineKey key = mapLineKey(push.getKey());
        if (push.getHistorySeq() <= previous || !finalExtent.contains(push.getHistorySeq())) {
          throw new IllegalArgumentException("invalid history push");
        }
        previous = push.getHistorySeq();
        pushes.add(new HistoryPush(push.getHistorySeq(), key));
      }
      history = new HistoryMutation(finalExtent, pushes);
    }
    return new TerminalCommit(
        pb.getInstanceId(), pb.getLayoutEpoch(), pb.getBaseRevision(), pb.getRevision(),
        pb.getHistoryGeneration(),
        pb.hasActiveBuffer() ? buffer(pb.getActiveBuffer()) : null,
        upserts, screen, history,
        pb.hasCursor() ? cursor(pb.getCursor()) : null,
        pb.hasModes() ? modes(pb.getModes()) : null,
        pb.hasPalette() ? palette(pb.getPalette()) : null);
  }

  public static LineBodyRecord mapLineBodyRecord(
      TerminalScreenV3Proto.LineBodyRecord pb, WireDictionary dictionary) {
    DecodedLine decoded = decodeRecord(pb, dictionary);
    return new LineBodyRecord(decoded.key(), decoded.body());
  }

  public static LineKey mapLineKey(TerminalScreenV3Proto.LineKey pb) {
    if (pb == null || pb.getLineId() <= 0 || pb.getBodyVersion() <= 0) {
      throw new IllegalArgumentException("invalid line key");
    }
    return new LineKey(pb.getLineId(), pb.getBodyVersion());
  }

  public static WireDictionary mapDictionary(TerminalScreenV3Proto.Dictionary pb) {
    return wireDictionary(pb);
  }

  private static List<HistoryPush> mapHistoryBindings(
      List<TerminalScreenV3Proto.HistoryBinding> wireBindings,
      HistoryExtent extent) {
    List<HistoryPush> bindings = new ArrayList<>(wireBindings.size());
    long previousSeq = 0;
    for (TerminalScreenV3Proto.HistoryBinding binding : wireBindings) {
      LineKey key = mapLineKey(binding.getKey());
      if (binding.getHistorySeq() <= previousSeq
          || !extent.contains(binding.getHistorySeq())) {
        throw new IllegalArgumentException("invalid baseline history binding");
      }
      bindings.add(new HistoryPush(binding.getHistorySeq(), key));
      previousSeq = binding.getHistorySeq();
    }
    if (extent.logicalSize() != bindings.size()) {
      throw new IllegalArgumentException("baseline history catalog is incomplete");
    }
    return bindings;
  }

  private static DecodedLine decodeRecord(
      TerminalScreenV3Proto.LineBodyRecord pb, WireDictionary dictionary) {
    LineKey key = mapLineKey(pb.getKey());
    return LINE_DECODER.decode(wireLineRecord(pb, key), dictionary);
  }

  private static WireLineData wireLineRecord(
      TerminalScreenV3Proto.LineBodyRecord pb, LineKey key) {
    List<WireLineData.Span> spans = new ArrayList<>(pb.getStyleSpansCount());
    for (TerminalScreenV3Proto.StyleSpan span : pb.getStyleSpansList()) {
      spans.add(new WireLineData.Span(
          span.getStartCol(), span.getEndCol(), span.getStyleId(), span.getLinkId()));
    }
    return new WireLineData(
        key.lineId(), key.lineVersion(), 0,
        pb.getPhysicalColumns(), pb.getWrapped(),
        pb.getUtf8Text(), pb.getGlyphMeta(), spans);
  }

  private static WireDictionary wireDictionary(TerminalScreenV3Proto.Dictionary pb) {
    Map<Integer, StyleValue> styles = new HashMap<>();
    if (pb.getStylesCount() > 4096 || pb.getLinksCount() > 4096) {
      throw new IllegalArgumentException("dictionary exceeds limit");
    }
    for (TerminalScreenV3Proto.TerminalStyle style : pb.getStylesList()) {
      if (style.getId() == 0) continue;
      StyleValue previous = styles.put(style.getId(), new StyleValue(
          color(style.getFg()), color(style.getBg()), color(style.getUnderlineColor()),
          attrs(style.getAttrs())));
      if (previous != null) throw new IllegalArgumentException("duplicate style id");
    }
    Map<Integer, LinkValue> links = new HashMap<>();
    for (TerminalScreenV3Proto.Hyperlink link : pb.getLinksList()) {
      if (link.getId() == 0) continue;
      LinkValue previous = links.put(link.getId(), new LinkValue(link.getUri()));
      if (previous != null) throw new IllegalArgumentException("duplicate link id");
    }
    return new WireDictionary(styles, links);
  }

  private static HistoryExtent extent(TerminalScreenV3Proto.HistoryExtent pb) {
    if (pb.getFirstSeq() == 0 && pb.getLastSeq() == 0) return HistoryExtent.INITIAL_EMPTY;
    return new HistoryExtent(pb.getFirstSeq(), pb.getLastSeq());
  }

  private static TerminalBufferKind buffer(TerminalScreenV3Proto.BufferKind kind) {
    return kind == TerminalScreenV3Proto.BufferKind.BUFFER_KIND_ALTERNATE
        ? TerminalBufferKind.ALTERNATE : TerminalBufferKind.MAIN;
  }

  private static TerminalCursor cursor(TerminalScreenV3Proto.Cursor pb) {
    TerminalCursor.Shape shape = TerminalCursor.Shape.BLOCK;
    if (pb.getShape() == TerminalScreenV3Proto.CursorShape.CURSOR_SHAPE_BAR) {
      shape = TerminalCursor.Shape.BAR;
    } else if (pb.getShape() == TerminalScreenV3Proto.CursorShape.CURSOR_SHAPE_UNDERLINE) {
      shape = TerminalCursor.Shape.UNDERLINE;
    }
    return new TerminalCursor(pb.getRow(), pb.getCol(), pb.getVisible(), shape, pb.getBlink());
  }

  private static TerminalModes modes(TerminalScreenV3Proto.Modes pb) {
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

  private static TerminalPalette palette(TerminalScreenV3Proto.TerminalPalette pb) {
    Map<Integer, Integer> indexed = new HashMap<>();
    for (TerminalScreenV3Proto.IndexedPaletteColor entry : pb.getIndexedColorsList()) {
      if (entry.getIndex() >= 0 && entry.getIndex() < 256) {
        indexed.put(entry.getIndex(), entry.getRgb());
      }
    }
    return new TerminalPalette(
        color(pb.getDefaultFg()), color(pb.getDefaultBg()), color(pb.getCursorColor()),
        pb.getReverseVideo(), indexed, pb.getGeneration());
  }

  private static TerminalColor color(TerminalScreenV3Proto.Color pb) {
    switch (pb.getKind()) {
      case COLOR_KIND_DEFAULT_BG: return TerminalColor.DEFAULT_BG;
      case COLOR_KIND_CURSOR: return TerminalColor.CURSOR;
      case COLOR_KIND_INDEXED: return TerminalColor.indexed(pb.getIndex());
      case COLOR_KIND_RGB: return TerminalColor.rgb(pb.getRgb());
      default: return TerminalColor.DEFAULT_FG;
    }
  }

  private static int attrs(TerminalScreenV3Proto.CellAttrs pb) {
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
