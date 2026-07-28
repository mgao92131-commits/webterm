package screenprojection

import "webterm/go-core/internal/terminalengine"

// HistoryChange 绑定仍驻留在权威 scrollback 中的 LineID 与其首次进入可导出
// 投影的 revision。Cell 不保存在索引里，恢复时始终从 scrollback 读取。
type HistoryChange struct {
	HistorySeq      uint64
	LineID          uint64
	LineVersion     uint64
	CreatedRevision uint64
}

// HistoryChangeIndex 是有界历史版本索引。Changes 与权威 scrollback 同步 trim；
// GapRevision 记录最近一次不能用 HistoryPush + final extent 表达的 ID 缺口。
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
	w := scrollback.IndexWindow()
	gap := false
	if h.firstSeq != 0 && w.FirstSeq > h.firstSeq {
		h.WatermarkChangedRevision = revision
	}

	old := make(map[uint64]HistoryChange, len(h.Changes))
	for _, change := range h.Changes {
		old[change.HistorySeq] = change
	}
	next := make([]HistoryChange, 0, len(w.Entries))
	last := uint64(0)
	for _, entry := range w.Entries {
		if entry.HistorySeq < w.FirstSeq || entry.HistorySeq > w.LastSeq ||
			(last != 0 && entry.HistorySeq <= last) {
			gap = true
			continue
		}
		change := HistoryChange{
			HistorySeq: entry.HistorySeq, LineID: entry.LineID,
			LineVersion: entry.LineVersion, CreatedRevision: revision,
		}
		if previous, ok := old[entry.HistorySeq]; ok &&
			previous.LineID == entry.LineID && previous.LineVersion == entry.LineVersion {
			change.CreatedRevision = previous.CreatedRevision
		}
		next = append(next, change)
		last = entry.HistorySeq
	}
	h.Changes = next

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
