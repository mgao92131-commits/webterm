package com.webterm.terminal.model;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;

/**
 * Android 远程终端模型。
 *
 * <p>实时位置轴只由 {@link ScreenProjectionReducer} 修改，HTTP 历史响应只经
 * {@link HistoryBodyReducer} 填充 {@link BodyCache}。模型本身只负责原子替换投影 root、
 * 发布渲染快照和维护 Runtime 所需的轻量只读状态。</p>
 */
public final class RemoteTerminalModel {
  public static final long SCHEMA_GENERATION = 3L;

  public String instanceId;
  public long layoutEpoch;
  public long screenRevision;
  public int rows;
  public int columns;
  public TerminalBufferKind activeBuffer = TerminalBufferKind.MAIN;

  private final HistoryBudget historyBudget;
  private final ScreenProjectionReducer projectionReducer;
  private final HistoryBodyReducer historyBodyReducer = new HistoryBodyReducer();
  private ProjectionState state;
  private long historyGeneration;
  private long historyTopologyHash;
  private boolean historyTopologyKnown;
  private boolean reuseHistoryTopologyForNextPublish;
  private EvictionPins evictionPins = EvictionPins.NONE;

  private volatile RenderSnapshot renderSnapshot = RenderSnapshot.empty();
  private volatile ProjectionReadView projectionReadView = ProjectionReadView.empty();
  private volatile ProjectionHealth projectionHealth =
      ProjectionHealth.incomplete(SCHEMA_GENERATION);
  private RenderDirtyState pendingRenderDirty = new RenderDirtyState();
  private TerminalStateUpdate pendingTerminalState = new TerminalStateUpdate();
  private final AtomicReference<RenderPublication> pendingPublication =
      new AtomicReference<>();
  private final AtomicReference<Object> renderPublicationAuthority =
      new AtomicReference<>();
  private final AtomicLong publicationVersion = new AtomicLong();
  private Executor publicationExecutor;
  private Runnable publicationFlushedListener;
  private boolean publicationFlushScheduled;
  private boolean pendingForceSnapshotRebuild;
  private long snapshotBuildCount;
  private long publicationFlushCount;
  /** 已安装的 projection/body 状态版本，以及最近一次 RenderSnapshot 对应的版本。 */
  private long stateMutationVersion;
  private long snapshotMutationVersion;

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

    private ProjectionReadView(
        String instanceId,
        long layoutEpoch,
        long screenRevision,
        long historyGeneration,
        HistoryExtent mainHistoryExtent,
        boolean projectionComplete) {
      this.instanceId = instanceId == null ? "" : instanceId;
      this.layoutEpoch = layoutEpoch;
      this.screenRevision = screenRevision;
      this.historyGeneration = historyGeneration;
      this.mainHistoryExtent = mainHistoryExtent;
      this.displayExtent = mainHistoryExtent;
      this.remoteAvailableExtent = mainHistoryExtent;
      this.projectionComplete = projectionComplete;
    }

