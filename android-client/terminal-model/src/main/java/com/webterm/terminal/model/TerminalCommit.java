package com.webterm.terminal.model;

public final class TerminalCommit {
  public final String instanceId;
  public final long layoutEpoch;
  public final long dictionaryGeneration;
  public final long historyGeneration;
  public final DictionaryEntries dictionaryAdditions;
  public final TerminalBufferKind activeBuffer;
  public final long baseRevision;
  public final long revision;
  public final ScreenMutation screen;
  public final HistoryMutation history;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;

  public TerminalCommit(String instanceId, long layoutEpoch, long baseRevision, long revision,
                        long dictionaryGeneration, long historyGeneration,
                        DictionaryEntries dictionaryAdditions, TerminalBufferKind activeBuffer,
                        ScreenMutation screen, HistoryMutation history, TerminalCursor cursor,
                        TerminalModes modes, TerminalPalette palette) {
    this.instanceId = instanceId; this.layoutEpoch = layoutEpoch;
    this.baseRevision = baseRevision; this.revision = revision;
    this.dictionaryGeneration = dictionaryGeneration; this.historyGeneration = historyGeneration;
    this.dictionaryAdditions = dictionaryAdditions == null ? DictionaryEntries.EMPTY : dictionaryAdditions;
    this.activeBuffer = activeBuffer; this.screen = screen; this.history = history;
    this.cursor = cursor; this.modes = modes; this.palette = palette;
  }
}
