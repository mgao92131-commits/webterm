package com.webterm.terminal.model;

import java.util.List;

public final class ScreenBaseline {
  public final String sessionId;
  public final String instanceId;
  public final long layoutEpoch;
  public final long screenRevision;
  public final long dictionaryGeneration;
  public final long historyGeneration;
  public final int rows;
  public final int cols;
  public final TerminalBufferKind activeBuffer;
  public final HistoryExtent historyExtent;
  public final List<HistoryPush> historyBindings;
  boolean historyCatalogComplete;
  public final List<ScreenLineContent> screen;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;

  public ScreenBaseline(
      String sessionId, String instanceId, long layoutEpoch, long screenRevision,
      long dictionaryGeneration, long historyGeneration,
      int rows, int cols, TerminalBufferKind activeBuffer,
      HistoryExtent historyExtent, List<HistoryPush> historyBindings,
      List<ScreenLineContent> screen,
      TerminalCursor cursor, TerminalModes modes, TerminalPalette palette) {
    this.sessionId = sessionId;
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.screenRevision = screenRevision;
    this.dictionaryGeneration = dictionaryGeneration;
    this.historyGeneration = historyGeneration;
    this.rows = rows;
    this.cols = cols;
    this.activeBuffer = activeBuffer;
    this.historyExtent = historyExtent;
    this.historyBindings = historyBindings;
    this.historyCatalogComplete = true;
    this.screen = screen;
    this.cursor = cursor;
    this.modes = modes;
    this.palette = palette;
  }

  /** 迁移期测试构造器；生产 Baseline 必须携带完整 historyBindings。 */
  @Deprecated
  public ScreenBaseline(
      String sessionId, String instanceId, long layoutEpoch, long screenRevision,
      long dictionaryGeneration, long historyGeneration,
      DictionaryEntries ignoredDictionary,
      int rows, int cols, TerminalBufferKind activeBuffer,
      HistoryExtent historyExtent, List<TerminalLine> screen,
      TerminalCursor cursor, TerminalModes modes, TerminalPalette palette) {
    this(sessionId, instanceId, layoutEpoch, screenRevision,
        dictionaryGeneration, historyGeneration, rows, cols, activeBuffer,
        historyExtent, java.util.Collections.emptyList(),
        SemanticLineAdapter.screenContents(screen), cursor, modes, palette);
    this.historyCatalogComplete = false;
  }
}
