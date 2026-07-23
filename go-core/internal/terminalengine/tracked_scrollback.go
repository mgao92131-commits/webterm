package terminalengine

import (
	"sort"
	"sync"

	headlessterm "github.com/danielgatis/go-headless-term"
)

// HistoryLine 是带稳定 LineID 和独立 HistorySeq 的历史行，由 TrackedScrollback 维护。
type HistoryLine struct {
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
// Lines 中 HistoryLine 的 Cells 与 scrollback 内部共享且不可变（Push 时已
// 拷贝，推出后不再修改）；切片本身是新分配的副本，可安全在锁外使用。
type ScrollbackWindow struct {
	FirstSeq uint64        // 当前最老可用 HistorySeq
	LastSeq  uint64        // 当前最新 HistorySeq；历史为空时为 FirstSeq-1
	Lines    []HistoryLine // 窗口内的行，按 HistorySeq 升序
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
	HistorySeq uint64
	LineID     uint64
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
	Lines  []HistoryLine
}

// TrackedScrollback 是 headless-term 的唯一 scrollback provider。LineID 来自
// 屏幕行并永不在这里改写；firstSeq/nextSeq 仅保存严格递增的 HistorySeq。
type TrackedScrollback struct {
	mu       sync.RWMutex
	capacity int
	maxBytes int
	bytes    int

	layoutEpoch uint64
	firstSeq    uint64
	nextSeq     uint64
	lines       []HistoryLine

	onTrim func(ScrollbackTrimEvent)
}

// DefaultScrollbackLineLimit 是行数安全上限的缺省值。它是上限而非保留承诺：
// 字节预算（DefaultScrollbackByteLimit）可能先触发驱逐，实际保留行数可以远低于它。
const DefaultScrollbackLineLimit = 10000

// DefaultScrollbackByteLimit bounds memory independently of line count. Wide
// terminals and richly styled output can make 10k physical rows much larger
// than a line-only capacity suggests.
const DefaultScrollbackByteLimit = 8 << 20

// NewTrackedScrollback 创建可跟踪 scrollback。
// capacity 是行数安全上限（<=0 使用 DefaultScrollbackLineLimit），字节预算
// 默认 DefaultScrollbackByteLimit；两个上限以先达到者为准从最旧端驱逐，
// 因此不承诺保留任何固定行数。
func NewTrackedScrollback(capacity int, onTrim func(ScrollbackTrimEvent)) *TrackedScrollback {
	if capacity <= 0 {
		capacity = DefaultScrollbackLineLimit
	}
	return &TrackedScrollback{
		capacity: capacity,
		maxBytes: DefaultScrollbackByteLimit,
		onTrim:   onTrim,
		firstSeq: 1,
		nextSeq:  1,
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
	for i := range t.lines {
		t.lines[i].HistorySeq = uint64(i + 1)
	}
	t.firstSeq = 1
	t.nextSeq = uint64(len(t.lines)) + 1
}

// ResetForReflow discards physical history after a real reflow rebuild. It is
// deliberately separate from SetLayoutEpoch so a normal rows/cols resize can
// never erase a user's scrollback.
func (t *TrackedScrollback) ResetForReflow(epoch uint64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.layoutEpoch = epoch
	t.firstSeq = 1
	t.nextSeq = 1
	t.lines = t.lines[:0]
	t.bytes = 0
}

// LayoutEpoch 返回当前 layout epoch。
func (t *TrackedScrollback) LayoutEpoch() uint64 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.layoutEpoch
}

// Push 追加一行到历史。
func (t *TrackedScrollback) Push(line headlessterm.ScrollbackLine) {
	t.mu.Lock()
	defer t.mu.Unlock()

	cells := make([]headlessterm.Cell, len(line.Cells))
	copy(cells, line.Cells)

	historyLine := HistoryLine{
		HistorySeq: t.nextSeq,
		LineID:     line.LineID,
		Wrapped:    line.Wrapped,
		Version:    line.LineVersion,
		Cells:      cells,
		bytes:      estimateHistoryLineBytes(cells),
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
}

// Pop 移除并返回最新一行。
func (t *TrackedScrollback) Pop() headlessterm.ScrollbackLine {
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.lines) == 0 {
		return headlessterm.ScrollbackLine{}
	}
	line := t.lines[len(t.lines)-1]
	t.lines = t.lines[:len(t.lines)-1]
	t.bytes -= line.bytes
	if len(t.lines) > 0 {
		t.firstSeq = t.lines[0].HistorySeq
	} else {
		t.firstSeq = t.nextSeq
	}
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
func (t *TrackedScrollback) LineByID(id uint64) (HistoryLine, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	for _, line := range t.lines {
		if line.LineID == id {
			return line, true
		}
	}
	return HistoryLine{}, false
}

// LineByHistorySeq returns the history entry selected by pagination/trim
// cursor. It is distinct from LineByID because LineID is not ordered by time.
func (t *TrackedScrollback) LineByHistorySeq(seq uint64) (HistoryLine, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	index := sort.Search(len(t.lines), func(i int) bool { return t.lines[i].HistorySeq >= seq })
	if index >= len(t.lines) || t.lines[index].HistorySeq != seq {
		return HistoryLine{}, false
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
	result.Lines = make([]HistoryLine, end-start)
	copy(result.Lines, t.lines[start:end])
	return result
}

// PageBefore returns rows strictly before the HistorySeq cursor, in entrance order.
func (t *TrackedScrollback) PageBefore(beforeSeq uint64, limit int) []HistoryLine {
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
	result := make([]HistoryLine, end-start)
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
	w.Lines = make([]HistoryLine, len(t.lines)-start)
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
	w.Lines = make([]HistoryLine, len(t.lines)-start)
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
		w.Entries[i-start] = HistoryIndexEntry{HistorySeq: t.lines[i].HistorySeq, LineID: t.lines[i].LineID}
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
	if len(t.lines) == 0 {
		return
	}
	t.lines = t.lines[:0]
	t.bytes = 0
	// clear 是同一 layout epoch 内的历史裁剪，不是 LineID 空间重建。
	// 保持 nextSeq 单调递增，使 HistoryExtent 水位可被客户端接受，也避免后续
	// 输出复用已经被客户端见过的历史行 ID。
	t.firstSeq = t.nextSeq
	t.fireTrimLocked()
}

// SetMaxLines 调整行数安全上限；字节预算（SetMaxBytes）仍可能先触发驱逐。
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
	return true
}

// estimateHistoryLineBytes 返回一行的近似堆占用。这是容量记账预算而非精确的
// Go 堆内省：只要求不明显低估，允许与真实占用有 ±15% 左右的偏差。
//
// 常量依据 BenchmarkHistoryLineMemory（go 1.25.1, darwin/arm64）的实测
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
// 多字节字符簇的字符串数据；基线 64B 覆盖 HistoryLine 结构体（48B）与
// cells 切片的分配/size-class 取整开销。逐 cell 独立颜色的极端样式输出
// （每 cell 两个 8B 颜色对象）仍可能低估约 15%，可接受。
func estimateHistoryLineBytes(cells []headlessterm.Cell) int {
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
