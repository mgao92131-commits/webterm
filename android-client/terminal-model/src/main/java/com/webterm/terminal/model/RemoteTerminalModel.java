package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Android 远程终端模型。只维护投影和缓存，可由 Go 权威快照重建。
 */
public final class RemoteTerminalModel {

  public static final long SCHEMA_GENERATION = 2L;
  private static final int MIGRATION_REVISION_WINDOW = 8;
  private static final int META_CURSOR = 1;
  private static final int META_PALETTE = 1 << 1;
  private static final int META_MODES = 1 << 2;
  private static final int META_BUFFER = 1 << 3;
  private static final int META_TITLE = 1 << 4;
  private static final int META_CWD = 1 << 5;
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
  private long remoteScreenRevision;
  private HistoryExtent displayExtent = HistoryExtent.INITIAL_EMPTY;
  private HistoryExtent remoteAvailableExtent = HistoryExtent.INITIAL_EMPTY;
  private boolean staleProjection;
  private TerminalLine[] screen;
  /** 只保存当前实时 screen 可引用的行；历史行完全由 PagedTerminalHistory 所有。 */
  private final Map<Long, TerminalLine> screenLineStore = new HashMap<>();
  /** 仅保留刚离开 screen、等待 HistoryDelta 绑定的行身份；绝不随会话无限增长。 */
  private final LinkedHashMap<Long, PendingHistoryMigration> pendingHistoryMigrations =
      new LinkedHashMap<>();

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
  /** 模型线程发布、UI VSync getAndSet 消费；UI 不获取模型 monitor。 */
  private final AtomicReference<RenderPublication> pendingPublication = new AtomicReference<>();
  /** Runtime 绑定后，禁止任何无 capability 的旁路破坏性消费。 */
  private final AtomicReference<Object> renderPublicationAuthority = new AtomicReference<>();
  private final AtomicLong publicationVersion = new AtomicLong();
  private volatile ProjectionHealth projectionHealth =
      ProjectionHealth.incomplete(SCHEMA_GENERATION);
  /** Runtime/UI 只读的轻量投影视图；完整模型事务结束后一次 volatile 发布。 */
  private volatile ProjectionReadView projectionReadView = ProjectionReadView.empty();
  /** 仅由单元测试注入；生产实例为 null，不在 Patch 热路径执行。 */
  private final LongConsumer baselineHistoryValidationProbe;
  private long patchScreenCloneCount;
  private long patchScreenStoreRebuildCount;
  private long patchMigrationScanCount;

  private static final class PendingHistoryMigration {
    final TerminalLine screenLine;
    final long removedAtScreenRevision;

    PendingHistoryMigration(TerminalLine screenLine, long removedAtScreenRevision) {
      this.screenLine = screenLine;
      this.removedAtScreenRevision = removedAtScreenRevision;
    }
  }

  private static final class RenderPublication {
    final long version;
    final RenderUpdate update;

    RenderPublication(long version, RenderUpdate update) {
      this.version = version;
      this.update = update;
    }
  }

  public static final class ProjectionReadView {
    public final String instanceId;
    public final long layoutEpoch;
    public final long screenRevision;
    public final HistoryExtent displayExtent;
    public final HistoryExtent remoteAvailableExtent;
    public final boolean projectionComplete;

    private ProjectionReadView(String instanceId, long layoutEpoch, long screenRevision,
                               HistoryExtent displayExtent,
                               HistoryExtent remoteAvailableExtent,
                               boolean projectionComplete) {
      this.instanceId = instanceId == null ? "" : instanceId;
      this.layoutEpoch = layoutEpoch;
      this.screenRevision = screenRevision;
      this.displayExtent = displayExtent;
      this.remoteAvailableExtent = remoteAvailableExtent;
      this.projectionComplete = projectionComplete;
    }

