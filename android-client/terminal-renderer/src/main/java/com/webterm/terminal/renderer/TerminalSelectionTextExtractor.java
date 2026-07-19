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
   * @param history   按 {@link TerminalLine#historyOrder()}（HistorySeq）升序排列的历史行快照；可能为空
   * @param screen    活动屏幕行数组；可能为 null
   */
  static String extract(TerminalSelection selection, TerminalHistorySnapshot history, TerminalLine[] screen) {
    SelectionTextBuilder output = new SelectionTextBuilder(screenColumns(screen));
    TerminalSelection.Anchor start = selection.start;
    TerminalSelection.Anchor end = selection.end;

    if (start.historySeq != 0) {
      if (end.historySeq != 0) {
        // 历史内部选择。
        appendHistoryRange(output, start.historySeq, end.historySeq, start.col, end.col, history);
      } else {
        // 起点在历史、终点在屏幕：先取到历史末尾，再取屏幕开头到终点。
        appendHistoryRange(output, start.historySeq, Long.MAX_VALUE, start.col, -1, history);
        appendScreenRange(output, new TerminalSelection.Anchor(0, 0, 0), end, screen);
      }
    } else if (end.historySeq == 0 && start.screenRow == end.screenRow) {
      appendScreenRow(output, screen, start.screenRow, start.col, end.col);
    } else {
      appendScreenRange(output, start, end, screen);
    }
    return output.build();
  }

  private static int screenColumns(TerminalLine[] screen) {
    if (screen == null) return 0;
    for (TerminalLine line : screen) {
      if (line != null && line.length() > 0) return line.length();
    }
    return 0;
  }

  private static void appendHistoryRange(SelectionTextBuilder output, long startSeq, long endSeq,
                                         int startCol, int endCol, TerminalHistorySnapshot history) {
    if (history == null || history.isEmpty()) return;
    int startIndex = history.findSeqIndex(startSeq);
    if (startIndex < 0) startIndex = 0;
    int endIndex = endCol >= 0 ? history.findSeqIndex(endSeq) : history.size() - 1;
    if (endIndex < 0) endIndex = history.size() - 1;

    for (int i = startIndex; i <= endIndex && i < history.size(); i++) {
      TerminalLine line = history.lineAt(i);
      long seq = line.historyOrder();
      if (seq < startSeq) continue;
      if (seq > endSeq) break;
      int c0 = seq == startSeq ? startCol : 0;
      int c1 = seq == endSeq ? (endCol >= 0 ? endCol : line.length()) : line.length();
      output.append(line, c0, c1, /* inferVisualWrap */ false);
    }
  }

  private static void appendScreenRange(SelectionTextBuilder output, TerminalSelection.Anchor start,
                                        TerminalSelection.Anchor end, TerminalLine[] screen) {
    if (screen == null) return;
    for (int row = start.screenRow; row <= end.screenRow && row < screen.length; row++) {
      int c0 = row == start.screenRow ? start.col : 0;
      int c1 = row == end.screenRow ? end.col : (row < screen.length ? screen[row].length() : 0);
      appendScreenRow(output, screen, row, c0, c1);
    }
  }

  private static void appendScreenRow(SelectionTextBuilder output, TerminalLine[] screen,
                                      int row, int colStart, int colEnd) {
    if (screen == null || row < 0 || row >= screen.length) return;
    output.append(screen[row], colStart, colEnd, /* inferVisualWrap */ true);
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

  /**
   * 按终端的逻辑行语义组装经过美化的复制文本：跳过空白物理行，
   * 软换行的下一个物理行不加换行符，硬换行则去掉行尾填充。
   * 对于少数丢失 wrapped 标记的行，内容顶到屏幕右边界时也视为视觉折行。
   */
  private static final class SelectionTextBuilder {
    private final StringBuilder text = new StringBuilder();
    private final int terminalColumns;
    private boolean hasLine;
    private boolean previousContinues;

    SelectionTextBuilder(int terminalColumns) {
      this.terminalColumns = terminalColumns;
    }

    void append(TerminalLine line, int colStart, int colEnd, boolean inferVisualWrap) {
      if (line == null) return;

      StringBuilder fragment = new StringBuilder();
      appendLineText(fragment, line, colStart, colEnd);
      boolean reachesRightEdge = inferVisualWrap
          && terminalColumns > 0
          && line.length() >= terminalColumns
          && colEnd >= terminalColumns
          && fragment.length() > 0
          && fragment.charAt(fragment.length() - 1) != ' ';
      if (!line.wrapped) trimTrailingSpaces(fragment, 0);

      // 终端缓冲区的空白行不携带可复制内容，直接忽略。
      if (fragment.length() == 0) return;

      if (hasLine && !previousContinues) text.append('\n');
      text.append(fragment);

      hasLine = true;
      previousContinues = line.wrapped || reachesRightEdge;
    }

    String build() {
      return text.toString();
    }

    private static void trimTrailingSpaces(StringBuilder value, int contentStart) {
      while (value.length() > contentStart && value.charAt(value.length() - 1) == ' ') {
        value.setLength(value.length() - 1);
      }
    }
  }
}
