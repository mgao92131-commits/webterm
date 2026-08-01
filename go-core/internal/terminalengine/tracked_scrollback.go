package terminalengine

import (
	"sort"
	"sync"

	headlessterm "github.com/danielgatis/go-headless-term"
)

// ScrollbackEntry 只把权威 LineID 绑定到 HistorySeq；Cells 是 Buffer
// 移交给 scrollback provider 的源行。远程 LineKey/版本由 LineStore 生成。
type ScrollbackEntry struct {
	HistorySeq uint64
	LineID     uint64
	Wrapped    bool
	Cells      []headlessterm.Cell
	bytes      int
}

// ScrollbackTrimEvent 在历史行因容量限制被丢弃时触发。
type ScrollbackTrimEvent struct {
	FirstAvailableSeq uint64
}

// ScrollbackIndexWindow 是供版本索引使用的完整轻量窗口。它只复制位置身份，
// 不复制 Cell 切片；边界与 Entries 在同一次 RLock 下取得。
type ScrollbackIndexWindow struct {
	Generation      uint64
	FirstSeq        uint64
	LastSeq         uint64
	NextSeq         uint64
	MutationVersion uint64
	Entries         []HistoryIndexEntry
}

type HistoryIndexEntry struct {
	HistorySeq uint64
	LineID     uint64
}

// ScrollbackIndexDelta 是 mutationVersion 之间的轻量位置变化。Complete=false
// 表示调用方基线已早于有界 journal，必须读取一次完整 IndexWindow。
type ScrollbackIndexDelta struct {
	Generation      uint64
	FirstSeq        uint64
	LastSeq         uint64
	NextSeq         uint64
	MutationVersion uint64
	Entries         []HistoryIndexEntry
	Complete        bool
}

type scrollbackIndexMutation struct {
	version    uint64
	generation uint64
	hasEntry   bool
	entry      HistoryIndexEntry
}

// HistoryExtent 是同一 layout epoch 内可加载历史的绝对序号窗口。
// 空窗口使用 LastSeq+1==FirstSeq，保留 Clear 后的 trim 水位。
type HistoryExtent struct {
	FirstSeq uint64
	LastSeq  uint64
}

func (e HistoryExtent) Empty() bool {
	return e.FirstSeq > 0 && e.LastSeq+1 == e.FirstSeq
}

type HistoryRangeStatus uint8

const (
	HistoryRangeOK HistoryRangeStatus = iota + 1
	HistoryRangeRetryable
)

type HistoryRangeResult struct {
	Status     HistoryRangeStatus
	Generation uint64
	Extent     HistoryExtent
	Lines      []ScrollbackEntry
}

// TrackedScrollback 是 headless-term 的唯一 scrollback provider。LineID 来自
// 屏幕行并永不在这里改写；firstSeq/nextSeq 保存 HistorySeq 位置轴。
type TrackedScrollback struct {
	mu       sync.RWMutex
	capacity int
	maxBytes int
	bytes    int

	layoutEpoch     uint64
	generation      uint64
	firstSeq        uint64
	nextSeq         uint64
	mutationVersion uint64
	lines           []ScrollbackEntry
	indexJournal    []scrollbackIndexMutation

	onTrim func(ScrollbackTrimEvent)
}

// DefaultScrollbackLineLimit 是单个终端会话的行数安全上限缺省值。
const DefaultScrollbackLineLimit = 10000

// DefaultScrollbackByteLimit 是单个终端会话的近似堆占用预算缺省值。
// 行数与字节上限以先达到者为准。
const DefaultScrollbackByteLimit = 128 << 20

