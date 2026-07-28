package screenprojection

import (
	"image/color"
	"sync"

	headlessterm "github.com/danielgatis/go-headless-term"
	"webterm/go-core/internal/terminalengine"
)

// paletteState 是完整且可比较的动态调色板分量。固定数组避免 map 破坏
// canonical state 的不可变/可比较语义，presence bitmap 区分默认表与 OSC 4 覆盖。
type paletteState struct {
	reverseVideo bool
	defaultFG    terminalengine.Color
	defaultBG    terminalengine.Color
	cursorColor  terminalengine.Color
	indexed      [256]uint32
	indexedSet   [4]uint64
	generation   uint64
}

// projectedState 缓存最近一次完整权威投影。它只在导出侧（ExportState 持
// p.mu 期间）读写，不跨 goroutine 共享。screen 中未变化的行复用旧 Line
// 对象；Line 一旦创建即不可变，合并时只整体替换。
type projectedState struct {
	valid        bool
	rows         int
	cols         int
	screen       []terminalengine.Line // len == rows
	activeBuffer terminalengine.BufferKind
	cursor       terminalengine.Cursor
	modes        terminalengine.Modes
	palette      paletteState
}

// rebuild 用完整投影（Full）重建全部行与元数据。
func (s *projectedState) rebuild(proj headlessterm.ProjectionRead, exp *exporter) {
	previous := s.screen
	screen := make([]terminalengine.Line, proj.Rows)
	for _, row := range proj.DirtyRows {
		if row.Index >= 0 && row.Index < len(screen) {
			screen[row.Index] = reconcileExportLine(previous,
				exp.exportProjectionRow(row, proj.Cursor.Row, proj.Cursor.Col))
		}
	}
	s.screen = screen
	s.rows = proj.Rows
	s.cols = proj.Cols
	s.mergeMeta(proj)
	s.valid = true
}

// merge 只把 dirty 行重新转换为 Line 并替换缓存中对应下标；未变化行复用
// 旧 Line 对象。渲染元数据总是采用投影中的当前值，因此纯模式、光标变化
// 在无 dirty 行时也能反映到导出状态。
func (s *projectedState) merge(proj headlessterm.ProjectionRead, exp *exporter) {
	for _, row := range proj.DirtyRows {
		if row.Index >= 0 && row.Index < len(s.screen) {
			s.screen[row.Index] = reconcileExportLine(s.screen,
				exp.exportProjectionRow(row, proj.Cursor.Row, proj.Cursor.Col))
		}
	}
	s.mergeMeta(proj)
}

// reconcileExportLine gives Line.Version wire semantics: it is the version of
// the final exported representation, not merely Buffer's physical cell
// version.  The exporter suppresses stale software cursors based on the live
// cursor position, so a cursor move can alter Runs without touching a Cell.
// Conversely, a projection-dirty cursor row whose output is unchanged must
// retain its previous version and not create a needless LineData update.
func reconcileExportLine(previous []terminalengine.Line, candidate terminalengine.Line) terminalengine.Line {
	var prior *terminalengine.Line
	for i := range previous {
		if previous[i].ID == candidate.ID {
			prior = &previous[i]
			break
		}
	}
	if prior == nil {
		if candidate.Version == 0 {
			candidate.Version = 1
		}
		return candidate
	}
	if linesEqual(*prior, candidate) {
		candidate.Version = prior.Version
		return candidate
	}
	// Preserve physical versions where they already advance monotonically, but
	// create an ExportVersion step when only cursor-dependent filtering changed.
	if candidate.Version <= prior.Version {
		candidate.Version = prior.Version + 1
	}
	return candidate
}