    private static ProjectionReadView empty() {
      return new ProjectionReadView(
          "", 0, 0, 0, HistoryExtent.INITIAL_EMPTY, false);
    }
  }

  public RemoteTerminalModel() {
    this(HistoryBudget.defaults());
  }

  public RemoteTerminalModel(int softHistoryLimit, int hardHistoryLimit) {
    this(softHistoryLimit, hardHistoryLimit,
        HistoryBudget.DEFAULT_SOFT_BYTES, HistoryBudget.DEFAULT_HARD_BYTES);
  }

  public RemoteTerminalModel(
      int softHistoryLimit,
      int hardHistoryLimit,
      long softHistoryByteLimit,
      long hardHistoryByteLimit) {
    this(new HistoryBudget(
        softHistoryLimit, hardHistoryLimit,
        softHistoryByteLimit, hardHistoryByteLimit));
  }

  public RemoteTerminalModel(HistoryBudget budget) {
    historyBudget = budget == null ? HistoryBudget.defaults() : budget;
    projectionReducer = new ScreenProjectionReducer(historyBudget);
  }

  /**
   * 将快照物化投递到与模型 mutation 相同的串行 executor。
   *
   * <p>未绑定时保留同步行为，兼容 JVM 测试和旧调用方；Runtime 绑定自己的 modelExecutor
   * 后，连续 mutation 只在队尾安排一个 flush task。</p>
   */
  public synchronized void bindRenderPublicationExecutor(Executor executor) {
    bindRenderPublicationExecutor(executor, null);
  }

  /** 绑定 Runtime 的 render-needed 通知；通知发生在快照真正物化之后。 */
  public synchronized void bindRenderPublicationExecutor(
      Executor executor, Runnable publicationFlushedListener) {
    if (executor == null) throw new NullPointerException("executor");
    publicationExecutor = executor;
    this.publicationFlushedListener = publicationFlushedListener;
    if ((!pendingRenderDirty.isEmpty() || !pendingTerminalState.isEmpty())
        && !publicationFlushScheduled) {
      schedulePublicationFlushLocked();
    }
  }

  /**
   * Baseline 总是重建权威位置轴。
   * 对相同 instanceId/historyGeneration 且精确 LineKey 匹配的
   * immutable LineBody，可迁移到新 Surface。
   */
  public synchronized boolean applyBaseline(ScreenBaseline baseline) {
    return applyBaselineDetailed(baseline) instanceof ProjectionResult.Applied;
  }

  /** Runtime 使用的结构化入口；保留 reducer 的具体 Baseline fault。 */
  public synchronized ProjectionResult applyBaselineDetailed(ScreenBaseline baseline) {
    // 每次 Baseline 都重新判定拓扑复用；失败或不兼容时不能沿用上一次标志。
    reuseHistoryTopologyForNextPublish = false;
    ProjectionResult result = projectionReducer.applyBaseline(baseline);
    if (!(result instanceof ProjectionResult.Applied applied)) return result;
    ProjectionState previous = state;
    boolean previousStateMaterialized = state != null
        && stateMutationVersion == snapshotMutationVersion;
    ProjectionState next = applied.state();
    boolean compatibleHistoryTopology = previous != null
        && baseline.activeBuffer == TerminalBufferKind.MAIN
        && previous.activeBuffer == TerminalBufferKind.MAIN
        && previous.identity.instanceId().equals(baseline.instanceId)
        && previous.identity.layoutEpoch() == baseline.layoutEpoch
        && previous.identity.historyGeneration() == baseline.historyGeneration
        && previousStateMaterialized
        && historyTopologyKnown
        && historyTopologyHash == baseline.historyTopologyHash
        && previous.mainSurface.historyCatalog.extent().equals(baseline.historyExtent);
    boolean topologyReused = false;
    BaselineBodyReuse.Outcome reuse = null;
    if (compatibleHistoryTopology) {
      reuse = BaselineBodyReuse.tryReuseCompatibleHistoryTopology(
          previous, next, baseline, evictionPins);
      topologyReused = reuse != null;
    }
    if (reuse == null) {
      reuse = BaselineBodyReuse.reuse(previous, next, baseline, evictionPins);
    }
    BaselineBodyReuseMetrics.record(reuse);
    if (reuse instanceof BaselineBodyReuse.Outcome.Conflict) {
      return new ProjectionResult.NeedsBaseline(ProjectionFault.BASELINE_BODY_CONFLICT);
    }
    if (reuse instanceof BaselineBodyReuse.Outcome.Applied migrated) {
      next = migrated.state();
      if (topologyReused) {
        // Catalog 是不可变拓扑；保留其对象身份，正文缓存仍使用新 root。
        TerminalSurfaceState migratedMain = next.mainSurface;
        TerminalSurfaceState reusedMain = new TerminalSurfaceState(
            migratedMain.activeRows,
            previous.mainSurface.historyCatalog,
            migratedMain.bodyCache);
        next = new ProjectionState(
            next.identity, next.screenRevision, next.rows, next.columns,
            next.activeBuffer, reusedMain, next.alternateSurface,
            next.cursor, next.modes, next.palette);
        reuseHistoryTopologyForNextPublish = true;
        BaselineBodyReuseMetrics.topologyFastPath();
      }
    } else if (reuse instanceof BaselineBodyReuse.Outcome.IdentityRejected rejected) {
      next = rejected.state();
    }
    historyTopologyHash = baseline.historyTopologyHash;
    historyTopologyKnown = true;
    install(next);
    stateMutationVersion++;
    boolean geometryChanged =
        previous == null || previous.rows != rows || previous.columns != columns;
    pendingRenderDirty.merge(
        true, null, 0, null, rows, true, geometryChanged,
        true, previous == null ? -1 : previous.cursor.row, state.cursor.row,
        true, true, true, true, true);
    pendingRenderDirty.mergeHistoryRange(
        displayExtent().firstSeq, displayExtent().lastSeq, true);
    pendingTerminalState.merge(geometryChanged, true, false, false, 0, 0);
    publishPendingRenderUpdate(true);
    return new ProjectionResult.Applied(next, applied.delta());
  }

  public boolean applyTerminalCommit(TerminalCommit commit)
      throws RevisionGapException {
    return stageCommit(commit).commit();
  }

  /**
   * 在不修改已发布 state 的前提下完成全部 WS 校验，并返回一次性提交对象。
   */
  public synchronized StagedCommit stageCommit(TerminalCommit commit)
      throws RevisionGapException {
    ProjectionResult result =
        projectionReducer.applyCommit(state, commit, evictionPins);
    if (!(result instanceof ProjectionResult.Applied applied)) {
      ProjectionFault fault = ((ProjectionResult.NeedsBaseline) result).fault();
      throw new CommitValidationException(commitFailure(fault));
    }
    ProjectionState previous = state;
    ProjectionState next = applied.state();
    ProjectionDelta delta = applied.delta();
    boolean cursorChanged = !Objects.equals(previous.cursor, next.cursor);
    boolean modesChanged = !Objects.equals(previous.modes, next.modes);
    boolean paletteChanged = !Objects.equals(previous.palette, next.palette);
    boolean bufferChanged = previous.activeBuffer != next.activeBuffer;
    boolean renderChanged = delta.screenChanged() || delta.historyChanged()
        || delta.geometryChanged() || cursorChanged || modesChanged
        || paletteChanged || bufferChanged;
    return new StagedCommit(commit.baseRevision, () -> {
      HistoryExtent oldExtent = previous.mainSurface.historyCatalog.extent();
      install(next);
      stateMutationVersion++;
      pendingRenderDirty.merge(
          false, delta.changedRows(), delta.screenScrollRows(), delta.exposedRows(), rows,
          delta.historyChanged(), delta.geometryChanged(), cursorChanged,
          previous.cursor.row, next.cursor.row,
          paletteChanged, false, false, modesChanged, bufferChanged);
      if (delta.historyChanged()) {
        historyTopologyKnown = false;
        // Baseline 的兼容拓扑复用只对当前 pending publication 有效；一旦队尾又
        // 合并了会改变历史拓扑的 commit，不能把旧 HistoryPart 带进最终快照。
        reuseHistoryTopologyForNextPublish = false;
        HistoryExtent newExtent = next.mainSurface.historyCatalog.extent();
        mergeExtentDirty(oldExtent, newExtent);
        if (commit.history != null) {
          mergePushDirty(commit.history.pushes, !oldExtent.equals(newExtent));
        }
      }
      pendingTerminalState.merge(
          delta.geometryChanged(), delta.historyChanged(),
          false, false, 0, 0);
      if (renderChanged) publishPendingRenderUpdate();
      else publishProjectionReadView();
      return renderChanged;
    });
  }

  @FunctionalInterface
  private interface CommitAction {
    boolean run();
  }

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

  public synchronized HistoryBodyResult applyLineBodyBatch(
      LineBodyBatchResult batch, BodyBatchRequestContext request) {
    if (state == null) {
      return new HistoryBodyResult.Rejected(HistoryBodyFault.STALE_PROJECTION);
    }
    HistoryBodyResult result = historyBodyReducer.apply(
        batch, request, state.mainSurface, currentEvictionPins(0));
    if (!(result instanceof HistoryBodyResult.Applied applied)) return result;
    state = new ProjectionState(
        state.identity,
        state.screenRevision,
        state.rows,
        state.columns,
        state.activeBuffer,
        applied.state(),
        state.alternateSurface,
        state.cursor,
        state.modes,
        state.palette);
    // Body batch 改变的是历史正文驻留，不改变目录拓扑；但旧 snapshot 中的 HistoryPart
    // 仍然不能被兼容 Baseline fast path 直接复用。
    reuseHistoryTopologyForNextPublish = false;
    stateMutationVersion++;
    if (activeBuffer == TerminalBufferKind.MAIN) {
      if (applied.changedFromSeq() > 0 && applied.changedToSeq() >= applied.changedFromSeq()) {
        pendingRenderDirty.mergeHistoryRange(
            applied.changedFromSeq(), applied.changedToSeq(), false);
      }
      pendingTerminalState.merge(false, true, false, false, 0, 0);
      publishPendingRenderUpdate();
    } else {
      publishProjectionReadView();
    }
    return result;
  }

  /** @deprecated 测试与旧 seq-range 兼容；运行时使用 {@link #applyLineBodyBatch} */
  @Deprecated
  public synchronized HistoryBodyResult applyHistoryBody(
      HistoryRangeResult range, HistoryRequestContext request) {
    if (state == null) {
      return new HistoryBodyResult.Rejected(HistoryBodyFault.STALE_PROJECTION);
    }
    HistoryBodyResult result = historyBodyReducer.apply(
        range, request, state.mainSurface, currentEvictionPins(request.anchorSeq()));
    if (!(result instanceof HistoryBodyResult.Applied applied)) return result;
    state = new ProjectionState(
        state.identity,
        state.screenRevision,
        state.rows,
        state.columns,
        state.activeBuffer,
        applied.state(),
        state.alternateSurface,
        state.cursor,
        state.modes,
        state.palette);
    reuseHistoryTopologyForNextPublish = false;
    stateMutationVersion++;
    if (activeBuffer == TerminalBufferKind.MAIN) {
      pendingRenderDirty.mergeHistoryRange(
          applied.changedFromSeq(), applied.changedToSeq(), false);
      pendingTerminalState.merge(false, true, false, false, 0, 0);
      publishPendingRenderUpdate();
    } else {
      publishProjectionReadView();
    }
    return result;
  }

  public synchronized void setEvictionPins(EvictionPins pins) {
    evictionPins = pins == null ? EvictionPins.NONE : pins;
  }

  private EvictionPins currentEvictionPins(long fallbackAnchor) {
    return evictionPins == EvictionPins.NONE
        ? EvictionPins.forAnchor(fallbackAnchor) : evictionPins;
  }

  public synchronized long historyGeneration() {
    return historyGeneration;
  }

  public synchronized BodyCache bodyCache() {
    return state == null ? new BodyCache(historyBudget) : state.activeSurface().bodyCache;
  }

  public synchronized ActiveRowLayout activeRows() {
    return state == null ? ActiveRowLayout.empty() : state.activeSurface().activeRows;
  }

  public synchronized HistoryCatalog historyCatalog() {
    return state == null ? new HistoryCatalog() : state.activeSurface().historyCatalog;
  }

  public synchronized boolean isV2Projection() {
    return state != null;
  }

  public synchronized HistoryExtent displayExtent() {
    return state == null
        ? HistoryExtent.INITIAL_EMPTY : state.mainSurface.historyCatalog.extent();
  }

  public synchronized HistoryExtent remoteAvailableExtent() {
    return displayExtent();
  }

  public synchronized boolean staleProjection() {
    return false;
  }

  public synchronized ProjectionHealth projectionHealth() {
    return projectionHealth;
  }

  public RenderSnapshot renderSnapshot() {
    return renderSnapshot;
  }

  public RenderSnapshot peekRenderSnapshot() {
    return renderSnapshot;
  }

  public ProjectionReadView projectionReadView() {
    return projectionReadView;
  }

  public void bindRenderPublicationAuthority(Object authority) {
    if (authority == null) throw new NullPointerException("authority");
    Object current = renderPublicationAuthority.get();
    if (current == authority) return;
    if (!renderPublicationAuthority.compareAndSet(null, authority)) {
      throw new IllegalStateException("render publication authority already bound");
    }
  }

  public RenderUpdate consumeRenderUpdate(Object authority) {
    if (authority == null || renderPublicationAuthority.get() != authority) {
      throw new IllegalStateException("invalid render publication authority");
    }
    return consumeRenderPublication();
  }

  public RenderUpdate consumeRenderUpdate() {
    if (renderPublicationAuthority.get() != null) {
      throw new IllegalStateException(
          "RenderUpdate must be consumed through TerminalSessionRuntime");
    }
    return consumeRenderPublication();
  }

  private RenderUpdate consumeRenderPublication() {
    RenderPublication publication = pendingPublication.getAndSet(null);
    return publication == null ? null : publication.update;
  }

  public long lastPublicationVersion() {
    return publicationVersion.get();
  }

  public synchronized void requestFullRender() {
    pendingRenderDirty.merge(
        true, null, 0, null, rows, false, false,
        false, -1, -1, false, false, false, false, false);
    publishPendingRenderUpdate();
  }

  public synchronized int historySize() {
    return (int) Math.min(Integer.MAX_VALUE, displayExtent().logicalSize());
  }

  public synchronized long firstCachedHistorySeq() {
    if (state == null) return 0;
    return new SemanticHistoryRenderView(
        state.mainSurface.historyCatalog,
        state.mainSurface.bodyCache).firstLoadedSeq();
  }

  public synchronized long historyBytes() {
    return state == null ? 0 : state.mainSurface.bodyCache.estimatedHistoryBytes();
  }

  public synchronized TerminalCursor cursor() {
    return state == null ? TerminalCursor.hidden() : state.cursor;
  }

  public synchronized TerminalModes modes() {
    return state == null ? TerminalModes.defaults() : state.modes;
  }

  public synchronized TerminalPalette palette() {
    return state == null ? TerminalPalette.defaults() : state.palette;
  }

  public synchronized long firstAvailableHistorySeq() {
    return displayExtent().firstSeq;
  }

  public synchronized boolean hasMoreHistoryBefore() {
    return false;
  }

  boolean renderPublicationPendingForTest() {
    return pendingPublication.get() != null;
  }

  private void install(ProjectionState next) {
    state = next;
    instanceId = next.identity.instanceId();
    layoutEpoch = next.identity.layoutEpoch();
    historyGeneration = next.identity.historyGeneration();
    screenRevision = next.screenRevision;
    rows = next.rows;
    columns = next.columns;
    activeBuffer = next.activeBuffer;
    projectionHealth = ProjectionHealth.complete(
        instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION);
  }

  private void mergePushDirty(List<HistoryPush> pushes, boolean structureChanged) {
    long from = Long.MAX_VALUE;
    long to = 0;
    HistoryExtent extent = displayExtent();
    for (HistoryPush push : pushes) {
      if (push == null || !extent.contains(push.historySeq)) continue;
      from = Math.min(from, push.historySeq);
      to = Math.max(to, push.historySeq);
    }
    pendingRenderDirty.mergeHistoryRange(
        from == Long.MAX_VALUE ? 1 : from, to, structureChanged);
  }

  private void mergeExtentDirty(HistoryExtent previous, HistoryExtent current) {
    if (previous.equals(current)) return;
    if (!previous.isEmpty()) {
      pendingRenderDirty.mergeHistoryRange(
          previous.firstSeq, previous.lastSeq, true);
    }
    if (!current.isEmpty()) {
      pendingRenderDirty.mergeHistoryRange(
          current.firstSeq, current.lastSeq, true);
    }
  }

  private void publishPendingRenderUpdate() {
    publishPendingRenderUpdate(false);
  }

  private void publishPendingRenderUpdate(boolean forceSnapshotRebuild) {
    if (pendingRenderDirty.isEmpty() && pendingTerminalState.isEmpty()) return;
    pendingForceSnapshotRebuild |= forceSnapshotRebuild;
    if (publicationExecutor != null) {
      if (!publicationFlushScheduled) schedulePublicationFlushLocked();
      return;
    }
    flushPendingRenderPublicationLocked();
  }

  private void schedulePublicationFlushLocked() {
    if (publicationFlushScheduled || publicationExecutor == null) return;
    publicationFlushScheduled = true;
    try {
      publicationExecutor.execute(this::flushPendingRenderPublication);
    } catch (RuntimeException error) {
      publicationFlushScheduled = false;
      // Executor 已关闭时保持旧的同步语义，不能把已安装的 projection 永久留在
      // 没有 RenderSnapshot 的状态。
      if (flushPendingRenderPublicationLocked()
          && publicationFlushedListener != null) {
        // 这里只会发生在 executor 拒绝任务的异常兜底路径；正常路径的 listener
        // 在退出 model monitor 后调用。回调本身不能依赖新的 executor 任务。
        publicationFlushedListener.run();
      }
    }
  }

  private void flushPendingRenderPublication() {
    Runnable listener = null;
    synchronized (this) {
      publicationFlushScheduled = false;
      boolean flushed = flushPendingRenderPublicationLocked();
      if (flushed) listener = publicationFlushedListener;
      if ((!pendingRenderDirty.isEmpty() || !pendingTerminalState.isEmpty())
          && publicationExecutor != null) {
        schedulePublicationFlushLocked();
      }
    }
    if (listener != null) listener.run();
  }

  /** 只在 synchronized(model) 内调用；把当前合并状态物化成一个 publication。 */
  private boolean flushPendingRenderPublicationLocked() {
    if (pendingRenderDirty.isEmpty() && pendingTerminalState.isEmpty()) return false;
    long started = System.nanoTime();
    try {
      RenderDirtyState currentDirty = pendingRenderDirty;
      TerminalStateUpdate currentState = pendingTerminalState;
      boolean forceSnapshotRebuild = pendingForceSnapshotRebuild;
      pendingRenderDirty = new RenderDirtyState();
      pendingTerminalState = new TerminalStateUpdate();
      pendingForceSnapshotRebuild = false;
      publicationFlushCount++;
      publishRenderSnapshot(currentDirty, forceSnapshotRebuild);
      RenderSnapshot currentSnapshot = renderSnapshot;
      long version = publicationVersion.incrementAndGet();
      pendingPublication.getAndUpdate(previous -> {
        if (previous == null) {
          return new RenderPublication(
              version,
              new RenderUpdate(version, currentSnapshot, currentDirty, currentState));
        }
        RenderDirtyState mergedDirty = new RenderDirtyState();
        mergedDirty.mergeFrom(previous.update.dirty, rows);
        mergedDirty.mergeFrom(currentDirty, rows);
        TerminalStateUpdate mergedState = new TerminalStateUpdate();
        mergedState.mergeFrom(previous.update.state);
        mergedState.mergeFrom(currentState);
        return new RenderPublication(
            version,
            new RenderUpdate(version, currentSnapshot, mergedDirty, mergedState));
      });
    } finally {
      TerminalRenderMetrics.renderPublicationDuration(System.nanoTime() - started);
    }
    return true;
  }

  private void publishRenderSnapshot(
      RenderDirtyState dirty, boolean forceSnapshotRebuild) {
    snapshotBuildCount++;
    if (state == null) {
      renderSnapshot = RenderSnapshot.empty();
      snapshotMutationVersion = stateMutationVersion;
      publishProjectionReadView();
      return;
    }
    TerminalSurfaceState surface = state.activeSurface();
    RenderSnapshot previous = renderSnapshot;
    boolean screenChanged = forceSnapshotRebuild
        || dirty.fullInvalidate || dirty.screenRegionInvalidate
        || dirty.geometryChanged || dirty.activeBufferChanged
        || dirty.screenScrollRows != 0
        || !dirty.changedScreenRows.isEmpty()
        || !dirty.exposedScreenRows.isEmpty()
        || previous.screenView.size() != surface.activeRows.size();
    ScreenRenderView screenView = screenChanged
        ? buildScreenRenderView(
            surface, previous.screenView, dirty,
            forceSnapshotRebuild || dirty.fullInvalidate || dirty.screenRegionInvalidate
                || dirty.geometryChanged || dirty.activeBufferChanged)
        : previous.screenView;
    boolean historyChanged = forceSnapshotRebuild
        || dirty.historyChanged || dirty.activeBufferChanged;
    HistoryRenderView historyView = historyChanged
        ? new SemanticHistoryRenderView(surface.historyCatalog, surface.bodyCache)
        : previous.history;
    boolean reuseHistoryTopology = reuseHistoryTopologyForNextPublish;
    reuseHistoryTopologyForNextPublish = false;
    UnifiedContentAxis contentAxis;
    if (screenChanged || historyChanged) {
      contentAxis = reuseHistoryTopology
          ? UnifiedContentAxis.reuseHistoryTopology(surface, screenView, previous.contentAxis)
          : UnifiedContentAxis.update(surface, screenView, previous.contentAxis, dirty);
    } else {
      contentAxis = previous.contentAxis;
    }
    renderSnapshot = new RenderSnapshot(
        instanceId,
        layoutEpoch,
        screenRevision,
        historyGeneration,
        rows,
        columns,
        activeBuffer,
        screenView,
        historyView,
        contentAxis,
        state.cursor,
        state.modes,
        state.palette,
        displayExtent().firstSeq,
        false);
    snapshotMutationVersion = stateMutationVersion;
    publishProjectionReadView();
  }

  synchronized long snapshotBuildCountForTest() {
    return snapshotBuildCount;
  }

  synchronized long publicationFlushCountForTest() {
    return publicationFlushCount;
  }

  private static ScreenRenderView buildScreenRenderView(
      TerminalSurfaceState surface, ScreenRenderView previous,
      RenderDirtyState dirty, boolean rebuildAll) {
    int rowCount = surface.activeRows.size();
    if (rebuildAll || previous.size() != rowCount) {
      RenderLine[] rebuilt = new RenderLine[rowCount];
      for (int row = 0; row < rowCount; row++) {
        rebuilt[row] = renderLineAt(surface, row);
      }
      return ScreenRenderView.takeOwnership(rebuilt);
    }

    RenderLine[] screen = previous.copyLines();
    int shift = dirty.screenScrollRows;
    if (Math.abs((long) shift) >= rowCount) {
      for (int row = 0; row < rowCount; row++) {
        screen[row] = renderLineAt(surface, row);
      }
      return ScreenRenderView.takeOwnership(screen);
    }
    if (shift > 0) {
      System.arraycopy(screen, shift, screen, 0, rowCount - shift);
    } else if (shift < 0) {
      int amount = -shift;
      System.arraycopy(screen, 0, screen, amount, rowCount - amount);
    }
    BitSet rebuildRows = (BitSet) dirty.changedScreenRows.clone();
    rebuildRows.or(dirty.exposedScreenRows);
    for (int row = rebuildRows.nextSetBit(0);
         row >= 0 && row < rowCount;
         row = rebuildRows.nextSetBit(row + 1)) {
      screen[row] = renderLineAt(surface, row);
    }
    return ScreenRenderView.takeOwnership(screen);
  }

  private static RenderLine renderLineAt(TerminalSurfaceState surface, int row) {
    LineKey key = surface.activeRows.keyAt(row);
    LineBody body = surface.bodyCache.body(key);
    if (body == null) {
      throw new IllegalStateException("active row body missing for " + key);
    }
    return new RenderLine(key, body);
  }

  private void publishProjectionReadView() {
    projectionReadView = new ProjectionReadView(
        instanceId,
        layoutEpoch,
        screenRevision,
        historyGeneration,
        displayExtent(),
        projectionHealth.complete);
  }

  private static CommitFailure commitFailure(ProjectionFault fault) {
    return switch (fault) {
      case IDENTITY_MISMATCH -> CommitFailure.IDENTITY_MISMATCH;
      case LAYOUT_EPOCH_MISMATCH -> CommitFailure.LAYOUT_EPOCH_MISMATCH;
      case REVISION_GAP -> CommitFailure.REVISION_GAP;
      case PROMOTION_BODY_INVARIANT_FAILURE ->
          CommitFailure.HISTORY_PROMOTION_MISSING_LINE;
      case HISTORY_GENERATION_MISMATCH ->
          CommitFailure.HISTORY_GENERATION_MISMATCH;
      case INVALID_HISTORY_MUTATION ->
          CommitFailure.INVALID_HISTORY_SEQUENCE;
      case INVALID_BASELINE, INVALID_IDENTITY, INVALID_GENERATION, INVALID_GEOMETRY,
          SCREEN_LINE_COUNT_MISMATCH, SCREEN_LAYOUT_COUNT_MISMATCH,
          HISTORY_BINDING_COUNT_MISMATCH, HISTORY_SEQ_OUT_OF_ORDER,
          HISTORY_SEQ_OUT_OF_EXTENT, DUPLICATE_HISTORY_KEY, DUPLICATE_ACTIVE_KEY,
          ACTIVE_HISTORY_KEY_CONFLICT, LINE_COLUMN_COUNT_MISMATCH, INVALID_LINE_BODY,
          BASELINE_BODY_CONFLICT, INVALID_DICTIONARY, MAPPER_FAILURE, MODEL_REJECTED_BASELINE,
          INVALID_SCREEN_MUTATION ->
          CommitFailure.INVALID_LINE_DATA;
    };
  }

  /** Renderer 只读的语义快照。 */
  public static final class RenderSnapshot {
    public final String instanceId;
    public final long layoutEpoch;
    public final long screenRevision;
    public final long historyGeneration;
    public final int rows;
    public final int columns;
    public final TerminalBufferKind activeBuffer;
    public final ScreenRenderView screenView;
    public final HistoryRenderView history;
    public final UnifiedContentAxis contentAxis;
    public final TerminalCursor cursor;
    public final TerminalModes modes;
    public final TerminalPalette palette;
    public final long firstAvailableHistorySeq;
    public final boolean hasMoreHistoryBefore;

    private RenderSnapshot(
        String instanceId,
        long layoutEpoch,
        long screenRevision,
        long historyGeneration,
        int rows,
        int columns,
        TerminalBufferKind activeBuffer,
        ScreenRenderView screenView,
        HistoryRenderView history,
        UnifiedContentAxis contentAxis,
        TerminalCursor cursor,
        TerminalModes modes,
        TerminalPalette palette,
        long firstAvailableHistorySeq,
        boolean hasMoreHistoryBefore) {
      this.instanceId = instanceId;
      this.layoutEpoch = layoutEpoch;
      this.screenRevision = screenRevision;
      this.historyGeneration = historyGeneration;
      this.rows = rows;
      this.columns = columns;
      this.activeBuffer = activeBuffer;
      this.screenView = screenView;
      this.history = history;
      this.contentAxis = contentAxis;
      this.cursor = cursor;
      this.modes = modes;
      this.palette = palette;
      this.firstAvailableHistorySeq = firstAvailableHistorySeq;
      this.hasMoreHistoryBefore = hasMoreHistoryBefore;
    }

    private static RenderSnapshot empty() {
      HistoryCatalog catalog = new HistoryCatalog();
      BodyCache cache = new BodyCache(HistoryBudget.defaults());
      return new RenderSnapshot(
          null, 0, 0, 0, 0, 0, TerminalBufferKind.MAIN,
          ScreenRenderView.empty(),
          new SemanticHistoryRenderView(catalog, cache),
          UnifiedContentAxis.empty(),
          TerminalCursor.hidden(),
          TerminalModes.defaults(),
          TerminalPalette.defaults(),
          0,
          false);
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
