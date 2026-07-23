package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Android 远程终端模型。只维护投影和缓存，可由 Go 权威快照重建。
 */
public final class RemoteTerminalModel {

  public static final long SCHEMA_GENERATION = 2L;
  // 历史容量是双上限：行数是安全上限，字节是近似内存预算（estimateHistoryLineBytes），
  // 先达到者触发驱逐。保留行数随列宽和内容变化（80 列文本行约 5–6KB 估算），
  // 产品和注释都不承诺固定保留行数。默认值可由 HistoryBudget 按设备内存分档覆盖。

  public String instanceId;
  public long layoutEpoch;
  public long screenRevision;
  public int rows;
  public int columns;
  public TerminalBufferKind activeBuffer;

  private final PagedTerminalHistory pagedHistory;
  private boolean v2Projection;
  private long streamGeneration;
  private HistoryExtent displayExtent = HistoryExtent.INITIAL_EMPTY;
  private HistoryExtent remoteAvailableExtent = HistoryExtent.INITIAL_EMPTY;
  private boolean staleProjection;
  private TerminalLine[] screen;
  /** 当前 layout 与缓存历史引用的不可变行内容。 */
  private final Map<Long, TerminalLine> lineStore = new HashMap<>();

  private long firstAvailableHistorySeq;
  private boolean hasMoreHistoryBefore;
  private TerminalCursor cursor = TerminalCursor.hidden();
  private TerminalModes modes = TerminalModes.defaults();
  private TerminalPalette palette = TerminalPalette.defaults();
  private String title = "";
  private String workingDirectory = "";