func (s *projectedState) mergeMeta(proj headlessterm.ProjectionRead) {
	activeBuffer := terminalengine.BufferMain
	if proj.ActiveBuffer == headlessterm.BufferKindAlternate {
		activeBuffer = terminalengine.BufferAlternate
	}
	s.activeBuffer = activeBuffer
	s.cursor = exportProjectionCursor(proj.Cursor)
	s.modes = exportProjectionModes(proj.Modes)
	nextPalette := paletteState{
		reverseVideo: false,
		defaultFG:    terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		defaultBG:    terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
		cursorColor:  terminalengine.Color{Kind: terminalengine.ColorCursor},
	}
	for index, value := range proj.Colors {
		rgb := projectionColorRGB(value)
		switch {
		case index >= 0 && index < 256:
			nextPalette.indexed[index] = rgb
			nextPalette.indexedSet[index/64] |= uint64(1) << uint(index%64)
		case index == headlessterm.NamedColorForeground:
			nextPalette.defaultFG = terminalengine.Color{Kind: terminalengine.ColorRGB, RGB: rgb}
		case index == headlessterm.NamedColorBackground:
			nextPalette.defaultBG = terminalengine.Color{Kind: terminalengine.ColorRGB, RGB: rgb}
		case index == headlessterm.NamedColorCursor:
			nextPalette.cursorColor = terminalengine.Color{Kind: terminalengine.ColorRGB, RGB: rgb}
		}
	}
	previousGeneration := s.palette.generation
	nextPalette.generation = previousGeneration
	if !paletteValuesEqual(s.palette, nextPalette) {
		nextPalette.generation++
		if nextPalette.generation == 0 {
			nextPalette.generation = 1
		}
	}
	s.palette = nextPalette
}

// Projector 为每个 screen client 维护发送基线并生成 snapshot/patch。
type Projector struct {
	mu          sync.RWMutex
	engine      *terminalengine.Engine
	scrollback  *terminalengine.TrackedScrollback
	sessionID   string
	instanceID  string
	exporter    *exporter
	exportEpoch uint64
	projected   projectedState
	// dictGeneration increments every time the style/link exporter is rebuilt
	// (layout epoch change or >4096 dictionary rotation). It is stamped onto
	// every exported state so per-client FrameDerivers can detect a baseline
	// from a stale dictionary even when the ForceSnapshot frame was coalesced
	// away by a single-slot mailbox.
	dictGeneration            uint64
	historyGeneration         uint64
	observedHistoryGeneration uint64
	// changeIndex 记录各状态组件最后一次变化的导出 revision 与持久 snapshot
	// 屏障（计划 docs/superpowers/plans/2026-07-14-screen-state-delta-resume.md
	// §4.2/§4.3），只在 p.mu 持锁期间读写（规则 5）。它不参与在线
	// FrameDeriver 热路径，仅供 resume 推导（resume.go）。
	changeIndex ChangeIndex
	// historyChangeIndex 记录当前权威 scrollback 中各 LineID 的创建 revision。
	// 与 changeIndex 一样只在 p.mu 下同步和查询；Cell 始终从 scrollback 导出，
	// 索引本身不复制终端内容。
	historyChangeIndex HistoryChangeIndex
	// changeIndexReady 标记首次导出已完成：NewProjector 后的首次导出视为
	// “projector 整体重建”事件，把 barrier 初始化到首次导出 revision。
	changeIndexReady bool
}

// SnapshotBarrierRevision 返回当前 epoch 内最近的快照屏障。
// 仅暴露版本号用于安全观测，不泄露投影正文。
func (p *Projector) SnapshotBarrierRevision() uint64 {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.changeIndex.SnapshotBarrierRevision
}

// FrameDeriver owns one transport client's last successfully scheduled
// authoritative state. It derives a frame only when that client is actually
// about to write, so a slow client can collapse many intermediate states
// without creating a BaseRevision gap.
//
// 相对 baseline 无任何可观察变化时（bell、title 设回原值等输出仍会推进
// canonical revision），DeriveForState 返回 Kind 未设置的零值帧表示"不发送"，
// 且不推进 baseline：下一个真实 patch 的 base 仍等于最后实际写出的 revision。
type FrameDeriver struct {
	baseline terminalengine.ScreenFrame
}

func (d *FrameDeriver) Reset() {
	d.baseline = terminalengine.ScreenFrame{}
}

// SeedAfterSuccessfulWrite 只在物理写成功后提交该客户端的完整权威状态。
func (d *FrameDeriver) SeedAfterSuccessfulWrite(state terminalengine.ScreenFrame) {
	d.baseline = state
}

// DeriveForState 只派生、不推进 baseline；物理写成功后调用
// SeedAfterSuccessfulWrite 提交。
func (d *FrameDeriver) DeriveForState(state terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	baseline := d.baseline
	return frameForBaseline(&baseline, state)
}

