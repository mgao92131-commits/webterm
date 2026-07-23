package screenprojection

import "webterm/go-core/internal/terminalengine"

// HistoryChange 绑定仍驻留在权威 scrollback 中的 LineID 与其首次进入可导出
// 投影的 revision。Cell 不保存在索引里，恢复时始终从 scrollback 读取。
type HistoryChange struct {
	HistorySeq      uint64
	LineID          uint64
	CreatedRevision uint64
}

// HistoryChangeIndex 是有界历史版本索引。Changes 与权威 scrollback 同步 trim；
// GapRevision 记录最近一次不能用 append 表达的尾部回退/ID 缺口。
type HistoryChangeIndex struct {
	Changes                  []HistoryChange
	GapRevision              uint64
	WatermarkChangedRevision uint64
	firstSeq                 uint64
	lastSeq                  uint64
	nextSeq                  uint64
}

// sync 在 Projector 的导出提交点同步一次历史索引。返回 true 表示本次发现了
// 必须推进 snapshot barrier 的结构缺口。普通从最旧端 trim 不算缺口：恢复
// Patch 通过 first_available_history_seq 原子推进水位即可。
func (h *HistoryChangeIndex) sync(scrollback *terminalengine.TrackedScrollback, revision uint64) bool {
	if scrollback == nil {
		return false
	}
	w := scrollback.IndexAfter(h.lastSeq)
	gap := false
	if h.firstSeq != 0 && w.FirstSeq > h.firstSeq {
		h.WatermarkChangedRevision = revision
	}

	if h.nextSeq != 0 && w.NextSeq < h.nextSeq {
		// Clear/ResetForReflow 重置了 LineID 空间。
		h.Changes = nil
		gap = true
	}
	if h.lastSeq != 0 && w.LastSeq < h.lastSeq {
		// Pop 从尾部删除历史，append+前端水位无法表达尾删。
		gap = true
		for len(h.Changes) > 0 && h.Changes[len(h.Changes)-1].HistorySeq > w.LastSeq {
			h.Changes = h.Changes[:len(h.Changes)-1]
		}
	}

	// 实际 trim 事件是唯一水位锚点；同步删除已不再权威窗口中的索引项。
	cut := 0
	for cut < len(h.Changes) && h.Changes[cut].HistorySeq < w.FirstSeq {
		cut++
	}
	if cut > 0 {
		copy(h.Changes, h.Changes[cut:])
		h.Changes = h.Changes[:len(h.Changes)-cut]
	}

	last := uint64(0)
	if len(h.Changes) > 0 {
		last = h.Changes[len(h.Changes)-1].HistorySeq
	}
	for _, entry := range w.Entries {
		if entry.HistorySeq < w.FirstSeq || entry.HistorySeq > w.LastSeq || (last != 0 && entry.HistorySeq <= last) {
			gap = true
		}
		if last == 0 || entry.HistorySeq > last {
			h.Changes = append(h.Changes, HistoryChange{HistorySeq: entry.HistorySeq, LineID: entry.LineID, CreatedRevision: revision})
			last = entry.HistorySeq
		}
	}

	// 防御性核对：当前驻留窗口非空却没有覆盖到尾部，说明一次 flush 跨过了
	// 未捕获的 LineID，禁止静默少发。
	if w.LastSeq >= w.FirstSeq && (len(h.Changes) == 0 || h.Changes[len(h.Changes)-1].HistorySeq != w.LastSeq) {
		gap = true
	}
	if gap {
		h.GapRevision = revision
	}
	h.firstSeq, h.lastSeq, h.nextSeq = w.FirstSeq, w.LastSeq, w.NextSeq
	return gap
}

// HistoryView 提供分页历史查询。
type HistoryView struct {
	scrollback *terminalengine.TrackedScrollback
}

// NewHistoryView 创建历史视图。
func NewHistoryView(scrollback *terminalengine.TrackedScrollback) *HistoryView {
	return &HistoryView{scrollback: scrollback}
}

// Page 返回严格小于 beforeSeq 的最多 limit 行，按 ID 升序。
func (v *HistoryView) Page(beforeSeq uint64, limit int) terminalengine.HistoryWindow {
	exp := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	return v.pageWithExporter(beforeSeq, limit, exp)
}

func (v *HistoryView) pageWithExporter(beforeSeq uint64, limit int, exp *exporter) terminalengine.HistoryWindow {
	if limit <= 0 {
		limit = 250
	}
	if limit > 500 {
		limit = 500
	}

	firstAvailable := v.scrollback.FirstSeq()
	if beforeSeq <= firstAvailable {
		return terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: firstAvailable,
			FirstIncludedHistorySeq:  firstAvailable,
			LastIncludedHistorySeq:   firstAvailable - 1,
			HasMoreBefore:            false,
			Lines:                    nil,
		}
	}

	lines := v.scrollback.PageBefore(beforeSeq, limit)
	if len(lines) == 0 {
		return terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: firstAvailable,
			FirstIncludedHistorySeq:  beforeSeq,
			LastIncludedHistorySeq:   beforeSeq - 1,
			HasMoreBefore:            false,
			Lines:                    nil,
		}
	}

	exported := make([]terminalengine.Line, len(lines))
	for i, hl := range lines {
		exported[i] = exp.exportHistoryLine(hl)
	}

	return terminalengine.HistoryWindow{
		FirstAvailableHistorySeq: firstAvailable,
		FirstIncludedHistorySeq:  exported[0].HistorySeq,
		LastIncludedHistorySeq:   exported[len(exported)-1].HistorySeq,
		HasMoreBefore:            exported[0].HistorySeq > firstAvailable,
		Lines:                    exported,
	}
}
