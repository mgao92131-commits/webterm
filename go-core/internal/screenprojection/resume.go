package screenprojection

import "webterm/go-core/internal/terminalengine"

type ResumeScreenLine struct {
	LineID      uint64
	LineVersion uint64
}

type ResumeToken struct {
	InstanceID           string
	LayoutEpoch          uint64
	ScreenRevision       uint64
	DictionaryGeneration uint64
	HistoryGeneration    uint64
	ActiveBuffer         terminalengine.BufferKind
	ActiveRows           []ResumeScreenLine
}

type ResumeKind uint8

const (
	ResumeBaseline ResumeKind = iota + 1
	ResumeAccepted
	ResumeCommit
)

type ResumeResult struct {
	Kind  ResumeKind
	State terminalengine.ScreenFrame
	Frame terminalengine.ScreenFrame
}

// Resume 从完整连续性令牌判断无变化接受、跨 revision Commit 或 Baseline。
// 它不依赖无界 patch journal；Commit 只由令牌中可证明的 ActiveRows 身份派生。
// forceBaseline 为 true 时跳过 Resume Commit，直接返回完整 Baseline。
func (p *Projector) Resume(token *ResumeToken, epoch, revision uint64, forceBaseline bool) ResumeResult {
	p.mu.Lock()
	defer p.mu.Unlock()
	state := p.exportStateLocked(epoch, revision)
	baseline := func() ResumeResult {
		state.Kind = terminalengine.FrameSnapshot
		return ResumeResult{Kind: ResumeBaseline, State: state, Frame: state}
	}
	if forceBaseline {
		return baseline()
	}
	if token == nil {
		return baseline()
	}
	if token.InstanceID != state.InstanceID || token.LayoutEpoch != state.Epoch ||
		token.HistoryGeneration != state.HistoryGeneration ||
		token.DictionaryGeneration != state.DictionaryGeneration ||
		token.ScreenRevision > state.Seq || token.ScreenRevision < p.changeIndex.SnapshotBarrierRevision ||
		len(token.ActiveRows) != len(state.Screen) {
		return baseline()
	}
	seen := make(map[uint64]struct{}, len(token.ActiveRows))
	oldScreen := make([]terminalengine.Line, len(token.ActiveRows))
	identityEqual := token.ActiveBuffer == state.ActiveBuffer
	for i, line := range token.ActiveRows {
		if line.LineID == 0 || line.LineVersion == 0 {
			return baseline()
		}
		if _, duplicate := seen[line.LineID]; duplicate {
			return baseline()
		}
		seen[line.LineID] = struct{}{}
		oldScreen[i] = terminalengine.Line{ID: line.LineID, Version: line.LineVersion, Row: i}
		if state.Screen[i].ID != line.LineID || state.Screen[i].Version != line.LineVersion {
			identityEqual = false
		}
	}
	if token.ScreenRevision == state.Seq && identityEqual {
		return ResumeResult{Kind: ResumeAccepted, State: state}
	}
	old := terminalengine.ScreenFrame{
		Kind: terminalengine.FrameSnapshot, InstanceID: token.InstanceID,
		Epoch: token.LayoutEpoch, Seq: token.ScreenRevision,
		Rows: state.Rows, Cols: state.Cols, ActiveBuffer: token.ActiveBuffer,
		Screen: oldScreen, DictionaryGeneration: token.DictionaryGeneration,
		HistoryGeneration: token.HistoryGeneration,
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: state.History.FirstAvailableHistorySeq,
		},
	}
	// 用创建 revision 推导客户端最后已知的位置。正文是否连续驻留不参与恢复。
	for _, change := range p.historyChangeIndex.Changes {
		if change.CreatedRevision <= token.ScreenRevision && change.HistorySeq > old.History.LastIncludedHistorySeq {
			old.History.LastIncludedHistorySeq = change.HistorySeq
		}
	}
	for i, created := range p.changeIndex.StyleCreatedRevision {
		if created <= token.ScreenRevision && i < len(state.Styles) {
			old.Styles = append(old.Styles, state.Styles[i])
		}
	}
	for i, created := range p.changeIndex.LinkCreatedRevision {
		if created <= token.ScreenRevision && i < len(state.Links) {
			old.Links = append(old.Links, state.Links[i])
		}
	}
	frame := diffToPatch(old, state)
	frame.CursorChanged = true
	frame.ModesChanged = true
	frame.PaletteChanged = true
	frame.Kind = terminalengine.FramePatch
	frame.BaseRevision = token.ScreenRevision
	return ResumeResult{Kind: ResumeCommit, State: state, Frame: frame}
}
