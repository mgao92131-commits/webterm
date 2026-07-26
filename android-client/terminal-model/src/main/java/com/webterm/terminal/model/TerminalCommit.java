package com.webterm.terminal.model;

public final class TerminalCommit {
  public final String instanceId;
  public final long layoutEpoch;
  public final long streamGeneration;
  public final long baseRevision;
  public final long revision;
  public final ScreenMutation screen;
  public final HistoryMutation history;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;

  public TerminalCommit(
      String instanceId, long layoutEpoch, long streamGeneration,
      long baseRevision, long revision, ScreenMutation screen,
      HistoryMutation history, TerminalCursor cursor, TerminalModes modes,
      TerminalPalette palette) {
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.streamGeneration = streamGeneration;
    this.baseRevision = baseRevision;
    this.revision = revision;
    this.screen = screen;
    this.history = history;
    this.cursor = cursor;
    this.modes = modes;
    this.palette = palette;
  }
}
