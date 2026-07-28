package terminalengine

import (
	"sort"
	"sync"

	headlessterm "github.com/danielgatis/go-headless-term"

	"webterm/go-core/internal/historysegment"
)

// ScrollbackEntry 只把权威 LineID 绑定到 HistorySeq；Cells 是 Buffer
// 移交给 scrollback provider 的同一份不可变正文，不创建第二种历史行正文。
type ScrollbackEntry struct {
	HistorySeq uint64
	LineID     uint64
	Version    uint64
	Wrapped    bool
	Cells      []headlessterm.Cell
	bytes      int
}

// ScrollbackTrimEvent 在历史行因容量限制被丢弃时触发。
type ScrollbackTrimEvent struct {
	FirstAvailableSeq uint64
}

// ScrollbackWindow 是 TrackedScrollback 一次原子读到的连续行窗口及其边界。
// Lines 中 ScrollbackEntry 的 Cells 与 scrollback 内部共享且不可变；切片本身
// 是新分配的位置条目副本，可安全在锁外使用。
type ScrollbackWindow struct {
	FirstSeq uint64            // 当前最老可用 HistorySeq
	LastSeq  uint64            // 当前最新 HistorySeq；历史为空时为 FirstSeq-1
	Lines    []ScrollbackEntry // 窗口内的行，按 HistorySeq 升序
}

// ScrollbackIndexWindow 是供版本索引使用的轻量窗口。它只复制 LineID，
// 不复制 Cell 切片；FirstSeq/LastSeq/NextSeq 与 LineIDs 在同一次 RLock 下取得。
type ScrollbackIndexWindow struct {
	FirstSeq uint64
	LastSeq  uint64
	NextSeq  uint64
	Entries  []HistoryIndexEntry
	// LineIDs is retained for internal tests/diagnostics; ordering decisions
	// must use Entries[].HistorySeq.
	LineIDs []uint64
}

type HistoryIndexEntry struct {
	HistorySeq  uint64
	LineID      uint64
	LineVersion uint64
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
	HistoryRangeTrimmed
	HistoryRangeRetryable
)

type HistoryRangeResult struct {
	Status HistoryRangeStatus
	Extent HistoryExtent
	Lines  []ScrollbackEntry
}

// TrackedScrollback 是 headless-term 的唯一 scrollback provider。LineID 来自
// 屏幕行并永不在这里改写；firstSeq/nextSeq 仅保存严格递增的 HistorySeq。
//
// 满 SEGMENT_SIZE 行的完整分段会封存进 SegmentStore；封存后同一
// (generation, segmentNumber) 正文永不变化。Pop 若回退进已封存区，会删除
// 对应段并回退 sealedThroughSeq。
type TrackedScrollback struct {
	mu       sync.RWMutex
	capacity int
	maxBytes int
	bytes    int

	layoutEpoch uint64
	generation  uint64
	firstSeq    uint64
	nextSeq     uint64
	lines       []ScrollbackEntry

	// sealedThroughSeq 是已封存的最大 HistorySeq；0 表示尚无封存段。
	sealedThroughSeq uint64
	segments         historysegment.Store

	onTrim func(ScrollbackTrimEvent)
}

// DefaultScrollbackLineLimit 是行数安全上限的缺省值。
// 必须为 historysegment.Size（128）的整数倍，使 trim 边界落在整段边界上，
// 避免首段与 Catalog.trimBeforeSeq 部分相交。
const DefaultScrollbackLineLimit = 20096 // 128 * 157

// DefaultScrollbackByteLimit 是字节预算缺省值；0 表示不按字节驱逐，
// 仅受行数上限约束。显式 SetMaxBytes(>0) 仍可启用字节预算。
const DefaultScrollbackByteLimit = 0

