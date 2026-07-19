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

  public static final long SCHEMA_GENERATION = 1L;
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
  /** 当前 layout 与缓存历史引用的不可变行内容。 */
  private final Map<Long, TerminalLine> lineStore = new HashMap<>();

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
    this.softHistoryLimit = budget.softLines;
    this.hardHistoryLimit = budget.hardLines;
    this.softHistoryByteLimit = budget.softBytes;
    this.hardHistoryByteLimit = budget.hardBytes;
    this.activeBuffer = ScreenSnapshot.BufferKind.MAIN;
    this.history = new TerminalHistory(RemoteTerminalModel::estimateHistoryLineBytes);
  }

  public synchronized void applySnapshot(ScreenSnapshot snapshot) {
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
	this.lineStore.clear();
    this.historyBytes = 0;
    this.firstAvailableHistoryId = snapshot.history.firstAvailableLineId;
    this.hasMoreHistoryBefore = snapshot.history.hasMoreBefore;
    for (TerminalLine line : snapshot.history.lines) {
      if (line.id != 0) {
		lineStore.put(line.id, line);
        putHistoryLine(line);
      }
    }
    evictHistoryIfNeeded();

    this.screen = new TerminalLine[this.rows];
    for (int i = 0; i < this.rows; i++) {
      this.screen[i] = emptyLine(i, this.columns);
    }
    for (int row = 0; row < snapshot.screen.size() && row < this.screen.length; row++) {
      TerminalLine line = padOrCopyLine(snapshot.screen.get(row), this.columns);
      lineStore.put(line.id, line);
      this.screen[row] = line;
    }

    markRenderDirty(true, null, true, geometryChanged, true, -1, cursor.row,
        true, true, true, true, true);
    markTerminalState(geometryChanged, true, true, true, 0, 0);
    projectionHealth = projectionIsStructurallyComplete()
        ? ProjectionHealth.complete(instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION)
        : ProjectionHealth.incomplete(SCHEMA_GENERATION);

  }

  public synchronized void applyPatch(ScreenPatch patch) throws RevisionGapException {
    if (!instanceIdMatches(patch) || layoutEpoch != patch.layoutEpoch) {
      throw new RevisionGapException("instance/layout epoch mismatch");
    }
    if (screenRevision != patch.baseRevision) {
      throw new RevisionGapException("revision gap: local=" + screenRevision + " base=" + patch.baseRevision);
    }
    if (patch.screenRevision <= patch.baseRevision) {
      throw new RevisionGapException("patch revision must advance beyond base");
    }
    validatePatchAtomically(patch);
    boolean projectionWasComplete = projectionHealth.complete;
    TerminalCursor previousCursor = cursor;
    TerminalModes previousModes = modes;
    TerminalPalette previousPalette = palette;
    String previousTitle = title;
    String previousWorkingDirectory = workingDirectory;

    BitSet changedRows = new BitSet(rows);
    int tailAppendedLines = 0;
    long watermark = patch.historyTrimBeforeId != null
        ? patch.historyTrimBeforeId : firstAvailableHistoryId;

    // 恢复 Patch 的原子顺序：先推进水位并 trim，再丢弃水位以下 append。
    if (patch.historyTrimBeforeId != null) {
      firstAvailableHistoryId = watermark;
      history.trimHeadUntil(watermark);
      recalculateHistoryBytes();
    }

    if (patch.newStyles != null) styles.putAll(patch.newStyles);
    if (patch.newLinks != null) links.putAll(patch.newLinks);

    Map<Long, TerminalLine> historyLinesBySeq = new HashMap<>();
    for (TerminalLine line : patch.lineUpdates) {
      TerminalLine normalized = padOrCopyLine(line, columns);
      if (normalized.historySeq != 0) historyLinesBySeq.put(normalized.historySeq, normalized);
      TerminalLine previous = lineStore.get(normalized.id);
      // A same-version identical replay is intentionally a no-op. Do not
      // replace the immutable object: otherwise an idempotent resume patch
      // would create a fake dirty row and unnecessary redraw.
      if (previous == null || normalized.version > previous.version) {
        lineStore.put(normalized.id, normalized);
      }
    }

    for (long seq : patch.historyAppendIds) {
      TerminalLine line = historyLinesBySeq.get(seq);
      if (line == null) {
        int existing = history.findLineIndex(seq);
        if (existing >= 0) line = history.lineAt(existing);
      }
      // Direct-model callers from before HistorySeq can still provide a LineID
      // here. Wire frames always take the explicit HistorySeq path above.
      if (line == null) line = lineStore.get(seq);
      if (line != null && line.historyOrder() >= watermark) {
        if (putHistoryLine(line)) tailAppendedLines++;
      }
    }

    if (patch.layout != null) {
      for (int row = 0; row < rows; row++) {
        TerminalLine line = lineStore.get(patch.layout[row]);
        if (screen[row] != line) changedRows.set(row);
        screen[row] = line;
      }
    } else {
      for (int row = 0; row < rows; row++) {
        TerminalLine current = screen[row];
        TerminalLine updated = lineStore.get(current.id);
        if (updated != null && updated != current) { screen[row] = updated; changedRows.set(row); }
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
    if (patch.title != null) {
      title = patch.title;
    }
    if (patch.workingDirectory != null) {
      workingDirectory = patch.workingDirectory;
    }

    boolean historyChanged = patch.historyTrimBeforeId != null
        || !patch.historyAppendIds.isEmpty();
    boolean stylesChanged = !patch.newStyles.isEmpty();
    boolean linksChanged = !patch.newLinks.isEmpty();
    boolean cursorChanged = !Objects.equals(previousCursor, cursor);
    boolean modesChanged = !Objects.equals(previousModes, modes);
    boolean paletteChanged = !Objects.equals(previousPalette, palette);
    boolean titleChanged = !Objects.equals(previousTitle, title);
    boolean workingDirectoryChanged = !Objects.equals(previousWorkingDirectory, workingDirectory);
    screenRevision = patch.screenRevision;
    evictHistoryIfNeeded();
    pruneLineStore();
    markRenderDirty(false, changedRows, historyChanged, false, cursorChanged,
        cursorChanged ? previousCursor.row : -1, cursorChanged ? cursor.row : -1,
        paletteChanged, stylesChanged, linksChanged, modesChanged, false);
    markTerminalState(false, historyChanged, titleChanged, workingDirectoryChanged,
        tailAppendedLines, 0);
    projectionHealth = projectionWasComplete && patchReferencesComplete(patch)
        ? ProjectionHealth.complete(instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION)
        : ProjectionHealth.incomplete(SCHEMA_GENERATION);

  }

  /** exact resume 只推进已验证的 revision，不修改任何投影内容。 */
  public synchronized void applyResumeAck(String ackInstanceId, long ackLayoutEpoch,
                                          long ackScreenRevision) throws RevisionGapException {
    if (instanceId == null || !instanceId.equals(ackInstanceId)
        || layoutEpoch != ackLayoutEpoch || ackScreenRevision < screenRevision) {
      throw new RevisionGapException("resume ack does not match local projection");
    }
    screenRevision = ackScreenRevision;
    projectionHealth = projectionIsStructurallyComplete()
        ? ProjectionHealth.complete(instanceId, layoutEpoch, screenRevision, SCHEMA_GENERATION)
        : ProjectionHealth.incomplete(SCHEMA_GENERATION);
  }

  public synchronized void prependHistoryPage(HistoryPage page) {
    if (layoutEpoch != page.layoutEpoch) {
      return;
    }
    int historySizeBefore = history.size();
    // 可见锚点：prepend 前缓存中最旧的行。用户向上翻页时视口钉在已缓存内容上，
    // 该行即翻页前可见窗口的顶部边界；驱逐必须保护它和刚加载的新页。
    long anchorId = history.isEmpty() ? -1 : history.firstLineId();
    List<TerminalLine> toPrepend = new ArrayList<>();
    for (TerminalLine line : page.lines) {
      if (line.id != 0 && line.historyOrder() != 0 && history.findLineIndex(line.historyOrder()) < 0) {
        TerminalLine normalized = padOrCopyLine(line, columns);
        lineStore.put(normalized.id, normalized);
        toPrepend.add(normalized);
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
    markRenderDirty(false, null, true, false, false, -1, -1,
        false, !page.styles.isEmpty(), !page.links.isEmpty(), false, false);
    markTerminalState(false, true, false, false, 0, insertedHistoryLines);
  }

  public synchronized void trimHistory(long trimLayoutEpoch, long firstAvailableLineId) {
    if (layoutEpoch != trimLayoutEpoch) return;
    // HistoryTrim 可能与恢复 Patch 跨 channel/队列乱序到达；水位只能单调前进。
    if (firstAvailableLineId <= this.firstAvailableHistoryId) return;
    this.firstAvailableHistoryId = firstAvailableLineId;
    history.trimHeadUntil(firstAvailableLineId);
    recalculateHistoryBytes();
    pruneLineStore();
    markRenderDirty(false, null, true, false, false, -1, -1,
        false, false, false, false, false);
    markTerminalState(false, true, false, false, 0, 0);
  }

  /** 只能在 model executor 的完整事务边界读取，返回不可变快照。 */
  public synchronized ProjectionHealth projectionHealth() {
    return projectionHealth;
  }

  /** 只能在 model executor 上调用，保证版本字段来自同一个已提交模型事务。 */
  public synchronized ResumeToken resumeToken() {
    return ResumeToken.from(projectionHealth);
  }

  private boolean projectionIsStructurallyComplete() {
    if (instanceId == null || instanceId.isEmpty() || layoutEpoch < 1 || screenRevision < 1
        || rows <= 0 || columns <= 0 || screen == null || screen.length != rows) {
      return false;
    }
    for (TerminalLine line : screen) {
      if (line == null || line.length() != columns || !referencesComplete(line)) return false;
    }
    TerminalHistorySnapshot historySnapshot = history.snapshot();
    for (int i = 0; i < historySnapshot.size(); i++) {
      if (!referencesComplete(historySnapshot.lineAt(i))) return false;
    }
    return true;
  }

  private boolean referencesComplete(TerminalLine line) {
    for (int i = 0; i < line.length(); i++) {
      TerminalCell cell = line.at(i);
      if (cell == null) return false;
      if (cell.styleId != 0 && !styles.containsKey(cell.styleId)) return false;
      if (cell.linkId != 0 && !links.containsKey(cell.linkId)) return false;
    }
    return true;
  }

  private boolean patchReferencesComplete(ScreenPatch patch) {
    for (TerminalLine line : patch.lineUpdates) {
      if (!referencesComplete(line)) return false;
    }
    return true;
  }

  private void validatePatchAtomically(ScreenPatch patch) throws RevisionGapException {
    if (screen == null || screen.length != rows) {
      throw new RevisionGapException("local projection is incomplete");
    }
    if (patch.historyTrimBeforeId != null
        && patch.historyTrimBeforeId < firstAvailableHistoryId) {
      throw new RevisionGapException("history watermark moved backwards");
    }
    if (patch.layout != null && patch.layout.length != rows) {
      throw new RevisionGapException("layout length mismatch");
    }
    Set<Long> updateIds = new HashSet<>();
    for (TerminalLine line : patch.lineUpdates) {
      TerminalLine normalized = padOrCopyLine(line, columns);
      TerminalLine previous = lineStore.get(line.id);
      if (line.id <= 0 || !updateIds.add(line.id)
          || (previous != null && (line.version < previous.version
              || (line.version == previous.version && !previous.sameContent(normalized))))) {
        throw new RevisionGapException("invalid line update");
      }
      validateReferences(normalized, styles, patch.newStyles, links, patch.newLinks);
    }
    long watermark = patch.historyTrimBeforeId != null
        ? patch.historyTrimBeforeId : firstAvailableHistoryId;
    Map<Long, TerminalLine> historyUpdates = new HashMap<>();
    for (TerminalLine line : patch.lineUpdates) {
      if (line.historySeq != 0) historyUpdates.put(line.historySeq, line);
      else historyUpdates.put(line.id, line); // direct-model legacy fixture
    }
    long previous = -1;
    for (long seq : patch.historyAppendIds) {
      if (seq <= 0 || seq < watermark || (previous >= 0 && seq <= previous)
          || (history.findLineIndex(seq) >= 0 && !historyUpdates.containsKey(seq))) {
        throw new RevisionGapException("invalid history append sequence");
      }
      if (!historyUpdates.containsKey(seq) && !lineStore.containsKey(seq)) {
        throw new RevisionGapException("history line data missing");
      }
      previous = seq;
    }
    if (patch.layout != null) {
      for (long id : patch.layout) {
        if (id <= 0 || (!lineStore.containsKey(id) && !updateIds.contains(id))) {
          throw new RevisionGapException("layout line data missing");
        }
      }
    }
  }

  private static void validateReferences(TerminalLine line,
      Map<Integer, TerminalStyle> existingStyles, Map<Integer, TerminalStyle> patchStyles,
      Map<Integer, Hyperlink> existingLinks, Map<Integer, Hyperlink> patchLinks)
      throws RevisionGapException {
    for (int i = 0; i < line.length(); i++) {
      TerminalCell cell = line.at(i);
      if (cell == null
          || (cell.styleId != 0 && !existingStyles.containsKey(cell.styleId)
              && !patchStyles.containsKey(cell.styleId))
          || (cell.linkId != 0 && !existingLinks.containsKey(cell.linkId)
              && !patchLinks.containsKey(cell.linkId))) {
        throw new RevisionGapException("unknown style/link reference");
      }
    }
  }

  /**
   * 供旧的非 View 调用方读取当前模型；正式绘制必须使用
   * {@link #consumeRenderUpdate()} 返回的快照。
   *
   * <p>这条兼容读取不会消费 pending dirty。生产 View 已不走此路径，因此不会在高频
   * Patch 时发布无用的中间快照。</p>
   */
  public synchronized RenderSnapshot renderSnapshot() {
    if (renderPublicationPending) publishRenderSnapshot(pendingRenderDirty);
    return renderSnapshot;
  }

  /**
   * 原子交换本帧累计的脏状态，并在同一把模型锁内发布与它严格对应的不可变快照。
   * 新到达的 Patch 会写入新的 pending 实例，绝不会被本帧消费后的 clear 覆盖。
   */
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

  /** 页面 attach、恢复或本地滚动需要重画时，由模型统一记录全量脏区。 */
  public synchronized void requestFullRender() {
    markRenderDirty(true, null, false, false, false, -1, -1,
        false, false, false, false, false);
  }

  /** 历史行数。不需要拷贝全部历史。 */
  public synchronized int historySize() {
    return history.size();
  }

  /** 本地缓存最旧行 id；缓存为空时返回 -1。分页请求用它作 beforeId。 */
  public synchronized long firstCachedHistoryId() {
    return history.firstLineId();
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
    return new TerminalLine(line.id, line.version, line.wrapped, cells);
  }

  /** 只保留活动 Layout 或 Android 历史缓存仍引用的行，防止 LineStore 无界增长。 */
  private void pruneLineStore() {
    java.util.HashSet<Long> retained = new java.util.HashSet<>();
    if (screen != null) for (TerminalLine line : screen) if (line != null) retained.add(line.id);
    TerminalHistorySnapshot snapshot = history.snapshot();
    for (int i = 0; i < snapshot.size(); i++) retained.add(snapshot.lineAt(i).id);
    java.util.Iterator<Long> it = lineStore.keySet().iterator();
    while (it.hasNext()) if (!retained.contains(it.next())) it.remove();
  }

  /**
   * 插入或替换一行历史。普通 tail append 走 O(1) 快速路径；
   * 非递增 id（replace/resync）走 O(log n) 查找路径。
   */
  private boolean putHistoryLine(TerminalLine line) {
    TerminalLine normalized = padOrCopyLine(line, columns);
    if (!history.isEmpty() && normalized.historyOrder() <= history.lastLineId()) {
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
    TerminalHistorySnapshot historySnapshot = dirty.historyChanged || dirty.fullInvalidate
        ? history.snapshot()
        : previous.history;
    Map<Integer, TerminalStyle> stylesCopy = dirty.stylesChanged || dirty.fullInvalidate
        ? Collections.unmodifiableMap(new HashMap<>(styles))
        : previous.styles;
    Map<Integer, Hyperlink> linksCopy = dirty.linksChanged || dirty.fullInvalidate
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