// NewTrackedScrollback 创建可跟踪 scrollback。
// capacity 是行数安全上限（<=0 使用 DefaultScrollbackLineLimit）。
// 默认字节预算与行数上限取先到者驱逐；显式 SetMaxBytes(<=0) 可关闭字节预算。
func NewTrackedScrollback(capacity int, onTrim func(ScrollbackTrimEvent)) *TrackedScrollback {
	if capacity <= 0 {
		capacity = DefaultScrollbackLineLimit
	}
	return &TrackedScrollback{
		capacity:        capacity,
		maxBytes:        DefaultScrollbackByteLimit,
		onTrim:          onTrim,
		firstSeq:        1,
		nextSeq:         1,
		mutationVersion: 1,
		generation:      1,
	}
}

// SetLayoutEpoch records the geometry generation that produced the live grid.
// Ordinary terminal resize does not invalidate scrollback: history line IDs are
// stable for the lifetime of a session and remain pageable across geometry
// changes. Call ResetForReflow only when a future true reflow implementation
// has rebuilt every physical history line.
func (t *TrackedScrollback) SetLayoutEpoch(epoch uint64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.layoutEpoch = epoch
}

// RebaseForLayoutEpoch 保留 scrollback 内容与稳定 LineID，但把新 epoch 内的
// HistorySeq 稠密重编号。headless-term 在增大 rows 时会 Pop 尾部历史；若沿用
// 旧序号，后续 Push 会形成永久空洞并在虚拟列表中产生假空白行。
func (t *TrackedScrollback) RebaseForLayoutEpoch(epoch uint64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.layoutEpoch = epoch
	t.generation++
	for i := range t.lines {
		t.lines[i].HistorySeq = uint64(i + 1)
	}
	t.firstSeq = 1
	t.nextSeq = uint64(len(t.lines)) + 1
	t.mutationVersion++
	t.resetIndexJournalLocked()
	t.recordIndexMutationLocked(HistoryIndexEntry{}, false)
}

// ResetForReflow discards physical history after a real reflow rebuild. It is
// deliberately separate from SetLayoutEpoch so a normal rows/cols resize can
// never erase a user's scrollback.
func (t *TrackedScrollback) ResetForReflow(epoch uint64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.layoutEpoch = epoch
	t.generation++
	t.firstSeq = 1
	t.nextSeq = 1
	t.lines = t.lines[:0]
	t.bytes = 0
	t.mutationVersion++
	t.resetIndexJournalLocked()
	t.recordIndexMutationLocked(HistoryIndexEntry{}, false)
}

// LayoutEpoch 返回当前 layout epoch。
func (t *TrackedScrollback) LayoutEpoch() uint64 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.layoutEpoch
}

func (t *TrackedScrollback) Generation() uint64 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.generation
}

// Push 追加一行到历史。
func (t *TrackedScrollback) Push(line headlessterm.ScrollbackLine) {
	t.mu.Lock()
	defer t.mu.Unlock()

	historyLine := ScrollbackEntry{
		HistorySeq: t.nextSeq,
		LineID:     line.LineID,
		Wrapped:    line.Wrapped,
		Cells:      line.Cells,
		bytes:      estimateScrollbackEntryBytes(line.Cells),
	}
	if historyLine.LineID == 0 {
		// Buffer-created rows always have an ID. Keep the provider defensive for
		// standalone callers/tests that construct a zero-value ScrollbackLine;
		// this is allocation of an invalid/missing identity, never a rewrite of a
		// valid LineID based on history order.
		historyLine.LineID = historyLine.HistorySeq
	}
	// LineID is a logical identity allocated by Buffer. History order is a
	// separate sequence: reverse index, insert/delete and resize may legitimately
	// push existing LineIDs in a non-monotonic order.
	t.nextSeq++
	t.lines = append(t.lines, historyLine)
	t.bytes += historyLine.bytes

	if t.trimToLimitsLocked() {
		t.fireTrimLocked()
	}
	t.mutationVersion++
	t.recordIndexMutationLocked(HistoryIndexEntry{
		HistorySeq: historyLine.HistorySeq,
		LineID:     historyLine.LineID,
	}, true)
}

