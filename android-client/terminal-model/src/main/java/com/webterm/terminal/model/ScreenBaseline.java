package com.webterm.terminal.model;

import java.util.List;

public final class ScreenBaseline {
  public final String sessionId;
  public final String instanceId;
  public final long layoutEpoch;
  public final long screenRevision;
  public final long historyGeneration;
  /** 客户端按 extent + HistorySeq→LineKey 计算的兼容拓扑指纹。 */
  public final long historyTopologyHash;
  public final int rows;
  public final int cols;
  public final TerminalBufferKind activeBuffer;
  public final HistoryExtent historyExtent;
  public final List<HistoryPush> historyBindings;
  public final List<LineKey> screenRows;
  public final List<LineBodyRecord> screenBodies;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;

  public ScreenBaseline(
      String sessionId, String instanceId, long layoutEpoch, long screenRevision,
      long historyGeneration,
      int rows, int cols, TerminalBufferKind activeBuffer,
      HistoryExtent historyExtent, List<HistoryPush> historyBindings,
      List<LineKey> screenRows, List<LineBodyRecord> screenBodies,
      TerminalCursor cursor, TerminalModes modes, TerminalPalette palette) {
    this.sessionId = sessionId;
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.screenRevision = screenRevision;
    this.historyGeneration = historyGeneration;
    this.historyTopologyHash = historyTopologyHash(historyExtent, historyBindings);
    this.rows = rows;
    this.cols = cols;
    this.activeBuffer = activeBuffer;
    this.historyExtent = historyExtent;
    this.historyBindings = historyBindings;
    this.screenRows = screenRows;
    this.screenBodies = screenBodies;
    this.cursor = cursor;
    this.modes = modes;
    this.palette = palette;
  }

  private static long historyTopologyHash(
      HistoryExtent extent, List<HistoryPush> bindings) {
    long hash = 0xcbf29ce484222325L;
    hash = mix(hash, extent == null ? 0 : extent.firstSeq);
    hash = mix(hash, extent == null ? 0 : extent.lastSeq);
    if (bindings != null) {
      for (HistoryPush binding : bindings) {
        if (binding == null || binding.key == null) {
          hash = mix(hash, 0);
          continue;
        }
        hash = mix(hash, binding.historySeq);
        hash = mix(hash, binding.key.lineId());
        hash = mix(hash, binding.key.lineVersion());
      }
    }
    return hash;
  }

  private static long mix(long hash, long value) {
    hash ^= value;
    return hash * 0x100000001b3L;
  }
}
