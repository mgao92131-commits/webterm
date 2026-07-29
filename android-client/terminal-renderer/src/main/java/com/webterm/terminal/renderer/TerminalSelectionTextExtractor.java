package com.webterm.terminal.renderer;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryRenderView;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalSelection;

import java.util.ArrayList;
import java.util.List;

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
   * @param history   按逻辑 HistorySeq 槽位排列的历史视图；可能为空
   * @param screen    活动屏幕行数组；可能为 null
   */
  static String extract(TerminalSelection selection, HistoryRenderView history, RenderLine[] screen) {
    List<CopyLine> lines = new ArrayList<>();
    TerminalSelection.Anchor start = selection.start;
    TerminalSelection.Anchor end = selection.end;

    if (start.historySeq != 0) {
      if (end.historySeq != 0) {
        // 历史内部选择。
        appendHistoryRange(lines, start.historySeq, end.historySeq, start.col, end.col, history);
      } else {
        // 起点在历史、终点在屏幕：先取到历史末尾，再取屏幕开头到终点。
        appendHistoryRange(lines, start.historySeq, Long.MAX_VALUE, start.col, -1, history);
        appendScreenRange(lines, new TerminalSelection.Anchor(0, 0, 0), end, screen);
      }
    } else if (end.historySeq == 0 && start.screenRow == end.screenRow) {
      appendScreenRow(lines, screen, start.screenRow, start.col, end.col);
    } else {
      appendScreenRange(lines, start, end, screen);
    }
    return TerminalCopyBeautifier.beautify(lines);
  }

  private static void appendHistoryRange(List<CopyLine> lines, long startSeq, long endSeq,
                                         int startCol, int endCol, HistoryRenderView history) {
    if (history == null || history.isEmpty()) return;
    int startIndex = history.findSeqIndex(startSeq);
    if (startIndex < 0) startIndex = 0;
    int endIndex = endCol >= 0 ? history.findSeqIndex(endSeq) : history.size() - 1;
    if (endIndex < 0) endIndex = history.size() - 1;

    for (int i = startIndex; i <= endIndex && i < history.size(); i++) {
      long seq = history.seqAt(i);
      RenderLine line = history.renderLineAt(i);
      if (line == null) {
        // 未加载槽位：跳过该行，避免 NPE；不把 LineID 误当 HistorySeq。
        continue;
      }
      if (seq < startSeq) continue;
      if (seq > endSeq) break;
      int c0 = seq == startSeq ? startCol : 0;
      int c1 = seq == endSeq ? (endCol >= 0 ? endCol : line.length()) : line.length();
      appendLine(lines, line, c0, c1);
    }
  }

  private static void appendScreenRange(List<CopyLine> lines, TerminalSelection.Anchor start,
                                        TerminalSelection.Anchor end, RenderLine[] screen) {
    if (screen == null) return;
    for (int row = start.screenRow; row <= end.screenRow && row < screen.length; row++) {
      int c0 = row == start.screenRow ? start.col : 0;
      int c1 = row == end.screenRow ? end.col : screen[row] == null ? 0 : screen[row].length();
      appendScreenRow(lines, screen, row, c0, c1);
    }
  }

  private static void appendScreenRow(List<CopyLine> lines, RenderLine[] screen,
                                      int row, int colStart, int colEnd) {
    if (screen == null || row < 0 || row >= screen.length) return;
    RenderLine line = screen[row];
    if (line == null) {
      lines.add(new CopyLine("", false));
      return;
    }
    appendLine(lines, line, colStart, colEnd);
  }

  private static void appendLine(List<CopyLine> lines, RenderLine line, int colStart, int colEnd) {
    if (line == null) return;
    StringBuilder text = new StringBuilder();
    int start = Math.max(0, Math.min(line.length(), colStart));
    int end = Math.max(0, Math.min(line.length(), colEnd));
    for (int i = start; i < end; i++) {
      CellValue cell = line.at(i);
      if (cell == null || cell.isSpacer()) continue;
      String value = cell.text();
      text.append(value == null || value.isEmpty() ? " " : value);
    }
    lines.add(new CopyLine(text.toString(), line.body().wrapped));
  }
}
