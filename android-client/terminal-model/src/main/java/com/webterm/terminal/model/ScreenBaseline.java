package com.webterm.terminal.model;

import java.util.List;

public final class ScreenBaseline {
  public final String sessionId;
  public final String instanceId;
  public final long layoutEpoch;
  public final long screenRevision;
  public final long streamGeneration;
  public final int rows;
  public final int cols;
  public final TerminalBufferKind activeBuffer;
  public final HistoryExtent historyExtent;
  public final List<TerminalLine> historyTail;
  public final List<TerminalLine> screen;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;
  public final String title;
  public final String workingDirectory;

  public ScreenBaseline(
      String sessionId, String instanceId, long layoutEpoch, long screenRevision,
      long streamGeneration, int rows, int cols, TerminalBufferKind activeBuffer,
      HistoryExtent historyExtent, List<TerminalLine> historyTail, List<TerminalLine> screen,
      TerminalCursor cursor, TerminalModes modes, TerminalPalette palette,
      String title, String workingDirectory) {
    this.sessionId = sessionId;
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.screenRevision = screenRevision;
    this.streamGeneration = streamGeneration;
    this.rows = rows;
    this.cols = cols;
    this.activeBuffer = activeBuffer;
    this.historyExtent = historyExtent;
    this.historyTail = historyTail;
    this.screen = screen;
    this.cursor = cursor;
    this.modes = modes;
    this.palette = palette;
    this.title = title;
    this.workingDirectory = workingDirectory;
  }
}
