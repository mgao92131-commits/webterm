package screenprojection

import (
	"sort"

	"webterm/go-core/internal/terminalengine"
)

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
	mutationVersion          uint64
	generation               uint64
	lineageView              *terminalengine.HistoryLineageView
	mutationHead             *terminalengine.HistoryMutationBatch
	mutationBatchCount       int
}

const maxHistoryMutationBatches = 1024

// sync 在 Projector 的导出提交点同步一次历史索引。返回 true 表示本次发现了
// 必须推进 snapshot barrier 的结构缺口。普通从最旧端 trim 不算缺口：恢复
// Patch 通过 first_available_history_seq 原子推进水位即可。
func (h *HistoryChangeIndex) sync(scrollback *terminalengine.TrackedScrollback, revision uint64) bool {
	if scrollback == nil {
		return false
	}
	delta, deltaChanged := scrollback.IndexDeltaIfChanged(
		h.mutationVersion, h.generation)
	if !deltaChanged {
		return false
	}
	if delta.Complete && h.generation != 0 && delta.Generation == h.generation {
		if gap, applied := h.applyDelta(delta, revision); applied {
			return gap
		}
	}
	oldFirstSeq := h.firstSeq
	oldMutationVersion := h.mutationVersion
	w, changed := scrollback.IndexWindowIfChanged(h.mutationVersion)
	generationChanged := h.generation != 0 && w.Generation != h.generation
	if generationChanged && !changed {
		w, changed = scrollback.IndexWindowIfChanged(0)
	}
	if generationChanged {
		h.Changes = nil
		h.lineageView = nil
		h.GapRevision = 0
		h.WatermarkChangedRevision = 0
		h.firstSeq = 0
		h.lastSeq = 0
		h.nextSeq = 0
		h.mutationVersion = 0
		h.mutationHead = nil
		h.mutationBatchCount = 0
	}
	h.generation = w.Generation
	h.firstSeq, h.lastSeq, h.nextSeq = w.FirstSeq, w.LastSeq, w.NextSeq
	if !changed && !generationChanged {
		return false
	}
	gap := false
	if !generationChanged && oldFirstSeq != 0 && w.FirstSeq > oldFirstSeq {
		h.WatermarkChangedRevision = revision
	}

	old := make(map[uint64]HistoryChange, len(h.Changes))
	for _, change := range h.Changes {
		old[change.HistorySeq] = change
	}
	next := make([]HistoryChange, 0, len(w.Entries))
	pushes := make([]terminalengine.HistoryPush, 0)
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
		} else {
			pushes = append(pushes, terminalengine.HistoryPush{
				HistorySeq:  entry.HistorySeq,
				LineID:      entry.LineID,
				LineVersion: entry.LineVersion,
			})
		}
		next = append(next, change)
		last = entry.HistorySeq
	}
	h.Changes = next
	lineage := make([]terminalengine.HistoryPush, len(next))
	for i, change := range next {
		lineage[i] = terminalengine.HistoryPush{
			HistorySeq: change.HistorySeq, LineID: change.LineID,
			LineVersion: change.LineVersion,
		}
	}
	h.lineageView = terminalengine.NewHistoryLineageView(
		w.FirstSeq, w.LastSeq, lineage)

	// 防御性核对：当前驻留窗口非空却没有覆盖到尾部，说明一次 flush 跨过了
	// 未捕获的 LineID，禁止静默少发。
	if w.LastSeq >= w.FirstSeq && (len(h.Changes) == 0 || h.Changes[len(h.Changes)-1].HistorySeq != w.LastSeq) {
		gap = true
	}
	if gap {
		h.GapRevision = revision
	}
	h.mutationVersion = w.MutationVersion
	h.appendMutationBatch(oldMutationVersion, w.MutationVersion, pushes)
	return gap
}

