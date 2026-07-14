package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 实时 patch。baseRevision 必须等于当前 screenRevision 才可应用。
 */
public final class ScreenPatch {
  public final String instanceId;
  public final long layoutEpoch;
  public final long baseRevision;
  public final long screenRevision;
  public final List<TerminalLine> historyAppend;
  public final List<TerminalLine> screenRows;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;
  public final Map<Integer, TerminalStyle> newStyles;
  public final Map<Integer, Hyperlink> newLinks;
  public final String title;
  public final String workingDirectory;
  public final List<PromotedRow> promotedRows;
  /** null=字段 absent；非 null=恢复 Patch 原子历史水位。 */
  public final Long firstAvailableHistoryLineId;

  public ScreenPatch(String instanceId, long layoutEpoch, long baseRevision, long screenRevision,
                     List<TerminalLine> historyAppend, List<TerminalLine> screenRows,
                     TerminalCursor cursor, TerminalModes modes, TerminalPalette palette,
                     Map<Integer, TerminalStyle> newStyles, Map<Integer, Hyperlink> newLinks,
                     String title, String workingDirectory, List<PromotedRow> promotedRows) {
    this(instanceId, layoutEpoch, baseRevision, screenRevision, historyAppend, screenRows,
        cursor, modes, palette, newStyles, newLinks, title, workingDirectory, promotedRows, null);
  }

  public ScreenPatch(String instanceId, long layoutEpoch, long baseRevision, long screenRevision,
                     List<TerminalLine> historyAppend, List<TerminalLine> screenRows,
                     TerminalCursor cursor, TerminalModes modes, TerminalPalette palette,
                     Map<Integer, TerminalStyle> newStyles, Map<Integer, Hyperlink> newLinks,
                     String title, String workingDirectory, List<PromotedRow> promotedRows,
                     Long firstAvailableHistoryLineId) {
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.baseRevision = baseRevision;
    this.screenRevision = screenRevision;
    this.historyAppend = historyAppend != null ? historyAppend : Collections.emptyList();
    this.screenRows = screenRows != null ? screenRows : Collections.emptyList();
    this.cursor = cursor;
    this.modes = modes;
    this.palette = palette;
    this.newStyles = newStyles != null ? newStyles : Collections.emptyMap();
    this.newLinks = newLinks != null ? newLinks : Collections.emptyMap();
    this.title = title;
    this.workingDirectory = workingDirectory;
    this.promotedRows = promotedRows != null ? promotedRows : Collections.emptyList();
    this.firstAvailableHistoryLineId = firstAvailableHistoryLineId;
  }

  public static final class PromotedRow {
    public final int screenRow;
    public final long historyLineId;

    public PromotedRow(int screenRow, long historyLineId) {
      this.screenRow = screenRow;
      this.historyLineId = historyLineId;
    }
  }
}
