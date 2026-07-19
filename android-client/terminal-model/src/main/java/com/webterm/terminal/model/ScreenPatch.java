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
  /** null 表示布局未变化；非 null 时下标即当前屏幕 row。 */
  public final long[] layout;
  public final List<TerminalLine> lineUpdates;
  public final List<Long> historyAppendIds;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;
  public final Map<Integer, TerminalStyle> newStyles;
  public final Map<Integer, Hyperlink> newLinks;
  public final String title;
  public final String workingDirectory;
  /** null=字段 absent；非 null=历史在此 Line ID 之前已被服务端裁剪。 */
  public final Long historyTrimBeforeId;

  public ScreenPatch(String instanceId, long layoutEpoch, long baseRevision, long screenRevision,
                     long[] layout, List<TerminalLine> lineUpdates, List<Long> historyAppendIds,
                     TerminalCursor cursor, TerminalModes modes, TerminalPalette palette,
                     Map<Integer, TerminalStyle> newStyles, Map<Integer, Hyperlink> newLinks,
                     String title, String workingDirectory) {
    this(instanceId, layoutEpoch, baseRevision, screenRevision, layout, lineUpdates, historyAppendIds,
        cursor, modes, palette, newStyles, newLinks, title, workingDirectory, null);
  }

  public ScreenPatch(String instanceId, long layoutEpoch, long baseRevision, long screenRevision,
                     long[] layout, List<TerminalLine> lineUpdates, List<Long> historyAppendIds,
                     TerminalCursor cursor, TerminalModes modes, TerminalPalette palette,
                     Map<Integer, TerminalStyle> newStyles, Map<Integer, Hyperlink> newLinks,
                     String title, String workingDirectory, Long historyTrimBeforeId) {
    this.instanceId = instanceId;
    this.layoutEpoch = layoutEpoch;
    this.baseRevision = baseRevision;
    this.screenRevision = screenRevision;
    this.layout = layout;
    this.lineUpdates = lineUpdates != null ? lineUpdates : Collections.emptyList();
    this.historyAppendIds = historyAppendIds != null ? historyAppendIds : Collections.emptyList();
    this.cursor = cursor;
    this.modes = modes;
    this.palette = palette;
    this.newStyles = newStyles != null ? newStyles : Collections.emptyMap();
    this.newLinks = newLinks != null ? newLinks : Collections.emptyMap();
    this.title = title;
    this.workingDirectory = workingDirectory;
    this.historyTrimBeforeId = historyTrimBeforeId;
  }
}
