package com.webterm.feature.terminal.domain;

import com.webterm.terminal.protocol.generated.TerminalScreenProto;

/** Stable-ID wire fixtures shared by terminal-runtime tests. */
final class TestScreenFrames {
  private TestScreenFrames() {}

  static TerminalScreenProto.ScreenSnapshot.Builder snapshotBuilder(long revision) {
    TerminalScreenProto.ScreenSnapshot.Builder snapshot = TerminalScreenProto.ScreenSnapshot.newBuilder()
        .setSessionId("s1").setInstanceId("i1").setLayoutEpoch(1).setScreenRevision(revision)
        .setGeometry(TerminalScreenProto.Size.newBuilder().setRows(5).setCols(10));
    addScreen(snapshot, 5, 10);
    return snapshot;
  }

  static void addScreen(TerminalScreenProto.ScreenSnapshot.Builder snapshot, int rows, int cols) {
    TerminalScreenProto.ScreenLayout.Builder layout = TerminalScreenProto.ScreenLayout.newBuilder();
    for (int row = 0; row < rows; row++) {
      long id = row + 1L;
      layout.addLineIds(id);
      snapshot.addScreenLines(line(id, 1, ""));
    }
    snapshot.setLayout(layout);
  }

  static TerminalScreenProto.LineData line(long id, long version, String text) {
    TerminalScreenProto.LineData.Builder line = TerminalScreenProto.LineData.newBuilder()
        .setLineId(id).setLineVersion(version);
    if (!text.isEmpty()) {
      line.addRuns(TerminalScreenProto.CellRun.newBuilder().setCol(0)
          .addCells(TerminalScreenProto.Cell.newBuilder().setText(text).setWidth(1)));
    }
    return line.build();
  }

  static TerminalScreenProto.LineData line(long id, long version, String text, int styleId) {
    return TerminalScreenProto.LineData.newBuilder().setLineId(id).setLineVersion(version)
        .addRuns(TerminalScreenProto.CellRun.newBuilder().setCol(0)
            .addCells(TerminalScreenProto.Cell.newBuilder()
                .setText(text).setWidth(1).setStyleId(styleId)))
        .build();
  }
}
