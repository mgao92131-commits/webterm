package com.webterm.terminal.protocol;

import com.webterm.terminal.model.*;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 webterm.screen.v1 Protobuf 消息映射为纯 Java 领域模型。
 */
public final class ScreenMessageMapper {

  private ScreenMessageMapper() {}

  public static ScreenSnapshot mapSnapshot(TerminalScreenProto.ScreenSnapshot pb) {
    Map<Long, TerminalLine> lines = mapLines(pb.getScreenLinesList(), pb.getGeometry().getCols());
    List<TerminalLine> screen = resolveLayout(pb.getLayout().getLineIdsList(), lines);
    List<TerminalLine> historyLines = new ArrayList<>();
    Map<Long, TerminalLine> history = new HashMap<>();
    for (int i = 0; i < pb.getHistoryTailLinesCount(); i++) {
      TerminalScreenProto.LineData data = pb.getHistoryTailLines(i);
      TerminalLine line = mapLine(data, pb.getGeometry().getCols());
      long seq = line.historySeq;
      if (seq == 0 || history.put(seq, line) != null) {
        throw new IllegalArgumentException("invalid snapshot history sequence");
      }
    }
    for (long seq : pb.getHistoryTailIdsList()) {
      TerminalLine line = history.get(seq);
      if (line == null) throw new IllegalArgumentException("snapshot history line data missing: " + seq);
      historyLines.add(line);
    }
    return new ScreenSnapshot(
        pb.getSessionId(),
        pb.getInstanceId(),
        pb.getLayoutEpoch(),
        pb.getScreenRevision(),
        pb.getGeometry().getRows(),
        pb.getGeometry().getCols(),
        mapBufferKind(pb.getActiveBuffer()),
        mapCursor(pb.getCursor()),
        mapModes(pb.getModes()),
        mapPalette(pb.getPalette()),
        new HistoryWindow(pb.getFirstAvailableHistoryLineId(),
            historyLines.isEmpty() ? 0 : historyLines.get(0).historyOrder(),
            historyLines.isEmpty() ? 0 : historyLines.get(historyLines.size() - 1).historyOrder(),
            pb.getHasMoreHistoryBefore(), historyLines),
        screen,
        mapStyles(pb.getStylesList()),
        mapLinks(pb.getLinksList()),
        pb.hasTitle() ? pb.getTitle() : "",
        pb.hasWorkingDirectory() ? pb.getWorkingDirectory() : ""
    );
  }

  public static ScreenPatch mapPatch(TerminalScreenProto.ScreenPatch pb) {
    return mapPatch(pb, -1);
  }

  /**
   * Maps a patch directly to the current grid width so model application can reuse each line
   * instead of allocating a second padded copy.
   */
  public static ScreenPatch mapPatch(TerminalScreenProto.ScreenPatch pb, int columns) {
    List<TerminalLine> updates = new ArrayList<>(mapLines(pb.getLineUpdatesList(), columns).values());
    List<Long> historyAppend = new ArrayList<>();
    for (long id : pb.getHistoryAppendIdsList()) historyAppend.add(id);
    long[] layout = null;
    if (pb.hasLayout()) { layout = new long[pb.getLayout().getLineIdsCount()]; for (int i = 0; i < layout.length; i++) layout[i] = pb.getLayout().getLineIds(i); }
    // title/working_directory 是 proto3 optional，按 presence 区分三态：
    // absent 映射为 null（模型保持原值）；present 即使是空串也原样传递（模型清空）。
    return new ScreenPatch(
        pb.getInstanceId(),
        pb.getLayoutEpoch(),
        pb.getBaseRevision(),
        pb.getScreenRevision(),
        layout,
        updates,
        historyAppend,
        pb.hasCursor() ? mapCursor(pb.getCursor()) : null,
        pb.hasModes() ? mapModes(pb.getModes()) : null,
        pb.hasPalette() ? mapPalette(pb.getPalette()) : null,
        mapStyles(pb.getNewStylesList()),
        mapLinks(pb.getNewLinksList()),
        pb.hasTitle() ? pb.getTitle() : null,
        pb.hasWorkingDirectory() ? pb.getWorkingDirectory() : null,
        pb.hasHistoryTrimBeforeId() ? pb.getHistoryTrimBeforeId() : null
    );
  }

  public static HistoryPage mapHistoryPage(TerminalScreenProto.HistoryPage pb) {
    return mapHistoryPage(pb, -1);
  }

