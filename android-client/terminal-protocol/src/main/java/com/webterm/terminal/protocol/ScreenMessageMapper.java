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
    List<TerminalLine> screen = new ArrayList<>();
    for (TerminalScreenProto.TerminalLine line : pb.getScreenList()) {
      screen.add(mapLine(line, pb.getGeometry().getCols()));
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
        mapHistoryWindow(pb.getHistory()),
        screen,
        mapStyles(pb.getStylesList()),
        mapLinks(pb.getLinksList()),
        pb.getTitle(),
        pb.getWorkingDirectory()
    );
  }

  public static ScreenPatch mapPatch(TerminalScreenProto.ScreenPatch pb) {
    List<TerminalLine> historyAppend = new ArrayList<>();
    for (TerminalScreenProto.HistoryLine line : pb.getHistoryAppendList()) {
      historyAppend.add(mapHistoryLine(line));
    }
    List<TerminalLine> screenRows = new ArrayList<>();
    for (TerminalScreenProto.TerminalLine line : pb.getScreenRowsList()) {
      screenRows.add(mapLine(line, -1));
    }
    List<ScreenPatch.PromotedRow> promotedRows = new ArrayList<>();
    for (TerminalScreenProto.PromotedRow row : pb.getPromotedRowsList()) {
      promotedRows.add(new ScreenPatch.PromotedRow(row.getScreenRow(), row.getHistoryLineId()));
    }
    return new ScreenPatch(
        pb.getInstanceId(),
        pb.getLayoutEpoch(),
        pb.getBaseRevision(),
        pb.getScreenRevision(),
        historyAppend,
        screenRows,
        pb.hasCursor() ? mapCursor(pb.getCursor()) : null,
        pb.hasModes() ? mapModes(pb.getModes()) : null,
        pb.hasPalette() ? mapPalette(pb.getPalette()) : null,
        mapStyles(pb.getNewStylesList()),
        mapLinks(pb.getNewLinksList()),
        !pb.getTitle().isEmpty() ? pb.getTitle() : null,
        !pb.getWorkingDirectory().isEmpty() ? pb.getWorkingDirectory() : null,
        promotedRows
    );
  }

  public static HistoryPage mapHistoryPage(TerminalScreenProto.HistoryPage pb) {
    List<TerminalLine> lines = new ArrayList<>();
    for (TerminalScreenProto.HistoryLine line : pb.getLinesList()) {
      lines.add(mapHistoryLine(line));
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

  private static TerminalLine mapLine(TerminalScreenProto.TerminalLine pb, int columns) {
    return new TerminalLine(pb.getRow(), pb.getWrapped(), expandRuns(pb.getRunsList(), columns));
  }

  private static TerminalLine mapHistoryLine(TerminalScreenProto.HistoryLine pb) {
    return new TerminalLine(pb.getId(), pb.getWrapped(), expandRuns(pb.getRunsList(), -1));
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
    return new TerminalPalette(
        mapColor(pb.getDefaultFg()),
        mapColor(pb.getDefaultBg()),
        mapColor(pb.getCursorColor()),
        pb.getReverseVideo()
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

  private static HistoryWindow mapHistoryWindow(TerminalScreenProto.HistoryWindow pb) {
    List<TerminalLine> lines = new ArrayList<>();
    for (TerminalScreenProto.HistoryLine line : pb.getLinesList()) {
      lines.add(mapHistoryLine(line));
    }
    return new HistoryWindow(
        pb.getFirstAvailableLineId(),
        pb.getFirstIncludedLineId(),
        pb.getLastIncludedLineId(),
        pb.getHasMoreBefore(),
        lines
    );
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