// NewProjector 创建新的 screen projector。
func NewProjector(engine *terminalengine.Engine, scrollback *terminalengine.TrackedScrollback, sessionID, instanceID string) *Projector {
	return &Projector{
		engine:            engine,
		scrollback:        scrollback,
		sessionID:         sessionID,
		instanceID:        instanceID,
		exporter:          newExporter(terminalengine.Color{Kind: terminalengine.ColorDefaultFG}, terminalengine.Color{Kind: terminalengine.ColorDefaultBG}),
		dictGeneration:    1,
		historyGeneration: 1,
	}
}

// HistoryRange 导出同一权威快照中的闭区间历史与 message-local 字典。
func (p *Projector) HistoryRange(fromSeq, toSeq uint64) terminalengine.HistoryRangeData {
	result := p.scrollback.Range(fromSeq, toSeq)
	exp := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	lines := make([]terminalengine.Line, len(result.Lines))
	for i, line := range result.Lines {
		lines[i] = exp.exportScrollbackEntry(line)
	}
	return terminalengine.HistoryRangeData{
		Status:            result.Status,
		Extent:            result.Extent,
		Lines:             lines,
		Styles:            exp.styleTable.Styles(),
		Links:             exp.linkTable.Links(),
		HistoryGeneration: result.Generation,
	}
}

// HistoryGeneration returns the identity of the currently published history
// lineage. HistoryRange responses use it even when no line body is returned.
func (p *Projector) HistoryGeneration() uint64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.historyGeneration
}

// ExportState exports the authoritative terminal once for a screen revision.
// Runtime shares this immutable value across all clients before deriving their
// individual snapshot/patch frames. This keeps export cost independent of the
// number of attached viewers.
func (p *Projector) ExportState(epoch, seq uint64) terminalengine.ScreenFrame {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.exportStateLocked(epoch, seq)
}

func (p *Projector) exportStateLocked(epoch, seq uint64) terminalengine.ScreenFrame {
	if !p.changeIndexReady {
		// projector 整体重建（NewProjector 后首次导出，§4.2）：barrier 初值取
		// 首次导出 revision。结合 §6 的 clientRevision < barrier → Snapshot
		// 语义，更早 revision 的恢复请求一律走 snapshot；同一 instance/epoch
		// 的客户端 revision 必然 >= 该值，不会被误伤。
		p.changeIndex.advanceBarrier(seq)
		p.changeIndexReady = true
	}
	if p.exportEpoch != epoch {
		p.exporter = newExporter(terminalengine.Color{Kind: terminalengine.ColorDefaultFG}, terminalengine.Color{Kind: terminalengine.ColorDefaultBG})
		p.exportEpoch = epoch
		p.dictGeneration++
		// 缓存行引用的 style/link ID 出自被废弃的字典，必须整体重建。
		p.projected = projectedState{}
		// epoch 变化：ChangeIndex 整体重置，barrier 直接设为新 epoch 首个导出
		// revision。同 epoch 的客户端不可能跨越 epoch resume（§6 的 epoch 校验
		// 先于 barrier 判定），因此 barrier 的单调性只需在 epoch 内成立；新
		// epoch 客户端的 revision 必然 >= 该值。字典随 epoch 轮转本身也是
		// §4.2 的 barrier 事件。
		p.changeIndex.resetForEpoch(seq)
	}
	if p.scrollback != nil {
		generation := p.scrollback.Generation()
		if generation == 0 {
			generation = 1
		}
		if p.observedHistoryGeneration != 0 && generation != p.observedHistoryGeneration {
			p.historyChangeIndex = HistoryChangeIndex{}
			p.changeIndex.advanceBarrier(seq)
		}
		p.observedHistoryGeneration = generation
		p.historyGeneration = generation
	}
	if p.historyChangeIndex.sync(p.scrollback, seq) {
		// LineID 跳号或索引遗漏意味着旧投影无法准确修复。推进持久 barrier，
		// 并确保在线客户端也收到同 revision snapshot。
		p.changeIndex.advanceBarrier(seq)
	}
	frame := p.mergeAndExport(epoch, seq)
	if p.historyChangeIndex.GapRevision == seq {
		frame.ForceSnapshot = true
	}
	// 字典只增不改；大量瞬时 RGB/OSC8 若使历史字典膨胀，则以权威 snapshot
	// 旋转字典。当前可见状态仍超过上限时由协议校验拒绝。
	if len(frame.Styles) > 4096 || len(frame.Links) > 4096 {
		p.exporter = newExporter(terminalengine.Color{Kind: terminalengine.ColorDefaultFG}, terminalengine.Color{Kind: terminalengine.ColorDefaultBG})
		p.dictGeneration++
		p.projected = projectedState{}
		// 字典世代轮转（现有 ForceSnapshot 事件，§4.2）：推进 barrier 并重建
		// created-revision 索引（规则 4）；行/元数据索引仍然有效——屏幕内容
		// 未变，只是字典编码重建。
		p.changeIndex.advanceBarrier(seq)
		p.changeIndex.resetDictionary()
		frame = p.mergeAndExport(epoch, seq)
		frame.ForceSnapshot = true
	}
	frame.DictionaryGeneration = p.dictGeneration
	frame.HistoryGeneration = p.historyGeneration
	return frame
}