  /**
   * Published only after a complete model mutation. Canvas runs on the main
   * thread and reads this immutable view without copying the full history map
   * on every frame.
   */
  private volatile RenderSnapshot renderSnapshot = RenderSnapshot.empty();
  /** 仅由模型事务写入、由 VSync 原子交换出去的渲染真相。 */
  private RenderDirtyState pendingRenderDirty = new RenderDirtyState();
  private TerminalStateUpdate pendingTerminalState = new TerminalStateUpdate();
  private boolean renderPublicationPending;
  private volatile ProjectionHealth projectionHealth =
      ProjectionHealth.incomplete(SCHEMA_GENERATION);

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
    this.activeBuffer = TerminalBufferKind.MAIN;
    this.pagedHistory = new PagedTerminalHistory(budget, RemoteTerminalModel::estimateHistoryLineBytes);
  }

  public synchronized boolean applyBaseline(ScreenBaseline baseline) {
    if (baseline == null || baseline.instanceId == null || baseline.instanceId.isEmpty()
        || baseline.layoutEpoch < 1 || baseline.screenRevision < 1
        || baseline.streamGeneration < 1 || baseline.streamGeneration < streamGeneration
        || baseline.rows <= 0 || baseline.cols <= 0
        || baseline.historyExtent == null || baseline.historyTail == null
        || baseline.historyTail.size() > PagedTerminalHistory.PAGE_SIZE
        || baseline.screen == null || baseline.screen.size() != baseline.rows) {
      return false;
    }
    java.util.HashSet<Long> baselineLineIds = new java.util.HashSet<>();
    long previousHistorySeq = 0;
    for (TerminalLine line : baseline.historyTail) {
      if (line == null || line.id <= 0 || line.historySeq <= previousHistorySeq
          || !baseline.historyExtent.contains(line.historySeq)
          || !baselineLineIds.add(line.id)) return false;
      previousHistorySeq = line.historySeq;
    }
    for (TerminalLine line : baseline.screen) {
      if (line == null || line.id <= 0 || line.historySeq != 0
          || !baselineLineIds.add(line.id)) return false;
    }
    boolean sameProjection = v2Projection
        && baseline.instanceId.equals(instanceId)
        && baseline.layoutEpoch == layoutEpoch;
    boolean geometryChanged = !sameProjection || rows != baseline.rows || columns != baseline.cols;

    PagedTerminalHistory.Editor historyEditor = pagedHistory.edit();
    if (!sameProjection) {
      historyEditor.setExtent(1, 0);
    }
    historyEditor.setExtent(baseline.historyExtent.firstSeq, baseline.historyExtent.lastSeq);
    List<TerminalLine> normalizedHistoryTail = new ArrayList<>();
    for (TerminalLine line : baseline.historyTail) {
      TerminalLine normalized = padOrCopyLine(line, baseline.cols);
      if (baseline.historyExtent.contains(normalized.historySeq)) {
        historyEditor.put(normalized.historySeq, normalized);
        normalizedHistoryTail.add(normalized);
      }
    }
    historyEditor.evictIfNeeded(
        baseline.historyExtent.isEmpty() ? 1 : baseline.historyExtent.lastSeq).commit();
    if (!sameProjection) lineStore.clear();
    for (TerminalLine line : normalizedHistoryTail) lineStore.put(line.id, line);

    this.v2Projection = true;
    this.streamGeneration = baseline.streamGeneration;
    this.instanceId = baseline.instanceId;
    this.layoutEpoch = baseline.layoutEpoch;
    this.screenRevision = baseline.screenRevision;
    this.rows = baseline.rows;
    this.columns = baseline.cols;
    this.activeBuffer = baseline.activeBuffer;
    this.displayExtent = baseline.historyExtent;
    this.remoteAvailableExtent = baseline.historyExtent;
    this.staleProjection = false;
    this.cursor = baseline.cursor != null ? baseline.cursor : TerminalCursor.hidden();
    this.modes = baseline.modes != null ? baseline.modes : TerminalModes.defaults();
    this.palette = baseline.palette != null ? baseline.palette : TerminalPalette.defaults();
    this.title = baseline.title != null ? baseline.title : "";
    this.workingDirectory = baseline.workingDirectory != null ? baseline.workingDirectory : "";
    this.firstAvailableHistorySeq = baseline.historyExtent.firstSeq;
    this.hasMoreHistoryBefore = false;

    this.screen = new TerminalLine[rows];
    for (int row = 0; row < rows; row++) {
      TerminalLine line = padOrCopyLine(baseline.screen.get(row), columns);
      this.screen[row] = line;
      lineStore.put(line.id, line);
    }
    pruneLineStore();
    markRenderDirty(true, null, true, geometryChanged, true, -1, cursor.row,
        true, true, true, true, true);
    markTerminalState(geometryChanged, true, true, true, 0, 0);
    projectionHealth = projectionIsStructurallyComplete()
        ? ProjectionHealth.complete(instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION)
        : ProjectionHealth.incomplete(SCHEMA_GENERATION);
    return projectionHealth.complete;
  }

  public synchronized void applyScreenPatch(ScreenPatchV2 patch) throws RevisionGapException {
    if (!v2Projection || patch == null || patch.streamGeneration != streamGeneration
        || instanceId == null || !instanceId.equals(patch.instanceId)
        || layoutEpoch != patch.layoutEpoch || screenRevision != patch.baseRevision
        || patch.screenRevision <= patch.baseRevision) {
      throw new RevisionGapException("screen.v2 patch identity/revision mismatch");
    }
    if (patch.layout != null && patch.layout.length != rows) {
      throw new RevisionGapException("screen.v2 layout length mismatch");
    }
    Map<Long, TerminalLine> stagedLines = new HashMap<>();
    for (TerminalLine line : patch.lineUpdates) {
      TerminalLine normalized = padOrCopyLine(line, columns);
      if (normalized.id <= 0 || normalized.historySeq != 0) {
        throw new RevisionGapException("screen.v2 patch contains invalid screen line");
      }
      TerminalLine previous = lineStore.get(normalized.id);
      if (previous != null && normalized.version < previous.version) {
        throw new RevisionGapException("screen.v2 line version regressed");
      }
      if (stagedLines.put(normalized.id, normalized) != null) {
        throw new RevisionGapException("screen.v2 patch repeats line id");
      }
    }
    if (patch.layout != null) {
      Set<Long> layoutIds = new HashSet<>();
      for (long id : patch.layout) {
        if (id <= 0 || !layoutIds.add(id)
            || (!stagedLines.containsKey(id) && !lineStore.containsKey(id))) {
          throw new RevisionGapException("screen.v2 layout line missing or repeated");
        }
      }
    }
    lineStore.putAll(stagedLines);
    BitSet changedRows = new BitSet(rows);
    if (patch.layout != null) {
      for (int row = 0; row < rows; row++) {
        TerminalLine next = lineStore.get(patch.layout[row]);
        if (next == null) throw new RevisionGapException("screen.v2 layout line missing");
        if (screen[row] != next) changedRows.set(row);
        screen[row] = next;
      }
    } else {
      for (int row = 0; row < rows; row++) {
        TerminalLine next = lineStore.get(screen[row].id);
        if (next != null && next != screen[row]) {
          screen[row] = next;
          changedRows.set(row);
        }
      }
    }
    TerminalCursor previousCursor = cursor;
    TerminalPalette previousPalette = palette;
    TerminalModes previousModes = modes;
    TerminalBufferKind previousBuffer = activeBuffer;
    String previousTitle = title;
    String previousCwd = workingDirectory;
    if (patch.cursor != null) cursor = patch.cursor;
    if (patch.palette != null) palette = patch.palette;
    if (patch.modes != null) modes = patch.modes;
    if (patch.activeBuffer != null) activeBuffer = patch.activeBuffer;
    if (patch.title != null) title = patch.title;
    if (patch.workingDirectory != null) workingDirectory = patch.workingDirectory;
    screenRevision = patch.screenRevision;
    pruneLineStore();
    markRenderDirty(false, changedRows, false, false,
        !Objects.equals(previousCursor, cursor),
        previousCursor != null ? previousCursor.row : -1, cursor.row,
        !Objects.equals(previousPalette, palette), false, false,
        !Objects.equals(previousModes, modes), previousBuffer != activeBuffer);
    markTerminalState(false, false, !Objects.equals(previousTitle, title),
        !Objects.equals(previousCwd, workingDirectory), 0, 0);
    projectionHealth = projectionIsStructurallyComplete()
        ? ProjectionHealth.complete(instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION)
        : ProjectionHealth.incomplete(SCHEMA_GENERATION);
  }

  public synchronized boolean applyHistoryDelta(HistoryDelta delta) {
    if (!v2Projection || delta == null || delta.streamGeneration != streamGeneration
        || !Objects.equals(instanceId, delta.instanceId) || layoutEpoch != delta.layoutEpoch) {
      return false;
    }
    HistoryExtent nextExtent = delta.availableExtent;
    PagedTerminalHistory.Editor editor = pagedHistory.edit()
        .setExtent(nextExtent.firstSeq, nextExtent.lastSeq);
    List<TerminalLine> acceptedLines = new ArrayList<>();
    for (TerminalLine line : delta.lines) {
      TerminalLine normalized = padOrCopyLine(line, columns);
      if (nextExtent.contains(normalized.historySeq)) {
        editor.put(normalized.historySeq, normalized);
        acceptedLines.add(normalized);
      }
    }
    editor.evictIfNeeded(nextExtent.isEmpty() ? 1 : nextExtent.lastSeq).commit();
    for (TerminalLine line : acceptedLines) lineStore.put(line.id, line);
    remoteAvailableExtent = nextExtent;
    displayExtent = nextExtent;
    firstAvailableHistorySeq = displayExtent.firstSeq;
    pruneLineStore();
    markRenderDirty(false, null, true, false, false, -1, -1,
        false, false, false, false, false);
    markTerminalState(false, true, false, false, 0, 0);
    return true;
  }

  public synchronized boolean applyHistoryRange(HistoryRangeResult range, long anchorSeq) {
    return applyHistoryRange(range, anchorSeq,
        range != null && !range.lines.isEmpty() ? range.lines.get(0).historySeq : 1,
        range != null && !range.lines.isEmpty()
            ? range.lines.get(range.lines.size() - 1).historySeq : 0);
  }

  public synchronized boolean applyHistoryRange(
      HistoryRangeResult range, long anchorSeq, long requestedFromSeq, long requestedToSeq) {
    if (!v2Projection || range == null || !Objects.equals(instanceId, range.instanceId)
        || layoutEpoch != range.layoutEpoch) {
      return false;
    }
    if (range.status == HistoryRangeResult.Status.STALE_PROJECTION) {
      remoteAvailableExtent = range.availableExtent;
      staleProjection = true;
      return false;
    }
    if (range.status == HistoryRangeResult.Status.RETRYABLE) return false;
    PagedTerminalHistory.Editor editor = pagedHistory.edit();
    // 对请求区间中已经超出服务端权威 extent 的槽位作永久不可用标记。可见页驱动
    // 只请求 UNLOADED，因此 OK/TRIMMED 空交集都不会形成重复热循环。
    if (requestedFromSeq <= requestedToSeq && !displayExtent.isEmpty()) {
      if (range.availableExtent.isEmpty()) {
        editor.markUnavailable(requestedFromSeq, requestedToSeq);
      } else {
        if (requestedFromSeq < range.availableExtent.firstSeq) {
          editor.markUnavailable(requestedFromSeq,
              Math.min(requestedToSeq, range.availableExtent.firstSeq - 1));
        }
        if (requestedToSeq > range.availableExtent.lastSeq) {
          editor.markUnavailable(Math.max(requestedFromSeq, range.availableExtent.lastSeq + 1),
              requestedToSeq);
        }
      }
    }
    List<TerminalLine> acceptedLines = new ArrayList<>();
    for (TerminalLine line : range.lines) {
      TerminalLine normalized = padOrCopyLine(line, columns);
      if (displayExtent.contains(normalized.historySeq)) {
        editor.put(normalized.historySeq, normalized);
        acceptedLines.add(normalized);
      }
    }
    editor.evictIfNeeded(anchorSeq > 0 ? anchorSeq : displayExtent.lastSeq).commit();
    for (TerminalLine line : acceptedLines) lineStore.put(line.id, line);
    remoteAvailableExtent = range.availableExtent;
    pruneLineStore();
    markRenderDirty(false, null, true, false, false, -1, -1,
        false, false, false, false, false);
    markTerminalState(false, true, false, false, 0, 0);
    return true;
  }

  public synchronized long streamGeneration() {
    return streamGeneration;
  }

  public synchronized boolean isV2Projection() {
    return v2Projection;
  }

  public synchronized HistoryExtent displayExtent() {
    return displayExtent;
  }

  public synchronized HistoryExtent remoteAvailableExtent() {
    return remoteAvailableExtent;
  }

  public synchronized boolean staleProjection() {
    return staleProjection;
  }

  /** FROZEN 模式只更新远端水位，不改变当前显示 extent 或 screen revision。 */
  public synchronized boolean observeTailStatus(String instanceId, long layoutEpoch,
                                                HistoryExtent latestExtent) {
    if (!v2Projection || latestExtent == null || this.instanceId == null
        || !this.instanceId.equals(instanceId) || this.layoutEpoch != layoutEpoch) {
      return false;
    }
    remoteAvailableExtent = latestExtent;
    return true;
  }

  /** 只能在 model executor 的完整事务边界读取，返回不可变快照。 */
  public synchronized ProjectionHealth projectionHealth() {
    return projectionHealth;
  }


  private boolean projectionIsStructurallyComplete() {
    if (instanceId == null || instanceId.isEmpty() || layoutEpoch < 1 || screenRevision < 1
        || rows <= 0 || columns <= 0 || screen == null || screen.length != rows) {
      return false;
    }
    for (TerminalLine line : screen) {
      if (line == null || line.length() != columns || !referencesComplete(line)) return false;
    }
    TerminalHistoryView historySnapshot = pagedHistory.snapshot();
    for (int i = 0; i < historySnapshot.size(); i++) {
      TerminalLine line = historySnapshot.lineAt(i);
      if (line != null && !referencesComplete(line)) return false;
    }
    return true;
  }

  private boolean referencesComplete(TerminalLine line) {
    for (int i = 0; i < line.length(); i++) {
      if (line.at(i) == null) return false;
    }
    return true;
  }

  public synchronized RenderSnapshot renderSnapshot() {
    if (renderPublicationPending) publishRenderSnapshot(pendingRenderDirty);
    return renderSnapshot;
  }

  public synchronized RenderSnapshot peekRenderSnapshot() {
    return renderSnapshot;
  }

  public synchronized RenderUpdate consumeRenderUpdate() {
    if (!renderPublicationPending
        || (pendingRenderDirty.isEmpty() && pendingTerminalState.isEmpty())) return null;
    RenderDirtyState consumed = pendingRenderDirty;
    TerminalStateUpdate consumedState = pendingTerminalState;
    pendingRenderDirty = new RenderDirtyState();
    pendingTerminalState = new TerminalStateUpdate();
    renderPublicationPending = false;
    publishRenderSnapshot(consumed);
    return new RenderUpdate(renderSnapshot, consumed, consumedState);
  }

  public synchronized void requestFullRender() {
    markRenderDirty(true, null, false, false, false, -1, -1,
        false, false, false, false, false);
  }

  public synchronized int historySize() {
    return pagedHistory.snapshot().size();
  }

  public synchronized long firstCachedHistorySeq() {
    return pagedHistory.snapshot().firstLoadedSeq();
  }

  public synchronized long historyBytes() {
    return pagedHistory.snapshot().estimatedByteCount();
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

  public synchronized String title() {
    return title;
  }

  public synchronized String workingDirectory() {
    return workingDirectory;
  }

  public synchronized long firstAvailableHistorySeq() {
    return firstAvailableHistorySeq;
  }

  public synchronized boolean hasMoreHistoryBefore() {
    return hasMoreHistoryBefore;
  }


  private int findRow(long row) {
    if (row >= 0 && row < screen.length) {
      return (int) row;
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
    return new TerminalLine(
        line.id, line.version, line.historySeq, line.wrapped, cells);
  }

  /** 只保留活动 Layout 或 Android 历史缓存仍引用的行，防止 LineStore 无界增长。 */
  private void pruneLineStore() {
    java.util.HashSet<Long> retained = new java.util.HashSet<>();
    if (screen != null) for (TerminalLine line : screen) if (line != null) retained.add(line.id);
    TerminalHistoryView snapshot = pagedHistory.snapshot();
    for (int i = 0; i < snapshot.size(); i++) {
      TerminalLine line = snapshot.lineAt(i);
      if (line != null) retained.add(line.id);
    }
    java.util.Iterator<Long> it = lineStore.keySet().iterator();
    while (it.hasNext()) if (!retained.contains(it.next())) it.remove();
  }

  /**
   * 单行近似字节（JVM 口径，HotSpot 17 + compressed oops）：
   *   - 48B 基线 = TerminalLine 对象（32B）+ cells 数组头（16B）。
   *     旧 TreeMap 实现中 112B 还包含 TreeMap.Entry（40B）+ Long key（24B）共 64B
   *     映射开销；分页缓存下这部分开销几乎为 0，因此基线下调。
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

  private void markRenderDirty(boolean fullInvalidate, BitSet changedRows, boolean historyChanged,
                               boolean geometryChanged, boolean cursorChanged,
                               int previousCursorRow, int currentCursorRow,
                               boolean paletteChanged, boolean stylesChanged,
                               boolean linksChanged, boolean modesChanged,
                               boolean activeBufferChanged) {
    pendingRenderDirty.merge(fullInvalidate, changedRows, historyChanged, geometryChanged,
        cursorChanged, previousCursorRow, currentCursorRow, paletteChanged, stylesChanged,
        linksChanged, modesChanged, activeBufferChanged);
    renderPublicationPending = !pendingRenderDirty.isEmpty();
  }

  private void markTerminalState(boolean geometryChanged, boolean historyChanged,
                                 boolean titleChanged, boolean workingDirectoryChanged,
                                 int tailAppendedLines, int historyPrependedLines) {
    pendingTerminalState.merge(geometryChanged, historyChanged, titleChanged,
        workingDirectoryChanged, tailAppendedLines, historyPrependedLines);
    renderPublicationPending = true;
  }

  /** 保留给性能基线的反射入口；正式路径只在 consumeRenderUpdate 时发布。 */
  @SuppressWarnings("unused")
  private synchronized void publishRenderSnapshot(boolean historyChanged, boolean stylesChanged,
                                                  boolean linksChanged) {
    RenderDirtyState dirty = new RenderDirtyState();
    dirty.merge(false, null, historyChanged, false, false, -1, -1,
        false, stylesChanged, linksChanged, false, false);
    publishRenderSnapshot(dirty);
  }

  private void publishRenderSnapshot(RenderDirtyState dirty) {
    RenderSnapshot previous = renderSnapshot;
    // TerminalLine is immutable. Metadata-only and history-only patches therefore safely reuse
    // the previous screen array rather than cloning rows that did not change.
    boolean screenChanged = dirty.fullInvalidate || dirty.geometryChanged
        || dirty.activeBufferChanged || !dirty.changedScreenRows.isEmpty();
    TerminalLine[] screenCopy = screenChanged && screen != null ? screen.clone() : previous.screen;
    TerminalHistoryView historySnapshot = dirty.historyChanged || dirty.fullInvalidate
        ? pagedHistory.snapshot()
        : previous.history;
    renderSnapshot = new RenderSnapshot(instanceId, layoutEpoch, screenRevision, rows, columns,
        activeBuffer, screenCopy, historySnapshot, cursor, modes, palette,
        title, workingDirectory, firstAvailableHistorySeq,
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
    public final TerminalBufferKind activeBuffer;
    public final TerminalLine[] screen;
    /** Segmented immutable history snapshot for indexed rendering. */
    public final TerminalHistoryView history;
    public final TerminalCursor cursor;
    public final TerminalModes modes;
    public final TerminalPalette palette;
    public final String title;
    public final String workingDirectory;
    public final long firstAvailableHistorySeq;
    public final boolean hasMoreHistoryBefore;

    private RenderSnapshot(String instanceId, long layoutEpoch, long screenRevision, int rows,
                           int columns, TerminalBufferKind activeBuffer,
                           TerminalLine[] screen, TerminalHistoryView history,
                           TerminalCursor cursor, TerminalModes modes, TerminalPalette palette,
                           String title, String workingDirectory, long firstAvailableHistorySeq,
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
      this.title = title;
      this.workingDirectory = workingDirectory;
      this.firstAvailableHistorySeq = firstAvailableHistorySeq;
      this.hasMoreHistoryBefore = hasMoreHistoryBefore;
    }

    private static RenderSnapshot empty() {
      return new RenderSnapshot(null, 0, 0, 0, 0, TerminalBufferKind.MAIN, null,
          TerminalHistorySnapshot.empty(), TerminalCursor.hidden(),
          TerminalModes.defaults(), TerminalPalette.defaults(),
          "", "", 0, false);
    }
  }

  public static final class RevisionGapException extends Exception {
    public RevisionGapException(String message) {
      super(message);
    }
  }
}