// NewTrackedScrollback 创建可跟踪 scrollback。
// capacity 是行数安全上限（<=0 使用 DefaultScrollbackLineLimit）。
// 默认不启用字节预算；仅当 SetMaxBytes(>0) 时才与行数上限取先到者驱逐。
func NewTrackedScrollback(capacity int, onTrim func(ScrollbackTrimEvent)) *TrackedScrollback {
	if capacity <= 0 {
		capacity = DefaultScrollbackLineLimit
	}
	return &TrackedScrollback{
		capacity:   capacity,
		maxBytes:   DefaultScrollbackByteLimit,
		onTrim:     onTrim,
		firstSeq:   1,
		nextSeq:    1,
		generation: 1,
	}
}

// AttachSegmentStore 安装不可变分段存储。应在产生历史输出前调用；可传 nil 卸载。
func (t *TrackedScrollback) AttachSegmentStore(store historysegment.Store) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.segments = store
	t.resealAllLocked()
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
	oldGeneration := t.generation
	t.layoutEpoch = epoch
	t.generation++
	for i := range t.lines {
		t.lines[i].HistorySeq = uint64(i + 1)
	}
	t.firstSeq = 1
	t.nextSeq = uint64(len(t.lines)) + 1
	t.clearSegmentsLocked(oldGeneration)
	t.resealAllLocked()
}

// ResetForReflow discards physical history after a real reflow rebuild. It is
// deliberately separate from SetLayoutEpoch so a normal rows/cols resize can
// never erase a user's scrollback.
func (t *TrackedScrollback) ResetForReflow(epoch uint64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	oldGeneration := t.generation
	t.layoutEpoch = epoch
	t.generation++
	t.firstSeq = 1
	t.nextSeq = 1
	t.lines = t.lines[:0]
	t.bytes = 0
	t.clearSegmentsLocked(oldGeneration)
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

// SealedThroughSeq 返回已封存的最大 HistorySeq；尚无封存时为 0。
func (t *TrackedScrollback) SealedThroughSeq() uint64 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.sealedThroughSeq
}

// HistoryCatalog 返回 WS 目录快照：trim/seal/tail 水位与 generation。
func (t *TrackedScrollback) HistoryCatalog() historysegment.Catalog {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return historysegment.Catalog{
		Generation:       t.generation,
		TrimBeforeSeq:    t.firstSeq,
		SealedThroughSeq: t.sealedThroughSeq,
		TailLastSeq:      t.lastSeqLocked(),
	}
}

// SegmentStore 返回当前安装的分段存储（可 nil）。
func (t *TrackedScrollback) SegmentStore() historysegment.Store {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.segments
}

