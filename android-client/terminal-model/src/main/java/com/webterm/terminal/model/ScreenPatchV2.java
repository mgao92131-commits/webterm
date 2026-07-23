package com.webterm.terminal.model;

import java.util.List;

public final class ScreenPatchV2 {
  public final String instanceId;
  public final long layoutEpoch;
  public final long streamGeneration;
  public final long baseRevision;
  public final long screenRevision;
  public final long[] layout;
  public final List<TerminalLine> lineUpdates;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;
  public final TerminalBufferKind activeBuffer;
  public final String title;
  public final String workingDirectory;

  public ScreenPatchV2(
      String instanceId, long layoutEpoch, long streamGeneration, long baseRevision,
      long screenRevision, long[] layout, List<TerminalLine> lineUpdates,
      TerminalCursor cursor, TerminalModes modes, TerminalPalette palette,
      TerminalBufferKind activeBuffer, String title, String workingDirectory) {
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.streamGeneration = streamGeneration;
    this.baseRevision = baseRevision;
    this.screenRevision = screenRevision;
    this.layout = layout;
    this.lineUpdates = lineUpdates;
    this.cursor = cursor;
    this.modes = modes;
    this.palette = palette;
    this.activeBuffer = activeBuffer;
    this.title = title;
    this.workingDirectory = workingDirectory;
  }
}
