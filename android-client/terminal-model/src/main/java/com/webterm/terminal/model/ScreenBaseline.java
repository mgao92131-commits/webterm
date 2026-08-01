package com.webterm.terminal.model;

import java.util.List;

public final class ScreenBaseline {
  public final String sessionId;
  public final String instanceId;
  public final long layoutEpoch;
  public final long screenRevision;
  public final long historyGeneration;
  public final int rows;
  public final int cols;
  public final TerminalBufferKind activeBuffer;
  public final HistoryExtent historyExtent;
  public final List<HistoryPush> historyBindings;
  public final List<LineKey> screenRows;
  public final List<LineBodyRecord> screenBodies;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;

  public ScreenBaseline(
      String sessionId, String instanceId, long layoutEpoch, long screenRevision,
      long historyGeneration,
      int rows, int cols, TerminalBufferKind activeBuffer,
      HistoryExtent historyExtent, List<HistoryPush> historyBindings,
      List<LineKey> screenRows, List<LineBodyRecord> screenBodies,
      TerminalCursor cursor, TerminalModes modes, TerminalPalette palette) {
    this.sessionId = sessionId;
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.screenRevision = screenRevision;
    this.historyGeneration = historyGeneration;
    this.rows = rows;
    this.cols = cols;
    this.activeBuffer = activeBuffer;
    this.historyExtent = historyExtent;
    this.historyBindings = historyBindings;
    this.screenRows = screenRows;
    this.screenBodies = screenBodies;
    this.cursor = cursor;
    this.modes = modes;
    this.palette = palette;
  }
}
