package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Android 远程终端模型。只维护投影和缓存，可由 Go 权威快照重建。
 */
public final class RemoteTerminalModel {
  // 与 Go TrackedScrollback 的 10k 上限对齐；避免“刚按需加载旧页就被本地淘汰”。
  private static final int DEFAULT_SOFT_HISTORY_LIMIT = 7500;
  private static final int DEFAULT_HARD_HISTORY_LIMIT = 10000;

  public String instanceId;
  public long layoutEpoch;
  public long screenRevision;
  public int rows;
  public int columns;
  public ScreenSnapshot.BufferKind activeBuffer;

  private final NavigableMap<Long, TerminalLine> historyCache = new TreeMap<>();
  private TerminalLine[] screen;

  private long firstAvailableHistoryId;
  private boolean hasMoreHistoryBefore;
  private TerminalCursor cursor = TerminalCursor.hidden();
  private TerminalModes modes = TerminalModes.defaults();
  private TerminalPalette palette = TerminalPalette.defaults();
  private final Map<Integer, TerminalStyle> styles = new HashMap<>();
  private final Map<Integer, Hyperlink> links = new HashMap<>();
  private String title = "";
  private String workingDirectory = "";

  private final int softHistoryLimit;
  private final int hardHistoryLimit;

  public RemoteTerminalModel() {
    this(DEFAULT_SOFT_HISTORY_LIMIT, DEFAULT_HARD_HISTORY_LIMIT);
  }

  public RemoteTerminalModel(int softHistoryLimit, int hardHistoryLimit) {
    this.softHistoryLimit = softHistoryLimit;
    this.hardHistoryLimit = hardHistoryLimit;
    this.activeBuffer = ScreenSnapshot.BufferKind.MAIN;
  }

  public synchronized ModelChange applySnapshot(ScreenSnapshot snapshot) {
    this.instanceId = snapshot.instanceId;
    this.layoutEpoch = snapshot.layoutEpoch;
    this.screenRevision = snapshot.screenRevision;
    this.rows = snapshot.rows;
    this.columns = snapshot.cols;
    this.activeBuffer = snapshot.activeBuffer;
    this.cursor = snapshot.cursor != null ? snapshot.cursor : TerminalCursor.hidden();
    this.modes = snapshot.modes != null ? snapshot.modes : TerminalModes.defaults();
    this.palette = snapshot.palette != null ? snapshot.palette : TerminalPalette.defaults();
    this.title = snapshot.title != null ? snapshot.title : "";
    this.workingDirectory = snapshot.workingDirectory != null ? snapshot.workingDirectory : "";

    this.styles.clear();
    if (snapshot.styles != null) {
      this.styles.putAll(snapshot.styles);
    }
    this.links.clear();
    if (snapshot.links != null) {
      this.links.putAll(snapshot.links);
    }

    this.historyCache.clear();
    this.firstAvailableHistoryId = snapshot.history.firstAvailableLineId;
    this.hasMoreHistoryBefore = snapshot.history.hasMoreBefore;
    for (TerminalLine line : snapshot.history.lines) {
      if (line.id != 0) {
        this.historyCache.put(line.id, padOrCopyLine(line, this.columns));
      }
    }

    this.screen = new TerminalLine[this.rows];
    for (int i = 0; i < this.rows; i++) {
      this.screen[i] = emptyLine(i, this.columns);
    }
    for (TerminalLine line : snapshot.screen) {
      int row = findRow(line.id);
      if (row >= 0 && row < this.screen.length) {
        this.screen[row] = padOrCopyLine(line, this.columns);
      }
    }

    return ModelChange.full();
  }

  public synchronized ModelChange applyPatch(ScreenPatch patch) throws RevisionGapException {
    if (!instanceIdMatches(patch) || layoutEpoch != patch.layoutEpoch) {
      throw new RevisionGapException("instance/layout epoch mismatch");
    }
    if (screenRevision != patch.baseRevision) {
      throw new RevisionGapException("revision gap: local=" + screenRevision + " base=" + patch.baseRevision);
    }

    Set<Integer> changedRows = new HashSet<>();
    int appendedHistoryLines = 0;

    for (TerminalLine line : patch.screenRows) {
      int row = findRow(line.id);
      if (row >= 0 && row < screen.length) {
        screen[row] = padOrCopyLine(line, columns);
        changedRows.add(row);
      }
    }

    for (TerminalLine line : patch.historyAppend) {
      if (line.id != 0) {
        if (historyCache.put(line.id, padOrCopyLine(line, columns)) == null) appendedHistoryLines++;
      }
    }

    for (ScreenPatch.PromotedRow promoted : patch.promotedRows) {
      if (promoted.screenRow >= 0 && promoted.screenRow < screen.length) {
        TerminalLine promotedLine = screen[promoted.screenRow];
        if (promotedLine != null && promoted.historyLineId != 0) {
          if (historyCache.put(promoted.historyLineId, promotedLine.withId(promoted.historyLineId)) == null) {
            appendedHistoryLines++;
          }
        }
      }
    }

    if (patch.cursor != null) {
      cursor = patch.cursor;
    }
    if (patch.modes != null) {
      modes = patch.modes;
    }
    if (patch.palette != null) {
      palette = patch.palette;
    }
    if (patch.newStyles != null) {
      styles.putAll(patch.newStyles);
    }
    if (patch.newLinks != null) {
      links.putAll(patch.newLinks);
    }
    if (patch.title != null) {
      title = patch.title;
    }
    if (patch.workingDirectory != null) {
      workingDirectory = patch.workingDirectory;
    }

    screenRevision = patch.screenRevision;
    evictHistoryIfNeeded();

    return new ModelChange(
        false,
        changedRows,
        !patch.historyAppend.isEmpty() || !patch.promotedRows.isEmpty(),
        patch.cursor != null,
        patch.modes != null,
        patch.title != null,
        appendedHistoryLines
    );
  }