func (h *HistoryChangeIndex) applyDelta(
	delta terminalengine.ScrollbackIndexDelta, revision uint64,
) (gap bool, applied bool) {
	oldFirstSeq := h.firstSeq
	var next []HistoryChange
	if delta.LastSeq+1 == delta.FirstSeq {
		next = nil
	} else {
		start := sort.Search(len(h.Changes), func(i int) bool {
			return h.Changes[i].HistorySeq >= delta.FirstSeq
		})
		end := sort.Search(len(h.Changes), func(i int) bool {
			return h.Changes[i].HistorySeq > delta.LastSeq
		})
		next = h.Changes[start:end]
	}
	expected := uint64(0)
	if delta.LastSeq+1 != delta.FirstSeq {
		expected = delta.LastSeq - delta.FirstSeq + 1
	}
	if uint64(len(next)) > expected {
		return false, false
	}
	missingCount := expected - uint64(len(next))
	var missing map[uint64]struct{}
	for _, entry := range delta.Entries {
		if entry.LineID == 0 || entry.LineVersion == 0 {
			return false, false
		}
		if entry.HistorySeq < delta.FirstSeq || entry.HistorySeq > delta.LastSeq {
			continue
		}
		index := sort.Search(len(next), func(i int) bool {
			return next[i].HistorySeq >= entry.HistorySeq
		})
		if index >= len(next) || next[index].HistorySeq != entry.HistorySeq {
			if missing == nil {
				missing = make(map[uint64]struct{}, missingCount)
			}
			missing[entry.HistorySeq] = struct{}{}
		}
	}
	if uint64(len(missing)) != missingCount {
		return false, false
	}
	for _, entry := range delta.Entries {
		if entry.HistorySeq < delta.FirstSeq || entry.HistorySeq > delta.LastSeq ||
			entry.LineID == 0 || entry.LineVersion == 0 {
			continue
		}
		index := sort.Search(len(next), func(i int) bool {
			return next[i].HistorySeq >= entry.HistorySeq
		})
		change := HistoryChange{
			HistorySeq:      entry.HistorySeq,
			LineID:          entry.LineID,
			LineVersion:     entry.LineVersion,
			CreatedRevision: revision,
		}
		if index < len(next) && next[index].HistorySeq == entry.HistorySeq {
			if next[index].LineID == entry.LineID &&
				next[index].LineVersion == entry.LineVersion {
				change.CreatedRevision = next[index].CreatedRevision
			}
			next[index] = change
			continue
		}
		next = append(next, HistoryChange{})
		copy(next[index+1:], next[index:])
		next[index] = change
	}
	h.Changes = next
	h.firstSeq = delta.FirstSeq
	h.lastSeq = delta.LastSeq
	h.nextSeq = delta.NextSeq
	h.generation = delta.Generation
	if oldFirstSeq != 0 && delta.FirstSeq > oldFirstSeq {
		h.WatermarkChangedRevision = revision
	}
	oldMutationVersion := h.mutationVersion
	h.mutationVersion = delta.MutationVersion
	pushes := make([]terminalengine.HistoryPush, 0, len(delta.Entries))
	for _, entry := range delta.Entries {
		if entry.HistorySeq >= delta.FirstSeq && entry.HistorySeq <= delta.LastSeq {
			pushes = append(pushes, terminalengine.HistoryPush{
				HistorySeq:  entry.HistorySeq,
				LineID:      entry.LineID,
				LineVersion: entry.LineVersion,
			})
		}
	}
	if h.lineageView == nil {
		h.lineageView = terminalengine.NewHistoryLineageView(
			delta.FirstSeq, delta.LastSeq, pushes)
	} else {
		h.lineageView = h.lineageView.WithChanges(
			delta.FirstSeq, delta.LastSeq, pushes)
	}
	h.appendMutationBatch(oldMutationVersion, delta.MutationVersion, pushes)
	return false, true
}

func (h *HistoryChangeIndex) appendMutationBatch(
	baseVersion, version uint64, pushes []terminalengine.HistoryPush,
) {
	if version == 0 || version == baseVersion {
		return
	}
	previous := h.mutationHead
	if h.mutationBatchCount >= maxHistoryMutationBatches {
		// 有界链被截断后，仍持有更老 baseline 的客户端会检测到覆盖缺口并回退
		// snapshot；新 baseline 从当前 version 继续使用后续节点。
		previous = nil
		h.mutationBatchCount = 0
	}
	copied := append([]terminalengine.HistoryPush(nil), pushes...)
	h.mutationHead = &terminalengine.HistoryMutationBatch{
		BaseVersion: baseVersion,
		Version:     version,
		Pushes:      copied,
		Previous:    previous,
	}
	h.mutationBatchCount++
}
