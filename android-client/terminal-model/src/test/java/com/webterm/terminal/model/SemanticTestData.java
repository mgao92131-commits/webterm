package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SemanticTestData {
  private SemanticTestData() {}

  static LineBody body(String text) {
    return new LineBody(1, false, new CellValue[] {
        new CellValue(text, (byte) 1, null, null)
    });
  }

  static ScreenLineContent screen(long id, long version, String text) {
    return new ScreenLineContent(new LineKey(id, version), body(text));
  }

  static ScreenBaseline baseline(int rows, int columns) {
    List<ScreenLineContent> screen = new ArrayList<>();
    for (int row = 0; row < rows; row++) {
      CellValue[] cells = new CellValue[columns];
      java.util.Arrays.fill(cells, CellValue.EMPTY);
      screen.add(new ScreenLineContent(
          new LineKey(1 + row, 1),
          new LineBody(columns, false, cells)));
    }
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1,
        rows, columns, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(), screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }
}