  public synchronized ModelChange prependHistoryPage(HistoryPage page) {
    if (layoutEpoch != page.layoutEpoch) {
      return ModelChange.none();
    }
    for (TerminalLine line : page.lines) {
      if (line.id != 0) {
        historyCache.putIfAbsent(line.id, padOrCopyLine(line, columns));
      }
    }
    styles.putAll(page.styles);
    links.putAll(page.links);
    this.firstAvailableHistoryId = page.firstAvailableLineId;
    this.hasMoreHistoryBefore = page.hasMoreBefore;
    evictHistoryIfNeeded();
    return new ModelChange(false, null, true, false, false, false);
  }

  public synchronized ModelChange trimHistory(long trimLayoutEpoch, long firstAvailableLineId) {
    if (layoutEpoch != trimLayoutEpoch) return ModelChange.none();
    this.firstAvailableHistoryId = firstAvailableLineId;
    Iterator<Map.Entry<Long, TerminalLine>> it = historyCache.entrySet().iterator();
    while (it.hasNext()) {
      if (it.next().getKey() < firstAvailableLineId) {
        it.remove();
      }
    }
    return new ModelChange(false, null, true, false, false, false);
  }

  public synchronized void resetForReconnect() {
    instanceId = null;
    layoutEpoch = 0;
    screenRevision = 0;
    screen = null;
    historyCache.clear();
    styles.clear();
    links.clear();
    cursor = TerminalCursor.hidden();
    modes = TerminalModes.defaults();
    palette = TerminalPalette.defaults();
    title = "";
    workingDirectory = "";
  }

  public synchronized TerminalLine[] screen() {
    return screen != null ? screen.clone() : null;
  }

  public synchronized NavigableMap<Long, TerminalLine> historyCache() {
    return new TreeMap<>(historyCache);
  }

  /** 历史行数。比 {@link #historyCache()} 轻量，不需要拷贝整棵 TreeMap。 */
  public synchronized int historySize() {
    return historyCache.size();
  }

  public synchronized TerminalCursor cursor() {
    return cursor;
  }

  public synchronized TerminalModes modes() {
    return modes;
  }

  public synchronized TerminalPalette palette() {
    return palette;
  }

  public synchronized Map<Integer, TerminalStyle> styles() {
    return new HashMap<>(styles);
  }

  public synchronized Map<Integer, Hyperlink> links() {
    return new HashMap<>(links);
  }

  public synchronized String title() {
    return title;
  }

  public synchronized String workingDirectory() {
    return workingDirectory;
  }

  public synchronized long firstAvailableHistoryId() {
    return firstAvailableHistoryId;
  }

  public synchronized boolean hasMoreHistoryBefore() {
    return hasMoreHistoryBefore;
  }

  private boolean instanceIdMatches(ScreenPatch patch) {
    return instanceId != null && instanceId.equals(patch.instanceId);
  }

  private int findRow(long lineId) {
    if (lineId >= 0 && lineId < screen.length) {
      return (int) lineId;
    }
    return -1;
  }

  private TerminalLine padOrCopyLine(TerminalLine line, int cols) {
    if (line.length() == cols) {
      return line;
    }
    TerminalCell[] cells = new TerminalCell[cols];
    int copyLen = Math.min(line.length(), cols);
    for (int i = 0; i < copyLen; i++) {
      cells[i] = line.at(i);
    }
    for (int i = copyLen; i < cols; i++) {
      cells[i] = TerminalCell.EMPTY;
    }
    return new TerminalLine(line.id, line.wrapped, cells);
  }

  private void evictHistoryIfNeeded() {
    if (historyCache.size() <= hardHistoryLimit) {
      return;
    }
    int target = Math.max(softHistoryLimit, hardHistoryLimit / 2);
    while (historyCache.size() > target) {
      historyCache.pollFirstEntry();
    }
  }

  private TerminalLine emptyLine(long id, int cols) {
    TerminalCell[] cells = new TerminalCell[cols];
    java.util.Arrays.fill(cells, TerminalCell.EMPTY);
    return new TerminalLine(id, false, cells);
  }

  public static final class RevisionGapException extends Exception {
    public RevisionGapException(String message) {
      super(message);
    }
  }
}
