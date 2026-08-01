package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;

public final class TerminalCommit {
  public final String instanceId;
  public final long layoutEpoch;
  public final long historyGeneration;
  public final TerminalBufferKind activeBuffer;
  public final long baseRevision;
  public final long revision;
  public final List<LineBodyRecord> bodyUpserts;
  public final ScreenMutation screen;
  public final HistoryMutation history;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;

  public TerminalCommit(String instanceId, long layoutEpoch, long baseRevision, long revision,
                        long historyGeneration,
                        TerminalBufferKind activeBuffer,
                        List<LineBodyRecord> bodyUpserts,
                        ScreenMutation screen, HistoryMutation history, TerminalCursor cursor,
                        TerminalModes modes, TerminalPalette palette) {
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.baseRevision = baseRevision;
    this.revision = revision;
    this.historyGeneration = historyGeneration;
    this.activeBuffer = activeBuffer;
    this.bodyUpserts = bodyUpserts == null
        ? Collections.emptyList() : List.copyOf(bodyUpserts);
    this.screen = screen;
    this.history = history;
    this.cursor = cursor;
    this.modes = modes;
    this.palette = palette;
  }
}