// Pop 移除并返回最新一行，同时回收尾部 HistorySeq。
func (t *TrackedScrollback) Pop() headlessterm.ScrollbackLine {
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.lines) == 0 {
		return headlessterm.ScrollbackLine{}
	}
	line := t.lines[len(t.lines)-1]
	t.lines = t.lines[:len(t.lines)-1]
	t.bytes -= line.bytes
	// 回收尾部 HistorySeq，避免 resize Pop 后再 Push 留下永久空洞。
	t.nextSeq = line.HistorySeq
	if len(t.lines) > 0 {
		t.firstSeq = t.lines[0].HistorySeq
	} else {
		t.firstSeq = t.nextSeq
	}
	t.mutationVersion++
	t.recordIndexMutationLocked(HistoryIndexEntry{}, false)
	return headlessterm.ScrollbackLine{Cells: line.Cells, Wrapped: line.Wrapped, LineID: line.LineID}
}

// Len 返回当前历史行数。
func (t *TrackedScrollback) Len() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return len(t.lines)
}

// Line 按索引（0=最老）返回历史行。
func (t *TrackedScrollback) Line(index int) headlessterm.ScrollbackLine {
	t.mu.RLock()
	defer t.mu.RUnlock()
	if index < 0 || index >= len(t.lines) {
		return headlessterm.ScrollbackLine{}
	}
	line := t.lines[index]
	return headlessterm.ScrollbackLine{Cells: line.Cells, Wrapped: line.Wrapped, LineID: line.LineID}
}

// LineByID 按稳定逻辑 LineID 返回历史行；历史本身按 HistorySeq 排列，故不能
// 用 ID 二分。
func (t *TrackedScrollback) LineByID(id uint64) (ScrollbackEntry, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	for _, line := range t.lines {
		if line.LineID == id {
			return line, true
		}
	}
	return ScrollbackEntry{}, false
}

// LineByHistorySeq returns the history entry selected by pagination/trim
// cursor. It is distinct from LineByID because LineID is not ordered by time.
func (t *TrackedScrollback) LineByHistorySeq(seq uint64) (ScrollbackEntry, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	index := sort.Search(len(t.lines), func(i int) bool { return t.lines[i].HistorySeq >= seq })
	if index >= len(t.lines) || t.lines[index].HistorySeq != seq {
		return ScrollbackEntry{}, false
	}
	return t.lines[index], true
}

// Extent 原子返回当前可加载窗口；空历史仍保留 first trim 水位。
func (t *TrackedScrollback) Extent() HistoryExtent {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return HistoryExtent{FirstSeq: t.firstSeq, LastSeq: t.lastSeqLocked()}
}

// Range 返回闭区间内仍可用的行。请求前缀已被 trim 时仍返回存活后缀，
// 让客户端一次响应即可填页并停止对已裁剪序号的重试。
func (t *TrackedScrollback) Range(fromSeq, toSeq uint64) HistoryRangeResult {
	t.mu.RLock()
	defer t.mu.RUnlock()
	extent := HistoryExtent{FirstSeq: t.firstSeq, LastSeq: t.lastSeqLocked()}
	result := HistoryRangeResult{
		Status:     HistoryRangeOK,
		Generation: t.generation,
		Extent:     extent,
	}
	if fromSeq < extent.FirstSeq {
		fromSeq = extent.FirstSeq
	}
	if fromSeq > toSeq || len(t.lines) == 0 || fromSeq > extent.LastSeq {
		return result
	}
	if toSeq > extent.LastSeq {
		toSeq = extent.LastSeq
	}
	start := sort.Search(len(t.lines), func(i int) bool {
		return t.lines[i].HistorySeq >= fromSeq
	})
	end := sort.Search(len(t.lines), func(i int) bool {
		return t.lines[i].HistorySeq > toSeq
	})
	result.Lines = make([]ScrollbackEntry, end-start)
	copy(result.Lines, t.lines[start:end])
	return result
}

