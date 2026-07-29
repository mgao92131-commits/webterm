package com.webterm.terminal.model;

public final class TerminalCommit {
  public final String instanceId;
  public final long layoutEpoch;
  public final long dictionaryGeneration;
  public final long historyGeneration;
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
                        TerminalBufferKind activeBuffer,
                        ScreenMutation screen, HistoryMutation history, TerminalCursor cursor,
                        TerminalModes modes, TerminalPalette palette) {
    this.instanceId = instanceId; this.layoutEpoch = layoutEpoch;
    this.baseRevision = baseRevision; this.revision = revision;
    this.dictionaryGeneration = dictionaryGeneration; this.historyGeneration = historyGeneration;
    this.activeBuffer = activeBuffer; this.screen = screen; this.history = history;
    this.cursor = cursor; this.modes = modes; this.palette = palette;
  }

  /** 迁移期测试构造器；字典已由 protocol 边界解析，不再进入领域命令。 */
  @Deprecated
  public TerminalCommit(
      String instanceId, long layoutEpoch, long baseRevision, long revision,
      long dictionaryGeneration, long historyGeneration,
      DictionaryEntries ignoredDictionaryAdditions,
      TerminalBufferKind activeBuffer,
      ScreenMutation screen, HistoryMutation history, TerminalCursor cursor,
      TerminalModes modes, TerminalPalette palette) {
    this(instanceId, layoutEpoch, baseRevision, revision,
        dictionaryGeneration, historyGeneration, activeBuffer,
        screen, history, cursor, modes, palette);
  }
}