// mergeAndExport 读一次投影、合并进全屏缓存并产出完整 State。产出的帧始终
// 是完整状态（全屏行 + 元数据），FrameDeriver 与协议层无感知。行/元数据索引
// 在 assemble 前更新；字典 created revision 在 assemble 后更新（历史窗口导出
// 可能在 assemble 阶段向字典表追加条目）。
func (p *Projector) mergeAndExport(epoch, seq uint64) terminalengine.ScreenFrame {
	s := &p.projected
	prev := projectedMeta{
		valid:        s.valid,
		activeBuffer: s.activeBuffer,
		cursor:       s.cursor,
		modes:        s.modes,
		palette:      s.palette,
		layout:       screenLayout(s.screen),
	}
	proj := p.engine.ReadProjection()
	if !proj.Full && (!s.valid || s.rows != proj.Rows || s.cols != proj.Cols) {
		// 缓存不可用（首次导出后被 epoch/字典轮转丢弃）或几何与缓存不一致，
		// 但终端未标全脏：dirty 行不足以重建，改取完整投影。
		proj = p.engine.ReadFullProjection()
	}
	if proj.Full {
		s.rebuild(proj, p.exporter)
	} else {
		s.merge(proj, p.exporter)
	}
	p.engine.ConsumeProjectionDirty(proj)
	p.updateChangeIndexScreenLocked(seq, prev, proj)
	frame := p.assembleFrame(epoch, seq)
	p.updateChangeIndexDictionaryLocked(seq)
	return frame
}

// assembleFrame 从全屏缓存组装完整 State。历史只导出 extent 与位置谱系，
// 正文统一由 HTTP Range 导出；备用屏绝不混入主屏 scrollback。
func (p *Projector) assembleFrame(epoch, seq uint64) terminalengine.ScreenFrame {
	s := &p.projected
	// State 被所有客户端及其 baseline 共享，必须不可变；缓存切片会在后续
	// 增量合并中原地替换元素，因此每帧复制切片头。Line 为浅拷贝：未变化
	// 行的 Runs 与缓存/历史帧共享且不可变，这是有意的零拷贝复用。
	screen := make([]terminalengine.Line, len(s.screen))
	copy(screen, s.screen)
	rowChangedRevision := make([]uint64, len(p.changeIndex.RowChangedRevision))
	copy(rowChangedRevision, p.changeIndex.RowChangedRevision)
	history := terminalengine.HistoryWindow{}
	// 备用屏是完整 TUI 的当前画面，绝不能混入主屏 scrollback。
	// 切屏会触发 snapshot，客户端据此清空旧历史并只渲染该屏内容。
	if s.activeBuffer == terminalengine.BufferMain {
		history = historyExtentWindow(p.scrollback)
	}

	return terminalengine.ScreenFrame{
		Version:               1,
		Kind:                  terminalengine.FrameSnapshot,
		SessionID:             p.sessionID,
		InstanceID:            p.instanceID,
		Epoch:                 epoch,
		Seq:                   seq,
		Rows:                  s.rows,
		Cols:                  s.cols,
		ActiveBuffer:          s.activeBuffer,
		ReverseVideo:          s.palette.reverseVideo,
		DefaultFG:             s.palette.defaultFG,
		DefaultBG:             s.palette.defaultBG,
		CursorColor:           s.palette.cursorColor,
		IndexedPalette:        s.palette.indexed,
		IndexedPaletteSet:     s.palette.indexedSet,
		PaletteGeneration:     s.palette.generation,
		Cursor:                s.cursor,
		Modes:                 s.modes,
		History:               history,
		Screen:                screen,
		Styles:                p.exporter.styleTable.Styles(),
		Links:                 p.exporter.linkTable.Links(),
		RowChangedRevision:    rowChangedRevision,
		DictionaryGeneration:  p.dictGeneration,
		HistoryGeneration:     p.historyGeneration,
		ScrollbackLineage:     p.historyChangeIndex.lineage,
		HistoryLineageVersion: p.historyChangeIndex.mutationVersion,
	}
}

