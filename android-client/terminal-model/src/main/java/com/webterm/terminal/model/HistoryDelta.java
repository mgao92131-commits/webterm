package com.webterm.terminal.model;

import java.util.List;

public final class HistoryDelta {
  public final String instanceId;
  public final long layoutEpoch;
  public final long streamGeneration;
  public final HistoryExtent availableExtent;
  public final List<TerminalLine> lines;

  public HistoryDelta(
      String instanceId, long layoutEpoch, long streamGeneration,
      HistoryExtent availableExtent, List<TerminalLine> lines) {
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.streamGeneration = streamGeneration;
    this.availableExtent = availableExtent;
    this.lines = lines;
  }
}