    private static ProjectionReadView empty() {
      return new ProjectionReadView("", 0, 0, HistoryExtent.INITIAL_EMPTY,
          HistoryExtent.INITIAL_EMPTY, false);
    }
  }

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
    this(budget, null);
  }

  RemoteTerminalModel(HistoryBudget budget, LongConsumer baselineHistoryValidationProbe) {
    this.activeBuffer = TerminalBufferKind.MAIN;
    this.pagedHistory = new PagedTerminalHistory(budget, RemoteTerminalModel::estimateHistoryLineBytes);
    this.baselineHistoryValidationProbe = baselineHistoryValidationProbe;
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
    List<TerminalLine> normalizedHistoryTail = new ArrayList<>(baseline.historyTail.size());
    List<TerminalLine> normalizedScreen = new ArrayList<>(baseline.rows);
    long previousHistorySeq = 0;
    for (TerminalLine line : baseline.historyTail) {
      if (line == null || line.id <= 0 || line.historySeq <= previousHistorySeq
          || !baseline.historyExtent.contains(line.historySeq)
          || !baselineLineIds.add(line.id)) return false;
      TerminalLine normalized = normalizeCompleteLine(line, baseline.cols);
      if (normalized == null) return false;
      if (baselineHistoryValidationProbe != null) {
        baselineHistoryValidationProbe.accept(normalized.historySeq);
      }
      normalizedHistoryTail.add(normalized);
      previousHistorySeq = line.historySeq;
    }
    for (TerminalLine line : baseline.screen) {
      if (line == null || line.id <= 0 || line.historySeq != 0
          || !baselineLineIds.add(line.id)) return false;
      TerminalLine normalized = normalizeCompleteLine(line, baseline.cols);
      if (normalized == null) return false;
      normalizedScreen.add(normalized);
    }
    boolean sameProjection = v2Projection
        && baseline.instanceId.equals(instanceId)
        && baseline.layoutEpoch == layoutEpoch;
    boolean geometryChanged = !sameProjection || rows != baseline.rows || columns != baseline.cols;

    PagedTerminalHistory.Editor historyEditor = pagedHistory.edit();
    try {
      if (!sameProjection) {
        historyEditor.setExtent(1, 0);
      }
      historyEditor.setExtent(baseline.historyExtent.firstSeq, baseline.historyExtent.lastSeq);
      historyEditor.setAvailableExtent(
          baseline.historyExtent.firstSeq, baseline.historyExtent.lastSeq);
      for (TerminalLine normalized : normalizedHistoryTail) {
        if (baseline.historyExtent.contains(normalized.historySeq)) {
          historyEditor.put(normalized.historySeq, normalized);
        }
      }
      historyEditor.evictIfNeeded(
          baseline.historyExtent.isEmpty() ? 1 : baseline.historyExtent.lastSeq);
      // 同 projection 的 Baseline 可以保留旧驻留页；提交前用有界索引验证新 screen，
      // 不能让同一 LineID 同时归属于 screen 与 loaded history。
      for (TerminalLine normalized : normalizedScreen) {
        if (historyEditor.historySeqByLineId(normalized.id) != null) return false;
      }
    } catch (IllegalArgumentException | IllegalStateException invalidHistory) {
      return false;
    }
    historyEditor.commit();
    // Baseline 是权威完整投影；不让旧 Patch 的迁移身份跨同步边界存活。
    pendingHistoryMigrations.clear();
    this.v2Projection = true;
    this.streamGeneration = baseline.streamGeneration;
    this.instanceId = baseline.instanceId;
    this.layoutEpoch = baseline.layoutEpoch;
    this.screenRevision = baseline.screenRevision;
    this.remoteScreenRevision = baseline.screenRevision;
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
    screenLineStore.clear();
    for (int row = 0; row < rows; row++) {
      TerminalLine line = normalizedScreen.get(row);
      this.screen[row] = line;
      screenLineStore.put(line.id, line);
    }
    TerminalRenderMetrics.screenLineStoreSize(screenLineStore.size());
    markRenderDirty(true, null, 0, null, rows, true, geometryChanged, true, -1, cursor.row,
        true, true, true, true, true);
    markTerminalState(geometryChanged, true, true, true, 0, 0);
    projectionHealth = ProjectionHealth.complete(
        instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION);
    publishPendingRenderUpdate();
    return true;
  }

  public synchronized boolean applyScreenPatch(ScreenPatchV2 patch) throws RevisionGapException {
    if (!v2Projection || patch == null || patch.streamGeneration != streamGeneration
        || instanceId == null || !instanceId.equals(patch.instanceId)
        || layoutEpoch != patch.layoutEpoch || screenRevision != patch.baseRevision
        || patch.screenRevision <= patch.baseRevision) {
      throw new RevisionGapException("screen.v2 patch identity/revision mismatch");
    }
    if (patch.layout != null && patch.layout.length != rows) {
      throw new RevisionGapException("screen.v2 layout length mismatch");
    }
    boolean hasLayout = patch.layout != null;
    boolean hasLineUpdates = patch.lineUpdates != null && !patch.lineUpdates.isEmpty();
    TerminalCursor previousCursor = cursor;

    // revision-only / metadata-only：不分配 staged Map/BitSet，不复制 screen，
    // 不重建 owner store，也不扫描 migration。
    if (!hasLayout && !hasLineUpdates) {
      int metadata = applyPatchMetadata(patch);
      boolean cursorChanged = (metadata & META_CURSOR) != 0;
      boolean publicationChanged = metadata != 0;
      screenRevision = patch.screenRevision;
      remoteScreenRevision = patch.screenRevision;
      projectionHealth = ProjectionHealth.complete(
          instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION);
      if (publicationChanged) {
        markRenderDirty(false, null, 0, null, rows, false, false, cursorChanged,
            previousCursor != null ? previousCursor.row : -1, cursor.row,
            (metadata & META_PALETTE) != 0, false, false,
            (metadata & META_MODES) != 0, (metadata & META_BUFFER) != 0);
        markTerminalState(false, false, (metadata & META_TITLE) != 0,
            (metadata & META_CWD) != 0, 0, 0);
        publishPendingRenderUpdate();
      } else {
        publishProjectionReadView();
      }
      return publicationChanged;
    }

    Map<Long, TerminalLine> stagedLines = new HashMap<>();
    for (TerminalLine line : patch.lineUpdates != null
        ? patch.lineUpdates : Collections.<TerminalLine>emptyList()) {
      TerminalLine normalized = padOrCopyLine(line, columns);
      if (normalized.id <= 0 || normalized.historySeq != 0) {
        throw new RevisionGapException("screen.v2 patch contains invalid screen line");
      }
      if (pagedHistory.historySeqByLineId(normalized.id) != null) {
        throw new RevisionGapException("screen.v2 LineID is owned by loaded history");
      }
      TerminalLine previous = screenLineStore.get(normalized.id);
      if (!hasLayout && previous == null) {
        throw new RevisionGapException("screen.v2 line update is not owned by screen");
      }
      if (previous != null) {
        if (normalized.version < previous.version) {
          throw new RevisionGapException("screen.v2 line version regressed");
        }
        if (normalized.version == previous.version) {
          if (!normalized.sameContent(previous)) {
            throw new RevisionGapException(
                "screen.v2 line content changed without version increment");
          }
          normalized = previous;
        }
      }
      if (stagedLines.put(normalized.id, normalized) != null) {
        throw new RevisionGapException("screen.v2 patch repeats line id");
      }
    }
    if (hasLayout) {
      Set<Long> layoutIds = new HashSet<>();
      for (long id : patch.layout) {
        if (id <= 0 || !layoutIds.add(id)
            || (!stagedLines.containsKey(id) && !screenLineStore.containsKey(id))) {
          throw new RevisionGapException("screen.v2 layout line missing or repeated");
        }
      }
    }
    int screenScrollRows = 0;
    BitSet exposedRows = new BitSet(rows);
    BitSet changedRows = new BitSet(rows);
    TerminalLine[] previousScreen = hasLayout ? screen : null;
    if (hasLayout) {
      screenScrollRows = detectScreenScroll(screen, patch.layout);
      if (screenScrollRows != 0) {
        int exposedCount = Math.abs(screenScrollRows);
        if (screenScrollRows > 0) {
          // 向上滚动：底部暴露出新行。
          for (int i = 0; i < exposedCount; i++) exposedRows.set(rows - 1 - i);
        } else {
          // 向下滚动：顶部暴露出新行。
          for (int i = 0; i < exposedCount; i++) exposedRows.set(i);
        }
        // 滚动前先保存旧 screen：滚动分支中 screen[row] 的旧值是被移走的另一行，
        // 不能直接比较。对非暴露的保留行，与旧数组中位移来源位置的行比较 version：
        // 向上滚动（screenScrollRows > 0）时新 row 来自旧 row + screenScrollRows；
        // 向下滚动（screenScrollRows 为负）时同样来自旧 row + screenScrollRows。
        // id 已由 detectScreenScroll 保证匹配，version 升高即同 Patch 内 lineUpdates
        // 更新的保留行，必须标脏，否则渲染层行缓存会复用旧录制显示陈旧内容。
        // （同 version 重发的行内容不变，按 version 比较避免无谓整屏重录。）
        changedRows.or(exposedRows);
      }
      TerminalLine[] nextScreen = new TerminalLine[rows];
      for (int row = 0; row < rows; row++) {
        TerminalLine next = stagedLines.get(patch.layout[row]);
        if (next == null) next = screenLineStore.get(patch.layout[row]);
        if (next == null) throw new RevisionGapException("screen.v2 layout line missing");
        nextScreen[row] = next;
        if (screenScrollRows != 0) {
          int sourceRow = row + screenScrollRows;
          if (!exposedRows.get(row) && sourceRow >= 0 && sourceRow < rows
              && previousScreen[sourceRow].version != next.version) {
            changedRows.set(row);
          }
        } else if (previousScreen[row] != next) {
          changedRows.set(row);
        }
      }
      screen = nextScreen;
      rebuildScreenLineStore();
      recordPendingHistoryMigrations(previousScreen, patch.screenRevision);
    } else {
      // 无 layout 的更新不会改变 ownership；只扫描每个更新对应的小屏幕数组，
      // 不重建全 store、不运行 migration 扫描。
      for (TerminalLine next : stagedLines.values()) {
        TerminalLine previous = screenLineStore.get(next.id);
        if (next == previous) continue;
        for (int row = 0; row < rows; row++) {
          if (screen[row].id != next.id) continue;
          screen[row] = next;
          screenLineStore.put(next.id, next);
          changedRows.set(row);
          break;
        }
      }
    }
    int metadata = applyPatchMetadata(patch);
    boolean cursorChanged = (metadata & META_CURSOR) != 0;
    boolean paletteChanged = (metadata & META_PALETTE) != 0;
    boolean modesChanged = (metadata & META_MODES) != 0;
    boolean activeBufferChanged = (metadata & META_BUFFER) != 0;
    boolean titleChanged = (metadata & META_TITLE) != 0;
    boolean workingDirectoryChanged = (metadata & META_CWD) != 0;
    boolean publicationChanged = !changedRows.isEmpty() || screenScrollRows != 0
        || !exposedRows.isEmpty() || cursorChanged || paletteChanged || modesChanged
        || activeBufferChanged || titleChanged || workingDirectoryChanged;
    screenRevision = patch.screenRevision;
    remoteScreenRevision = patch.screenRevision;
    markRenderDirty(false, changedRows, screenScrollRows, exposedRows, rows, false, false,
        cursorChanged,
        previousCursor != null ? previousCursor.row : -1, cursor.row,
        paletteChanged, false, false, modesChanged, activeBufferChanged);
    markTerminalState(false, false, titleChanged, workingDirectoryChanged, 0, 0);
    // Patch 的身份、revision、layout、更新行和最终 screen 已在本事务内完成验证。
    // 历史归 PagedTerminalHistory 独立所有，不能在高频 Patch 热路径重新扫描逻辑 extent。
    projectionHealth = ProjectionHealth.complete(
        instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION);
    if (publicationChanged) {
      publishPendingRenderUpdate();
    } else {
      publishProjectionReadView();
    }
    return publicationChanged;
  }

  /** 原子应用一个实时投影 Commit；任一阶段失败都不会修改模型或发布渲染。 */
  public synchronized boolean applyTerminalCommit(TerminalCommit commit)
      throws RevisionGapException {
    if (!v2Projection || commit == null || commit.streamGeneration != streamGeneration
        || !Objects.equals(instanceId, commit.instanceId) || layoutEpoch != commit.layoutEpoch
        || screenRevision != commit.baseRevision || commit.revision <= commit.baseRevision) {
      throw new RevisionGapException("screen.v2 commit identity/revision mismatch");
    }

    TerminalLine[] stagedScreen = screen;
    BitSet changedRows = new BitSet(rows);
    BitSet exposedRows = new BitSet(rows);
    int screenScrollRows = 0;
    if (commit.screen != null) {
      stagedScreen = java.util.Arrays.copyOf(screen, rows);
      ScreenScroll scroll = commit.screen.scroll;
      if (scroll != null) {
        int height = scroll.bottomRowExclusive - scroll.topRow;
        int shift = scroll.deltaRows;
        if (scroll.topRow < 0 || scroll.bottomRowExclusive > rows || height <= 0
            || shift == 0 || Math.abs((long) shift) >= height) {
          throw new RevisionGapException("screen.v2 commit contains invalid scroll");
        }
        screenScrollRows = shift;
        if (shift > 0) {
          System.arraycopy(stagedScreen, scroll.topRow + shift, stagedScreen,
              scroll.topRow, height - shift);
          exposedRows.set(scroll.bottomRowExclusive - shift, scroll.bottomRowExclusive);
        } else {
          int amount = -shift;
          System.arraycopy(stagedScreen, scroll.topRow, stagedScreen,
              scroll.topRow + amount, height - amount);
          exposedRows.set(scroll.topRow, scroll.topRow + amount);
        }
        changedRows.set(scroll.topRow, scroll.bottomRowExclusive);
      }
      BitSet writtenRows = new BitSet(rows);
      for (ScreenRowWrite write : commit.screen.writes) {
        if (write == null || write.row < 0 || write.row >= rows || writtenRows.get(write.row)) {
          throw new RevisionGapException("screen.v2 commit repeats or exceeds screen row");
        }
        writtenRows.set(write.row);
        TerminalLine normalized = normalizeCompleteLine(write.line, columns);
        if (normalized == null || normalized.id <= 0 || normalized.historySeq != 0) {
          throw new RevisionGapException("screen.v2 commit contains invalid screen line");
        }
        TerminalLine previous = stagedScreen[write.row];
        if (previous != null && previous.id == normalized.id) {
          if (normalized.version < previous.version
              || (normalized.version == previous.version && !normalized.sameContent(previous))) {
            throw new RevisionGapException("screen.v2 commit line version/content mismatch");
          }
          if (normalized.version == previous.version) normalized = previous;
        }
        stagedScreen[write.row] = normalized;
        changedRows.set(write.row);
      }
      for (TerminalLine line : stagedScreen) {
        if (line == null || line.historySeq != 0) {
          throw new RevisionGapException("screen.v2 commit leaves incomplete screen");
        }
      }
    }

    HistoryExtent oldExtent = displayExtent;
    HistoryExtent nextExtent = oldExtent;
    List<TerminalLine> appendedLines = Collections.emptyList();
    PagedTerminalHistory.Editor historyEditor = null;
    if (commit.history != null) {
      if (commit.history.finalExtent == null || commit.history.appendedLines.size() > 128) {
        throw new RevisionGapException("screen.v2 commit contains invalid history mutation");
      }
      nextExtent = commit.history.finalExtent;
      appendedLines = new ArrayList<>(commit.history.appendedLines.size());
      long previousSeq = 0;
      for (TerminalLine line : commit.history.appendedLines) {
        TerminalLine normalized = normalizeCompleteLine(line, columns);
        if (normalized == null || normalized.id <= 0 || normalized.historySeq <= previousSeq
            || !nextExtent.contains(normalized.historySeq)) {
          throw new RevisionGapException("screen.v2 commit contains invalid history line");
        }
        appendedLines.add(normalized);
        previousSeq = normalized.historySeq;
      }
      historyEditor = pagedHistory.edit()
          .setExtent(nextExtent.firstSeq, nextExtent.lastSeq)
          .setAvailableExtent(nextExtent.firstSeq, nextExtent.lastSeq);
      try {
        for (TerminalLine line : appendedLines) historyEditor.put(line.historySeq, line);
        historyEditor.evictIfNeeded(nextExtent.isEmpty() ? 1 : nextExtent.lastSeq);
      } catch (IllegalArgumentException | IllegalStateException invalidHistory) {
        throw new RevisionGapException("screen.v2 commit history rejected", invalidHistory);
      }
    }

    TerminalCursor nextCursor = commit.cursor != null ? commit.cursor : cursor;
    TerminalModes nextModes = commit.modes != null ? commit.modes : modes;
    TerminalPalette nextPalette = commit.palette != null ? commit.palette : palette;
    boolean cursorChanged = !Objects.equals(cursor, nextCursor);
    boolean modesChanged = !Objects.equals(modes, nextModes);
    boolean paletteChanged = !Objects.equals(palette, nextPalette);
    TerminalCursor previousCursor = cursor;
    long appendedCount = nextExtent.lastSeq > oldExtent.lastSeq
        ? nextExtent.lastSeq - oldExtent.lastSeq : 0;
    int tailAppendedLines = appendedCount > Integer.MAX_VALUE
        ? Integer.MAX_VALUE : (int) appendedCount;

    if (historyEditor != null) historyEditor.commit();
    screen = stagedScreen;
    cursor = nextCursor;
    modes = nextModes;
    palette = nextPalette;
    displayExtent = nextExtent;
    remoteAvailableExtent = nextExtent;
    firstAvailableHistorySeq = nextExtent.firstSeq;
    screenRevision = commit.revision;
    remoteScreenRevision = commit.revision;
    projectionHealth = ProjectionHealth.complete(
        instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION);

    boolean historyChanged = !oldExtent.equals(nextExtent) || !appendedLines.isEmpty();
    markRenderDirty(false, changedRows, screenScrollRows, exposedRows, rows,
        historyChanged, false, cursorChanged,
        previousCursor != null ? previousCursor.row : -1,
        nextCursor != null ? nextCursor.row : -1,
        paletteChanged, false, false, modesChanged, false);
    if (historyChanged) mergeHistoryDirtyRange(appendedLines, !oldExtent.equals(nextExtent));
    markTerminalState(false, historyChanged, false, false, tailAppendedLines, 0);
    boolean renderChanged = !changedRows.isEmpty() || screenScrollRows != 0 || historyChanged
        || cursorChanged || modesChanged || paletteChanged;
    if (renderChanged) publishPendingRenderUpdate();
    else publishProjectionReadView();
    return renderChanged;
  }

  private int applyPatchMetadata(ScreenPatchV2 patch) {
    int changes = 0;
    if (patch.cursor != null && !Objects.equals(cursor, patch.cursor)) {
      cursor = patch.cursor;
      changes |= META_CURSOR;
    }
    if (patch.palette != null && !Objects.equals(palette, patch.palette)) {
      palette = patch.palette;
      changes |= META_PALETTE;
    }
    if (patch.modes != null && !Objects.equals(modes, patch.modes)) {
      modes = patch.modes;
      changes |= META_MODES;
    }
    if (patch.activeBuffer != null && activeBuffer != patch.activeBuffer) {
      activeBuffer = patch.activeBuffer;
      changes |= META_BUFFER;
    }
    if (patch.title != null && !Objects.equals(title, patch.title)) {
      title = patch.title;
      changes |= META_TITLE;
    }
    if (patch.workingDirectory != null
        && !Objects.equals(workingDirectory, patch.workingDirectory)) {
      workingDirectory = patch.workingDirectory;
      changes |= META_CWD;
    }
    return changes;
  }

  public synchronized boolean applyHistoryDelta(HistoryDelta delta) {
    if (!v2Projection || delta == null || delta.streamGeneration != streamGeneration
        || !Objects.equals(instanceId, delta.instanceId) || layoutEpoch != delta.layoutEpoch) {
      return false;
    }
    HistoryExtent previousExtent = displayExtent;
    HistoryExtent nextExtent = delta.availableExtent;
    PagedTerminalHistory.Editor editor = pagedHistory.edit()
        .setExtent(nextExtent.firstSeq, nextExtent.lastSeq)
        .setAvailableExtent(nextExtent.firstSeq, nextExtent.lastSeq);
    List<Long> completedMigrations = new ArrayList<>();
    for (TerminalLine line : delta.lines) {
      TerminalLine normalized = normalizeHistoryLine(line);
      if (nextExtent.contains(normalized.historySeq)) {
        validatePendingHistoryMigration(normalized, completedMigrations);
        editor.put(normalized.historySeq, normalized);
      }
    }
    editor.evictIfNeeded(nextExtent.isEmpty() ? 1 : nextExtent.lastSeq).commit();
    clearCompletedMigrations(completedMigrations);
    remoteAvailableExtent = nextExtent;
    displayExtent = nextExtent;
    firstAvailableHistorySeq = displayExtent.firstSeq;
    markRenderDirty(false, null, 0, null, rows, false, false, false, -1, -1,
        false, false, false, false, false);
    mergeHistoryDirtyRange(delta.lines, !previousExtent.equals(nextExtent));
    markTerminalState(false, true, false, false, 0, 0);
    publishPendingRenderUpdate();
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
      if (!range.lines.isEmpty()) {
        throw new IllegalArgumentException("stale HistoryRange must not contain lines");
      }
      remoteAvailableExtent = range.availableExtent;
      staleProjection = true;
      publishProjectionReadView();
      return false;
    }
    if (range.status == HistoryRangeResult.Status.RETRYABLE) {
      if (!range.lines.isEmpty()) {
        throw new IllegalArgumentException("retryable HistoryRange must not contain lines");
      }
      return false;
    }
    if (requestedFromSeq < 1 || requestedToSeq < requestedFromSeq
        || requestedToSeq - requestedFromSeq >= 256
        || range.lines.size() > requestedToSeq - requestedFromSeq + 1) {
      throw new IllegalArgumentException("invalid HistoryRange request bounds");
    }

    // 先规范化并验证整批响应，再创建 Editor。任一异常行都不能写入页面 overlay、
    // 清理 migration、更新 available extent 或发布 RenderPublication。
    List<TerminalLine> normalizedLines = new ArrayList<>(range.lines.size());
    Set<Long> responseLineIds = new HashSet<>();
    List<Long> completedMigrations = new ArrayList<>();
    long previousSeq = 0;
    for (TerminalLine line : range.lines) {
      TerminalLine normalized = normalizeHistoryLine(line);
      long seq = normalized.historySeq;
      if (seq < requestedFromSeq || seq > requestedToSeq
          || !range.availableExtent.contains(seq)
          || !displayExtent.contains(seq)) {
        throw new IllegalArgumentException("HistoryRange line outside negotiated bounds");
      }
      if (previousSeq != 0 && seq <= previousSeq) {
        throw new IllegalArgumentException("HistoryRange lines are not strictly increasing");
      }
      if (!responseLineIds.add(normalized.id)) {
        throw new IllegalArgumentException("HistoryRange contains duplicate LineID");
      }
      validatePendingHistoryMigration(normalized, completedMigrations);
      normalizedLines.add(normalized);
      previousSeq = seq;
    }

    HistoryExtent previousAvailableExtent = remoteAvailableExtent;
    PagedTerminalHistory.Editor editor = pagedHistory.edit();
    editor.setAvailableExtent(
        range.availableExtent.firstSeq, range.availableExtent.lastSeq);
    for (TerminalLine normalized : normalizedLines) {
      editor.put(normalized.historySeq, normalized);
    }
    editor.evictIfNeeded(anchorSeq > 0 ? anchorSeq : displayExtent.lastSeq).commit();
    clearCompletedMigrations(completedMigrations);
    remoteAvailableExtent = range.availableExtent;
    markRenderDirty(false, null, 0, null, rows, false, false, false, -1, -1,
        false, false, false, false, false);
    mergeHistoryDirtyRange(normalizedLines, false);
    mergeAvailableExtentDirty(previousAvailableExtent, range.availableExtent);
    markTerminalState(false, true, false, false, 0, 0);
    publishPendingRenderUpdate();
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
                                                long latestScreenRevision,
                                                HistoryExtent latestExtent) {
    if (!v2Projection || latestExtent == null || this.instanceId == null
        || !this.instanceId.equals(instanceId) || this.layoutEpoch != layoutEpoch
        || latestScreenRevision < screenRevision
        || latestScreenRevision < remoteScreenRevision) {
      return false;
    }
    remoteScreenRevision = latestScreenRevision;
    remoteAvailableExtent = latestExtent;
    publishProjectionReadView();
    return true;
  }

  public synchronized long remoteScreenRevision() {
    return remoteScreenRevision;
  }

  public synchronized boolean hasRemoteTailChanges() {
    return remoteScreenRevision > screenRevision
        || !remoteAvailableExtent.equals(displayExtent);
  }

  /** 只能在 model executor 的完整事务边界读取，返回不可变快照。 */
  public synchronized ProjectionHealth projectionHealth() {
    return projectionHealth;
  }


  private boolean referencesComplete(TerminalLine line) {
    for (int i = 0; i < line.length(); i++) {
      if (line.at(i) == null) return false;
    }
    return true;
  }

  public RenderSnapshot renderSnapshot() {
    return renderSnapshot;
  }

  public RenderSnapshot peekRenderSnapshot() {
    return renderSnapshot;
  }

  /** UI/Runtime 轻量读取；不获取模型 monitor。 */
  public ProjectionReadView projectionReadView() {
    return projectionReadView;
  }

  /** Runtime 初始化时幂等绑定私有 capability；同一模型不得归属两个 Runtime。 */
  public void bindRenderPublicationAuthority(Object authority) {
    if (authority == null) throw new NullPointerException("authority");
    Object current = renderPublicationAuthority.get();
    if (current == authority) return;
    if (!renderPublicationAuthority.compareAndSet(null, authority)) {
      throw new IllegalStateException("render publication authority already bound");
    }
  }

  /** Runtime capability 消费；owner 身份由 Runtime 在进入这里之前验证。 */
  public RenderUpdate consumeRenderUpdate(Object authority) {
    if (authority == null || renderPublicationAuthority.get() != authority) {
      throw new IllegalStateException("invalid render publication authority");
    }
    return consumeRenderPublication();
  }

  /**
   * 未绑定 Runtime 的独立模型测试/工具入口。生产 Runtime 一旦绑定 capability，旁路调用
   * 会被明确拒绝，避免取得 model 引用的代码抢走 publication。
   */
  public RenderUpdate consumeRenderUpdate() {
    if (renderPublicationAuthority.get() != null) {
      throw new IllegalStateException("RenderUpdate must be consumed through TerminalSessionRuntime");
    }
    return consumeRenderPublication();
  }

  private RenderUpdate consumeRenderPublication() {
    RenderPublication publication = pendingPublication.getAndSet(null);
    return publication == null ? null : publication.update;
  }

  public synchronized void requestFullRender() {
    markRenderDirty(true, null, 0, null, rows, false, false, false, -1, -1,
        false, false, false, false, false);
    publishPendingRenderUpdate();
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

  /** 供规模回归测试与无正文诊断确认 screen store 始终有界。 */
  synchronized int screenLineStoreSize() {
    return screenLineStore.size();
  }

  synchronized long loadedHistoryLineCountForTest() {
    return pagedHistory.snapshot().loadedLineCount();
  }

  synchronized int residentHistoryPageCountForTest() {
    return pagedHistory.residentPageCountForTest();
  }

  synchronized int loadedLineIdentityCountForTest() {
    return screenLineStore.size() + pagedHistory.loadedLineIdentityCountForTest();
  }

  synchronized Long loadedHistorySeqForLineIdForTest(long lineId) {
    return pagedHistory.historySeqByLineId(lineId);
  }

  boolean renderPublicationPendingForTest() {
    return pendingPublication.get() != null;
  }

  synchronized int pendingHistoryMigrationCountForTest() {
    return pendingHistoryMigrations.size();
  }

  synchronized long patchScreenCloneCountForTest() {
    return patchScreenCloneCount;
  }

  synchronized long patchScreenStoreRebuildCountForTest() {
    return patchScreenStoreRebuildCount;
  }

  synchronized long patchMigrationScanCountForTest() {
    return patchMigrationScanCount;
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

  private TerminalLine normalizeCompleteLine(TerminalLine line, int cols) {
    if (line == null || line.cells == null) return null;
    TerminalLine normalized = padOrCopyLine(line, cols);
    return referencesComplete(normalized) ? normalized : null;
  }

  private TerminalLine normalizeHistoryLine(TerminalLine line) {
    TerminalLine normalized = normalizeCompleteLine(line, columns);
    if (normalized == null || normalized.id <= 0 || normalized.historySeq <= 0) {
      throw new IllegalStateException("screen.v2 contains invalid history line");
    }
    // Go writer 的生产顺序是先用 ScreenPatch 移出 screen，再发送 HistoryDelta；
    // 因此这里仍有 screen owner 不是合法迁移窗口，而是乱序或身份冲突。
    if (screenLineStore.containsKey(normalized.id)) {
      throw new IllegalStateException("screen.v2 history LineID is owned by screen");
    }
    return normalized;
  }

  /** 成功 Patch 后才调用：只记录真正离开 screen 的行，并清理重新进入 screen 的 ID。 */
  private void recordPendingHistoryMigrations(
      TerminalLine[] previousScreen, long removedAtScreenRevision) {
    patchMigrationScanCount++;
    if (previousScreen != null) {
      for (TerminalLine previous : previousScreen) {
        if (previous != null && !screenLineStore.containsKey(previous.id)) {
          pendingHistoryMigrations.put(previous.id,
              new PendingHistoryMigration(previous, removedAtScreenRevision));
        }
      }
    }
    for (TerminalLine current : screenLineStore.values()) {
      pendingHistoryMigrations.remove(current.id);
    }
    prunePendingHistoryMigrations(removedAtScreenRevision);
  }

  private void validatePendingHistoryMigration(
      TerminalLine historyLine, List<Long> completedMigrations) {
    PendingHistoryMigration migration = pendingHistoryMigrations.get(historyLine.id);
    if (migration == null) return;
    // Go writer 将屏幕行原样 Push 到不可变 scrollback，HistorySeq 是唯一允许变化的位置。
    if (migration.screenLine.version != historyLine.version
        || !migration.screenLine.sameContent(historyLine)) {
      throw new IllegalStateException("screen.v2 history migration changed LineID content");
    }
    completedMigrations.add(historyLine.id);
  }

  private void clearCompletedMigrations(List<Long> completedMigrations) {
    for (long lineId : completedMigrations) pendingHistoryMigrations.remove(lineId);
  }

  private void prunePendingHistoryMigrations(long currentRevision) {
    long oldestAllowed = currentRevision > MIGRATION_REVISION_WINDOW
        ? currentRevision - MIGRATION_REVISION_WINDOW : 0;
    java.util.Iterator<Map.Entry<Long, PendingHistoryMigration>> iterator =
        pendingHistoryMigrations.entrySet().iterator();
    while (iterator.hasNext()) {
      if (iterator.next().getValue().removedAtScreenRevision < oldestAllowed) iterator.remove();
    }
    int capacity = Math.max(32, rows * 4);
    while (pendingHistoryMigrations.size() > capacity) {
      java.util.Iterator<Long> oldest = pendingHistoryMigrations.keySet().iterator();
      oldest.next();
      oldest.remove();
    }
  }

  /** Patch 提交后仅按当前 screen 重建，成本严格受 rows 上限约束。 */
  private void rebuildScreenLineStore() {
    patchScreenStoreRebuildCount++;
    screenLineStore.clear();
    if (screen != null) {
      for (TerminalLine line : screen) {
        if (line != null) screenLineStore.put(line.id, line);
      }
    }
    TerminalRenderMetrics.screenLineStoreSize(screenLineStore.size());
  }

  /**
   * 检测实时屏幕 layout 是否只是整体位移。返回正数表示向上滚动对应行数，
   * 负数表示向下滚动，0 表示无法识别为连续滚动。
   *
   * <p>只检查常见的 1～8 行滚动，避免高复杂度搜索；不计算整行 Cell 哈希；
   * 任何异常都安全回退到 0。</p>
   */
  private static int detectScreenScroll(TerminalLine[] previousScreen, long[] nextLayout) {
    if (previousScreen == null || nextLayout == null) return 0;
    int rowCount = previousScreen.length;
    if (nextLayout.length != rowCount || rowCount <= 1) return 0;
    int maxShift = Math.min(8, rowCount - 1);

    // 向上滚动：new[row] == old[row + shift]
    for (int shift = 1; shift <= maxShift; shift++) {
      boolean matched = true;
      for (int row = 0; row < rowCount - shift; row++) {
        TerminalLine previousLine = previousScreen[row + shift];
        if (previousLine == null || previousLine.id != nextLayout[row]) {
          matched = false;
          break;
        }
      }
      if (matched) return shift;
    }

    // 向下滚动：new[row] == old[row - shift]
    for (int shift = 1; shift <= maxShift; shift++) {
      boolean matched = true;
      for (int row = shift; row < rowCount; row++) {
        TerminalLine previousLine = previousScreen[row - shift];
        if (previousLine == null || previousLine.id != nextLayout[row]) {
          matched = false;
          break;
        }
      }
      if (matched) return -shift;
    }

    return 0;
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

  private void markRenderDirty(boolean fullInvalidate, BitSet changedRows, int screenScrollRows,
                               BitSet exposedRows, int rowCount, boolean historyChanged,
                               boolean geometryChanged, boolean cursorChanged,
                               int previousCursorRow, int currentCursorRow,
                               boolean paletteChanged, boolean stylesChanged,
                               boolean linksChanged, boolean modesChanged,
                               boolean activeBufferChanged) {
    pendingRenderDirty.merge(fullInvalidate, changedRows, screenScrollRows, exposedRows, rowCount,
        historyChanged, geometryChanged, cursorChanged, previousCursorRow, currentCursorRow,
        paletteChanged, stylesChanged, linksChanged, modesChanged, activeBufferChanged);
  }

  private void markTerminalState(boolean geometryChanged, boolean historyChanged,
                                 boolean titleChanged, boolean workingDirectoryChanged,
                                 int tailAppendedLines, int historyPrependedLines) {
    pendingTerminalState.merge(geometryChanged, historyChanged, titleChanged,
        workingDirectoryChanged, tailAppendedLines, historyPrependedLines);
  }

  private void mergeHistoryDirtyRange(List<TerminalLine> lines, boolean structureChanged) {
    long from = Long.MAX_VALUE;
    long to = Long.MIN_VALUE;
    for (TerminalLine line : lines) {
      if (line == null || !displayExtent.contains(line.historySeq)) continue;
      from = Math.min(from, line.historySeq);
      to = Math.max(to, line.historySeq);
    }
    pendingRenderDirty.mergeHistoryRange(
        from == Long.MAX_VALUE ? 1 : from,
        to == Long.MIN_VALUE ? 0 : to,
        structureChanged);
  }

  /** 权威可用窗口变化只改变对应占位槽状态，不改变 display geometry。 */
  private void mergeAvailableExtentDirty(HistoryExtent previous, HistoryExtent current) {
    if (previous.equals(current)) return;
    if (previous.isEmpty() && current.isEmpty()) return;
    if (previous.isEmpty()) {
      pendingRenderDirty.mergeHistoryRange(current.firstSeq, current.lastSeq, false);
    } else if (current.isEmpty()) {
      pendingRenderDirty.mergeHistoryRange(previous.firstSeq, previous.lastSeq, false);
    } else {
      if (previous.firstSeq != current.firstSeq) {
        pendingRenderDirty.mergeHistoryRange(
            Math.min(previous.firstSeq, current.firstSeq),
            Math.max(previous.firstSeq, current.firstSeq) - 1L, false);
      }
      if (previous.lastSeq != current.lastSeq) {
        pendingRenderDirty.mergeHistoryRange(
            Math.min(previous.lastSeq, current.lastSeq) + 1L,
            Math.max(previous.lastSeq, current.lastSeq), false);
      }
    }
  }

  /** 保留给性能基线的反射入口；正式路径只在 consumeRenderUpdate 时发布。 */
  @SuppressWarnings("unused")
  private synchronized void publishRenderSnapshot(boolean historyChanged, boolean stylesChanged,
                                                  boolean linksChanged) {
    RenderDirtyState dirty = new RenderDirtyState();
    dirty.merge(false, null, 0, null, rows, historyChanged, false, false, -1, -1,
        false, stylesChanged, linksChanged, false, false);
    publishRenderSnapshot(dirty);
  }

  /**
   * modelExecutor 在完整事务末尾调用。先冻结最新 RenderSnapshot，再用 CAS 与尚未被
   * VSync 消费的 dirty/state 合并；CAS 每次重试都创建新的合并对象，绝不修改已发布值。
   */
  private void publishPendingRenderUpdate() {
    if (pendingRenderDirty.isEmpty() && pendingTerminalState.isEmpty()) return;
    RenderDirtyState currentDirty = pendingRenderDirty;
    TerminalStateUpdate currentState = pendingTerminalState;
    pendingRenderDirty = new RenderDirtyState();
    pendingTerminalState = new TerminalStateUpdate();
    publishRenderSnapshot(currentDirty);
    RenderSnapshot currentSnapshot = renderSnapshot;
    long version = publicationVersion.incrementAndGet();
    pendingPublication.getAndUpdate(previous -> {
      if (previous == null) {
        return new RenderPublication(version,
            new RenderUpdate(currentSnapshot, currentDirty, currentState));
      }
      RenderDirtyState mergedDirty = new RenderDirtyState();
      mergedDirty.mergeFrom(previous.update.dirty, rows);
      mergedDirty.mergeFrom(currentDirty, rows);
      TerminalStateUpdate mergedState = new TerminalStateUpdate();
      mergedState.mergeFrom(previous.update.state);
      mergedState.mergeFrom(currentState);
      return new RenderPublication(version,
          new RenderUpdate(currentSnapshot, mergedDirty, mergedState));
    });
  }

  private void publishRenderSnapshot(RenderDirtyState dirty) {
    RenderSnapshot previous = renderSnapshot;
    // TerminalLine is immutable. Metadata-only and history-only patches therefore safely reuse
    // the previous screen array rather than cloning rows that did not change.
    boolean screenChanged = dirty.fullInvalidate || dirty.geometryChanged
        || dirty.activeBufferChanged || !dirty.changedScreenRows.isEmpty()
        || dirty.screenScrollRows != 0 || !dirty.exposedScreenRows.isEmpty();
    TerminalLine[] screenCopy = screenChanged && screen != null ? screen.clone() : previous.screen;
    TerminalHistoryView historySnapshot = dirty.historyChanged || dirty.fullInvalidate
        ? pagedHistory.snapshot()
        : previous.history;
    renderSnapshot = new RenderSnapshot(instanceId, layoutEpoch, screenRevision, rows, columns,
        activeBuffer, screenCopy, historySnapshot, cursor, modes, palette,
        title, workingDirectory, firstAvailableHistorySeq,
        hasMoreHistoryBefore);
    publishProjectionReadView();
  }

  private void publishProjectionReadView() {
    projectionReadView = new ProjectionReadView(
        instanceId, layoutEpoch, screenRevision, displayExtent, remoteAvailableExtent,
        projectionHealth.complete);
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

    public RevisionGapException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
