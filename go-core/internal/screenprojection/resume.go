package screenprojection

import (
	"webterm/go-core/internal/terminalengine"
)

// snapshotRowThresholdPercent 是 patch 成本降级阈值：变化活动行超过该比例时
// 优先 snapshot（计划 §6.1 第 1 条）。在线 diffToPatch 与 resume 推导共用，
// 阈值由 benchmark/真机流量校准（§6.1 第 4 条），不要凭经验改动。
const snapshotRowThresholdPercent = 60

// ResumeOutcome 是 resume 推导的判定结果类别。
type ResumeOutcome uint8

const (
	// ResumeOutcomePatch：Frame 携带 Kind=FramePatch 的累计 patch，
	// 满足 baseRevision=clientRevision、screenRevision=currentRevision。
	ResumeOutcomePatch ResumeOutcome = iota + 1
	// ResumeOutcomeSnapshot：不得产 patch，需发送 snapshot；Reason 给出原因
	// （对齐 §6/§13 的 resume_reason 取值）。
	ResumeOutcomeSnapshot
	// ResumeOutcomeExact：客户端投影内容与当前权威状态一致（revision 相等，
	// 或 revision gap 内无任何可观察变化）。本函数不产帧——合法 patch 必须
	// 满足 screenRevision > baseRevision（约束 5），空 patch 也被 §10.1
	// 禁止；Task 4 走 ResumeAck 路径直接把客户端推进到 currentRevision。
	ResumeOutcomeExact
)

// snapshot 原因（§6/§13 resume_reason）。
const (
	ResumeReasonFutureRevision = "future_revision"
	ResumeReasonBarrier        = "barrier"
	ResumeReasonPatchCost      = "patch_cost"
	ResumeReasonHistoryGap     = "history_gap"
)

// ResumeDerivation 是 DeriveResumeFrame 的返回。
type ResumeDerivation struct {
	Outcome ResumeOutcome
	Reason  string // Outcome==ResumeOutcomeSnapshot 时有效
	Frame   terminalengine.ScreenFrame
}

