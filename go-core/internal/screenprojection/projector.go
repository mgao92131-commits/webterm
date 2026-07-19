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
	title        string
	workingDir   string
	// 历史窗口缓存（阶段 2c）：已导出的尾部窗口行（Line 不可变，跨帧零拷贝
	// 复用）与缓存窗口最后一行的 ID。historyValid=false 表示缓存不反映当前
	// scrollback（首次导出、epoch/字典轮转、备用屏期间），下次主屏导出经
	// exportHistoryWindow 全量重建。
	historyValid   bool
	historyLines   []terminalengine.Line
	historyLastSeq uint64 // 缓存窗口最后一行的 ID；窗口为空时为 FirstAvailable-1
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
// 旧 Line 对象。元数据总是采用投影中的当前值，因此纯元数据变化（标题、
// cwd、模式、光标移动）在无 dirty 行时也能反映到导出状态。
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
	s.title = proj.Title
	s.workingDir = proj.WorkingDir
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
	dictGeneration uint64
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
	// scrollbackNextSeq 是上次导出时观察到的 scrollback.NextSeq()。nextSeq 回退
	// 表示历史 LineID 体系被 Clear/ResetForReflow 重置（§4.2 barrier 事件）；
	// Pop（resize 放大从 scrollback 回拉行）不回退 nextSeq，不会误触发。
	scrollbackNextSeq uint64
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
// canonical revision），FrameForState 返回 Kind 未设置的零值帧表示"不发送"，
// 且不推进 baseline：下一个真实 patch 的 base 仍等于最后实际写出的 revision。
type FrameDeriver struct {
	baseline terminalengine.ScreenFrame
}

func (d *FrameDeriver) Reset() {
	d.baseline = terminalengine.ScreenFrame{}
}

// Seed 在恢复首帧成功写出后提交该客户端的完整权威 baseline。恢复 Patch 和
// ResumeAck 本身都不是完整状态，不能直接作为后续在线 diff 的基线。
func (d *FrameDeriver) Seed(state terminalengine.ScreenFrame) {
	d.baseline = state
}

// FrameForState 返回应写出的帧；返回值的 Kind 为 0 表示该状态相对 baseline
// 无任何可观察变化，调用方不得编码或发送它。
func (d *FrameDeriver) FrameForState(state terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	return frameForBaseline(&d.baseline, state)
}

// NewProjector 创建新的 screen projector。
func NewProjector(engine *terminalengine.Engine, scrollback *terminalengine.TrackedScrollback, sessionID, instanceID string) *Projector {
	return &Projector{
		engine:     engine,
		scrollback: scrollback,
		sessionID:  sessionID,
		instanceID: instanceID,
		exporter:   newExporter(terminalengine.Color{Kind: terminalengine.ColorDefaultFG}, terminalengine.Color{Kind: terminalengine.ColorDefaultBG}),
	}
}

