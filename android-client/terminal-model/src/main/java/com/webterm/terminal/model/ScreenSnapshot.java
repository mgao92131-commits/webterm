package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 权威屏幕快照。原子替换当前模型。
 */
public final class ScreenSnapshot {
  public final String sessionId;
  public final String instanceId;
  public final long layoutEpoch;
  public final long screenRevision;
  public final int rows;
  public final int cols;
  public final BufferKind activeBuffer;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;
  public final HistoryWindow history;
  public final List<TerminalLine> screen;
  public final Map<Integer, TerminalStyle> styles;
  public final Map<Integer, Hyperlink> links;
  public final String title;
  public final String workingDirectory;

  public ScreenSnapshot(String sessionId, String instanceId, long layoutEpoch,
                        long screenRevision, int rows, int cols, BufferKind activeBuffer,
                        TerminalCursor cursor, TerminalModes modes, TerminalPalette palette,
                        HistoryWindow history, List<TerminalLine> screen,
                        Map<Integer, TerminalStyle> styles, Map<Integer, Hyperlink> links,
                        String title, String workingDirectory) {
    this.sessionId = sessionId;
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.screenRevision = screenRevision;
    this.rows = rows;
    this.cols = cols;
    this.activeBuffer = activeBuffer;
    this.cursor = cursor;
    this.modes = modes;
    this.palette = palette;
    this.history = history;
    this.screen = screen;
    this.styles = styles;
    this.links = links;
    this.title = title;
    this.workingDirectory = workingDirectory;
  }

  public enum BufferKind {
    MAIN,
    ALTERNATE
  }
}
