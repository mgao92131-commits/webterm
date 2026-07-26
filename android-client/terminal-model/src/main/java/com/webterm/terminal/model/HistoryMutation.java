package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;

public final class HistoryMutation {
  public final HistoryExtent finalExtent;
  public final List<TerminalLine> appendedLines;

  public HistoryMutation(HistoryExtent finalExtent, List<TerminalLine> appendedLines) {
    this.finalExtent = finalExtent;
    this.appendedLines = appendedLines == null ? Collections.emptyList() : appendedLines;
  }
}
