package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class V2ModelTestData {
  private V2ModelTestData() {}

  static TerminalLine line(long id, long version, long historySeq, String text) {
    return new TerminalLine(id, version, historySeq, false,
        new TerminalCell[] {new TerminalCell(text, (byte) 1, null, null)});
  }

  static ScreenBaseline baseline(long revision, long generation) {
    List<TerminalLine> tail = new ArrayList<>();
    for (long seq = 173; seq <= 300; seq++) tail.add(line(seq, 1, seq, "h"));
    return new ScreenBaseline(
        "s1", "i1", 1, revision, generation, 1, 1,
        TerminalBufferKind.MAIN, new HistoryExtent(1, 300), tail,
        Collections.singletonList(line(1000, 1, 0, "a")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
  }
}
