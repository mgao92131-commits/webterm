package com.webterm.terminal.model;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
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
  private static final int META_CURSOR = 1;
  private static final int META_PALETTE = 1 << 1;
  private static final int META_MODES = 1 << 2;
  private static final int META_BUFFER = 1 << 3;
  // 历史容量是双上限：行数是安全上限，字节是近似内存预算（estimateHistoryLineBytes），
  // 先达到者触发驱逐。保留行数随列宽和内容变化（80 列文本行约 5–6KB 估算），
  // 产品和注释都不承诺固定保留行数。默认值可由 HistoryBudget 按设备内存分档覆盖。

  public String instanceId;
  public long layoutEpoch;
  public long screenRevision;
  public int rows;
  public int columns;
  public TerminalBufferKind activeBuffer;

  private TerminalSurface mainSurface;
  private TerminalSurface alternateSurface;
  private final HistoryBudget historyBudget;
  private final ScreenProjectionReducer projectionReducer;
  private final HistoryBodyReducer historyBodyReducer = new HistoryBodyReducer();
  private ProjectionState projectionState;
  private boolean v2Projection;
  private long dictionaryGeneration;
  private long historyGeneration;
  /** 仅迁移期旧单元测试可为 false；真实 wire Baseline 始终携带完整 Catalog。 */
  private boolean historyCatalogComplete;
  private HistoryExtent displayExtent = HistoryExtent.INITIAL_EMPTY;
  private HistoryExtent remoteAvailableExtent = HistoryExtent.INITIAL_EMPTY;
  private EvictionPins evictionPins = EvictionPins.NONE;
  private boolean staleProjection;
  private TerminalLine[] screen;

  private long firstAvailableHistorySeq;
  private boolean hasMoreHistoryBefore;
  private TerminalCursor cursor = TerminalCursor.hidden();
  private TerminalModes modes = TerminalModes.defaults();
  private TerminalPalette palette = TerminalPalette.defaults();

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
    public final long historyGeneration;
    public final HistoryExtent mainHistoryExtent;
    public final HistoryExtent displayExtent;
    public final HistoryExtent remoteAvailableExtent;
    public final boolean projectionComplete;

    private ProjectionReadView(String instanceId, long layoutEpoch, long screenRevision,
                               long historyGeneration,
                               HistoryExtent mainHistoryExtent,
                               HistoryExtent displayExtent,
                               HistoryExtent remoteAvailableExtent,
                               boolean projectionComplete) {
      this.instanceId = instanceId == null ? "" : instanceId;
      this.layoutEpoch = layoutEpoch;
      this.screenRevision = screenRevision;
      this.historyGeneration = historyGeneration;
      this.mainHistoryExtent = mainHistoryExtent;
      this.displayExtent = displayExtent;
      this.remoteAvailableExtent = remoteAvailableExtent;
      this.projectionComplete = projectionComplete;
    }

    private static ProjectionReadView empty() {
      return new ProjectionReadView("", 0, 0, 0,
          HistoryExtent.INITIAL_EMPTY, HistoryExtent.INITIAL_EMPTY,
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
    this.historyBudget = budget;
    this.projectionReducer = new ScreenProjectionReducer(budget);
    this.mainSurface = new TerminalSurface(budget);
    this.alternateSurface = new TerminalSurface(budget);
    this.baselineHistoryValidationProbe = baselineHistoryValidationProbe;
  }

  private TerminalSurface surface(TerminalBufferKind kind) {
    return kind == TerminalBufferKind.ALTERNATE ? alternateSurface : mainSurface;
  }

  private TerminalSurface activeSurface() {
    return surface(activeBuffer);
  }

  public synchronized boolean applyBaseline(ScreenBaseline baseline) {
    if (baseline != null && baseline.historyCatalogComplete) {
      ProjectionResult semanticResult = projectionReducer.applyBaseline(baseline);
      if (!(semanticResult instanceof ProjectionResult.Applied)) {
        return false;
      }
      return applySemanticBaseline(
          ((ProjectionResult.Applied) semanticResult).state());
    }
    ProjectionState nextProjection = null;
    if (baseline == null || baseline.instanceId == null || baseline.instanceId.isEmpty()
        || baseline.layoutEpoch < 1 || baseline.screenRevision < 1
        || baseline.dictionaryGeneration < 1 || baseline.historyGeneration < 1
        || baseline.rows <= 0 || baseline.cols <= 0
        || baseline.historyExtent == null
        || baseline.historyBindings == null
        || baseline.screen == null || baseline.screen.size() != baseline.rows) {
      return false;
    }
    java.util.HashSet<Long> baselineLineIds = new java.util.HashSet<>();
    List<TerminalLine> normalizedScreen = new ArrayList<>(baseline.rows);
    for (ScreenLineContent content : baseline.screen) {
      TerminalLine line = legacyLine(content.key(), 0, content.body());
      if (!baselineLineIds.add(line.id)) return false;
      TerminalLine normalized = normalizeCompleteLine(line, baseline.cols);
      if (normalized == null) return false;
      normalizedScreen.add(normalized);
    }
    boolean geometryChanged = !v2Projection || rows != baseline.rows || columns != baseline.cols;
    TerminalSurface baselineSurface = new TerminalSurface(historyBudget);

    PagedTerminalHistory.Editor historyEditor = baselineSurface.history.edit();
    LineStore.Editor lineEditor = baselineSurface.lineStore.edit();
    HistoryIndex.Editor historyIndexEditor =
        baselineSurface.historyIndex.edit().setExtent(baseline.historyExtent);
    long[] activeLineIds = new long[baseline.rows];
    try {
      historyEditor.setExtent(baseline.historyExtent.firstSeq, baseline.historyExtent.lastSeq);
      historyEditor.setAvailableExtent(
          baseline.historyExtent.firstSeq, baseline.historyExtent.lastSeq);
      long previousBindingSeq = 0;
      Set<Long> boundLineIds = new HashSet<>();
      for (HistoryPush binding : baseline.historyBindings) {
        if (binding == null || binding.historySeq <= previousBindingSeq
            || !baseline.historyExtent.contains(binding.historySeq)
            || !boundLineIds.add(binding.key.lineId())) {
          return false;
        }
        historyIndexEditor.bindAuthoritative(
            binding.historySeq, binding.key.lineId(), binding.key.lineVersion());
        previousBindingSeq = binding.historySeq;
      }
      if (baseline.historyCatalogComplete
          && baseline.historyExtent.logicalSize() != baseline.historyBindings.size()) {
        return false;
      }
      historyEditor.evictIfNeeded(currentEvictionPins(
          baseline.historyExtent.isEmpty() ? 1 : baseline.historyExtent.lastSeq));
      for (int row = 0; row < normalizedScreen.size(); row++) {
        TerminalLine normalized = normalizedScreen.get(row);
        if (historyEditor.historySeqByLineId(normalized.id) != null) return false;
        normalized = lineEditor.put(normalized);
        normalizedScreen.set(row, normalized);
        if (historyIndexEditor.historySeq(normalized.id) != null) return false;
        activeLineIds[row] = normalized.id;
      }
    } catch (CommitValidationException
        | IllegalArgumentException | IllegalStateException invalidHistory) {
      return false;
    }
    historyEditor.commit();
    Set<Long> loadedHistoryIds = baselineSurface.history.snapshot().loadedLineIds();
    Set<Long> retainedLineIds = new HashSet<>(loadedHistoryIds);
    for (long lineId : activeLineIds) retainedLineIds.add(lineId);
    lineEditor.retainOnly(retainedLineIds);
    historyIndexEditor.commit();
    lineEditor.commit();
    baselineSurface.activeRows = new ActiveRows(activeLineIds);
    if (baseline.activeBuffer == TerminalBufferKind.ALTERNATE) {
      mainSurface = new TerminalSurface(historyBudget);
      alternateSurface = baselineSurface;
    } else {
      mainSurface = baselineSurface;
      alternateSurface = new TerminalSurface(historyBudget);
    }
    // Baseline 是权威完整投影；不让旧 Patch 的迁移身份跨同步边界存活。
    this.v2Projection = true;
    this.dictionaryGeneration = baseline.dictionaryGeneration;
    this.historyGeneration = baseline.historyGeneration;
    this.historyCatalogComplete = baseline.historyCatalogComplete;
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
    this.firstAvailableHistorySeq = baseline.historyExtent.firstSeq;
    this.hasMoreHistoryBefore = false;
    this.projectionState = nextProjection;

    this.screen = new TerminalLine[rows];
    for (int row = 0; row < rows; row++) {
      TerminalLine line = normalizedScreen.get(row);
      this.screen[row] = line;
    }
    markRenderDirty(true, null, 0, null, rows, true, geometryChanged, true, -1, cursor.row,
        true, true, true, true, true);
    markTerminalState(geometryChanged, true, false, false, 0, 0);
    projectionHealth = ProjectionHealth.complete(
        instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION);
    publishPendingRenderUpdate();
    return true;
  }

  public boolean applyTerminalCommit(TerminalCommit commit)
      throws RevisionGapException {
    return stageCommit(commit).commit();
  }

  /**
   * Validates every model-dependent invariant and prepares a transaction without
   * mutating any published model state.
   */
  public synchronized StagedCommit stageCommit(TerminalCommit commit)
      throws RevisionGapException {
    if (projectionState != null) {
      return stageSemanticCommit(commit);
    }
    if (!v2Projection || commit == null || !Objects.equals(instanceId, commit.instanceId)) {
      throw new CommitValidationException(CommitFailure.IDENTITY_MISMATCH);
    }
    if (layoutEpoch != commit.layoutEpoch) {
      throw new CommitValidationException(CommitFailure.LAYOUT_EPOCH_MISMATCH);
    }
    long commitDictionaryGeneration = commit.dictionaryGeneration == 0
        ? dictionaryGeneration : commit.dictionaryGeneration;
    long commitHistoryGeneration = commit.historyGeneration == 0
        ? historyGeneration : commit.historyGeneration;
    if (commitDictionaryGeneration != dictionaryGeneration) {
      throw new CommitValidationException(CommitFailure.DICTIONARY_GENERATION_MISMATCH);
    }
    if (commitHistoryGeneration != historyGeneration) {
      throw new CommitValidationException(CommitFailure.HISTORY_GENERATION_MISMATCH);
    }
    if (screenRevision != commit.baseRevision || commit.revision <= commit.baseRevision) {
      throw new CommitValidationException(CommitFailure.REVISION_GAP);
    }
    ProjectionState nextProjection = null;
    if (projectionState != null) {
      ProjectionResult semanticResult = projectionReducer.applyCommit(projectionState, commit);
      if (!(semanticResult instanceof ProjectionResult.Applied)) {
        ProjectionFault fault = ((ProjectionResult.NeedsBaseline) semanticResult).fault();
        throw new CommitValidationException(commitFailure(fault));
      }
      nextProjection = ((ProjectionResult.Applied) semanticResult).state();
    }

    long dictionaryStartedNanos = System.nanoTime();
    TerminalRenderMetrics.dictionaryStagingDuration(
        System.nanoTime() - dictionaryStartedNanos);

    TerminalBufferKind nextActiveBuffer =
        commit.activeBuffer != null ? commit.activeBuffer : activeBuffer;
    boolean activeBufferChanged = nextActiveBuffer != activeBuffer;
    TerminalSurface targetSurface = surface(nextActiveBuffer);
    LineStore.Editor lineEditor = targetSurface.lineStore.edit();
    HistoryIndex.Editor historyIndexEditor = targetSurface.historyIndex.edit();
    HistoryExtent oldExtent = targetSurface.historyIndex.extent();
    HistoryExtent nextExtent = oldExtent;
    List<HistoryPush> historyPushes = Collections.emptyList();
    if (commit.history != null) {
      if (commit.history.finalExtent == null) {
        throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
      }
      nextExtent = commit.history.finalExtent;
      if (!activeBufferChanged && nextExtent.firstSeq < oldExtent.firstSeq) {
        throw new CommitValidationException(CommitFailure.HISTORY_EXTENT_REGRESSION);
      }
      historyPushes = new ArrayList<>(commit.history.pushes);
      long previousSeq = 0;
      for (HistoryPush push : historyPushes) {
        if (push == null || push.historySeq <= previousSeq
            || !nextExtent.contains(push.historySeq)
            || push.key == null) {
          throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
        }
        previousSeq = push.historySeq;
      }
      // 先应用最终位置范围，使 Resize Pop 回到屏幕的行不再与旧尾部绑定冲突。
      historyIndexEditor.setExtent(nextExtent);
    }
    TerminalLine[] stagedScreen = activeBufferChanged ? new TerminalLine[rows] : screen;
    BitSet changedRows = new BitSet(rows);
    BitSet exposedRows = new BitSet(rows);
    int screenScrollRows = 0;
    if (commit.screen != null) {
      stagedScreen = activeBufferChanged
          ? new TerminalLine[rows] : java.util.Arrays.copyOf(screen, rows);
      ScreenScroll scroll = commit.screen.scroll;
      if (scroll != null) {
        if (activeBufferChanged) {
          throw new CommitValidationException(CommitFailure.INVALID_ACTIVE_BUFFER_TRANSITION);
        }
        int height = scroll.bottomRowExclusive - scroll.topRow;
        int shift = scroll.deltaRows;
        if (scroll.topRow != 0 || scroll.bottomRowExclusive != rows || height <= 0
            || shift == 0 || Math.abs((long) shift) >= height) {
          throw new CommitValidationException(CommitFailure.INVALID_SCROLL);
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
      }
      BitSet writtenRows = new BitSet(rows);
      for (ScreenRowWrite write : commit.screen.writes) {
        if (write == null || write.row < 0 || write.row >= rows || writtenRows.get(write.row)) {
          throw new CommitValidationException(CommitFailure.DUPLICATE_SCREEN_ROW);
        }
        writtenRows.set(write.row);
        TerminalLine decoded = legacyLine(write.line.key(), 0, write.line.body());
        TerminalLine normalized = normalizeCompleteLine(decoded, columns);
        if (normalized == null || normalized.id <= 0 || normalized.historySeq != 0) {
          throw new CommitValidationException(CommitFailure.INVALID_LINE_DATA);
        }
        normalized = lineEditor.put(normalized);
        TerminalLine previous = stagedScreen[write.row];
        if (previous != null && previous.id == normalized.id) {
          if (normalized.version < previous.version
              || (normalized.version == previous.version && !normalized.sameContent(previous))) {
            throw new CommitValidationException(normalized.version < previous.version
                ? CommitFailure.LINE_VERSION_REGRESSION
                : CommitFailure.LINE_CONTENT_CONFLICT);
          }
          if (normalized.version == previous.version) normalized = previous;
        }
        stagedScreen[write.row] = normalized;
        changedRows.set(write.row);
      }
      BitSet missingRows = (BitSet) exposedRows.clone();
      missingRows.andNot(writtenRows);
      if (!missingRows.isEmpty()) {
        throw new CommitValidationException(CommitFailure.EXPOSED_ROW_MISSING);
      }
      Set<Long> activeLineIds = new HashSet<>(rows);
      for (TerminalLine line : stagedScreen) {
        if (line == null || line.historySeq != 0) {
          throw new CommitValidationException(CommitFailure.INVALID_LINE_DATA);
        }
        if (!activeLineIds.add(line.id)) {
          throw new CommitValidationException(CommitFailure.DUPLICATE_ACTIVE_LINE_ID);
        }
      }
    } else if (activeBufferChanged) {
      throw new CommitValidationException(CommitFailure.INVALID_ACTIVE_BUFFER_TRANSITION);
    }

    PagedTerminalHistory.Editor historyEditor = null;
    if (commit.history != null) {
      historyEditor = targetSurface.history.edit()
          .setExtent(nextExtent.firstSeq, nextExtent.lastSeq)
          .setAvailableExtent(nextExtent.firstSeq, nextExtent.lastSeq);
      historyIndexEditor.setExtent(nextExtent);
      try {
        Set<Long> pushedSeqs = new HashSet<>();
        Set<Long> pushedIds = new HashSet<>();
        Set<Long> finalActive = new HashSet<>();
        for (TerminalLine line : stagedScreen) finalActive.add(line.id);
        // 先统一释放被替换位置的旧反向映射，再写入新绑定，允许同一批权威
        // Push 中多个位置交换/重排 LineID。
        for (HistoryPush push : historyPushes) {
          HistoryLineRef previous = historyIndexEditor.ref(push.historySeq);
          if (previous != null
              && (previous.lineId != push.key.lineId()
                  || previous.lineVersion != push.key.lineVersion())) {
            historyEditor.invalidate(push.historySeq);
            historyIndexEditor.removeBinding(push.historySeq);
          }
        }
        for (HistoryPush push : historyPushes) {
          if (!pushedSeqs.add(push.historySeq)) {
            throw new CommitValidationException(CommitFailure.DUPLICATE_HISTORY_SEQUENCE);
          }
          if (!pushedIds.add(push.key.lineId()) || !nextExtent.contains(push.historySeq)) {
            throw new CommitValidationException(CommitFailure.HISTORY_PROMOTION_CONFLICT);
          }
          if (finalActive.contains(push.key.lineId())) {
            throw new CommitValidationException(CommitFailure.ACTIVE_HISTORY_LINE_ID_CONFLICT);
          }
          historyIndexEditor.bindAuthoritative(
              push.historySeq, push.key.lineId(), push.key.lineVersion());
          TerminalLine held = lineEditor.line(push.key.lineId());
          if (held != null && held.version == push.key.lineVersion()) {
            historyEditor.put(push.historySeq, held.withHistorySeq(push.historySeq));
          }
        }
        historyEditor.evictIfNeeded(currentEvictionPins(
            nextExtent.isEmpty() ? 1 : nextExtent.lastSeq));
      } catch (CommitValidationException failure) {
        throw failure;
      } catch (IllegalArgumentException | IllegalStateException invalidHistory) {
        throw new CommitValidationException(CommitFailure.HISTORY_LINE_ID_CONFLICT, invalidHistory);
      }
    }
    for (TerminalLine line : stagedScreen) {
      if (historyIndexEditor.historySeq(line.id) != null) {
        throw new CommitValidationException(CommitFailure.ACTIVE_HISTORY_LINE_ID_CONFLICT);
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

    final PagedTerminalHistory.Editor stagedHistoryEditor = historyEditor;
    final TerminalLine[] finalScreen = stagedScreen;
    final HistoryExtent finalExtent = nextExtent;
    final List<HistoryPush> finalHistoryPushes = historyPushes;
    final int finalScreenScrollRows = screenScrollRows;
    final ProjectionState finalProjectionState = nextProjection;
    final boolean historyChanged =
        !oldExtent.equals(finalExtent) || !finalHistoryPushes.isEmpty();
    final boolean renderChanged = !changedRows.isEmpty() || finalScreenScrollRows != 0
        || historyChanged || cursorChanged || modesChanged || paletteChanged
        || activeBufferChanged;
    final long expectedBaseRevision = commit.baseRevision;
    return new StagedCommit(expectedBaseRevision, () -> {
      if (stagedHistoryEditor != null) stagedHistoryEditor.commit();
      Set<Long> loadedHistoryIds = targetSurface.history.snapshot().loadedLineIds();
      Set<Long> retainedLineIds = new HashSet<>(loadedHistoryIds);
      for (TerminalLine line : finalScreen) retainedLineIds.add(line.id);
      lineEditor.retainOnly(retainedLineIds);
      historyIndexEditor.commit();
      lineEditor.commit();
      long[] activeLineIds = new long[finalScreen.length];
      for (int row = 0; row < finalScreen.length; row++) {
        activeLineIds[row] = finalScreen[row].id;
      }
      targetSurface.activeRows = new ActiveRows(activeLineIds);
      screen = finalScreen;
      cursor = nextCursor;
      modes = nextModes;
      palette = nextPalette;
      activeBuffer = nextActiveBuffer;
      projectionState = finalProjectionState;
      displayExtent = finalExtent;
      remoteAvailableExtent = finalExtent;
      firstAvailableHistorySeq = finalExtent.firstSeq;
      screenRevision = commit.revision;
      projectionHealth = ProjectionHealth.complete(
          instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION);

      markRenderDirty(false, changedRows, finalScreenScrollRows, exposedRows, rows,
          historyChanged, false, cursorChanged,
          previousCursor != null ? previousCursor.row : -1,
          nextCursor != null ? nextCursor.row : -1,
          paletteChanged, false,
          false, modesChanged,
          activeBufferChanged);
      if (historyChanged) {
        mergeHistoryPushDirtyRange(finalHistoryPushes, !oldExtent.equals(finalExtent));
      }
      markTerminalState(false, historyChanged, false, false, tailAppendedLines, 0);
      if (renderChanged) publishPendingRenderUpdate();
      else publishProjectionReadView();
      return renderChanged;
    });
  }

  private boolean applySemanticBaseline(ProjectionState next) {
    boolean geometryChanged =
        !v2Projection || rows != next.rows || columns != next.columns;
    projectionState = next;
    v2Projection = true;
    historyCatalogComplete = true;
    instanceId = next.identity.instanceId();
    layoutEpoch = next.identity.layoutEpoch();
    historyGeneration = next.identity.historyGeneration();
    screenRevision = next.screenRevision;
    dictionaryGeneration = next.dictionaryGeneration;
    rows = next.rows;
    columns = next.columns;
    activeBuffer = next.activeBuffer;
    cursor = next.cursor;
    modes = next.modes;
    palette = next.palette;
    HistoryExtent extent = next.mainSurface.historyCatalog.extent();
    displayExtent = extent;
    remoteAvailableExtent = extent;
    firstAvailableHistorySeq = extent.firstSeq;
    hasMoreHistoryBefore = false;
    staleProjection = false;
    screen = renderScreen(next.activeSurface());
    projectionHealth = ProjectionHealth.complete(
        instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION);
    markRenderDirty(true, null, 0, null, rows, true, geometryChanged,
        true, -1, cursor.row, true, true, true, true, true);
    markTerminalState(geometryChanged, true, false, false, 0, 0);
    publishPendingRenderUpdate();
    return true;
  }

  private StagedCommit stageSemanticCommit(TerminalCommit commit)
      throws CommitValidationException {
    ProjectionResult result =
        projectionReducer.applyCommit(projectionState, commit, evictionPins);
    if (!(result instanceof ProjectionResult.Applied)) {
      ProjectionFault fault = ((ProjectionResult.NeedsBaseline) result).fault();
      throw new CommitValidationException(commitFailure(fault));
    }
    ProjectionResult.Applied applied = (ProjectionResult.Applied) result;
    ProjectionState next = applied.state();
    ProjectionDelta delta = applied.delta();
    ProjectionState previous = projectionState;
    boolean cursorChanged = !Objects.equals(previous.cursor, next.cursor);
    boolean modesChanged = !Objects.equals(previous.modes, next.modes);
    boolean paletteChanged = !Objects.equals(previous.palette, next.palette);
    boolean bufferChanged = previous.activeBuffer != next.activeBuffer;
    boolean renderChanged = delta.screenChanged() || delta.historyChanged()
        || delta.geometryChanged() || cursorChanged || modesChanged
        || paletteChanged || bufferChanged;
    long expectedBaseRevision = commit == null ? 0 : commit.baseRevision;
    return new StagedCommit(expectedBaseRevision, () -> {
      projectionState = next;
      instanceId = next.identity.instanceId();
      layoutEpoch = next.identity.layoutEpoch();
      historyGeneration = next.identity.historyGeneration();
      screenRevision = next.screenRevision;
      dictionaryGeneration = next.dictionaryGeneration;
      rows = next.rows;
      columns = next.columns;
      activeBuffer = next.activeBuffer;
      cursor = next.cursor;
      modes = next.modes;
      palette = next.palette;
      HistoryExtent extent = next.mainSurface.historyCatalog.extent();
      HistoryExtent previousExtent = displayExtent;
      displayExtent = extent;
      remoteAvailableExtent = extent;
      firstAvailableHistorySeq = extent.firstSeq;
      screen = renderScreen(next.activeSurface());
      projectionHealth = ProjectionHealth.complete(
          instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION);

      BitSet changedRows = null;
      if (delta.screenChanged()) {
        changedRows = new BitSet(rows);
        changedRows.set(0, rows);
      }
      markRenderDirty(
          false, changedRows, 0, null, rows,
          delta.historyChanged(), delta.geometryChanged(), cursorChanged,
          previous.cursor != null ? previous.cursor.row : -1,
          next.cursor != null ? next.cursor.row : -1,
          paletteChanged, false, false, modesChanged, bufferChanged);
      if (delta.historyChanged()) {
        mergeAvailableExtentDirty(previousExtent, extent);
        if (commit.history != null) {
          mergeHistoryPushDirtyRange(
              commit.history.pushes, !previousExtent.equals(extent));
        }
      }
      markTerminalState(
          delta.geometryChanged(), delta.historyChanged(), false, false, 0, 0);
      if (renderChanged) publishPendingRenderUpdate();
      else publishProjectionReadView();
      return renderChanged;
    });
  }

  @FunctionalInterface
  private interface CommitAction {
    boolean run();
  }

  /** A validated, one-shot transaction. */
  public final class StagedCommit {
    private final long expectedBaseRevision;
    private final CommitAction action;
    private boolean committed;

    private StagedCommit(long expectedBaseRevision, CommitAction action) {
      this.expectedBaseRevision = expectedBaseRevision;
      this.action = action;
    }

    public boolean commit() throws RevisionGapException {
      synchronized (RemoteTerminalModel.this) {
        if (committed) throw new IllegalStateException("StagedCommit already committed");
        if (screenRevision != expectedBaseRevision) {
          throw new CommitValidationException(CommitFailure.REVISION_GAP);
        }
        boolean changed = action.run();
        committed = true;
        return changed;
      }
    }
  }

  public synchronized boolean applyHistoryRange(HistoryRangeResult range, long anchorSeq) {
    return applyHistoryRange(range, anchorSeq,
        range != null && !range.lines.isEmpty() ? range.lines.get(0).historySeq() : 1,
        range != null && !range.lines.isEmpty()
            ? range.lines.get(range.lines.size() - 1).historySeq() : 0);
  }

  /**
   * 生产 HTTP Range 入口：只填充语义 BodyCache，并返回与连接恢复域隔离的 typed result。
   */
  public synchronized HistoryBodyResult applyHistoryBody(
      HistoryRangeResult range, HistoryRequestContext request) {
    if (!v2Projection || projectionState == null || range == null || request == null) {
      return new HistoryBodyResult.Rejected(HistoryBodyFault.STALE_PROJECTION);
    }
    HistoryBodyResult result = historyBodyReducer.apply(
        range, request, projectionState.mainSurface, currentEvictionPins(request.anchorSeq()));
    if (!(result instanceof HistoryBodyResult.Applied)) return result;

    HistoryBodyResult.Applied applied = (HistoryBodyResult.Applied) result;
    projectionState = new ProjectionState(
        projectionState.identity,
        projectionState.screenRevision,
        projectionState.dictionaryGeneration,
        projectionState.rows,
        projectionState.columns,
        projectionState.activeBuffer,
        applied.state(),
        projectionState.alternateSurface,
        projectionState.cursor,
        projectionState.modes,
        projectionState.palette);
    if (activeBuffer == TerminalBufferKind.MAIN) {
      pendingRenderDirty.mergeHistoryRange(
          applied.changedFromSeq(), applied.changedToSeq(), false);
      markTerminalState(false, true, false, false, 0, 0);
      publishPendingRenderUpdate();
    }
    return result;
  }

  public synchronized boolean applyHistoryRange(
      HistoryRangeResult range, long anchorSeq, long requestedFromSeq, long requestedToSeq) {
    if (!v2Projection || range == null || !Objects.equals(instanceId, range.instanceId)
        || layoutEpoch != range.layoutEpoch
        || historyGeneration != range.historyGeneration) {
      return false;
    }
    if (range.status == HistoryRangeResult.Status.STALE_PROJECTION) {
      if (!range.lines.isEmpty()) {
        throw new IllegalArgumentException("stale HistoryRange must not contain lines");
      }
      return false;
    }
    if (range.status == HistoryRangeResult.Status.RETRYABLE) {
      if (!range.lines.isEmpty()) {
        throw new IllegalArgumentException("retryable HistoryRange must not contain lines");
      }
      return false;
    }
    if (requestedFromSeq < 1 || requestedToSeq < requestedFromSeq) {
      throw new IllegalArgumentException("invalid HistoryRange request bounds");
    }

    HistoryBodyResult semanticResult = null;
    if (projectionState != null) {
      semanticResult = historyBodyReducer.apply(
          range,
          new HistoryRequestContext(
              projectionState.identity, requestedFromSeq, requestedToSeq, anchorSeq),
          projectionState.mainSurface,
          currentEvictionPins(anchorSeq));
      if (semanticResult instanceof HistoryBodyResult.Rejected) {
        HistoryBodyFault fault = ((HistoryBodyResult.Rejected) semanticResult).fault();
        if (fault == HistoryBodyFault.STALE_PROJECTION) return false;
        throw new IllegalArgumentException(fault.name());
      }
    }

    TerminalSurface targetSurface = mainSurface;
    HistoryExtent currentExtent = targetSurface.historyIndex.extent();

    // 响应可能含请求发出后已裁剪的前缀；只写入当前 extent 内仍存活的正文。
    List<TerminalLine> normalizedLines = new ArrayList<>(range.lines.size());
    Set<Long> responseLineIds = new HashSet<>();
    long previousSeq = 0;
    for (HistoryBodyEntry entry : range.lines) {
      TerminalLine normalized =
          normalizeHistoryLine(legacyLine(entry.key(), entry.historySeq(), entry.body()));
      long seq = normalized.historySeq;
      if (seq < requestedFromSeq || seq > requestedToSeq) {
        throw new IllegalArgumentException("HistoryRange line outside negotiated bounds");
      }
      if (previousSeq != 0 && seq <= previousSeq) {
        throw new IllegalArgumentException("HistoryRange lines are not strictly increasing");
      }
      if (!responseLineIds.add(normalized.id)) {
        throw new IllegalArgumentException("HistoryRange contains duplicate LineID");
      }
      previousSeq = seq;
      // 已裁剪前缀：丢弃，不拒绝整段。
      if (!currentExtent.contains(seq)) {
        continue;
      }
      normalizedLines.add(normalized);
    }
    PagedTerminalHistorySnapshot beforeWrite = targetSurface.history.snapshot();
    PagedTerminalHistory.Editor editor = targetSurface.history.edit()
        .setExtent(currentExtent.firstSeq, currentExtent.lastSeq)
        .setAvailableExtent(currentExtent.firstSeq, currentExtent.lastSeq);
    LineStore.Editor lineEditor = targetSurface.lineStore.edit();
    HistoryIndex.Editor historyIndexEditor =
        targetSurface.historyIndex.edit().setExtent(currentExtent);
    // HTTP Range 只补正文。它可能比 WS 位置提交更旧：这种绑定冲突是正常
    // 乱序，必须丢弃旧正文并让 Loader 按当前 WS Ref 重新请求。
    List<TerminalLine> appliedLines = new ArrayList<>(normalizedLines.size());
    for (TerminalLine normalized : normalizedLines) {
      if (targetSurface.activeRows.contains(normalized.id)) {
        continue;
      }
      long historySeq = normalized.historySeq;
      HistoryLineRef ref = historyIndexEditor.ref(historySeq);
      if (ref == null && historyCatalogComplete) {
        continue;
      }
      if (ref != null
          && (ref.lineId != normalized.id || ref.lineVersion != normalized.version)) {
        continue;
      }
      int logicalIndex = beforeWrite.findSeqIndex(historySeq);
      if (logicalIndex >= 0 && beforeWrite.slotStateAt(logicalIndex) == SlotState.LOADED) {
        TerminalLine loaded = beforeWrite.lineBySeq(historySeq);
        if (loaded == null || !loaded.sameContent(normalized)) {
          throw new IllegalArgumentException("same history line version changed body");
        }
        continue; // 完全相同的正文重放：幂等忽略
      }
      try {
        TerminalLine canonical = lineEditor.put(normalized);
        if (ref == null) {
          historyIndexEditor.bind(historySeq, canonical.id, canonical.version);
        }
        editor.put(historySeq, canonical);
        appliedLines.add(normalized);
      } catch (CommitValidationException invalidLineage) {
        throw new IllegalArgumentException(invalidLineage.failure.name(), invalidLineage);
      }
    }
    long fallbackAnchor = anchorSeq > 0 ? anchorSeq : currentExtent.lastSeq;
    editor.evictIfNeeded(currentEvictionPins(fallbackAnchor)).commit();
    Set<Long> loadedHistoryIds = targetSurface.history.snapshot().loadedLineIds();
    Set<Long> retainedLineIds = new HashSet<>(loadedHistoryIds);
    for (int row = 0; row < targetSurface.activeRows.size(); row++) {
      retainedLineIds.add(targetSurface.activeRows.lineIdAt(row));
    }
    lineEditor.retainOnly(retainedLineIds);
    historyIndexEditor.commit();
    lineEditor.commit();
    if (semanticResult instanceof HistoryBodyResult.Applied) {
      HistoryBodyResult.Applied applied = (HistoryBodyResult.Applied) semanticResult;
      projectionState = new ProjectionState(
          projectionState.identity,
          projectionState.screenRevision,
          projectionState.dictionaryGeneration,
          projectionState.rows,
          projectionState.columns,
          projectionState.activeBuffer,
          applied.state(),
          projectionState.alternateSurface,
          projectionState.cursor,
          projectionState.modes,
          projectionState.palette);
    }
    if (activeBuffer == TerminalBufferKind.MAIN) {
      markRenderDirty(false, null, 0, null, rows, false, false, false, -1, -1,
          false, false, false, false, false);
      mergeHistoryDirtyRange(appliedLines, false);
      markTerminalState(false, true, false, false, 0, 0);
      publishPendingRenderUpdate();
    }
    return !appliedLines.isEmpty();
  }

  /** Runtime 更新视口/请求 pins；后续 Baseline/Commit/Range 驱逐共用。 */
  public synchronized void setEvictionPins(EvictionPins pins) {
    evictionPins = pins == null ? EvictionPins.NONE : pins;
  }

  private EvictionPins currentEvictionPins(long fallbackAnchor) {
    if (evictionPins != null && evictionPins != EvictionPins.NONE) {
      return evictionPins;
    }
    return EvictionPins.forAnchor(fallbackAnchor);
  }

  public synchronized long dictionaryGeneration() { return dictionaryGeneration; }

  public synchronized long historyGeneration() { return historyGeneration; }

  /** 当前 Surface 的唯一正文存储；调用方只读。 */
  public synchronized LineStore lineStore() {
    return activeSurface().lineStore;
  }

  public synchronized BodyCache bodyCache() {
    return projectionState == null
        ? new BodyCache(historyBudget) : projectionState.activeSurface().bodyCache;
  }

  /** 当前 Surface 的 rowIndex → LineID 位置索引。 */
  public synchronized ActiveRows activeRows() {
    return activeSurface().activeRows;
  }

  /** 当前 Surface 已加载历史的 historySeq → LineID 位置索引。 */
  public synchronized HistoryIndex historyIndex() {
    return activeSurface().historyIndex;
  }

  public synchronized HistoryCatalog historyCatalog() {
    return projectionState == null
        ? new HistoryCatalog() : projectionState.activeSurface().historyCatalog;
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

  /** 最近一次成功 publish 分配的 publicationVersion（含已消费）；从未发布时为 0。 */
  public long lastPublicationVersion() {
    return publicationVersion.get();
  }

  public synchronized void requestFullRender() {
    markRenderDirty(true, null, 0, null, rows, false, false, false, -1, -1,
        false, false, false, false, false);
    publishPendingRenderUpdate();
  }

  public synchronized int historySize() {
    return projectionState != null
        ? (int) Math.min(Integer.MAX_VALUE,
            projectionState.activeSurface().historyCatalog.extent().logicalSize())
        : activeSurface().history.snapshot().size();
  }

  public synchronized long firstCachedHistorySeq() {
    return projectionState != null
        ? new SemanticHistoryRenderView(
            projectionState.activeSurface().historyCatalog,
            projectionState.activeSurface().bodyCache).firstLoadedSeq()
        : activeSurface().history.snapshot().firstLoadedSeq();
  }

  public synchronized long historyBytes() {
    return projectionState != null
        ? projectionState.activeSurface().bodyCache.estimatedHistoryBytes()
        : activeSurface().history.snapshot().estimatedByteCount();
  }

  synchronized long loadedHistoryLineCountForTest() {
    return activeSurface().history.snapshot().loadedLineCount();
  }

  synchronized int residentHistoryPageCountForTest() {
    return activeSurface().history.residentPageCountForTest();
  }

  synchronized int loadedLineIdentityCountForTest() {
    return (screen == null ? 0 : screen.length) + activeSurface().history.loadedLineIdentityCountForTest();
  }

  synchronized Long loadedHistorySeqForLineIdForTest(long lineId) {
    return activeSurface().history.historySeqByLineId(lineId);
  }

  boolean renderPublicationPendingForTest() {
    return pendingPublication.get() != null;
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
    if (line == null || line.cells == null
        || line.cells.length < 1 || line.cells.length > 500
        || line.id <= 0 || line.version <= 0 || line.historySeq <= 0
        || !referencesComplete(line)) {
      throw new IllegalStateException("screen.v2 contains invalid history line");
    }
    return line;
  }

  /**
   * Phase-1 renderer adapter. Wire 字典 ID 已在 protocol 边界消失；这里的旧渲染
   * 类型只承载语义值，后续 Renderer 迁移到 RenderLine 后删除。
   */
  private static TerminalLine legacyLine(LineKey key, long historySeq, LineBody body) {
    TerminalCell[] cells = new TerminalCell[body.length()];
    for (int column = 0; column < body.length(); column++) {
      CellValue cell = body.at(column);
      StyleValue style = cell.style();
      LinkValue link = cell.link();
      TerminalStyle legacyStyle = style == null ? null : new TerminalStyle(
          0, style.fg(), style.bg(), style.underlineColor(), style.attrs());
      Hyperlink legacyLink = link == null ? null : new Hyperlink(0, link.uri());
      cells[column] = cell.isDefault() ? TerminalCell.EMPTY
          : cell.isSpacer() ? TerminalCell.SPACER
          : new TerminalCell(cell.text(), cell.width(), legacyStyle, legacyLink);
    }
    return new TerminalLine(
        key.lineId(), key.lineVersion(), historySeq, body.wrapped, cells);
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
  static long estimateHistoryLineBytesForStore(TerminalLine line) {
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

  private void mergeHistoryPushDirtyRange(
      List<HistoryPush> pushes, boolean structureChanged) {
    long from = Long.MAX_VALUE;
    long to = Long.MIN_VALUE;
    for (HistoryPush push : pushes) {
      if (push == null || !displayExtent.contains(push.historySeq)) continue;
      from = Math.min(from, push.historySeq);
      to = Math.max(to, push.historySeq);
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
    long publicationStartedNanos = System.nanoTime();
    try {
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
            new RenderUpdate(version, currentSnapshot, currentDirty, currentState));
      }
      RenderDirtyState mergedDirty = new RenderDirtyState();
      mergedDirty.mergeFrom(previous.update.dirty, rows);
      mergedDirty.mergeFrom(currentDirty, rows);
      TerminalStateUpdate mergedState = new TerminalStateUpdate();
      mergedState.mergeFrom(previous.update.state);
      mergedState.mergeFrom(currentState);
      return new RenderPublication(version,
          new RenderUpdate(version, currentSnapshot, mergedDirty, mergedState));
    });
    } finally {
      TerminalRenderMetrics.renderPublicationDuration(
          System.nanoTime() - publicationStartedNanos);
    }
  }

  private void publishRenderSnapshot(RenderDirtyState dirty) {
    RenderSnapshot previous = renderSnapshot;
    // TerminalLine is immutable. Metadata-only and history-only patches therefore safely reuse
    // the previous screen array rather than cloning rows that did not change.
    boolean screenChanged = dirty.fullInvalidate || dirty.geometryChanged
        || dirty.activeBufferChanged || !dirty.changedScreenRows.isEmpty()
        || dirty.screenScrollRows != 0 || !dirty.exposedScreenRows.isEmpty();
    boolean semantic = projectionState != null;
    TerminalLine[] screenCopy = screenChanged
        ? semantic ? renderScreen(projectionState.activeSurface())
            : screen != null ? screen.clone() : null
        : previous.screen;
    HistoryRenderView historySnapshot = dirty.historyChanged || dirty.fullInvalidate
        ? semantic
            ? new SemanticHistoryRenderView(
                projectionState.activeSurface().historyCatalog,
                projectionState.activeSurface().bodyCache)
            : activeSurface().history.snapshot()
        : previous.history;
    renderSnapshot = new RenderSnapshot(
        instanceId, layoutEpoch, screenRevision, historyGeneration, rows, columns,
        activeBuffer, screenCopy, historySnapshot,
        semantic
            ? UnifiedContentAxis.build(projectionState.activeSurface())
            : UnifiedContentAxis.build(
                activeSurface().history.snapshot(),
                activeSurface().activeRows, activeSurface().lineStore),
        cursor, modes, palette,
        firstAvailableHistorySeq,
        hasMoreHistoryBefore);
    publishProjectionReadView();
  }

  private void publishProjectionReadView() {
    HistoryExtent mainExtent = projectionState == null
        ? HistoryExtent.INITIAL_EMPTY
        : projectionState.mainSurface.historyCatalog.extent();
    projectionReadView = new ProjectionReadView(
        instanceId, layoutEpoch, screenRevision, historyGeneration,
        mainExtent,
        displayExtent, remoteAvailableExtent,
        projectionHealth.complete);
  }

  private static TerminalLine[] renderScreen(TerminalSurfaceState surface) {
    TerminalLine[] result = new TerminalLine[surface.activeRows.size()];
    for (int row = 0; row < result.length; row++) {
      LineKey key = surface.activeRows.keyAt(row);
      LineBody body = surface.bodyCache.body(key);
      if (body == null) {
        throw new IllegalStateException("active row body missing for " + key);
      }
      result[row] = SemanticLineAdapter.renderLine(key, 0, body);
    }
    return result;
  }

  private static CommitFailure commitFailure(ProjectionFault fault) {
    return switch (fault) {
      case IDENTITY_MISMATCH -> CommitFailure.IDENTITY_MISMATCH;
      case LAYOUT_EPOCH_MISMATCH -> CommitFailure.LAYOUT_EPOCH_MISMATCH;
      case REVISION_GAP -> CommitFailure.REVISION_GAP;
      case DICTIONARY_GENERATION_MISMATCH ->
          CommitFailure.DICTIONARY_GENERATION_MISMATCH;
      case HISTORY_GENERATION_MISMATCH ->
          CommitFailure.HISTORY_GENERATION_MISMATCH;
      case INVALID_HISTORY_MUTATION -> CommitFailure.INVALID_HISTORY_SEQUENCE;
      case INVALID_BASELINE, INVALID_SCREEN_MUTATION -> CommitFailure.INVALID_LINE_DATA;
    };
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
    public final long historyGeneration;
    public final int rows;
    public final int columns;
    public final TerminalBufferKind activeBuffer;
    public final TerminalLine[] screen;
    /** Sparse immutable history snapshot for indexed rendering. */
    public final HistoryRenderView history;
    /** 历史、缺失占位和 ActiveRows 组成的单一纵向坐标空间。 */
    public final UnifiedContentAxis contentAxis;
    public final TerminalCursor cursor;
    public final TerminalModes modes;
    public final TerminalPalette palette;
    public final long firstAvailableHistorySeq;
    public final boolean hasMoreHistoryBefore;

    private RenderSnapshot(String instanceId, long layoutEpoch, long screenRevision,
                           long historyGeneration, int rows, int columns,
                           TerminalBufferKind activeBuffer,
                           TerminalLine[] screen, HistoryRenderView history,
                           UnifiedContentAxis contentAxis,
                           TerminalCursor cursor, TerminalModes modes, TerminalPalette palette,
                           long firstAvailableHistorySeq,
                           boolean hasMoreHistoryBefore) {
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.screenRevision = screenRevision;
      this.historyGeneration = historyGeneration;
      this.rows = rows;
      this.columns = columns;
      this.activeBuffer = activeBuffer;
      this.screen = screen;
      this.history = history;
      this.contentAxis = contentAxis;
      this.cursor = cursor;
      this.modes = modes;
      this.palette = palette;
      this.firstAvailableHistorySeq = firstAvailableHistorySeq;
      this.hasMoreHistoryBefore = hasMoreHistoryBefore;
    }

    private static RenderSnapshot empty() {
      return new RenderSnapshot(null, 0, 0, 0, 0, 0, TerminalBufferKind.MAIN, null,
          TerminalHistorySnapshot.empty(), UnifiedContentAxis.empty(), TerminalCursor.hidden(),
          TerminalModes.defaults(), TerminalPalette.defaults(), 0, false);
    }
  }

  public static class RevisionGapException extends Exception {
    public RevisionGapException(String message) {
      super(message);
    }

    public RevisionGapException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
