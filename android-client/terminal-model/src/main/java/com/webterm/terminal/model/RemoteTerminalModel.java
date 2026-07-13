package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android 远程终端模型。只维护投影和缓存，可由 Go 权威快照重建。
 */
public final class RemoteTerminalModel {
  // 历史容量是双上限：行数是安全上限，字节是近似内存预算（estimateHistoryLineBytes），
  // 先达到者触发驱逐。保留行数随列宽和内容变化（80 列文本行约 5–6KB 估算），
  // 产品和注释都不承诺固定保留行数。默认值可由 HistoryBudget 按设备内存分档覆盖。

  public String instanceId;
  public long layoutEpoch;
  public long screenRevision;
  public int rows;
  public int columns;
  public ScreenSnapshot.BufferKind activeBuffer;

  private final TerminalHistory history;
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
    this(HistoryBudget.defaults());
  }

  public RemoteTerminalModel(int softHistoryLimit, int hardHistoryLimit) {
    this(softHistoryLimit, hardHistoryLimit, HistoryBudget.DEFAULT_SOFT_BYTES,
        HistoryBudget.DEFAULT_HARD_BYTES);
  }

  public RemoteTerminalModel(int softHistoryLimit, int hardHistoryLimit,
                             long softHistoryByteLimit, long hardHistoryByteLimit) {
    this(new HistoryBudget(softHistoryLimit, hardHistoryLimit,
        softHistoryByteLimit, hardHistoryByteLimit));
  }

  public RemoteTerminalModel(HistoryBudget budget) {
    this.softHistoryLimit = budget.softLines;
    this.hardHistoryLimit = budget.hardLines;
    this.softHistoryByteLimit = budget.softBytes;
    this.hardHistoryByteLimit = budget.hardBytes;
    this.activeBuffer = ScreenSnapshot.BufferKind.MAIN;
    this.history = new TerminalHistory(RemoteTerminalModel::estimateHistoryLineBytes);
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

    this.history.clear();
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
    int tailAppendedLines = 0;

    for (TerminalLine line : patch.screenRows) {
      int row = findRow(line.id);
      if (row >= 0 && row < screen.length) {
        screen[row] = padOrCopyLine(line, columns);
        changedRows.add(row);
      }
    }

    for (TerminalLine line : patch.historyAppend) {
      if (line.id != 0) {
        if (putHistoryLine(line)) tailAppendedLines++;
      }
    }

    for (ScreenPatch.PromotedRow promoted : patch.promotedRows) {
      if (promoted.screenRow >= 0 && promoted.screenRow < screen.length) {
        TerminalLine promotedLine = screen[promoted.screenRow];
        if (promotedLine != null && promoted.historyLineId != 0) {
          if (putHistoryLine(promotedLine.withId(promoted.historyLineId))) {
            tailAppendedLines++;
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
        tailAppendedLines,
        0
    );
  }

  public synchronized ModelChange prependHistoryPage(HistoryPage page) {
    if (layoutEpoch != page.layoutEpoch) {
      return ModelChange.none();
    }
    int historySizeBefore = history.size();
    // 可见锚点：prepend 前缓存中最旧的行。用户向上翻页时视口钉在已缓存内容上，
    // 该行即翻页前可见窗口的顶部边界；驱逐必须保护它和刚加载的新页。
    long anchorId = history.isEmpty() ? -1 : history.firstLineId();
    List<TerminalLine> toPrepend = new ArrayList<>();
    for (TerminalLine line : page.lines) {
      if (line.id != 0 && history.findLineIndex(line.id) < 0) {
        toPrepend.add(line);
      }
    }
    history.prepend(toPrepend);
    historyBytes += estimateBytes(toPrepend);
    styles.putAll(page.styles);
    links.putAll(page.links);
    this.firstAvailableHistoryId = page.firstAvailableLineId;
    this.hasMoreHistoryBefore = page.hasMoreBefore;
    if (anchorId < 0 && !history.isEmpty()) {
      // prepend 前缓存为空：可见锚点是实时屏幕，保护新页中靠近屏幕的一侧。
      anchorId = history.lastLineId();
    }
    evictHistoryAfterPrepend(anchorId);
    // The new rows are physically inserted above all cached rows. In the
    // bottom-anchored renderer geometry historyRows and every old row's index
    // grow by the same amount, so old lines keep their screen Y with zero
    // viewport offset compensation. This count is reported separately from
    // tail appends and must never drive offset adjustments.
    int insertedHistoryLines = Math.max(0, history.size() - historySizeBefore);
    publishRenderSnapshot(true, !page.styles.isEmpty(), !page.links.isEmpty());
    return new ModelChange(false, null, true, false, false, false, 0, insertedHistoryLines);
  }

  public synchronized ModelChange trimHistory(long trimLayoutEpoch, long firstAvailableLineId) {
    if (layoutEpoch != trimLayoutEpoch) return ModelChange.none();
    this.firstAvailableHistoryId = firstAvailableLineId;
    history.trimHeadUntil(firstAvailableLineId);
    recalculateHistoryBytes();
    publishRenderSnapshot(true, false, false);
    return new ModelChange(false, null, true, false, false, false);
  }

  public synchronized void resetForReconnect() {
    instanceId = null;
    layoutEpoch = 0;
    screenRevision = 0;
    screen = null;
    history.clear();
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

  /** 历史行数。不需要拷贝全部历史。 */
  public synchronized int historySize() {
    return history.size();
  }

  /** 本地缓存最旧行 id；缓存为空时返回 -1。分页请求用它作 beforeId。 */
  public synchronized long firstCachedHistoryId() {
    return history.firstLineId();
  }

  /** 本地缓存最新行 id；缓存为空时返回 -1。 */
  public synchronized long lastCachedHistoryId() {
    return history.lastLineId();
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

  /**
   * 插入或替换一行历史。普通 tail append 走 O(1) 快速路径；
   * 非递增 id（replace/resync）走 O(log n) 查找路径。
   */
  private boolean putHistoryLine(TerminalLine line) {
    TerminalLine normalized = padOrCopyLine(line, columns);
    if (!history.isEmpty() && normalized.id <= history.lastLineId()) {
      boolean inserted = history.put(normalized);
      if (inserted) {
        historyBytes += estimateHistoryLineBytes(normalized);
      } else {
        // replace: bytes updated inside TerminalHistory, recalibrate our external counter
        recalculateHistoryBytes();
      }
      return inserted;
    }
    history.append(normalized);
    historyBytes += estimateHistoryLineBytes(normalized);
    return true;
  }

  private long estimateBytes(List<TerminalLine> lines) {
    long sum = 0;
    for (TerminalLine line : lines) {
      sum += estimateHistoryLineBytes(line);
    }
    return sum;
  }

  private void recalculateHistoryBytes() {
    historyBytes = history.estimatedBytes();
  }

  /**
   * 实时路径（snapshot 替换 / tail append）的驱逐：超 hard 上限后从最旧端驱逐到 soft 目标。
   * 实时输出只关心新内容，丢弃最旧历史对用户不可见。
   */
  private void evictHistoryIfNeeded() {
    if (!overHardLimits()) {
      return;
    }
    int targetLines = Math.max(softHistoryLimit, hardHistoryLimit / 2);
    long targetBytes = softHistoryByteLimit > 0 ? softHistoryByteLimit : Long.MAX_VALUE;
    history.trimHeadToBudget(targetLines, targetBytes);
    recalculateHistoryBytes();
  }

  /**
   * 历史分页后的驱逐：保护刚 prepend 的页和可见锚点，必要时从较新的历史端
   * （靠近屏幕一侧）驱逐，绝不把刚加载的页立即删掉导致重复请求同一页。
   *
   * anchorId 为 prepend 前缓存的最旧行 id（缓存为空时取新页最新行，代表实时屏幕一侧）。
   * 新页所有行 id 都小于 anchorId，因此「id <= anchorId」天然覆盖新页、锚点和全部
   * 更旧历史。驱逐顺序：
   *   1. 从较新端驱逐所有 id > anchorId 的行——用户翻旧页时这些靠近屏幕的行不在
   *      视口内；缓存始终保持连续 id 区间，不产生空洞。
   *   2. 预算连「锚点 + 新页」都装不下时，退化为丢弃新页最旧部分，保留靠近锚点
   *      （当前可见）的行；锚点本身永不驱逐，宁可暂时超预算。
   */
  private void evictHistoryAfterPrepend(long anchorId) {
    if (!overHardLimits() || anchorId < 0) {
      return;
    }
    int targetLines = Math.max(softHistoryLimit, hardHistoryLimit / 2);
    long targetBytes = softHistoryByteLimit > 0 ? softHistoryByteLimit : Long.MAX_VALUE;
    history.trimTailToBudget(targetLines, targetBytes, anchorId);
    recalculateHistoryBytes();
  }

  private boolean overHardLimits() {
    return history.size() > hardHistoryLimit
        || (hardHistoryByteLimit > 0 && historyBytes > hardHistoryByteLimit);
  }

  private boolean overSoftTargets() {
    int targetLines = Math.max(softHistoryLimit, hardHistoryLimit / 2);
    long targetBytes = softHistoryByteLimit > 0 ? softHistoryByteLimit : Long.MAX_VALUE;
    return history.size() > targetLines || historyBytes > targetBytes;
  }

  /**
   * 单行近似字节（JVM 口径，HotSpot 17 + compressed oops）：
   *   - 48B 基线 = TerminalLine 对象（32B）+ cells 数组头（16B）。
   *     旧 TreeMap 实现中 112B 还包含 TreeMap.Entry（40B）+ Long key（24B）共 64B
   *     映射开销；TerminalHistory 分段结构下这部分开销几乎为 0，因此基线下调。
   *   - 每 cell 4B 数组槽 + 64B 对象开销（TerminalCell 32B + String 24B + 对齐）；
   *   - 文本按 UTF-16 2B/char 计（LATIN1 字符串实际约 1B/char，略有高估）。
   * representative 样本实测（80/200 列 ASCII/宽字符/多样式，见
   * RemoteTerminalModelHistoryBudgetTest）：估算约为实测保留量的 0.8–1.5 倍，
   * 文本密集型行不明显低估；空白填充行高估，可接受。
   * 对象布局与 Go 侧不同，两侧各自校准，不要求数值一致。
   */
  private static long estimateHistoryLineBytes(TerminalLine line) {
    if (line == null) return 0;
    long bytes = 48 + line.cells.length * 4L;
    for (TerminalCell cell : line.cells) {
      if (cell == null) continue;
      bytes += 64;
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
    TerminalHistorySnapshot historySnapshot = historyChanged
        ? history.snapshot()
        : previous.history;
    Map<Integer, TerminalStyle> stylesCopy = stylesChanged
        ? Collections.unmodifiableMap(new HashMap<>(styles))
        : previous.styles;
    Map<Integer, Hyperlink> linksCopy = linksChanged
        ? Collections.unmodifiableMap(new HashMap<>(links))
        : previous.links;
    renderSnapshot = new RenderSnapshot(instanceId, layoutEpoch, screenRevision, rows, columns,
        activeBuffer, screenCopy, historySnapshot, cursor, modes, palette,
        stylesCopy, linksCopy, title, workingDirectory, firstAvailableHistoryId,
        hasMoreHistoryBefore);
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
    /** Segmented immutable history snapshot for indexed rendering. */
    public final TerminalHistorySnapshot history;
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
                           TerminalLine[] screen, TerminalHistorySnapshot history,
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
          TerminalHistorySnapshot.empty(), TerminalCursor.hidden(),
          TerminalModes.defaults(), TerminalPalette.defaults(),
          Collections.emptyMap(), Collections.emptyMap(), "", "", 0, false);
    }
  }

  public static final class RevisionGapException extends Exception {
    public RevisionGapException(String message) {
      super(message);
    }
  }
}
