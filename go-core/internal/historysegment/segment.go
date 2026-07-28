// Package historysegment 提供不可变历史分段存储。
//
// 同一 HistoryGeneration 内，SegmentKey=(generation, segmentNumber) 一旦封存，
// 正文永不变化。SEGMENT_SIZE=128，与客户端缓存页对齐：
//
//	segmentNumber = (historySeq - 1) / 128
package historysegment

import (
	headlessterm "github.com/danielgatis/go-headless-term"
)

// Size 是服务端与客户端共同理解的不可变存储单位行数。
const Size = 128

// Key 唯一标识一个已封存分段。
type Key struct {
	Generation uint64
	Number     uint64
}

// NumberForSeq 返回 historySeq 所属的 segmentNumber。
// historySeq 必须 >= 1；传入 0 时返回 0。
func NumberForSeq(historySeq uint64) uint64 {
	if historySeq == 0 {
		return 0
	}
	return (historySeq - 1) / Size
}

// SeqRange 返回 segmentNumber 对应的闭区间 [first, last]。
func SeqRange(segmentNumber uint64) (firstSeq, lastSeq uint64) {
	firstSeq = segmentNumber*Size + 1
	lastSeq = firstSeq + Size - 1
	return firstSeq, lastSeq
}

// AlignToSegmentStart 将 HistorySeq 向上对齐到分段起点。
// 若 seq 已是某段起点则不变；否则跳到下一段起点。
// Clear 后新 generation 必须从此对齐位置起编，避免 Catalog 声明
// trimBefore..sealedThrough 可加载、但首段因不完整从未封存。
func AlignToSegmentStart(seq uint64) uint64 {
	if seq <= 1 {
		return 1
	}
	size := uint64(Size)
	return ((seq-1+size-1)/size)*size + 1
}

// Catalog 是 WS 发布的权威历史目录（不含正文）。
type Catalog struct {
	Generation       uint64
	TrimBeforeSeq    uint64
	SealedThroughSeq uint64
	TailLastSeq      uint64
}

// Line 是封存段内的一行。Cells 与 scrollback 共享且不可变。
type Line struct {
	HistorySeq uint64
	LineID     uint64
	Version    uint64
	Wrapped    bool
	Cells      []headlessterm.Cell
}

// Segment 是封存后的不可变历史段。Put 之后不得修改 Lines。
type Segment struct {
	Generation uint64
	Number     uint64
	FirstSeq   uint64
	LastSeq    uint64
	Lines      []Line
}

// Key 返回本段的 SegmentKey。
func (s *Segment) Key() Key {
	return Key{Generation: s.Generation, Number: s.Number}
}

// NewSegment 从完整的 128 行构造不可变分段。
// lines 必须恰好覆盖 [first, last] 且严格递增；调用方应传入新切片头。
func NewSegment(generation, segmentNumber uint64, lines []Line) (*Segment, bool) {
	first, last := SeqRange(segmentNumber)
	if len(lines) != Size {
		return nil, false
	}
	if lines[0].HistorySeq != first || lines[len(lines)-1].HistorySeq != last {
		return nil, false
	}
	for i := 1; i < len(lines); i++ {
		if lines[i].HistorySeq != lines[i-1].HistorySeq+1 {
			return nil, false
		}
	}
	return &Segment{
		Generation: generation,
		Number:     segmentNumber,
		FirstSeq:   first,
		LastSeq:    last,
		Lines:      lines,
	}, true
}