// DeriveResumeFrame 是纯函数：给定当前权威完整状态、ChangeIndex 与客户端声明
// 的 screenRevision，按计划 §6 的累计 Patch 规则推导恢复帧。它不修改
// ChangeIndex 或任何 Projector 状态，可独立单测。
//
// 前置条件：调用方已按 §6 完成 instance/layoutEpoch 校验（本函数只处理同
// instance/epoch 下的 revision 判定）。
//
// 累计 patch 只表达 clientRevision 之后的最终状态（可从 revision 100 直接跳
// 到 150，不回放中间 revision）。history_append 与原子水位是 Task 3 的扩展点。
func DeriveResumeFrame(state terminalengine.ScreenFrame, idx *ChangeIndex, clientRevision uint64) ResumeDerivation {
	current := state.Seq
	switch {
	case clientRevision > current:
		// 客户端声称的 revision 超过权威 revision（§6 future_revision）。
		return ResumeDerivation{Outcome: ResumeOutcomeSnapshot, Reason: ResumeReasonFutureRevision}
	case clientRevision < idx.SnapshotBarrierRevision:
		// 客户端投影在 snapshot 屏障之前（buffer 切换/字典轮转/LineID 体系
		// 重置等），不能再用当前状态的 patch 恢复（§6 barrier）。
		return ResumeDerivation{Outcome: ResumeOutcomeSnapshot, Reason: ResumeReasonBarrier}
	case clientRevision == current:
		// exact resume：客户端已是最新权威状态，明确拒绝产 patch；
		// Task 4 以 ResumeAck(currentRevision) 确认。
		return ResumeDerivation{Outcome: ResumeOutcomeExact}
	}

	// 变化行：RowChangedRevision > clientRevision 的行，携带当前最终值。
	// 索引短于屏幕行数是内部不一致：保守地视为已变化——多带行不影响正确性，
	// 且通常会触发下面的成本降级。先计数再构帧：超阈值时不必收集行内容。
	changed := 0
	for r := 0; r < len(state.Screen); r++ {
		if r >= len(idx.RowChangedRevision) || idx.RowChangedRevision[r] > clientRevision {
			changed++
		}
	}
	if changed > len(state.Screen)*snapshotRowThresholdPercent/100 {
		// §6.1 第 1 条：变化活动行超过阈值时优先 snapshot（与 diffToPatch 一致）。
		return ResumeDerivation{Outcome: ResumeOutcomeSnapshot, Reason: ResumeReasonPatchCost}
	}
	var changedRows []terminalengine.Line
	if changed > 0 {
		changedRows = make([]terminalengine.Line, 0, changed)
		for r := 0; r < len(state.Screen); r++ {
			if r >= len(idx.RowChangedRevision) || idx.RowChangedRevision[r] > clientRevision {
				changedRows = append(changedRows, state.Screen[r])
			}
		}
	}

	titleChanged := idx.TitleChangedRevision > clientRevision
	cwdChanged := idx.CWDChangedRevision > clientRevision
	cursorChanged := idx.CursorChangedRevision > clientRevision
	modesChanged := idx.ModesChangedRevision > clientRevision
	paletteChanged := idx.PaletteChangedRevision > clientRevision
	newStyles := stylesCreatedAfter(state.Styles, idx.StyleCreatedRevision, clientRevision)
	newLinks := linksCreatedAfter(state.Links, idx.LinkCreatedRevision, clientRevision)

	if len(changedRows) == 0 && !titleChanged && !cwdChanged &&
		!cursorChanged && !modesChanged && !paletteChanged &&
		len(newStyles) == 0 && len(newLinks) == 0 {
		// revision gap 内没有任何可观察变化（bell 等只推进 revision 的输出）：
		// 客户端投影内容与权威状态一致。空 patch 违反 §10.1“至少一个实际变化
		// 字段”，按 exact 处理——Task 4 以 ResumeAck(currentRevision) 把客户端
		// revision 直接推进到 current。
		return ResumeDerivation{Outcome: ResumeOutcomeExact}
	}

	return ResumeDerivation{
		Outcome: ResumeOutcomePatch,
		Frame: terminalengine.ScreenFrame{
			Version:      1,
			Kind:         terminalengine.FramePatch,
			SessionID:    state.SessionID,
			InstanceID:   state.InstanceID,
			Epoch:        state.Epoch,
			Seq:          current,
			BaseRevision: clientRevision,
			Rows:         state.Rows,
			Cols:         state.Cols,
			ActiveBuffer: state.ActiveBuffer,
			// cursor/modes/palette 沿用 diffToPatch 的既有 wire 语义：ScreenFrame
			// 没有 presence 标志、encoder 对 patch 总是编码这三个组件，因此总是
			// 携带当前值（客户端无条件覆盖）。上面的 *Changed revision 只用于
			// 空 patch 判定与后续指标（§13），不改变 wire 内容；若未来引入显式
			// presence，可据此改为按 revision > clientRevision 决定出现与否。
			ReverseVideo:      state.ReverseVideo,
			DefaultFG:         state.DefaultFG,
			DefaultBG:         state.DefaultBG,
			CursorColor:       state.CursorColor,
			IndexedPalette:    state.IndexedPalette,
			IndexedPaletteSet: state.IndexedPaletteSet,
			PaletteGeneration: state.PaletteGeneration,
			Cursor:            state.Cursor,
			Modes:             state.Modes,
			Screen:            changedRows,
			Styles:            newStyles,
			Links:             newLinks,
			// title/cwd 以显式 presence 标志表达三态（未变化/变为空串/新值）。
			Title:             state.Title,
			WorkingDir:        state.WorkingDir,
			TitleChanged:      titleChanged,
			WorkingDirChanged: cwdChanged,
			// history append 与原子水位由 Projector.DeriveResumeFrame 在持锁
			// 历史选择后附加；纯函数层只推导屏幕与元数据。
		},
	}
}

