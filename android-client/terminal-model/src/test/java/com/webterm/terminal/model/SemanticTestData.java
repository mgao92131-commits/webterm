package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

  static List<LineKey> rowsFromScreen(List<ScreenLineContent> screen) {
    List<LineKey> rows = new ArrayList<>();
    for (ScreenLineContent line : screen) rows.add(line.key());
    return rows;
  }

  static List<LineBodyRecord> bodiesFromScreen(List<ScreenLineContent> screen) {
    List<LineBodyRecord> bodies = new ArrayList<>();
    for (ScreenLineContent line : screen) {
      bodies.add(new LineBodyRecord(line.key(), line.body()));
    }
    return bodies;
  }

  static ScreenBaseline baseline(int rows, int columns) {
    List<LineKey> screenRows = new ArrayList<>();
    List<LineBodyRecord> screenBodies = new ArrayList<>();
    for (int row = 0; row < rows; row++) {
      LineKey key = new LineKey(1 + row, 1);
      CellValue[] cells = new CellValue[columns];
      java.util.Arrays.fill(cells, CellValue.EMPTY);
      screenRows.add(key);
      screenBodies.add(new LineBodyRecord(key, new LineBody(columns, false, cells)));
    }
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1,
        rows, columns, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(),
        screenRows, screenBodies,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }

  static ScreenBaseline baselineLegacy(
      String sessionId, String instanceId, long layoutEpoch, long screenRevision,
      long ignoredDictionaryGeneration, long historyGeneration,
      int rows, int cols, TerminalBufferKind activeBuffer,
      HistoryExtent historyExtent, List<HistoryPush> historyBindings,
      List<ScreenLineContent> screen,
      TerminalCursor cursor, TerminalModes modes, TerminalPalette palette) {
    List<LineBodyRecord> bodies = new ArrayList<>(bodiesFromScreen(screen));
    Set<LineKey> indexed = new HashSet<>();
    for (LineBodyRecord record : bodies) indexed.add(record.key());
    if (historyBindings != null) {
      for (HistoryPush push : historyBindings) {
        if (push == null || push.key == null || !indexed.add(push.key)) continue;
        bodies.add(new LineBodyRecord(push.key, emptyBody(cols)));
      }
    }
    return new ScreenBaseline(
        sessionId, instanceId, layoutEpoch, screenRevision, historyGeneration,
        rows, cols, activeBuffer, historyExtent, historyBindings,
        rowsFromScreen(screen), bodies,
        cursor, modes, palette);
  }

  private static LineBody emptyBody(int cols) {
    CellValue[] cells = new CellValue[cols];
    java.util.Arrays.fill(cells, CellValue.EMPTY);
    return new LineBody(cols, false, cells);
  }

  static List<LineBodyRecord> upserts(ScreenLineContent... lines) {
    List<LineBodyRecord> out = new ArrayList<>();
    for (ScreenLineContent line : lines) {
      out.add(new LineBodyRecord(line.key(), line.body()));
    }
    return out;
  }

  static TerminalCommit commitLegacy(
      String instanceId, long layoutEpoch, long baseRevision, long revision,
      long ignoredDictionaryGeneration, long historyGeneration,
      TerminalBufferKind activeBuffer,
      ScreenMutation screen, HistoryMutation history,
      TerminalCursor cursor, TerminalModes modes, TerminalPalette palette) {
    return commitLegacy(
        instanceId, layoutEpoch, baseRevision, revision, ignoredDictionaryGeneration,
        historyGeneration, activeBuffer, Collections.emptyList(), screen, history,
        cursor, modes, palette);
  }

  static TerminalCommit commitLegacy(
      String instanceId, long layoutEpoch, long baseRevision, long revision,
      long ignoredDictionaryGeneration, long historyGeneration,
      TerminalBufferKind activeBuffer, List<LineBodyRecord> bodyUpserts,
      ScreenMutation screen, HistoryMutation history,
      TerminalCursor cursor, TerminalModes modes, TerminalPalette palette) {
    return new TerminalCommit(
        instanceId, layoutEpoch, baseRevision, revision, historyGeneration,
        activeBuffer,
        bodyUpserts == null ? Collections.emptyList() : bodyUpserts,
        screen, history, cursor, modes, palette);
  }
}
