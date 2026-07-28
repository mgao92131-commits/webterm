package com.webterm.terminal.renderer;

import com.webterm.terminal.model.HistoryPush;
import com.webterm.terminal.model.HistoryRangeResult;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.TerminalLine;
import java.util.ArrayList;
import java.util.List;

final class HistoryTestData {
  private HistoryTestData() {}

  static List<HistoryPush> pushes(List<TerminalLine> lines) {
    List<HistoryPush> pushes = new ArrayList<>(lines.size());
    for (TerminalLine line : lines) {
      pushes.add(new HistoryPush(line.historySeq, line.id, line.version));
    }
    return pushes;
  }

  static void loadRange(RemoteTerminalModel model, String instanceId, long layoutEpoch,
                        long historyGeneration, HistoryExtent extent,
                        List<TerminalLine> lines) {
    for (int start = 0; start < lines.size(); start += 256) {
      int end = Math.min(lines.size(), start + 256);
      List<TerminalLine> chunk = lines.subList(start, end);
      long from = chunk.get(0).historySeq;
      long to = chunk.get(chunk.size() - 1).historySeq;
      if (!model.applyHistoryRange(new HistoryRangeResult(
          "test-" + from, instanceId, layoutEpoch, historyGeneration,
          HistoryRangeResult.Status.OK, extent, chunk, 0), from, from, to)) {
        throw new AssertionError("history Range fixture was not applied: " + from + ".." + to);
      }
    }
  }
}