// HistoryPage 使用与实时投影相同的字典导出历史页，保证 style/link ID 稳定。
func (p *Projector) HistoryPage(beforeSeq uint64, limit int) terminalengine.HistoryPageData {
	p.mu.Lock()
	defer p.mu.Unlock()
	window := NewHistoryView(p.scrollback).pageWithExporter(beforeSeq, limit, p.exporter)
	return terminalengine.HistoryPageData{Window: window, Styles: p.exporter.styleTable.Styles(), Links: p.exporter.linkTable.Links()}
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
	// 历史 LineID 体系重置探测：nextSeq 回退只可能来自 TrackedScrollback
	// Clear/ResetForReflow（当前无生产调用路径，此处是保守的前置 seam）。
	// 旧 LineID 与新分配不可比较，断线期间的投影不能再 patch 恢复，推进
	// barrier。普通清屏（ED 2）、全屏重绘不触碰 LineID，不推进。
	if p.scrollback != nil {
		nextSeq := p.scrollback.NextSeq()
		if nextSeq < p.scrollbackNextSeq {
			p.changeIndex.advanceBarrier(seq)
		}
		p.scrollbackNextSeq = nextSeq
	}
	if p.historyChangeIndex.sync(p.scrollback, seq) {
		// 尾部回退、LineID 跳号或索引遗漏意味着旧投影无法用 append+watermark
		// 准确修复。推进持久 barrier，并确保在线客户端也收到同 revision snapshot。
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
	return frame
}

// mergeAndExport 读一次投影、合并进全屏缓存并产出完整 State。产出的帧始终
// 是完整状态（全屏行 + 元数据），FrameDeriver 与协议层无感知。合并完成后
// 同步更新 ChangeIndex（每次导出只与上一权威投影比较一次，因此同一 16ms
// 窗口内的多次变化天然只保留最终值与最终 revision，规则 6）。
func (p *Projector) mergeAndExport(epoch, seq uint64) terminalengine.ScreenFrame {
	s := &p.projected
	prev := projectedMeta{
		valid:        s.valid,
		activeBuffer: s.activeBuffer,
		cursor:       s.cursor,
		modes:        s.modes,
		palette:      s.palette,
		title:        s.title,
		workingDir:   s.workingDir,
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
	p.updateChangeIndexLocked(seq, prev, proj)
	return p.assembleFrame(epoch, seq)
}

// assembleFrame 从全屏缓存组装完整 State。历史窗口走增量缓存（syncHistoryWindow）；
// 备用屏绝不混入主屏 scrollback。
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
		history = p.syncHistoryWindow()
	} else {
		// 备用屏期间历史缓存失效，切回主屏时全量重建。
		s.historyValid = false
		s.historyLines = nil
	}

	return terminalengine.ScreenFrame{
		Version:            1,
		Kind:               terminalengine.FrameSnapshot,
		SessionID:          p.sessionID,
		InstanceID:         p.instanceID,
		Epoch:              epoch,
		Seq:                seq,
		Rows:               s.rows,
		Cols:               s.cols,
		ActiveBuffer:       s.activeBuffer,
		ReverseVideo:       s.palette.reverseVideo,
		DefaultFG:          s.palette.defaultFG,
		DefaultBG:          s.palette.defaultBG,
		CursorColor:        s.palette.cursorColor,
		IndexedPalette:     s.palette.indexed,
		IndexedPaletteSet:  s.palette.indexedSet,
		PaletteGeneration:  s.palette.generation,
		Cursor:             s.cursor,
		Modes:              s.modes,
		History:            history,
		Screen:             screen,
		Styles:             p.exporter.styleTable.Styles(),
		Links:              p.exporter.linkTable.Links(),
		Title:              s.title,
		WorkingDir:         s.workingDir,
		RowChangedRevision: rowChangedRevision,
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

// syncHistoryWindow 增量维护历史窗口缓存并返回当前完整窗口（持 p.mu 调用）。
// scrollback 行一旦推出即不可变（Push 时已拷贝），因此缓存的 Line 可跨帧
// 复用，每帧只导出缓存 lastSeq 之后的新行。产出的窗口与 exportHistoryWindow
// 全量路径内容完全相等。屏幕全量重建（Full 投影）不影响 LineID 连续性，
// 历史缓存跨 Full 帧保持有效——否则持续 scroll 场景（每帧 Full）无法受益。
func (p *Projector) syncHistoryWindow() terminalengine.HistoryWindow {
	s := &p.projected
	if !s.historyValid {
		return p.rebuildHistoryWindow()
	}
	delta := p.scrollback.LinesAfter(s.historyLastSeq, snapshotTailLines)
	if delta.LastSeq < s.historyLastSeq {
		// Pop（resize 放大从 scrollback 拉回行）/Clear/ResetForReflow 使缓存的
		// 较新行不再存在，增量无法修复，全量重建。
		return p.rebuildHistoryWindow()
	}
	if delta.FirstSeq > s.historyLastSeq {
		// Stable IDs may legitimately have gaps after a row was deleted or a
		// resize discarded it. A gap only means our cached tail was trimmed, so
		// rebuild from the authoritative bounded window instead of treating it
		// as a protocol discontinuity.
		s.historyLines = exportHistoryLines(p.exporter, delta.Lines)
	} else {
		// 连续：追加新行（新切片，不改动缓存旧切片，历史帧共享不受影响），
		// 再从旧端裁掉被 scrollback 驱逐的行并裁到窗口上限。
		lines := s.historyLines
		if len(delta.Lines) > 0 {
			merged := make([]terminalengine.Line, 0, len(lines)+len(delta.Lines))
			merged = append(merged, lines...)
			merged = append(merged, exportHistoryLines(p.exporter, delta.Lines)...)
			lines = merged
		}
		start := 0
		for start < len(lines) && lines[start].HistorySeq < delta.FirstSeq {
			start++
		}
		if len(lines)-start > snapshotTailLines {
			start = len(lines) - snapshotTailLines
		}
		s.historyLines = lines[start:]
	}
	if n := len(s.historyLines); n > 0 {
		s.historyLastSeq = s.historyLines[n-1].HistorySeq
	} else {
		s.historyLastSeq = delta.LastSeq
	}
	return historyWindowFromLines(s.historyLines, delta.FirstSeq)
}

// rebuildHistoryWindow 全量重建历史窗口缓存（首次导出、epoch/字典轮转、
// 备用屏返回、Pop/Clear/reflow 之后）。
func (p *Projector) rebuildHistoryWindow() terminalengine.HistoryWindow {
	s := &p.projected
	w := p.exporter.exportHistoryWindow(p.scrollback)
	s.historyLines = w.Lines
	s.historyLastSeq = w.LastIncludedHistorySeq
	s.historyValid = true
	return w
}

func frameForBaseline(baseline *terminalengine.ScreenFrame, state terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	// 输入始终是完整状态，可直接作为 snapshot 发送；统一打上 Kind，避免
	// 调用方漏设导致编码失败。diffToPatch 的 snapshot 回退路径也借此得到
	// 正确的 Kind。
	state.Kind = terminalengine.FrameSnapshot
	// 第一帧、字典轮转、字典世代（baseline 出自已废弃的字典，即使携带
	// ForceSnapshot 的轮转帧被 mailbox 覆盖也必须全量）、instance/layout
	// epoch 或备用屏变化，发送完整 snapshot。
	if state.ForceSnapshot || baseline.Seq == 0 || baseline.InstanceID != state.InstanceID || baseline.Epoch != state.Epoch || baseline.ActiveBuffer != state.ActiveBuffer || baseline.DictionaryGeneration != state.DictionaryGeneration {
		*baseline = state
		return state
	}

	// 否则生成 patch（整行替换）。
	patch := diffToPatch(*baseline, state)
	if patch.Kind == terminalengine.FramePatch && isEmptyPatch(*baseline, patch) {
		// 无可观察变化（bell、title 设回原值等仍会让 Runtime bump revision）：
		// 抑制空 patch（计划 §3.4/§10.1：patch 必须携带实际变化），不推进
		// baseline，下一帧仍相对最后实际写出的 revision 做 diff。
		return terminalengine.ScreenFrame{}
	}
	*baseline = state
	return patch
}

// isEmptyPatch 判断 diff 出的 patch 是否不含任何可观察变化。cursor/modes/palette
// 通过 patch presence 标志表达，避免把未变化的元数据重复编码到每一帧。
func isEmptyPatch(baseline, patch terminalengine.ScreenFrame) bool {
	return len(patch.History.Lines) == 0 &&
		len(patch.HistoryAppendSeqs) == 0 &&
		len(patch.Screen) == 0 &&
		len(patch.Layout) == 0 &&
		len(patch.Styles) == 0 &&
		len(patch.Links) == 0 &&
		!patch.TitleChanged &&
		!patch.WorkingDirChanged &&
		!patch.CursorChanged &&
		!patch.ModesChanged &&
		!patch.PaletteChanged
}

// diffToPatch 计算两帧差异并生成 patch 帧。
//
// 在线客户端已持有连续 baseline 时，即使本次变化覆盖整屏，也仍然使用
// Patch：它只携带当前屏幕的变化行与新增历史，不能因为 AGY 一类 TUI 的
// 全屏重绘而重复发送整个历史窗口并让 Android 重置投影。Snapshot 只由
// frameForBaseline 的真实同步边界（首帧、epoch/buffer/dictionary 变化或
// ForceSnapshot）触发。断线恢复的成本判定仍在 resume.go 单独处理。
//
// 历史窗口是 epoch 内连续 LineID 的尾部窗口，且历史行推出后不可变，因此
// history append 不需要逐行 ID 比对：窗口边界即可证明新增连续范围。
func diffToPatch(old, new terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	// Stable screen IDs can leave gaps in scrollback after delete/resize. Select
	// the newly present IDs instead of relying on historical ID contiguity.
	oldHistory := make(map[uint64]struct{}, len(old.History.Lines))
	for _, line := range old.History.Lines {
		oldHistory[line.HistorySeq] = struct{}{}
	}
	var historyAppend []terminalengine.Line
	var historyAppendSeqs []uint64
	for _, line := range new.History.Lines {
		if _, seen := oldHistory[line.HistorySeq]; !seen {
			historyAppendSeqs = append(historyAppendSeqs, line.HistorySeq)
			// A HistorySeq must be bound to a LineID at the receiver. Even when
			// the line was visible on the previous screen, include its LineData so
			// the bounded Android cache can append the correct history entry.
			historyAppend = append(historyAppend, line)
		}
	}
	screenRows := changedLinesByID(old, new.Screen)
	newLayout := screenLayout(new.Screen)
	var layout []uint64
	if !sameLayout(screenLayout(old.Screen), newLayout) {
		layout = newLayout
	}

	return terminalengine.ScreenFrame{
		Version:           1,
		Kind:              terminalengine.FramePatch,
		SessionID:         new.SessionID,
		InstanceID:        new.InstanceID,
		Epoch:             new.Epoch,
		Seq:               new.Seq,
		BaseRevision:      old.Seq,
		Rows:              new.Rows,
		Cols:              new.Cols,
		ActiveBuffer:      new.ActiveBuffer,
		ReverseVideo:      new.ReverseVideo,
		DefaultFG:         new.DefaultFG,
		DefaultBG:         new.DefaultBG,
		CursorColor:       new.CursorColor,
		IndexedPalette:    new.IndexedPalette,
		IndexedPaletteSet: new.IndexedPaletteSet,
		PaletteGeneration: new.PaletteGeneration,
		Cursor:            new.Cursor,
		Modes:             new.Modes,
		CursorChanged:     old.Cursor != new.Cursor,
		ModesChanged:      old.Modes != new.Modes,
		PaletteChanged: old.ReverseVideo != new.ReverseVideo ||
			old.DefaultFG != new.DefaultFG || old.DefaultBG != new.DefaultBG ||
			old.CursorColor != new.CursorColor || old.IndexedPalette != new.IndexedPalette ||
			old.IndexedPaletteSet != new.IndexedPaletteSet ||
			old.PaletteGeneration != new.PaletteGeneration,
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: new.History.FirstAvailableHistorySeq,
			FirstIncludedHistorySeq:  new.History.FirstIncludedHistorySeq,
			LastIncludedHistorySeq:   new.History.LastIncludedHistorySeq,
			HasMoreBefore:            new.History.HasMoreBefore,
			Lines:                    historyAppend,
		},
		HistoryAppendSeqs: historyAppendSeqs,
		Screen:            screenRows,
		Layout:            layout,
		// Snapshot owns a complete dictionary. A patch only needs entries that
		// appeared after the recipient's baseline; repeatedly sending the whole
		// table was pure wire and allocation overhead.
		Styles: newlyAddedStyles(old.Styles, new.Styles),
		Links:  newlyAddedLinks(old.Links, new.Links),
		// title/cwd 以显式 presence 标志表达三态：未变化 / 变为空串 / 新值。
		Title:             new.Title,
		WorkingDir:        new.WorkingDir,
		TitleChanged:      old.Title != new.Title,
		WorkingDirChanged: old.WorkingDir != new.WorkingDir,
	}
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
	if a.Wrapped != b.Wrapped || len(a.Runs) != len(b.Runs) {
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