func paletteValuesEqual(a, b paletteState) bool {
	return a.reverseVideo == b.reverseVideo && a.defaultFG == b.defaultFG &&
		a.defaultBG == b.defaultBG && a.cursorColor == b.cursorColor &&
		a.indexed == b.indexed && a.indexedSet == b.indexedSet
}

func projectionColorRGB(c color.Color) uint32 {
	if c == nil {
		return 0
	}
	r, g, b, _ := c.RGBA()
	return uint32(r>>8)<<16 | uint32(g>>8)<<8 | uint32(b>>8)
}

func frameForBaseline(baseline *terminalengine.ScreenFrame, state terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	// 输入始终是完整状态，可直接作为 snapshot 发送；统一打上 Kind，避免
	// 调用方漏设导致编码失败。diffToPatch 的 snapshot 回退路径也借此得到
	// 正确的 Kind。
	state.Kind = terminalengine.FrameSnapshot
	// 第一帧、字典轮转、字典世代（baseline 出自已废弃的字典，即使携带
	// ForceSnapshot 的轮转帧被 mailbox 覆盖也必须全量）、instance/layout
	// epoch 或备用屏变化，发送完整 snapshot。
	if state.ForceSnapshot || baseline.Seq == 0 || baseline.InstanceID != state.InstanceID || baseline.Epoch != state.Epoch || baseline.DictionaryGeneration != state.DictionaryGeneration || baseline.HistoryGeneration != state.HistoryGeneration {
		*baseline = state
		return state
	}
	// 否则生成 patch（整行替换）。
	patch := diffToPatch(*baseline, state)
	if patch.Kind != terminalengine.FramePatch {
		*baseline = state
		return patch
	}
	screenChanged := hasScreenChanges(patch)
	historyChanged := hasHistoryChanges(patch)
	switch {
	case !screenChanged && !historyChanged:
		// 无可观察变化（bell、title 设回原值等仍会让 Runtime bump revision）：
		// 抑制空 patch（计划 §3.4/§10.1：patch 必须携带实际变化），不推进
		// baseline，下一帧仍相对最后实际写出的 revision 做 diff。
		return terminalengine.ScreenFrame{}
	default:
		// 屏幕、历史与渲染元数据统一推进同一条 projection revision 链。
		*baseline = state
		return patch
	}
}

// hasScreenChanges 判断 patch 是否携带任何屏幕（非历史）可观察变化。
func hasScreenChanges(patch terminalengine.ScreenFrame) bool {
	return len(patch.Screen) > 0 ||
		patch.ScreenScroll != nil ||
		len(patch.Styles) > 0 ||
		len(patch.Links) > 0 ||
		patch.CursorChanged ||
		patch.ModesChanged ||
		patch.PaletteChanged ||
		patch.ActiveBufferChanged
}

// hasHistoryChanges 判断 Commit 是否携带任何历史位置或 extent 变化。
func hasHistoryChanges(patch terminalengine.ScreenFrame) bool {
	return len(patch.HistoryPushes) > 0 ||
		patch.FirstAvailableHistorySeqChanged
}

// isEmptyPatch 判断 diff 出的 patch 是否不含任何可观察变化（屏幕与历史皆无）。
// cursor/modes/palette 通过 patch presence 标志表达，避免把未变化的元数据重复
// 编码到每一帧。
func isEmptyPatch(baseline, patch terminalengine.ScreenFrame) bool {
	return !hasScreenChanges(patch) && !hasHistoryChanges(patch)
}

