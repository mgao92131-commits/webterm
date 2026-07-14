package screenprojection

import (
	"sync"

	headlessterm "github.com/danielgatis/go-headless-term"
	"webterm/go-core/internal/terminalengine"
)

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
	title        string
	workingDir   string
	// 历史窗口缓存（阶段 2c）：已导出的尾部窗口行（Line 不可变，跨帧零拷贝
	// 复用）与缓存窗口最后一行的 ID。historyValid=false 表示缓存不反映当前
	// scrollback（首次导出、epoch/字典轮转、备用屏期间），下次主屏导出经
	// exportHistoryWindow 全量重建。
	historyValid  bool
	historyLines  []terminalengine.Line
	historyLastID uint64 // 缓存窗口最后一行的 ID；窗口为空时为 FirstAvailable-1
}

// rebuild 用完整投影（Full）重建全部行与元数据。
func (s *projectedState) rebuild(proj headlessterm.ProjectionRead, exp *exporter) {
	screen := make([]terminalengine.Line, proj.Rows)
	for _, row := range proj.DirtyRows {
		if row.Index >= 0 && row.Index < len(screen) {
			screen[row.Index] = exp.exportProjectionRow(row, proj.Cursor.Row, proj.Cursor.Col)
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
			s.screen[row.Index] = exp.exportProjectionRow(row, proj.Cursor.Row, proj.Cursor.Col)
		}
	}
	s.mergeMeta(proj)
}

func (s *projectedState) mergeMeta(proj headlessterm.ProjectionRead) {
	activeBuffer := terminalengine.BufferMain
	if proj.ActiveBuffer == headlessterm.BufferKindAlternate {
		activeBuffer = terminalengine.BufferAlternate
	}
	s.activeBuffer = activeBuffer
	s.cursor = exportProjectionCursor(proj.Cursor)
	s.modes = exportProjectionModes(proj.Modes)
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
func (p *Projector) HistoryPage(beforeID uint64, limit int) terminalengine.HistoryPageData {
	p.mu.Lock()
	defer p.mu.Unlock()
	window := NewHistoryView(p.scrollback).pageWithExporter(beforeID, limit, p.exporter)
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
	if p.exportEpoch != epoch {
		p.exporter = newExporter(terminalengine.Color{Kind: terminalengine.ColorDefaultFG}, terminalengine.Color{Kind: terminalengine.ColorDefaultBG})
		p.exportEpoch = epoch
		p.dictGeneration++
		// 缓存行引用的 style/link ID 出自被废弃的字典，必须整体重建。
		p.projected = projectedState{}
	}
	frame := p.mergeAndExport(epoch, seq)
	// 字典只增不改；大量瞬时 RGB/OSC8 若使历史字典膨胀，则以权威 snapshot
	// 旋转字典。当前可见状态仍超过上限时由协议校验拒绝。
	if len(frame.Styles) > 4096 || len(frame.Links) > 4096 {
		p.exporter = newExporter(terminalengine.Color{Kind: terminalengine.ColorDefaultFG}, terminalengine.Color{Kind: terminalengine.ColorDefaultBG})
		p.dictGeneration++
		p.projected = projectedState{}
		frame = p.mergeAndExport(epoch, seq)
		frame.ForceSnapshot = true
	}
	frame.DictionaryGeneration = p.dictGeneration
	return frame
}

// mergeAndExport 读一次投影、合并进全屏缓存并产出完整 State。产出的帧始终
// 是完整状态（全屏行 + 元数据），FrameDeriver 与协议层无感知。
func (p *Projector) mergeAndExport(epoch, seq uint64) terminalengine.ScreenFrame {
	s := &p.projected
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
		Version:      1,
		Kind:         terminalengine.FrameSnapshot,
		SessionID:    p.sessionID,
		InstanceID:   p.instanceID,
		Epoch:        epoch,
		Seq:          seq,
		Rows:         s.rows,
		Cols:         s.cols,
		ActiveBuffer: s.activeBuffer,
		ReverseVideo: false,
		DefaultFG:    terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		DefaultBG:    terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
		CursorColor:  terminalengine.Color{Kind: terminalengine.ColorCursor},
		Cursor:       s.cursor,
		Modes:        s.modes,
		History:      history,
		Screen:       screen,
		Styles:       p.exporter.styleTable.Styles(),
		Links:        p.exporter.linkTable.Links(),
		Title:        s.title,
		WorkingDir:   s.workingDir,
	}
}

