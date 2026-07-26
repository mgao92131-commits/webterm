package com.webterm.terminal.model;

import java.util.List;

public final class ScreenBaseline {
  public final String sessionId;
  public final String instanceId;
  public final long layoutEpoch;
  public final long screenRevision;
  public final long dictionaryGeneration;
  public final long historyGeneration;
  public final boolean preserveCompatibleHistory;
  public final DictionaryEntries dictionary;
  public final int rows;
  public final int cols;
  public final TerminalBufferKind activeBuffer;
  public final HistoryExtent historyExtent;
  public final List<TerminalLine> historyTail;
  public final List<TerminalLine> screen;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;

  public ScreenBaseline(String sessionId, String instanceId, long layoutEpoch, long screenRevision,
                        long dictionaryGeneration, long historyGeneration,
                        boolean preserveCompatibleHistory, DictionaryEntries dictionary,
                        int rows, int cols, TerminalBufferKind activeBuffer,
                        HistoryExtent historyExtent, List<TerminalLine> historyTail,
                        List<TerminalLine> screen, TerminalCursor cursor, TerminalModes modes,
                        TerminalPalette palette) {
    this.sessionId = sessionId; this.instanceId = instanceId; this.layoutEpoch = layoutEpoch;
    this.screenRevision = screenRevision;
    this.dictionaryGeneration = dictionaryGeneration; this.historyGeneration = historyGeneration;
    this.preserveCompatibleHistory = preserveCompatibleHistory;
    this.dictionary = dictionary == null ? DictionaryEntries.EMPTY : dictionary;
    this.rows = rows; this.cols = cols; this.activeBuffer = activeBuffer;
    this.historyExtent = historyExtent; this.historyTail = historyTail; this.screen = screen;
    this.cursor = cursor; this.modes = modes; this.palette = palette;
  }
}
