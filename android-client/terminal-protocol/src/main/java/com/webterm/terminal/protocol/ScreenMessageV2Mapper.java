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
    List<TerminalLine> history = mapLineList(
        pb.getHistoryTail().getLinesList(), columns, dictionary);
    return new ScreenBaseline(
        pb.getSessionId(), pb.getInstanceId(), pb.getLayoutEpoch(),
        pb.getScreenRevision(), pb.getStreamGeneration(),
        pb.getGeometry().getRows(), columns, buffer(pb.getActiveBuffer()),
        extent(pb.getHistoryExtent()), history, screen,
        cursor(pb.getCursor()), modes(pb.getModes()), palette(pb.getPalette()),
        pb.hasTitle() ? pb.getTitle() : "",
        pb.hasWorkingDirectory() ? pb.getWorkingDirectory() : "");
  }

  public static ScreenPatchV2 mapPatch(
      TerminalScreenV2Proto.ScreenPatch pb, int columns) {
    Dictionary dictionary = dictionary(pb.getDictionary());
    long[] layout = null;
    if (pb.hasScreenLayout()) {
      layout = new long[pb.getScreenLayout().getLineIdsCount()];
      for (int i = 0; i < layout.length; i++) {
        layout[i] = pb.getScreenLayout().getLineIds(i);
      }
    }
    return new ScreenPatchV2(
        pb.getInstanceId(), pb.getLayoutEpoch(), pb.getStreamGeneration(),
        pb.getBaseScreenRevision(), pb.getScreenRevision(), layout,
        mapLineList(pb.getScreenLineUpdatesList(), columns, dictionary),
        pb.hasCursor() ? cursor(pb.getCursor()) : null,
        pb.hasModes() ? modes(pb.getModes()) : null,
        pb.hasPalette() ? palette(pb.getPalette()) : null,
        pb.hasActiveBuffer() ? buffer(pb.getActiveBuffer()) : null,
        pb.hasTitle() ? pb.getTitle() : null,
        pb.hasWorkingDirectory() ? pb.getWorkingDirectory() : null);
  }

  public static HistoryDelta mapHistoryDelta(
      TerminalScreenV2Proto.HistoryDelta pb, int columns) {
    Dictionary dictionary = dictionary(pb.getDictionary());
    return new HistoryDelta(
        pb.getInstanceId(), pb.getLayoutEpoch(), pb.getStreamGeneration(),
        extent(pb.getAvailableExtent()),
        mapLineList(pb.getLinesList(), columns, dictionary));
  }

  public static HistoryRangeResult mapHistoryRange(
      TerminalScreenV2Proto.HistoryRangeResponse pb, int columns) {
    Dictionary dictionary = dictionary(pb.getDictionary());
    HistoryRangeResult.Status status;
    switch (pb.getStatus()) {
      case HISTORY_RANGE_STATUS_STALE_PROJECTION:
        status = HistoryRangeResult.Status.STALE_PROJECTION;
        break;
      case HISTORY_RANGE_STATUS_TRIMMED:
        status = HistoryRangeResult.Status.TRIMMED;
        break;
      case HISTORY_RANGE_STATUS_RETRYABLE:
        status = HistoryRangeResult.Status.RETRYABLE;
        break;
      case HISTORY_RANGE_STATUS_OK:
        status = HistoryRangeResult.Status.OK;
        break;
      default:
        throw new IllegalArgumentException("unspecified history range status");
    }
    return new HistoryRangeResult(
        pb.getRequestId(), pb.getInstanceId(), pb.getLayoutEpoch(), status,
        extent(pb.getAvailableExtent()),
        mapLineList(pb.getLinesList(), columns, dictionary),
        pb.getRetryAfterMs());
  }

  private static Map<Long, TerminalLine> mapLines(
      List<TerminalScreenV2Proto.LineData> lines, int columns, Dictionary dictionary) {
    Map<Long, TerminalLine> result = new HashMap<>();
    for (TerminalScreenV2Proto.LineData line : lines) {
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
    for (TerminalScreenV2Proto.CellRun run : pb.getRunsList()) {
      int col = run.getCol();
      if (col < 0 || col >= columns) throw new IllegalArgumentException("invalid run column");
      for (TerminalScreenV2Proto.Cell wire : run.getCellsList()) {
        int width = wire.getWidth();
        if ((width != 1 && width != 2) || col + width > cells.length) {
          throw new IllegalArgumentException("invalid cell width");
        }
        TerminalStyle style = dictionary.style(wire.getStyleId());
        Hyperlink link = dictionary.link(wire.getLinkId());
        String text = wire.getText().isEmpty() ? " " : wire.getText();
        if (width == 1 && " ".equals(text) && style == null && link == null) {
          cells[col] = TerminalCell.EMPTY;
        } else {
          cells[col] = new TerminalCell(text, (byte) width, style, link);
        }
        if (width == 2) cells[col + 1] = TerminalCell.SPACER;
        col += width;
      }
    }
    return new TerminalLine(
        pb.getLineId(), pb.getLineVersion(), pb.getHistorySeq(), pb.getWrapped(), cells);
  }

  private static Dictionary dictionary(TerminalScreenV2Proto.Dictionary pb) {
    Map<Integer, TerminalStyle> styles = new HashMap<>();
    if (pb.getStylesCount() > 4096 || pb.getLinksCount() > 4096) {
      throw new IllegalArgumentException("dictionary exceeds limit");
    }
    for (TerminalScreenV2Proto.TerminalStyle style : pb.getStylesList()) {
      if (style.getId() == 0) continue;
      TerminalStyle previous = styles.put(style.getId(), new TerminalStyle(
          0, color(style.getFg()), color(style.getBg()), color(style.getUnderlineColor()),
          attrs(style.getAttrs())));
      if (previous != null) throw new IllegalArgumentException("duplicate style id");
    }
    Map<Integer, Hyperlink> links = new HashMap<>();
    for (TerminalScreenV2Proto.Hyperlink link : pb.getLinksList()) {
      if (link.getId() == 0) continue;
      Hyperlink previous = links.put(link.getId(), new Hyperlink(0, link.getUri()));
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
  }
}