  /** Maps a history page to the current grid width for one-pass line normalization. */
  public static HistoryPage mapHistoryPage(TerminalScreenProto.HistoryPage pb, int columns) {
    List<TerminalLine> lines = new ArrayList<>();
    for (TerminalScreenProto.LineData line : pb.getLinesList()) {
      lines.add(mapLine(line, columns));
    }
    return new HistoryPage(
        pb.getRequestId(),
        pb.getLayoutEpoch(),
        pb.getAsOfRevision(),
        pb.getFirstAvailableLineId(),
        pb.getHasMoreBefore(),
        lines,
        mapStyles(pb.getStylesList()),
        mapLinks(pb.getLinksList())
    );
  }

  private static TerminalLine mapLine(TerminalScreenProto.LineData pb, int columns) {
    boolean compact = !pb.getText().isEmpty() || !pb.getCellMeta().isEmpty();
    return new TerminalLine(pb.getLineId(), pb.getLineVersion(), pb.getHistorySeq(), pb.getWrapped(), compact
        ? expandCompact(pb.getText(), pb.getCellMeta().toByteArray(), pb.getStyleSpansList(), columns)
        : expandRuns(pb.getRunsList(), columns));
  }

  private static Map<Long, TerminalLine> mapLines(List<TerminalScreenProto.LineData> data, int columns) {
    Map<Long, TerminalLine> lines = new HashMap<>();
    for (TerminalScreenProto.LineData line : data) {
      if (lines.put(line.getLineId(), mapLine(line, columns)) != null) {
        throw new IllegalArgumentException("duplicate line data id: " + line.getLineId());
      }
    }
    return lines;
  }

  private static List<TerminalLine> resolveLayout(List<Long> layout, Map<Long, TerminalLine> lines) {
    List<TerminalLine> result = new ArrayList<>();
    for (long id : layout) {
      TerminalLine line = lines.get(id);
      if (line == null) throw new IllegalArgumentException("layout line data missing: " + id);
      result.add(line);
    }
    return result;
  }

  private static TerminalCell[] expandCompact(String text, byte[] cellMeta,
                                               List<TerminalScreenProto.StyleSpan> spans,
                                               int requestedColumns) {
    if (text.isEmpty() != (cellMeta.length == 0)) {
      throw new IllegalArgumentException("compact text and cell metadata must appear together");
    }
    int encodedColumns = 0;
    for (byte rawMeta : cellMeta) {
      int value = rawMeta & 0xff;
      int codePointCount = value & 0x7f;
      int width = (value & 0x80) != 0 ? 2 : 1;
      if (codePointCount == 0) {
        throw new IllegalArgumentException("compact cell metadata has zero code point count");
      }
      encodedColumns += width;
    }
    int columns = Math.max(Math.max(0, requestedColumns), encodedColumns);
    TerminalCell[] cells = new TerminalCell[columns];
    java.util.Arrays.fill(cells, TerminalCell.EMPTY);
    int spanIndex = 0;
    int textOffset = 0;
    int terminalCol = 0;
    for (byte rawMeta : cellMeta) {
      int value = rawMeta & 0xff;
      int width = (value & 0x80) != 0 ? 2 : 1;
      int codePointCount = value & 0x7f;
      if (codePointCount == 0 || terminalCol + width > cells.length) {
        throw new IllegalArgumentException("compact cell metadata exceeds terminal columns");
      }
      int textEnd = offsetByCodePoints(text, textOffset, codePointCount);
      while (spanIndex < spans.size() && spans.get(spanIndex).getEndCol() <= terminalCol) spanIndex++;
      int styleId = 0;
      int linkId = 0;
      if (spanIndex < spans.size()) {
        TerminalScreenProto.StyleSpan span = spans.get(spanIndex);
        if (span.getStartCol() <= terminalCol && terminalCol < span.getEndCol()) {
          styleId = span.getStyleId();
          linkId = span.getLinkId();
        }
      }
      String cellText = text.substring(textOffset, textEnd);
      // Go 端会裁剪尾部默认空格；行内默认空格继续复用 EMPTY，避免为大面积空白
      // 创建短命对象。宽度和字符簇边界完全由 Go 的 metadata 决定。
      if (width == 1 && " ".equals(cellText) && styleId == 0 && linkId == 0) {
        cells[terminalCol] = TerminalCell.EMPTY;
      } else {
        cells[terminalCol] = new TerminalCell(cellText, (byte) width, styleId, linkId);
      }
      if (width == 2) cells[terminalCol + 1] = TerminalCell.SPACER;
      textOffset = textEnd;
      terminalCol += width;
    }
    if (textOffset != text.length()) {
      throw new IllegalArgumentException("compact text has unconsumed code points");
    }
    return cells;
  }