// IndexWindowIfChanged 在 mutationVersion 变化时返回完整轻量索引；未变化
// 时不分配 Entries。检查版本与复制窗口在同一次 RLock 下完成。
func (t *TrackedScrollback) IndexWindowIfChanged(previousVersion uint64) (ScrollbackIndexWindow, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	w := ScrollbackIndexWindow{
		Generation:      t.generation,
		FirstSeq:        t.firstSeq,
		LastSeq:         t.lastSeqLocked(),
		NextSeq:         t.nextSeq,
		MutationVersion: t.mutationVersion,
	}
	if previousVersion == t.mutationVersion {
		return w, false
	}
	if len(t.lines) == 0 {
		return w, true
	}
	w.Entries = make([]HistoryIndexEntry, len(t.lines))
	for i := range t.lines {
		w.Entries[i] = HistoryIndexEntry{HistorySeq: t.lines[i].HistorySeq, LineID: t.lines[i].LineID}
	}
	return w, true
}

// IndexDeltaIfChanged 返回 previousVersion 之后仍由有界 journal 覆盖的 Push/
// rebind，并总是携带最终 extent。Pop、trim、Clear 不需要逐项记录，调用方按最终
// extent 删除窗口外绑定即可。
func (t *TrackedScrollback) IndexDeltaIfChanged(
	previousVersion, previousGeneration uint64,
) (ScrollbackIndexDelta, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	result := ScrollbackIndexDelta{
		Generation:      t.generation,
		FirstSeq:        t.firstSeq,
		LastSeq:         t.lastSeqLocked(),
		NextSeq:         t.nextSeq,
		MutationVersion: t.mutationVersion,
		Complete:        true,
	}
	if previousVersion == t.mutationVersion && previousGeneration == t.generation {
		return result, false
	}
	if previousVersion == 0 || previousGeneration != t.generation ||
		len(t.indexJournal) == 0 {
		result.Complete = false
		return result, true
	}
	start := sort.Search(len(t.indexJournal), func(i int) bool {
		return t.indexJournal[i].version > previousVersion
	})
	if start >= len(t.indexJournal) ||
		t.indexJournal[start].version != previousVersion+1 {
		result.Complete = false
		return result, true
	}
	for _, mutation := range t.indexJournal[start:] {
		if mutation.generation != t.generation {
			result.Complete = false
			result.Entries = nil
			return result, true
		}
		if mutation.hasEntry {
			result.Entries = append(result.Entries, mutation.entry)
		}
	}
	return result, true
}

// lastSeqLocked 返回当前最新 HistorySeq；历史为空时为 firstSeq-1。
func (t *TrackedScrollback) lastSeqLocked() uint64 {
	if len(t.lines) > 0 {
		return t.lines[len(t.lines)-1].HistorySeq
	}
	return t.firstSeq - 1
}

// FirstSeq 返回最老可用 HistorySeq。
func (t *TrackedScrollback) FirstSeq() uint64 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.firstSeq
}

// NextSeq 返回下一个将分配的 HistorySeq。
func (t *TrackedScrollback) NextSeq() uint64 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.nextSeq
}

// Clear 清空历史。
func (t *TrackedScrollback) Clear() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.lines = t.lines[:0]
	t.bytes = 0
	// Clear 只是裁剪全部历史，不改变 generation，也不对齐或跳跃 nextSeq。
	t.firstSeq = t.nextSeq
	t.mutationVersion++
	t.recordIndexMutationLocked(HistoryIndexEntry{}, false)
	t.fireTrimLocked()
}

// SetMaxLines 调整行数安全上限。
func (t *TrackedScrollback) SetMaxLines(max int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.capacity = max
	if t.trimToLimitsLocked() {
		t.mutationVersion++
		t.recordIndexMutationLocked(HistoryIndexEntry{}, false)
		t.fireTrimLocked()
	}
}

// SetMaxBytes changes the approximate memory budget. A non-positive value
// disables byte trimming while retaining the line cap.
func (t *TrackedScrollback) SetMaxBytes(max int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.maxBytes = max
	if t.trimToLimitsLocked() {
		t.mutationVersion++
		t.recordIndexMutationLocked(HistoryIndexEntry{}, false)
		t.fireTrimLocked()
	}
}

