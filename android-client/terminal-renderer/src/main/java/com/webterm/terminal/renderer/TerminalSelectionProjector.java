package com.webterm.terminal.renderer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalSelection;

/** 把一行的终端选择投影成一个半开物理列区间。 */
final class TerminalSelectionProjector {
  static final class Range {
    int startColumn;
    int endColumnExclusive;

    void clear() {
      startColumn = 0;
      endColumnExclusive = 0;
    }

    boolean isEmpty() {
      return endColumnExclusive <= startColumn;
    }
  }

  void project(
      @Nullable TerminalSelection normalized,
      @Nullable RenderLine line,
      long historySeq,
      int screenRow,
      int columns,
      @NonNull Range out) {
    out.clear();
    if (normalized == null || line == null || columns <= 0) return;
    int lineLength = Math.min(line.length(), columns);
    if (lineLength <= 0) return;

    int rowToStart = compareRow(historySeq, screenRow, normalized.start);
    int rowToEnd = compareRow(historySeq, screenRow, normalized.end);
    if (rowToStart < 0 || rowToEnd > 0) return;

    if (rowToStart == 0 && rowToEnd == 0) {
      out.startColumn = clamp(normalized.start.col, 0, lineLength);
      out.endColumnExclusive = clamp(normalized.end.col, 0, lineLength);
    } else if (rowToStart == 0) {
      out.startColumn = clamp(normalized.start.col, 0, lineLength);
      out.endColumnExclusive = lineLength;
    } else if (rowToEnd == 0) {
      out.startColumn = 0;
      out.endColumnExclusive = clamp(normalized.end.col, 0, lineLength);
    } else {
      out.startColumn = 0;
      out.endColumnExclusive = lineLength;
    }

    if (out.isEmpty()) {
      out.clear();
      return;
    }
    out.startColumn = expandStartOverWideSpacer(line, out.startColumn);
    out.endColumnExclusive = expandEndOverWideCell(line, out.endColumnExclusive, lineLength);
    out.startColumn = clamp(out.startColumn, 0, lineLength);
    out.endColumnExclusive = clamp(out.endColumnExclusive, 0, lineLength);
    if (out.isEmpty()) out.clear();
  }

  private static int compareRow(long historySeq, int screenRow,
                                TerminalSelection.Anchor other) {
    if (historySeq != 0 && other.historySeq != 0) {
      return Long.compare(historySeq, other.historySeq);
    }
    if (historySeq != 0) return -1;
    if (other.historySeq != 0) return 1;
    return Integer.compare(screenRow, other.screenRow);
  }

  private static int expandStartOverWideSpacer(RenderLine line, int column) {
    if (column > 0 && column < line.length()
        && line.at(column).isSpacer() && line.at(column - 1).isWideStart()) {
      return column - 1;
    }
    return column;
  }

  private static int expandEndOverWideCell(RenderLine line, int end, int lineLength) {
    if (end <= 0 || end >= lineLength || end >= line.length()) return end;
    if (line.at(end).isSpacer() && line.at(end - 1).isWideStart()) {
      return Math.min(lineLength, end + 1);
    }
    return end;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