// syncHistoryWindow 增量维护历史窗口缓存并返回当前完整窗口（持 p.mu 调用）。
// scrollback 行一旦推出即不可变（Push 时已拷贝），因此缓存的 Line 可跨帧
// 复用，每帧只导出缓存 lastID 之后的新行。产出的窗口与 exportHistoryWindow
// 全量路径内容完全相等。屏幕全量重建（Full 投影）不影响 LineID 连续性，
// 历史缓存跨 Full 帧保持有效——否则持续 scroll 场景（每帧 Full）无法受益。
func (p *Projector) syncHistoryWindow() terminalengine.HistoryWindow {
	s := &p.projected
	if !s.historyValid {
		return p.rebuildHistoryWindow()
	}
	delta := p.scrollback.LinesAfter(s.historyLastID, snapshotTailLines)
	if delta.LastID < s.historyLastID {
		// Pop（resize 放大从 scrollback 拉回行）/Clear/ResetForReflow 使缓存的
		// 较新行不再存在，增量无法修复，全量重建。
		return p.rebuildHistoryWindow()
	}
	if delta.FirstID > s.historyLastID+1 {
		// 缓存最后一行与新窗口之间出现缺口（中间行已被驱逐）：新窗口恰好
		// 是 delta——LinesAfter 已按窗口上限取最新一段。
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
		for start < len(lines) && lines[start].ID < delta.FirstID {
			start++
		}
		if len(lines)-start > snapshotTailLines {
			start = len(lines) - snapshotTailLines
		}
		s.historyLines = lines[start:]
	}
	if n := len(s.historyLines); n > 0 {
		s.historyLastID = s.historyLines[n-1].ID
	} else {
		s.historyLastID = delta.LastID
	}
	return historyWindowFromLines(s.historyLines, delta.FirstID)
}

// rebuildHistoryWindow 全量重建历史窗口缓存（首次导出、epoch/字典轮转、
// 备用屏返回、Pop/Clear/reflow 之后）。
func (p *Projector) rebuildHistoryWindow() terminalengine.HistoryWindow {
	s := &p.projected
	w := p.exporter.exportHistoryWindow(p.scrollback)
	s.historyLines = w.Lines
	s.historyLastID = w.LastIncludedLineID
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

// isEmptyPatch 判断 diff 出的 patch 是否不含任何可观察变化。patch 始终携带
// cursor/modes/palette 的当前值（尚无 presence 标志），因此这些组件与
// baseline 相等才算未变化；history append、变化行、promoted rows、新字典项
// 与 title/cwd 标志任一非空即为真实变化。
func isEmptyPatch(baseline, patch terminalengine.ScreenFrame) bool {
	return len(patch.History.Lines) == 0 &&
		len(patch.Screen) == 0 &&
		len(patch.PromotedRows) == 0 &&
		len(patch.Styles) == 0 &&
		len(patch.Links) == 0 &&
		!patch.TitleChanged &&
		!patch.WorkingDirChanged &&
		patch.Cursor == baseline.Cursor &&
		patch.Modes == baseline.Modes &&
		patch.ReverseVideo == baseline.ReverseVideo &&
		patch.DefaultFG == baseline.DefaultFG &&
		patch.DefaultBG == baseline.DefaultBG &&
		patch.CursorColor == baseline.CursorColor
}

// diffToPatch 计算两帧差异并生成 patch 帧。
// 如果变化行数超过活动屏幕的 60%，直接返回 snapshot。
//
// 历史窗口是 epoch 内连续 LineID 的尾部窗口，且历史行推出后不可变，因此
// history append 不需要逐行 ID 比对：窗口边界即可证明新增连续范围。
func diffToPatch(old, new terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	// history append 是 baseline 最后一行之后的连续新增段。baseline 已被 trim
	// 或中间行越出新窗口（追加量超过窗口容量）时 patch 无法表达缺失行，
	// 退回完整 snapshot。
	var historyAppend []terminalengine.Line
	if new.History.LastIncludedLineID > old.History.LastIncludedLineID {
		appended := new.History.LastIncludedLineID - old.History.LastIncludedLineID
		if appended > uint64(len(new.History.Lines)) {
			return new
		}
		historyAppend = new.History.Lines[len(new.History.Lines)-int(appended):]
	}
	changedRows := make(map[int]terminalengine.Line)
	for r := 0; r < len(new.Screen); r++ {
		if r >= len(old.Screen) || !linesEqual(old.Screen[r], new.Screen[r]) {
			changedRows[r] = new.Screen[r]
		}
	}

	const snapshotThresholdPercent = 60
	threshold := len(new.Screen) * snapshotThresholdPercent / 100
	if len(changedRows) > threshold {
		return new
	}

	screenRows := make([]terminalengine.Line, 0, len(changedRows))
	for r := 0; r < len(new.Screen); r++ {
		if line, ok := changedRows[r]; ok {
			screenRows = append(screenRows, line)
		}
	}

	return terminalengine.ScreenFrame{
		Version:      1,
		Kind:         terminalengine.FramePatch,
		SessionID:    new.SessionID,
		InstanceID:   new.InstanceID,
		Epoch:        new.Epoch,
		Seq:          new.Seq,
		BaseRevision: old.Seq,
		Rows:         new.Rows,
		Cols:         new.Cols,
		ActiveBuffer: new.ActiveBuffer,
		ReverseVideo: new.ReverseVideo,
		DefaultFG:    new.DefaultFG,
		DefaultBG:    new.DefaultBG,
		CursorColor:  new.CursorColor,
		Cursor:       new.Cursor,
		Modes:        new.Modes,
		History: terminalengine.HistoryWindow{
			FirstAvailableLineID: new.History.FirstAvailableLineID,
			FirstIncludedLineID:  new.History.FirstIncludedLineID,
			LastIncludedLineID:   new.History.LastIncludedLineID,
			HasMoreBefore:        new.History.HasMoreBefore,
			Lines:                historyAppend,
		},
		Screen: screenRows,
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