// diffToPatch 计算两帧差异并生成 patch 帧。
//
// 在线客户端已持有连续 baseline 时，即使本次变化覆盖整屏，也仍然使用
// Patch：它只携带当前屏幕的变化行与新增历史，不能因为 AGY 一类 TUI 的
// 全屏重绘而重复发送整个历史窗口并让 Android 重置投影。Snapshot 只由
// frameForBaseline 的真实同步边界（首帧、epoch/buffer/dictionary 变化或
// ForceSnapshot）触发。断线恢复的成本判定仍在 resume.go 单独处理。
//
// Push 按 HistorySeq 新增位置生成，与客户端是否持有正文无关。
func diffToPatch(old, new terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	var pushes []terminalengine.HistoryPush
	if old.HistoryLineageVersion == 0 || new.HistoryLineageVersion == 0 ||
		old.HistoryLineageVersion != new.HistoryLineageVersion {
		oldRefs := make(map[uint64]terminalengine.HistoryPush, len(old.ScrollbackLineage))
		for _, entry := range old.ScrollbackLineage {
			oldRefs[entry.HistorySeq] = entry
		}
		for _, entry := range new.ScrollbackLineage {
			previous, exists := oldRefs[entry.HistorySeq]
			if (!exists || previous.LineID != entry.LineID ||
				previous.LineVersion != entry.LineVersion) &&
				entry.HistorySeq >= new.History.FirstAvailableHistorySeq &&
				entry.HistorySeq <= new.History.LastIncludedHistorySeq {
				pushes = append(pushes, entry)
			}
		}
	}
	activeBufferChanged := old.ActiveBuffer != new.ActiveBuffer
	var scroll *terminalengine.ScreenScroll
	var screenRows []terminalengine.Line
	if activeBufferChanged {
		screenRows = append(screenRows, new.Screen...)
	} else {
		scroll = deriveFullScreenScroll(old.Screen, new.Screen)
		screenRows = commitScreenWrites(old.Screen, new.Screen, scroll)
	}

	return terminalengine.ScreenFrame{
		Version:             1,
		Kind:                terminalengine.FramePatch,
		SessionID:           new.SessionID,
		InstanceID:          new.InstanceID,
		Epoch:               new.Epoch,
		Seq:                 new.Seq,
		BaseRevision:        old.Seq,
		Rows:                new.Rows,
		Cols:                new.Cols,
		ActiveBuffer:        new.ActiveBuffer,
		ReverseVideo:        new.ReverseVideo,
		DefaultFG:           new.DefaultFG,
		DefaultBG:           new.DefaultBG,
		CursorColor:         new.CursorColor,
		IndexedPalette:      new.IndexedPalette,
		IndexedPaletteSet:   new.IndexedPaletteSet,
		PaletteGeneration:   new.PaletteGeneration,
		Cursor:              new.Cursor,
		Modes:               new.Modes,
		CursorChanged:       activeBufferChanged || old.Cursor != new.Cursor,
		ModesChanged:        activeBufferChanged || old.Modes != new.Modes,
		ActiveBufferChanged: activeBufferChanged,
		PaletteChanged: activeBufferChanged || old.ReverseVideo != new.ReverseVideo ||
			old.DefaultFG != new.DefaultFG || old.DefaultBG != new.DefaultBG ||
			old.CursorColor != new.CursorColor || old.IndexedPalette != new.IndexedPalette ||
			old.IndexedPaletteSet != new.IndexedPaletteSet ||
			old.PaletteGeneration != new.PaletteGeneration,
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: new.History.FirstAvailableHistorySeq,
			FirstIncludedHistorySeq:  new.History.FirstIncludedHistorySeq,
			LastIncludedHistorySeq:   new.History.LastIncludedHistorySeq,
			HasMoreBefore:            new.History.HasMoreBefore,
		},
		Screen:               screenRows,
		ScreenScroll:         scroll,
		HistoryPushes:        pushes,
		DictionaryGeneration: new.DictionaryGeneration,
		HistoryGeneration:    new.HistoryGeneration,
		// Snapshot owns a complete dictionary. A patch only needs entries that
		// appeared after the recipient's baseline; repeatedly sending the whole
		// table was pure wire and allocation overhead.
		Styles: newlyAddedStyles(old.Styles, new.Styles),
		Links:  newlyAddedLinks(old.Links, new.Links),
		// Commit 用该 presence 位表达位置新增、trim、Clear 或尾部回退。
		FirstAvailableHistorySeqChanged: activeBufferChanged || len(pushes) > 0 ||
			old.History.FirstAvailableHistorySeq != new.History.FirstAvailableHistorySeq ||
			old.History.LastIncludedHistorySeq != new.History.LastIncludedHistorySeq,
	}
}

