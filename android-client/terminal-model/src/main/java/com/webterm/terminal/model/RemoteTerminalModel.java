package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
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
  private static final long DEFAULT_SOFT_HISTORY_BYTES = 6L << 20;
  private static final long DEFAULT_HARD_HISTORY_BYTES = 8L << 20;

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
  private final long softHistoryByteLimit;
  private final long hardHistoryByteLimit;
  private long historyBytes;
  /**
   * Published only after a complete model mutation. Canvas runs on the main
   * thread and reads this immutable view without copying the full history map
   * on every frame.
   */
  private volatile RenderSnapshot renderSnapshot = RenderSnapshot.empty();

  public RemoteTerminalModel() {
    this(DEFAULT_SOFT_HISTORY_LIMIT, DEFAULT_HARD_HISTORY_LIMIT);
  }

  public RemoteTerminalModel(int softHistoryLimit, int hardHistoryLimit) {
    this(softHistoryLimit, hardHistoryLimit, DEFAULT_SOFT_HISTORY_BYTES,
        DEFAULT_HARD_HISTORY_BYTES);
  }

  public RemoteTerminalModel(int softHistoryLimit, int hardHistoryLimit,
                             long softHistoryByteLimit, long hardHistoryByteLimit) {
    this.softHistoryLimit = softHistoryLimit;
    this.hardHistoryLimit = hardHistoryLimit;
    this.softHistoryByteLimit = Math.max(0, softHistoryByteLimit);
    this.hardHistoryByteLimit = Math.max(this.softHistoryByteLimit, hardHistoryByteLimit);
    this.activeBuffer = ScreenSnapshot.BufferKind.MAIN;
  }

  public synchronized ModelChange applySnapshot(ScreenSnapshot snapshot) {
    boolean geometryChanged = instanceId == null
        || !instanceId.equals(snapshot.instanceId)
        || layoutEpoch != snapshot.layoutEpoch
        || rows != snapshot.rows
        || columns != snapshot.cols;
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
    this.historyBytes = 0;
    this.firstAvailableHistoryId = snapshot.history.firstAvailableLineId;
    this.hasMoreHistoryBefore = snapshot.history.hasMoreBefore;
    for (TerminalLine line : snapshot.history.lines) {
      if (line.id != 0) {
        putHistoryLine(line);
      }
    }
    evictHistoryIfNeeded();

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

    publishRenderSnapshot(true, true, true);

    return ModelChange.full(geometryChanged);
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
        if (putHistoryLine(line)) appendedHistoryLines++;
      }
    }

    for (ScreenPatch.PromotedRow promoted : patch.promotedRows) {
      if (promoted.screenRow >= 0 && promoted.screenRow < screen.length) {
        TerminalLine promotedLine = screen[promoted.screenRow];
        if (promotedLine != null && promoted.historyLineId != 0) {
          if (putHistoryLine(promotedLine.withId(promoted.historyLineId))) {
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

    boolean historyChanged = !patch.historyAppend.isEmpty() || !patch.promotedRows.isEmpty();
    boolean stylesChanged = !patch.newStyles.isEmpty();
    boolean linksChanged = !patch.newLinks.isEmpty();
    screenRevision = patch.screenRevision;
    evictHistoryIfNeeded();
    publishRenderSnapshot(historyChanged, stylesChanged, linksChanged);

    return new ModelChange(
        false,
        changedRows,
        historyChanged,
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
    int historySizeBefore = historyCache.size();
    for (TerminalLine line : page.lines) {
      if (line.id != 0 && !historyCache.containsKey(line.id)) {
        putHistoryLine(line);
      }
    }
    styles.putAll(page.styles);
    links.putAll(page.links);
    this.firstAvailableHistoryId = page.firstAvailableLineId;
    this.hasMoreHistoryBefore = page.hasMoreBefore;
    evictHistoryIfNeeded();
    // The new rows are physically inserted above all cached rows. Reporting
    // their net count lets the viewport add the same pixel height and keep the
    // currently visible line stationary while a page arrives at the top.
    int insertedHistoryLines = Math.max(0, historyCache.size() - historySizeBefore);
    publishRenderSnapshot(true, !page.styles.isEmpty(), !page.links.isEmpty());
    return new ModelChange(false, null, true, false, false, false, insertedHistoryLines);
  }

  public synchronized ModelChange trimHistory(long trimLayoutEpoch, long firstAvailableLineId) {
    if (layoutEpoch != trimLayoutEpoch) return ModelChange.none();
    this.firstAvailableHistoryId = firstAvailableLineId;
    Iterator<Map.Entry<Long, TerminalLine>> it = historyCache.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Long, TerminalLine> entry = it.next();
      if (entry.getKey() < firstAvailableLineId) {
        historyBytes -= estimateHistoryLineBytes(entry.getValue());
        it.remove();
      }
    }
    publishRenderSnapshot(true, false, false);
    return new ModelChange(false, null, true, false, false, false);
  }

  public synchronized void resetForReconnect() {
    instanceId = null;
    layoutEpoch = 0;
    screenRevision = 0;
    screen = null;
    historyCache.clear();
    historyBytes = 0;
    styles.clear();
    links.clear();
    cursor = TerminalCursor.hidden();
    modes = TerminalModes.defaults();
    palette = TerminalPalette.defaults();
    title = "";
    workingDirectory = "";
    publishRenderSnapshot(true, true, true);
  }

  /** Returns the atomically published, immutable input for one Canvas frame. */
  public RenderSnapshot renderSnapshot() {
    return renderSnapshot;
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

  /** Approximate bytes retained by the Android-side history cache. */
  public synchronized long historyBytes() {
    return historyBytes;
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

  /** Inserts or replaces one cached history line and updates its byte budget in O(1). */
  private boolean putHistoryLine(TerminalLine line) {
    TerminalLine normalized = padOrCopyLine(line, columns);
    TerminalLine previous = historyCache.put(normalized.id, normalized);
    historyBytes += estimateHistoryLineBytes(normalized) - estimateHistoryLineBytes(previous);
    return previous == null;
  }

  private void evictHistoryIfNeeded() {
    boolean overLines = historyCache.size() > hardHistoryLimit;
    boolean overBytes = hardHistoryByteLimit > 0 && historyBytes > hardHistoryByteLimit;
    if (!overLines && !overBytes) {
      return;
    }
    int targetLines = Math.max(softHistoryLimit, hardHistoryLimit / 2);
    long targetBytes = softHistoryByteLimit > 0 ? softHistoryByteLimit : Long.MAX_VALUE;
    while (historyCache.size() > 1
        && (historyCache.size() > targetLines || historyBytes > targetBytes)) {
      java.util.Map.Entry<Long, TerminalLine> removed = historyCache.pollFirstEntry();
      if (removed != null) {
        historyBytes -= estimateHistoryLineBytes(removed.getValue());
      }
    }
  }

  private static long estimateHistoryLineBytes(TerminalLine line) {
    if (line == null) return 0;
    long bytes = 64;
    for (TerminalCell cell : line.cells) {
      if (cell == null) continue;
      bytes += 40;
      if (cell.text != null) bytes += cell.text.length() * 2L;
    }
    return bytes;
  }

  private TerminalLine emptyLine(long id, int cols) {
    TerminalCell[] cells = new TerminalCell[cols];
    java.util.Arrays.fill(cells, TerminalCell.EMPTY);
    return new TerminalLine(id, false, cells);
  }

  private void publishRenderSnapshot(boolean historyChanged, boolean stylesChanged,
                                     boolean linksChanged) {
    RenderSnapshot previous = renderSnapshot;
    TerminalLine[] screenCopy = screen != null ? screen.clone() : null;
    NavigableMap<Long, TerminalLine> historyCopy = historyChanged
        ? Collections.unmodifiableNavigableMap(new TreeMap<>(historyCache))
        : previous.history;
    List<TerminalLine> historyLines = historyChanged
        ? Collections.unmodifiableList(new ArrayList<>(historyCache.values()))
        : previous.historyLines;
    Map<Integer, TerminalStyle> stylesCopy = stylesChanged
        ? Collections.unmodifiableMap(new HashMap<>(styles))
        : previous.styles;
    Map<Integer, Hyperlink> linksCopy = linksChanged
        ? Collections.unmodifiableMap(new HashMap<>(links))
        : previous.links;
    renderSnapshot = new RenderSnapshot(instanceId, layoutEpoch, screenRevision, rows, columns,
        activeBuffer, screenCopy, historyCopy, historyLines, cursor, modes, palette, stylesCopy, linksCopy,
        title, workingDirectory, firstAvailableHistoryId, hasMoreHistoryBefore);
  }

  /**
   * Immutable render data. TerminalLine, TerminalCell, style and palette
   * values are immutable; maps and the screen array are copied at publication
   * time, never by the View's draw loop.
   */
  public static final class RenderSnapshot {
    public final String instanceId;
    public final long layoutEpoch;
    public final long screenRevision;
    public final int rows;
    public final int columns;
    public final ScreenSnapshot.BufferKind activeBuffer;
    public final TerminalLine[] screen;
    public final NavigableMap<Long, TerminalLine> history;
    /** Ordered oldest-to-newest history for indexed rendering. */
    public final List<TerminalLine> historyLines;
    public final TerminalCursor cursor;
    public final TerminalModes modes;
    public final TerminalPalette palette;
    public final Map<Integer, TerminalStyle> styles;
    public final Map<Integer, Hyperlink> links;
    public final String title;
    public final String workingDirectory;
    public final long firstAvailableHistoryId;
    public final boolean hasMoreHistoryBefore;

    private RenderSnapshot(String instanceId, long layoutEpoch, long screenRevision, int rows,
                           int columns, ScreenSnapshot.BufferKind activeBuffer,
                           TerminalLine[] screen, NavigableMap<Long, TerminalLine> history,
                           List<TerminalLine> historyLines,
                           TerminalCursor cursor, TerminalModes modes, TerminalPalette palette,
                           Map<Integer, TerminalStyle> styles, Map<Integer, Hyperlink> links,
                           String title, String workingDirectory, long firstAvailableHistoryId,
                           boolean hasMoreHistoryBefore) {
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.screenRevision = screenRevision;
      this.rows = rows;
      this.columns = columns;
      this.activeBuffer = activeBuffer;
      this.screen = screen;
      this.history = history;
      this.historyLines = historyLines;
      this.cursor = cursor;
      this.modes = modes;
      this.palette = palette;
      this.styles = styles;
      this.links = links;
      this.title = title;
      this.workingDirectory = workingDirectory;
      this.firstAvailableHistoryId = firstAvailableHistoryId;
      this.hasMoreHistoryBefore = hasMoreHistoryBefore;
    }

    private static RenderSnapshot empty() {
      return new RenderSnapshot(null, 0, 0, 0, 0, ScreenSnapshot.BufferKind.MAIN, null,
          Collections.emptyNavigableMap(), Collections.emptyList(), TerminalCursor.hidden(), TerminalModes.defaults(),
          TerminalPalette.defaults(), Collections.emptyMap(), Collections.emptyMap(), "", "", 0,
          false);
    }
  }

  public static final class RevisionGapException extends Exception {
    public RevisionGapException(String message) {
      super(message);
    }
  }
}