func (t *TrackedScrollback) resetIndexJournalLocked() {
	t.indexJournal = nil
}

func (t *TrackedScrollback) recordIndexMutationLocked(
	entry HistoryIndexEntry, hasEntry bool,
) {
	limit := t.capacity * 2
	if limit < 1024 {
		limit = 1024
	}
	if len(t.indexJournal) >= limit {
		keep := limit / 2
		compacted := make([]scrollbackIndexMutation, keep)
		copy(compacted, t.indexJournal[len(t.indexJournal)-keep:])
		t.indexJournal = compacted
	}
	t.indexJournal = append(t.indexJournal, scrollbackIndexMutation{
		version:    t.mutationVersion,
		generation: t.generation,
		hasEntry:   hasEntry,
		entry:      entry,
	})
}

// Bytes returns the current approximate memory footprint of stored history.
func (t *TrackedScrollback) Bytes() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.bytes
}

// MaxLines 返回容量。
func (t *TrackedScrollback) MaxLines() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.capacity
}

// trimToLimitsLocked 从最旧端驱逐，直到行数与字节两个上限都不再超限。
// 两个上限以先达到者为准；超字节时至少保留最新一行（见 overBytes 条件）。
func (t *TrackedScrollback) trimToLimitsLocked() bool {
	trimmed := 0
	for trimmed < len(t.lines) {
		overLines := t.capacity > 0 && len(t.lines)-trimmed > t.capacity
		// Keep the newest line even if it individually exceeds the budget; an
		// empty scrollback is less useful and the next push will evict it.
		overBytes := t.maxBytes > 0 && t.bytes > t.maxBytes && len(t.lines)-trimmed > 1
		if !overLines && !overBytes {
			break
		}
		t.bytes -= t.lines[trimmed].bytes
		trimmed++
	}
	if trimmed == 0 {
		return false
	}
	t.lines = t.lines[trimmed:]
	if len(t.lines) > 0 {
		t.firstSeq = t.lines[0].HistorySeq
	} else {
		t.firstSeq = t.nextSeq
	}
	return true
}

// estimateScrollbackEntryBytes 返回一行的近似堆占用。这是容量记账预算而非精确的
// Go 堆内省：只要求不明显低估，允许与真实占用有 ±15% 左右的偏差。
//
// 常量依据 BenchmarkScrollbackEntryMemory（go 1.25.1, darwin/arm64）的实测
// （80/200 列 × 纯 ASCII/宽字符/逐 cell 样式样本，1024 行驻留堆）：
//
//	样本                  实测 B/行   本函数估算   偏差
//	80col-plain-ascii       8560      8544        -0.2%
//	200col-plain-ascii     19264     21264       +10%
//	80col-wide-cjk          8848      8864        +0.2%
//	200col-wide-cjk        20048     22064       +10%
//	80col-rich-styled      10112      8544        -15%
//	200col-rich-styled     23264     21264        -9%
//
// 组成：headlessterm.Cell 结构体 88B（unsafe.Sizeof）+ 每 cell 字符串数据
// 最小 size class 8B + 颜色/样式对象摊销 8B = 每 cell 104B；len(Char)*2 覆盖
// 多字节字符簇的字符串数据；基线 64B 覆盖 ScrollbackEntry 结构体（48B）与
// cells 切片的分配/size-class 取整开销。逐 cell 独立颜色的极端样式输出
// （每 cell 两个 8B 颜色对象）仍可能低估约 15%，可接受。
func estimateScrollbackEntryBytes(cells []headlessterm.Cell) int {
	bytes := 64
	for _, cell := range cells {
		bytes += 104 + len(cell.Char)*2
	}
	return bytes
}

func (t *TrackedScrollback) fireTrimLocked() {
	if t.onTrim != nil {
		t.onTrim(ScrollbackTrimEvent{FirstAvailableSeq: t.firstSeq})
	}
}