  private static int offsetByCodePoints(String text, int offset, int count) {
    if (offset < 0 || offset > text.length() || count <= 0) {
      throw new IllegalArgumentException("invalid compact text offset");
    }
    int cursor = offset;
    for (int i = 0; i < count; i++) {
      if (cursor >= text.length()) {
        throw new IllegalArgumentException("compact metadata exceeds text code points");
      }
      char ch = text.charAt(cursor);
      if (Character.isHighSurrogate(ch)) {
        if (cursor + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(cursor + 1))) {
          throw new IllegalArgumentException("malformed UTF-16 compact text");
        }
        cursor += 2;
      } else if (Character.isLowSurrogate(ch)) {
        throw new IllegalArgumentException("malformed UTF-16 compact text");
      } else {
        cursor++;
      }
    }
    return cursor;
  }

  private static TerminalCell[] expandRuns(List<TerminalScreenProto.CellRun> runs, int requestedColumns) {
    int columns = Math.max(0, requestedColumns);
    for (TerminalScreenProto.CellRun run : runs) {
      int end = Math.max(0, run.getCol());
      for (TerminalScreenProto.Cell cell : run.getCellsList()) {
        end += Math.max(1, cell.getWidth());
      }
      columns = Math.max(columns, end);
    }
    TerminalCell[] cells = new TerminalCell[columns];
    java.util.Arrays.fill(cells, TerminalCell.EMPTY);
    for (TerminalScreenProto.CellRun run : runs) {
      int col = Math.max(0, run.getCol());
      for (TerminalScreenProto.Cell pbCell : run.getCellsList()) {
        // Validator 会拒绝 width=0；这里仍防御性跳过，确保非法帧若绕过校验也不会
        // 将宽字符后的本地 spacer 再推进一列。
        if (pbCell.getWidth() == 0) continue;
        TerminalCell cell = mapCell(pbCell);
        if (col >= cells.length) break;
        cells[col] = cell;
        int width = Math.max(1, pbCell.getWidth());
        if (width == 2 && col + 1 < cells.length) {
          cells[col + 1] = TerminalCell.SPACER;
        }
        col += width;
      }
    }
    return cells;
  }

  private static TerminalCell mapCell(TerminalScreenProto.Cell pb) {
    return new TerminalCell(pb.getText(), (byte) pb.getWidth(), pb.getStyleId(), pb.getLinkId());
  }

  private static TerminalCursor mapCursor(TerminalScreenProto.Cursor pb) {
    return new TerminalCursor(
        pb.getRow(),
        pb.getCol(),
        pb.getVisible(),
        mapCursorShape(pb.getShape()),
        pb.getBlink()
    );
  }

  private static TerminalCursor.Shape mapCursorShape(TerminalScreenProto.CursorShape shape) {
    switch (shape) {
      case CURSOR_SHAPE_BAR: return TerminalCursor.Shape.BAR;
      case CURSOR_SHAPE_UNDERLINE: return TerminalCursor.Shape.UNDERLINE;
      case CURSOR_SHAPE_BLOCK:
      default: return TerminalCursor.Shape.BLOCK;
    }
  }

  private static TerminalModes mapModes(TerminalScreenProto.Modes pb) {
    return new TerminalModes(
        pb.getApplicationCursor(),
        pb.getApplicationKeypad(),
        pb.getBracketedPaste(),
        mapMouseTracking(pb.getMouseTracking()),
        mapMouseEncoding(pb.getMouseEncoding()),
        pb.getFocusReporting()
    );
  }

  private static TerminalModes.MouseTracking mapMouseTracking(TerminalScreenProto.MouseTracking t) {
    switch (t) {
      case MOUSE_TRACKING_X10: return TerminalModes.MouseTracking.X10;
      case MOUSE_TRACKING_VT200: return TerminalModes.MouseTracking.VT200;
      case MOUSE_TRACKING_VT200_HIGHLIGHT: return TerminalModes.MouseTracking.VT200_HIGHLIGHT;
      case MOUSE_TRACKING_BUTTON_EVENT: return TerminalModes.MouseTracking.BUTTON_EVENT;
      case MOUSE_TRACKING_ANY_EVENT: return TerminalModes.MouseTracking.ANY_EVENT;
      case MOUSE_TRACKING_SGR_PIXELS: return TerminalModes.MouseTracking.SGR_PIXELS;
      case MOUSE_TRACKING_NONE:
      default: return TerminalModes.MouseTracking.NONE;
    }
  }

  private static TerminalModes.MouseEncoding mapMouseEncoding(TerminalScreenProto.MouseEncoding e) {
    switch (e) {
      case MOUSE_ENCODING_UTF8: return TerminalModes.MouseEncoding.UTF8;
      case MOUSE_ENCODING_SGR: return TerminalModes.MouseEncoding.SGR;
      case MOUSE_ENCODING_URXVT: return TerminalModes.MouseEncoding.URXVT;
      case MOUSE_ENCODING_X10:
      default: return TerminalModes.MouseEncoding.X10;
    }
  }

  private static TerminalPalette mapPalette(TerminalScreenProto.TerminalPalette pb) {
    java.util.Map<Integer, Integer> indexedColors = new java.util.HashMap<>();
    for (TerminalScreenProto.IndexedPaletteColor entry : pb.getIndexedColorsList()) {
      if (entry.getIndex() >= 0 && entry.getIndex() < 256) {
        indexedColors.put(entry.getIndex(), entry.getRgb());
      }
    }
    return new TerminalPalette(
        mapColor(pb.getDefaultFg()),
        mapColor(pb.getDefaultBg()),
        mapColor(pb.getCursorColor()),
        pb.getReverseVideo(),
        indexedColors,
        pb.getGeneration()
    );
  }

  private static TerminalColor mapColor(TerminalScreenProto.Color pb) {
    switch (pb.getKind()) {
      case COLOR_KIND_DEFAULT_FG: return TerminalColor.DEFAULT_FG;
      case COLOR_KIND_DEFAULT_BG: return TerminalColor.DEFAULT_BG;
      case COLOR_KIND_CURSOR: return TerminalColor.CURSOR;
      case COLOR_KIND_INDEXED: return TerminalColor.indexed(pb.getIndex());
      case COLOR_KIND_RGB: return TerminalColor.rgb(pb.getRgb());
      default: return TerminalColor.DEFAULT_FG;
    }
  }


  private static Map<Integer, TerminalStyle> mapStyles(List<TerminalScreenProto.TerminalStyle> list) {
    Map<Integer, TerminalStyle> map = new HashMap<>();
    for (TerminalScreenProto.TerminalStyle s : list) {
      map.put(s.getId(), new TerminalStyle(
          s.getId(),
          mapColor(s.getFg()),
          mapColor(s.getBg()),
          mapColor(s.getUnderlineColor()),
          mapCellAttrs(s.getAttrs())
      ));
    }
    return map;
  }

  private static int mapCellAttrs(TerminalScreenProto.CellAttrs attrs) {
    int bits = 0;
    if (attrs.getBold()) bits |= 1 << 0;
    if (attrs.getDim()) bits |= 1 << 1;
    if (attrs.getItalic()) bits |= 1 << 2;
    if (attrs.getUnderline()) bits |= 1 << 3;
    if (attrs.getDoubleUnderline()) bits |= 1 << 4;
    if (attrs.getCurlyUnderline()) bits |= 1 << 5;
    if (attrs.getDottedUnderline()) bits |= 1 << 6;
    if (attrs.getDashedUnderline()) bits |= 1 << 7;
    if (attrs.getBlinkSlow()) bits |= 1 << 8;
    if (attrs.getBlinkFast()) bits |= 1 << 9;
    if (attrs.getReverse()) bits |= 1 << 10;
    if (attrs.getHidden()) bits |= 1 << 11;
    if (attrs.getStrike()) bits |= 1 << 12;
    return bits;
  }

  private static Map<Integer, Hyperlink> mapLinks(List<TerminalScreenProto.Hyperlink> list) {
    Map<Integer, Hyperlink> map = new HashMap<>();
    for (TerminalScreenProto.Hyperlink l : list) {
      map.put(l.getId(), new Hyperlink(l.getId(), l.getUri()));
    }
    return map;
  }

  private static ScreenSnapshot.BufferKind mapBufferKind(TerminalScreenProto.BufferKind kind) {
    return kind == TerminalScreenProto.BufferKind.BUFFER_KIND_ALTERNATE
        ? ScreenSnapshot.BufferKind.ALTERNATE
        : ScreenSnapshot.BufferKind.MAIN;
  }
}