// deriveFullScreenScroll 只用稳定 LineID 唯一确认全屏连续位移。
func deriveFullScreenScroll(oldScreen, newScreen []terminalengine.Line) *terminalengine.ScreenScroll {
	rows := len(oldScreen)
	if rows <= 1 || len(newScreen) != rows {
		return nil
	}
	var matched []int
	for shift := 1; shift < rows; shift++ {
		up := true
		for row := 0; row < rows-shift; row++ {
			if oldScreen[row+shift].ID != newScreen[row].ID {
				up = false
				break
			}
		}
		if up {
			matched = append(matched, shift)
		}
		down := true
		for row := shift; row < rows; row++ {
			if oldScreen[row-shift].ID != newScreen[row].ID {
				down = false
				break
			}
		}
		if down {
			matched = append(matched, -shift)
		}
	}
	if len(matched) != 1 {
		return nil
	}
	return &terminalengine.ScreenScroll{TopRow: 0, BottomRowExclusive: rows, DeltaRows: matched[0]}
}

func commitScreenWrites(oldScreen, newScreen []terminalengine.Line, scroll *terminalengine.ScreenScroll) []terminalengine.Line {
	writes := make([]terminalengine.Line, 0, len(newScreen))
	for row, next := range newScreen {
		write := false
		if row >= len(oldScreen) {
			write = true
		} else if scroll == nil {
			write = oldScreen[row].ID != next.ID || oldScreen[row].Version != next.Version
		} else {
			source := row + scroll.DeltaRows
			write = source < 0 || source >= len(oldScreen) ||
				oldScreen[source].ID != next.ID || oldScreen[source].Version != next.Version
		}
		if write {
			next.Row = row
			writes = append(writes, next)
		}
	}
	return writes
}

func screenLayout(lines []terminalengine.Line) []uint64 {
	ids := make([]uint64, len(lines))
	for i := range lines {
		ids[i] = lines[i].ID
	}
	return ids
}
func sameLayout(a, b []uint64) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// changedLinesByID deliberately ignores current row number: a scroll only
// changes Layout, while a line update is needed only when its stable identity
// is absent from the baseline or carries a newer content version.
func changedLinesByID(old terminalengine.ScreenFrame, lines []terminalengine.Line) []terminalengine.Line {
	// Android bounds LineStore to its current ScreenLayout and history cache.
	// A line that existed only in an old history tail is not a safe baseline for
	// a new screen layout: it may have been pruned locally. Re-entering screen
	// lines therefore need their LineData unless the previous ScreenLayout also
	// referenced them.
	known := make(map[uint64]uint64, len(old.Screen))
	for _, line := range old.Screen {
		known[line.ID] = line.Version
	}
	var changed []terminalengine.Line
	for _, line := range lines {
		if version, ok := known[line.ID]; !ok || version < line.Version {
			changed = append(changed, line)
		}
	}
	return changed
}

// changedScreenRows selects rows using projector revision stamps when available. The fallback
// keeps synthetic frames and older tests correct while production avoids O(rows*cols) compares
// for every connected client.
func changedScreenRows(old, new terminalengine.ScreenFrame) []terminalengine.Line {
	if len(new.RowChangedRevision) == len(new.Screen) {
		rows := make([]terminalengine.Line, 0)
		for r, changedRevision := range new.RowChangedRevision {
			if changedRevision > old.Seq {
				rows = append(rows, new.Screen[r])
			}
		}
		return rows
	}
	rows := make([]terminalengine.Line, 0)
	for r := 0; r < len(new.Screen); r++ {
		if r >= len(old.Screen) || !linesEqual(old.Screen[r], new.Screen[r]) {
			rows = append(rows, new.Screen[r])
		}
	}
	return rows
}

func newlyAddedStyles(old, new []terminalengine.TerminalStyle) []terminalengine.TerminalStyle {
	if len(new) <= len(old) {
		return nil
	}
	return new[len(old):]
}

func newlyAddedLinks(old, new []terminalengine.Hyperlink) []terminalengine.Hyperlink {
	if len(new) <= len(old) {
		return nil
	}
	return new[len(old):]
}

func linesEqual(a, b terminalengine.Line) bool {
	if a.PhysicalColumns != b.PhysicalColumns ||
		a.Wrapped != b.Wrapped || len(a.Runs) != len(b.Runs) {
		return false
	}
	for i := range a.Runs {
		if a.Runs[i].Col != b.Runs[i].Col || len(a.Runs[i].Cells) != len(b.Runs[i].Cells) {
			return false
		}
		for j := range a.Runs[i].Cells {
			if a.Runs[i].Cells[j] != b.Runs[i].Cells[j] {
				return false
			}
		}
	}
	return true
}