// Push 追加一行到历史。
func (t *TrackedScrollback) Push(line headlessterm.ScrollbackLine) {
	t.mu.Lock()
	defer t.mu.Unlock()

	historyLine := ScrollbackEntry{
		HistorySeq: t.nextSeq,
		LineID:     line.LineID,
		Wrapped:    line.Wrapped,
		Version:    line.LineVersion,
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
	t.sealCompletedSegmentsLocked()

	if t.trimToLimitsLocked() {
		t.fireTrimLocked()
	}
}

// Pop 移除并返回最新一行。若回退进已封存区，删除对应不可变段并回退水位。
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
	t.unsealFromLocked(line.HistorySeq)
	return headlessterm.ScrollbackLine{Cells: line.Cells, Wrapped: line.Wrapped, LineID: line.LineID, LineVersion: line.Version}
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
	return headlessterm.ScrollbackLine{Cells: line.Cells, Wrapped: line.Wrapped, LineID: line.LineID, LineVersion: line.Version}
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
	result := HistoryRangeResult{Status: HistoryRangeOK, Extent: extent}
	if fromSeq < extent.FirstSeq {
		result.Status = HistoryRangeTrimmed
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

// PageBefore returns rows strictly before the HistorySeq cursor, in entrance order.
func (t *TrackedScrollback) PageBefore(beforeSeq uint64, limit int) []ScrollbackEntry {
	t.mu.RLock()
	defer t.mu.RUnlock()
	if limit <= 0 || len(t.lines) == 0 || beforeSeq <= t.firstSeq {
		return nil
	}
	end := len(t.lines)
	if beforeSeq < t.nextSeq {
		end = sort.Search(len(t.lines), func(i int) bool { return t.lines[i].HistorySeq >= beforeSeq })
	}
	start := end - limit
	if start < 0 {
		start = 0
	}
	result := make([]ScrollbackEntry, end-start)
	copy(result, t.lines[start:end])
	return result
}

// LinesAfter returns entries strictly after a HistorySeq cursor.
// （不含 lastSeq）及当前窗口边界。行超过 limit 时保留最新段。
// 调用方用返回的 FirstSeq 判断连续性：FirstSeq > lastSeq+1 表示
// lastSeq 之后的部分行已被驱逐。limit<=0 时只返回边界。
func (t *TrackedScrollback) LinesAfter(lastSeq uint64, limit int) ScrollbackWindow {
	t.mu.RLock()
	defer t.mu.RUnlock()
	w := ScrollbackWindow{FirstSeq: t.firstSeq, LastSeq: t.lastSeqLocked()}
	if limit <= 0 || len(t.lines) == 0 || lastSeq >= w.LastSeq {
		return w
	}
	start := 0
	if lastSeq >= t.firstSeq {
		start = sort.Search(len(t.lines), func(i int) bool { return t.lines[i].HistorySeq > lastSeq })
	}
	if len(t.lines)-start > limit {
		start = len(t.lines) - limit
	}
	w.Lines = make([]ScrollbackEntry, len(t.lines)-start)
	copy(w.Lines, t.lines[start:])
	return w
}

// Window 一次 RLock 返回最新至多 limit 行的尾部窗口及当前边界，用于
// 全量/重建路径。limit<=0 时只返回边界。
func (t *TrackedScrollback) Window(limit int) ScrollbackWindow {
	t.mu.RLock()
	defer t.mu.RUnlock()
	w := ScrollbackWindow{FirstSeq: t.firstSeq, LastSeq: t.lastSeqLocked()}
	if limit <= 0 || len(t.lines) == 0 {
		return w
	}
	start := 0
	if len(t.lines) > limit {
		start = len(t.lines) - limit
	}
	w.Lines = make([]ScrollbackEntry, len(t.lines)-start)
	copy(w.Lines, t.lines[start:])
	return w
}

// IndexAfter 返回 HistorySeq 严格大于 lastSeq 的所有当前驻留行 LineID 以及原子边界。
// 该接口只供 HistoryChangeIndex 的增量同步使用；返回量最多等于实际驻留行数，
// 且不会复制历史 Cell。
func (t *TrackedScrollback) IndexAfter(lastSeq uint64) ScrollbackIndexWindow {
	t.mu.RLock()
	defer t.mu.RUnlock()
	w := ScrollbackIndexWindow{
		FirstSeq: t.firstSeq,
		LastSeq:  t.lastSeqLocked(),
		NextSeq:  t.nextSeq,
	}
	if len(t.lines) == 0 || lastSeq >= w.LastSeq {
		return w
	}
	start := 0
	if lastSeq >= t.firstSeq {
		start = sort.Search(len(t.lines), func(i int) bool { return t.lines[i].HistorySeq > lastSeq })
	}
	w.Entries = make([]HistoryIndexEntry, len(t.lines)-start)
	w.LineIDs = make([]uint64, len(t.lines)-start)
	for i := start; i < len(t.lines); i++ {
		w.Entries[i-start] = HistoryIndexEntry{HistorySeq: t.lines[i].HistorySeq, LineID: t.lines[i].LineID, LineVersion: t.lines[i].Version}
		w.LineIDs[i-start] = t.lines[i].LineID
	}
	return w
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
	oldGeneration := t.generation
	t.generation++
	t.lines = t.lines[:0]
	t.bytes = 0
	// clear 是同一 layout epoch 内的历史裁剪，不是 LineID 空间重建。
	// nextSeq 仍单调递增；同时必须对齐到完整 Segment 起点，否则新 generation
	// 的首段不完整、封存会跳过，但 Catalog 仍声明该区间可加载，客户端请求
	// 会得到 NOT_FOUND 并永久跳过，形成黑屏历史空洞。
	t.nextSeq = historysegment.AlignToSegmentStart(t.nextSeq)
	t.firstSeq = t.nextSeq
	t.clearSegmentsLocked(oldGeneration)
	t.fireTrimLocked()
}

// SetMaxLines 调整行数安全上限。
func (t *TrackedScrollback) SetMaxLines(max int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.capacity = max
	if t.trimToLimitsLocked() {
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
		t.fireTrimLocked()
	}
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
	t.trimSegmentsLocked()
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

// sealCompletedSegmentsLocked 把 scrollback 中已完整且尚未封存的段写入 Store。
func (t *TrackedScrollback) sealCompletedSegmentsLocked() {
	lastSeq := t.lastSeqLocked()
	if len(t.lines) == 0 || lastSeq < t.firstSeq {
		return
	}
	var nextNumber uint64
	if t.sealedThroughSeq == 0 {
		nextNumber = historysegment.NumberForSeq(t.firstSeq)
		first, _ := historysegment.SeqRange(nextNumber)
		if t.firstSeq > first {
			// 首部段不完整（已被裁剪前缀），跳过到下一段。
			nextNumber++
		}
	} else {
		nextNumber = historysegment.NumberForSeq(t.sealedThroughSeq) + 1
	}
	for {
		first, last := historysegment.SeqRange(nextNumber)
		if last > lastSeq {
			return
		}
		if first < t.firstSeq {
			nextNumber++
			continue
		}
		if !t.sealOneLocked(nextNumber, first, last) {
			return
		}
		nextNumber++
	}
}

func (t *TrackedScrollback) sealOneLocked(number, first, last uint64) bool {
	start := sort.Search(len(t.lines), func(i int) bool {
		return t.lines[i].HistorySeq >= first
	})
	end := sort.Search(len(t.lines), func(i int) bool {
		return t.lines[i].HistorySeq > last
	})
	if end-start != historysegment.Size {
		return false
	}
	if t.lines[start].HistorySeq != first || t.lines[end-1].HistorySeq != last {
		return false
	}
	copied := make([]historysegment.Line, historysegment.Size)
	for i := 0; i < historysegment.Size; i++ {
		src := t.lines[start+i]
		copied[i] = historysegment.Line{
			HistorySeq: src.HistorySeq,
			LineID:     src.LineID,
			Version:    src.Version,
			Wrapped:    src.Wrapped,
			Cells:      src.Cells,
		}
	}
	seg, ok := historysegment.NewSegment(t.generation, number, copied)
	if !ok {
		return false
	}
	if t.segments != nil {
		t.segments.Put(seg)
	}
	t.sealedThroughSeq = last
	return true
}

// resealAllLocked 在 Attach/Rebase 后按当前 generation 重建封存水位。
func (t *TrackedScrollback) resealAllLocked() {
	t.sealedThroughSeq = 0
	if t.segments != nil {
		t.segments.DeleteGeneration(t.generation)
	}
	t.sealCompletedSegmentsLocked()
}

func (t *TrackedScrollback) clearSegmentsLocked(oldGeneration uint64) {
	t.sealedThroughSeq = 0
	if t.segments == nil {
		return
	}
	t.segments.DeleteGeneration(oldGeneration)
	if t.generation != oldGeneration {
		t.segments.DeleteGeneration(t.generation)
	}
}

// unsealFromLocked 在 Pop 回退到 seq 时删除该 seq 所在段及之后的封存段。
func (t *TrackedScrollback) unsealFromLocked(seq uint64) {
	if t.sealedThroughSeq == 0 || seq > t.sealedThroughSeq {
		return
	}
	number := historysegment.NumberForSeq(seq)
	first, _ := historysegment.SeqRange(number)
	if t.segments != nil {
		t.segments.DeleteFrom(t.generation, number)
	}
	if first <= 1 {
		t.sealedThroughSeq = 0
	} else {
		t.sealedThroughSeq = first - 1
	}
}

func (t *TrackedScrollback) trimSegmentsLocked() {
	if t.segments != nil {
		t.segments.DeleteBefore(t.generation, t.firstSeq)
	}
}
