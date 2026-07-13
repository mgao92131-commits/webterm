package screenprojection

import (
	"sync"

	"webterm/go-core/internal/terminalengine"
)

// Projector 为每个 screen client 维护发送基线并生成 snapshot/patch。
type Projector struct {
	mu          sync.RWMutex
	engine      *terminalengine.Engine
	scrollback  *terminalengine.TrackedScrollback
	sessionID   string
	instanceID  string
	exporter    *exporter
	exportEpoch uint64

	clients map[string]*clientBaseline
}

type clientBaseline struct {
	frame terminalengine.ScreenFrame
}

// FrameDeriver owns one transport client's last successfully scheduled
// authoritative state. It derives a frame only when that client is actually
// about to write, so a slow client can collapse many intermediate states
// without creating a BaseRevision gap.
type FrameDeriver struct {
	baseline clientBaseline
}

func (d *FrameDeriver) Reset() {
	d.baseline = clientBaseline{}
}

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
		clients:    make(map[string]*clientBaseline),
	}
}

// RegisterClient 注册一个新 client。
func (p *Projector) RegisterClient(clientID string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.clients[clientID] = &clientBaseline{}
}

// UnregisterClient 移除 client。
func (p *Projector) UnregisterClient(clientID string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.clients, clientID)
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

// FrameForClient 为指定 client 生成 snapshot 或 patch。
// 如果 client 没有基线或 instance/layout epoch 变化，返回 snapshot。
func (p *Projector) FrameForClient(clientID string, epoch, seq uint64) terminalengine.ScreenFrame {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.frameForClientLocked(clientID, p.exportStateLocked(epoch, seq))
}

// FrameForClientState derives a client frame from a state previously returned
// by ExportState. The supplied state must belong to this projector and the
// current actor revision; it is intentionally a value so clients cannot alter
// the shared export.
func (p *Projector) FrameForClientState(clientID string, state terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.frameForClientLocked(clientID, state)
}

func (p *Projector) exportStateLocked(epoch, seq uint64) terminalengine.ScreenFrame {
	if p.exportEpoch != epoch {
		p.exporter = newExporter(terminalengine.Color{Kind: terminalengine.ColorDefaultFG}, terminalengine.Color{Kind: terminalengine.ColorDefaultBG})
		p.exportEpoch = epoch
	}
	frame := p.exporter.exportSnapshot(p.engine, p.scrollback, p.sessionID, p.instanceID, epoch, seq)
	// 字典只增不改；大量瞬时 RGB/OSC8 若使历史字典膨胀，则以权威 snapshot
	// 旋转字典并清空所有客户端基线。当前可见状态仍超过上限时由协议校验拒绝。
	if len(frame.Styles) > 4096 || len(frame.Links) > 4096 {
		p.exporter = newExporter(terminalengine.Color{Kind: terminalengine.ColorDefaultFG}, terminalengine.Color{Kind: terminalengine.ColorDefaultBG})
		frame = p.exporter.exportSnapshot(p.engine, p.scrollback, p.sessionID, p.instanceID, epoch, seq)
		for id := range p.clients {
			p.clients[id] = &clientBaseline{}
		}
		frame.ForceSnapshot = true
	}
	return frame
}

func (p *Projector) frameForClientLocked(clientID string, state terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	baseline, ok := p.clients[clientID]
	if !ok {
		baseline = &clientBaseline{}
		p.clients[clientID] = baseline
	}

	return frameForBaseline(baseline, state)
}

func frameForBaseline(baseline *clientBaseline, state terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	// 第一帧、字典轮转、instance/layout epoch 或备用屏变化，发送完整 snapshot。
	if state.ForceSnapshot || baseline.frame.Seq == 0 || baseline.frame.InstanceID != state.InstanceID || baseline.frame.Epoch != state.Epoch || baseline.frame.ActiveBuffer != state.ActiveBuffer {
		baseline.frame = state
		return state
	}

	// 否则生成 patch（整行替换）。
	patch := diffToPatch(baseline.frame, state)
	baseline.frame = state
	return patch
}

// diffToPatch 计算两帧差异并生成 patch 帧。
// 如果变化行数超过活动屏幕的 60%，直接返回 snapshot。
func diffToPatch(old, new terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	// 单次输出跨过了客户端持有窗口时，patch 无法表达中间历史行；改发完整窗口。
	if new.History.LastIncludedLineID > old.History.LastIncludedLineID {
		appended := new.History.LastIncludedLineID - old.History.LastIncludedLineID
		if appended > uint64(len(new.History.Lines)) {
			return new
		}
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

	// history append：新帧历史包含但旧帧不包含的行。
	var historyAppend []terminalengine.Line
	oldHistoryIDs := make(map[uint64]bool)
	for _, line := range old.History.Lines {
		oldHistoryIDs[line.ID] = true
	}
	for _, line := range new.History.Lines {
		if !oldHistoryIDs[line.ID] {
			historyAppend = append(historyAppend, line)
		}
	}

	return terminalengine.ScreenFrame{
		Version:      1,
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
		Title:  new.Title,
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
