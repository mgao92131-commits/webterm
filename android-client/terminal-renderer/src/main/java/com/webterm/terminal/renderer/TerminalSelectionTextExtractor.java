package com.webterm.terminal.renderer;

import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalHistorySnapshot;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalSelection;

/**
 * 把 {@link TerminalSelection} 转换为可复制到剪贴板的纯文本。
 *
 * <p>此类不依赖 Android 视图系统，因此可以单元测试。它接收已排序的历史行
 * 快照和活动屏幕行数组，输出选择范围内的字符文本（跳过宽字符 spacer）。
 */
final class TerminalSelectionTextExtractor {

  private TerminalSelectionTextExtractor() {}

  /**
   * 提取选择范围内的文本。
   *
   * @param selection 已归一化的选择范围（start <= end）
   * @param history   按 {@link TerminalLine#id} 升序排列的历史行快照；可能为空
   * @param screen    活动屏幕行数组；可能为 null
   */
  static String extract(TerminalSelection selection, TerminalHistorySnapshot history, TerminalLine[] screen) {
    StringBuilder sb = new StringBuilder();
    TerminalSelection.Anchor start = selection.start;
    TerminalSelection.Anchor end = selection.end;

    if (start.historyLineId != 0) {
      if (end.historyLineId != 0) {
        // 历史内部选择。
        appendHistoryRange(sb, start.historyLineId, end.historyLineId, start.col, end.col, history);
      } else {
        // 起点在历史、终点在屏幕：先取到历史末尾，再取屏幕开头到终点。
        appendHistoryRange(sb, start.historyLineId, Long.MAX_VALUE, start.col, -1, history);
        appendScreenRange(sb, new TerminalSelection.Anchor(0, 0, 0), end, screen, /* prependNewline */ sb.length() > 0);
      }
    } else if (end.historyLineId == 0 && start.screenRow == end.screenRow) {
      appendScreenRow(sb, screen, start.screenRow, start.col, end.col);
    } else {
      appendScreenRange(sb, start, end, screen, /* prependNewline */ false);
    }
    return sb.toString();
  }

  private static void appendHistoryRange(StringBuilder sb, long startLineId, long endLineId,
                                         int startCol, int endCol, TerminalHistorySnapshot history) {
    if (history == null || history.isEmpty()) return;
    int startIndex = history.findLineIndex(startLineId);
    if (startIndex < 0) startIndex = 0;
    int endIndex = endCol >= 0 ? history.findLineIndex(endLineId) : history.size() - 1;
    if (endIndex < 0) endIndex = history.size() - 1;

    boolean first = true;
    for (int i = startIndex; i <= endIndex && i < history.size(); i++) {
      TerminalLine line = history.lineAt(i);
      long lineId = line.id;
      if (lineId < startLineId) continue;
      if (lineId > endLineId) break;
      if (!first) sb.append('\n');
      first = false;
      int c0 = lineId == startLineId ? startCol : 0;
      int c1 = lineId == endLineId ? (endCol >= 0 ? endCol : line.length()) : line.length();
      appendLineText(sb, line, c0, c1);
    }
  }

  private static void appendScreenRange(StringBuilder sb, TerminalSelection.Anchor start,
                                        TerminalSelection.Anchor end, TerminalLine[] screen,
                                        boolean prependNewline) {
    if (screen == null) return;
    boolean first = !prependNewline;
    for (int row = start.screenRow; row <= end.screenRow && row < screen.length; row++) {
      if (!first) sb.append('\n');
      first = false;
      int c0 = row == start.screenRow ? start.col : 0;
      int c1 = row == end.screenRow ? end.col : (row < screen.length ? screen[row].length() : 0);
      appendScreenRow(sb, screen, row, c0, c1);
    }
  }

  private static void appendScreenRow(StringBuilder sb, TerminalLine[] screen, int row, int colStart, int colEnd) {
    if (screen == null || row < 0 || row >= screen.length) return;
    appendLineText(sb, screen[row], colStart, colEnd);
  }

  private static void appendLineText(StringBuilder sb, TerminalLine line, int colStart, int colEnd) {
    if (line == null) return;
    int start = Math.max(0, Math.min(line.length(), colStart));
    int end = Math.max(0, Math.min(line.length(), colEnd));
    for (int i = start; i < end; i++) {
      TerminalCell cell = line.at(i);
      if (cell == null || cell.isSpacer()) continue;
      String text = cell.text;
      sb.append(text == null || text.isEmpty() ? " " : text);
    }
  }
}
