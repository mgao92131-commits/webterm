package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 旧 Renderer 迁移期间唯一允许接触混合 TerminalLine 的适配边界。 */
final class SemanticLineAdapter {
  private SemanticLineAdapter() {}

  static ScreenLineContent screenContent(TerminalLine line) {
    if (line == null || line.historySeq != 0) {
      throw new IllegalArgumentException("invalid legacy screen line");
    }
    return new ScreenLineContent(new LineKey(line.id, line.version), body(line));
  }

  static List<ScreenLineContent> screenContents(List<TerminalLine> lines) {
    if (lines == null) return Collections.emptyList();
    List<ScreenLineContent> result = new ArrayList<>(lines.size());
    for (TerminalLine line : lines) result.add(screenContent(line));
    return result;
  }

  static List<HistoryBodyEntry> historyEntries(List<?> lines) {
    if (lines == null) return Collections.emptyList();
    List<HistoryBodyEntry> result = new ArrayList<>(lines.size());
    for (Object value : lines) {
      if (value instanceof HistoryBodyEntry) {
        result.add((HistoryBodyEntry) value);
      } else if (value instanceof TerminalLine) {
        TerminalLine line = (TerminalLine) value;
        result.add(new HistoryBodyEntry(
            line.historySeq, new LineKey(line.id, line.version), body(line)));
      } else {
        throw new IllegalArgumentException("unsupported history body value");
      }
    }
    return Collections.unmodifiableList(result);
  }

  private static LineBody body(TerminalLine line) {
    CellValue[] cells = new CellValue[line.length()];
    for (int column = 0; column < line.length(); column++) {
      TerminalCell cell = line.at(column);
      TerminalStyle style = cell.style;
      Hyperlink link = cell.link;
      StyleValue semanticStyle = style == null ? null : new StyleValue(
          style.fg, style.bg, style.underlineColor, style.attrs);
      LinkValue semanticLink = link == null ? null : new LinkValue(link.uri);
      cells[column] = cell.isDefault() ? CellValue.EMPTY
          : cell.isSpacer() ? CellValue.SPACER
          : new CellValue(cell.text, cell.width, semanticStyle, semanticLink);
    }
    return new LineBody(line.length(), line.wrapped, cells);
  }
}