// DeriveResumeFrame 在 Projector 读锁内用当前 ChangeIndex 做 resume 推导
// （规则 5：ChangeIndex 只在 Projector 锁内读写）。state 必须是本 Projector
// 最近导出的权威状态；instance/epoch 校验由调用方（Task 4 的 actor）完成。
func (p *Projector) DeriveResumeFrame(state terminalengine.ScreenFrame, clientRevision uint64) ResumeDerivation {
	p.mu.Lock()
	defer p.mu.Unlock()

	// 先执行不需要历史 Cell 的快速判定。future/barrier/活动行成本已经要求
	// snapshot 时，不再进入历史导出慢路径。
	derived := DeriveResumeFrame(state, &p.changeIndex, clientRevision)
	if derived.Outcome == ResumeOutcomeSnapshot {
		return derived
	}
	selection := p.historyChangeIndex.selectAfter(clientRevision)
	if selection.reason != "" {
		return ResumeDerivation{Outcome: ResumeOutcomeSnapshot, Reason: selection.reason}
	}
	historyLines, err := p.exportResumeHistoryLocked(selection.lineIDs)
	if err != nil {
		return ResumeDerivation{Outcome: ResumeOutcomeSnapshot, Reason: ResumeReasonHistoryGap}
	}

	// 历史行可能首次把某个 style/link 引入当前 exporter。把新增字典项绑定到
	// 当前导出 revision，再重新推导一次，确保恢复 Patch 先携带其引用。
	styles := p.exporter.styleTable.Styles()
	if len(styles) > 4096 || len(p.exporter.linkTable.Links()) > 4096 {
		return ResumeDerivation{Outcome: ResumeOutcomeSnapshot, Reason: ResumeReasonPatchCost}
	}
	for len(p.changeIndex.StyleCreatedRevision) < len(styles) {
		p.changeIndex.StyleCreatedRevision = append(p.changeIndex.StyleCreatedRevision, state.Seq)
	}
	links := p.exporter.linkTable.Links()
	for len(p.changeIndex.LinkCreatedRevision) < len(links) {
		p.changeIndex.LinkCreatedRevision = append(p.changeIndex.LinkCreatedRevision, state.Seq)
	}
	state.Styles = styles
	state.Links = links
	derived = DeriveResumeFrame(state, &p.changeIndex, clientRevision)
	if derived.Outcome == ResumeOutcomeSnapshot {
		return derived
	}

	if derived.Outcome == ResumeOutcomeExact && len(historyLines) == 0 && !selection.watermarkChanged {
		return derived
	}
	if derived.Outcome == ResumeOutcomeExact {
		// 只有 history append/watermark 发生变化时也必须生成合法非空 Patch。
		derived = ResumeDerivation{Outcome: ResumeOutcomePatch, Frame: terminalengine.ScreenFrame{
			Version:           1,
			Kind:              terminalengine.FramePatch,
			SessionID:         state.SessionID,
			InstanceID:        state.InstanceID,
			Epoch:             state.Epoch,
			Seq:               state.Seq,
			BaseRevision:      clientRevision,
			Rows:              state.Rows,
			Cols:              state.Cols,
			ActiveBuffer:      state.ActiveBuffer,
			ReverseVideo:      state.ReverseVideo,
			DefaultFG:         state.DefaultFG,
			DefaultBG:         state.DefaultBG,
			CursorColor:       state.CursorColor,
			IndexedPalette:    state.IndexedPalette,
			IndexedPaletteSet: state.IndexedPaletteSet,
			PaletteGeneration: state.PaletteGeneration,
			Cursor:            state.Cursor,
			Modes:             state.Modes,
		}}
	}
	derived.Frame.History = terminalengine.HistoryWindow{
		FirstAvailableLineID: selection.firstAvailableID,
		Lines:                historyLines,
	}
	// 每一帧恢复 Patch 都原子携带当前水位；接收端无需依赖 HistoryTrim 与
	// screen mailbox 的相对顺序。
	derived.Frame.FirstAvailableHistoryLineIDChanged = true
	return derived
}

// stylesCreatedAfter 选出 created revision > clientRevision 的字典项。
// 索引缺失（内部不一致）时保守地带上该项：重发字典项幂等无害，漏发则会
// 让客户端引用未知 ID。
func stylesCreatedAfter(styles []terminalengine.TerminalStyle, created []uint64, clientRevision uint64) []terminalengine.TerminalStyle {
	var out []terminalengine.TerminalStyle
	for i, style := range styles {
		if i >= len(created) || created[i] > clientRevision {
			out = append(out, style)
		}
	}
	return out
}

func linksCreatedAfter(links []terminalengine.Hyperlink, created []uint64, clientRevision uint64) []terminalengine.Hyperlink {
	var out []terminalengine.Hyperlink
	for i, link := range links {
		if i >= len(created) || created[i] > clientRevision {
			out = append(out, link)
		}
	}
	return out
}
