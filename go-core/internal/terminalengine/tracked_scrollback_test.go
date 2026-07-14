package terminalengine

import (
	"image/color"
	"math"
	"runtime"
	"testing"
	"unsafe"

	headlessterm "github.com/danielgatis/go-headless-term"
)

func TestTrackedScrollback_LineID(t *testing.T) {
	sb := NewTrackedScrollback(10000, nil)
	for i := 0; i < 5; i++ {
		sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	}
	if got := sb.FirstID(); got != 1 {
		t.Fatalf("FirstID=1, got %d", got)
	}
	if got := sb.NextID(); got != 6 {
		t.Fatalf("NextID=6, got %d", got)
	}
	line, ok := sb.LineByID(3)
	if !ok {
		t.Fatal("expected line 3")
	}
	if line.ID != 3 {
		t.Fatalf("line.ID=3, got %d", line.ID)
	}
}

func TestTrackedScrollback_Trim(t *testing.T) {
	var trim ScrollbackTrimEvent
	sb := NewTrackedScrollback(3, func(ev ScrollbackTrimEvent) { trim = ev })
	for i := 0; i < 5; i++ {
		sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	}
	if got := sb.FirstID(); got != 3 {
		t.Fatalf("FirstID=3 after trim, got %d", got)
	}
	if trim.FirstAvailableID != 3 {
		t.Fatalf("trim event FirstAvailableID=3, got %d", trim.FirstAvailableID)
	}
	if _, ok := sb.LineByID(2); ok {
		t.Fatal("line 2 should have been trimmed")
	}
}

func TestTrackedScrollback_PageBefore(t *testing.T) {
	sb := NewTrackedScrollback(10000, nil)
	for i := 0; i < 10; i++ {
		sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	}
	page := sb.PageBefore(8, 3)
	if len(page) != 3 {
		t.Fatalf("expected 3 lines, got %d", len(page))
	}
	if page[0].ID != 5 {
		t.Fatalf("page[0].ID=5, got %d", page[0].ID)
	}
	page = sb.PageBefore(math.MaxUint64, 3)
	if len(page) != 3 || page[0].ID != 8 || page[2].ID != 10 {
		t.Fatalf("max before id should return tail page: %+v", page)
	}
}

func TestTrackedScrollback_Pop(t *testing.T) {
	sb := NewTrackedScrollback(10000, nil)
	sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}, Wrapped: true})
	line := sb.Pop()
	if line.Cells == nil {
		t.Fatal("expected popped line")
	}
	if !line.Wrapped {
		t.Fatal("expected wrapped=true")
	}
}

func TestTrackedScrollback_SetLayoutEpochPreservesHistory(t *testing.T) {
	sb := NewTrackedScrollback(10000, nil)
	sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	sb.SetLayoutEpoch(7)
	if got := sb.LayoutEpoch(); got != 7 {
		t.Fatalf("LayoutEpoch=7, got %d", got)
	}
	if got := sb.Len(); got != 1 {
		t.Fatalf("expected history preserved across ordinary resize, got %d", got)
	}
	if got := sb.FirstID(); got != 1 {
		t.Fatalf("FirstID=1, got %d", got)
	}
}

func TestTrackedScrollback_ResetForReflowClearsHistory(t *testing.T) {
	sb := NewTrackedScrollback(10000, nil)
	sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	sb.ResetForReflow(7)
	if got := sb.LayoutEpoch(); got != 7 {
		t.Fatalf("LayoutEpoch=7, got %d", got)
	}
	if got := sb.Len(); got != 0 {
		t.Fatalf("expected empty after explicit reflow reset, got %d", got)
	}
	if got := sb.FirstID(); got != 1 {
		t.Fatalf("FirstID reset to 1, got %d", got)
	}
}

func TestTrackedScrollback_ByteBudgetTrimsOldestLines(t *testing.T) {
	tb := NewTrackedScrollback(100, nil)
	tb.SetMaxBytes(250)
	for i := 0; i < 4; i++ {
		tb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{{Char: "0123456789"}}})
	}
	if tb.Len() >= 4 {
		t.Fatalf("byte budget did not trim: len=%d bytes=%d", tb.Len(), tb.Bytes())
	}
	if tb.Bytes() > 250 && tb.Len() > 1 {
		t.Fatalf("byte budget exceeded: len=%d bytes=%d", tb.Len(), tb.Bytes())
	}
}

func TestTrackedScrollback_ByteBudgetTrimsBelowLineCapAndFiresTrim(t *testing.T) {
	var trims []ScrollbackTrimEvent
	sb := NewTrackedScrollback(1000, func(ev ScrollbackTrimEvent) { trims = append(trims, ev) })
	sb.SetMaxBytes(300)
	// 每行估算 64+104+2*10=188 字节，两行即超预算；行数远低于容量 1000。
	for i := 0; i < 5; i++ {
		sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{{Char: "0123456789"}}})
	}
	if sb.Len() >= 5 {
		t.Fatalf("byte budget did not trim below the line cap: len=%d", sb.Len())
	}
	if len(trims) == 0 {
		t.Fatal("expected onTrim event for byte-budget eviction")
	}
	last := trims[len(trims)-1]
	if last.FirstAvailableID != sb.FirstID() {
		t.Fatalf("trim event FirstAvailableID=%d, want %d", last.FirstAvailableID, sb.FirstID())
	}
	if _, ok := sb.LineByID(last.FirstAvailableID - 1); ok {
		t.Fatal("trimmed line should not be retrievable")
	}
}

func TestTrackedScrollback_ByteBudgetKeepsNewestLine(t *testing.T) {
	sb := NewTrackedScrollback(1000, nil)
	sb.SetMaxBytes(100)
	// 单行估算即超预算：超字节裁剪必须至少保留最新一行。
	sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{{Char: "0123456789"}}})
	if sb.Len() != 1 {
		t.Fatalf("newest line must survive even when it exceeds the byte budget: len=%d", sb.Len())
	}
	sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{{Char: "0123456789"}}})
	if sb.Len() != 1 || sb.FirstID() != 2 {
		t.Fatalf("expected only the newest line kept: len=%d firstID=%d", sb.Len(), sb.FirstID())
	}
}

func TestTrackedScrollback_SetMaxBytesZeroDisablesByteTrim(t *testing.T) {
	sb := NewTrackedScrollback(1000, nil)
	sb.SetMaxBytes(0)
	for i := 0; i < 100; i++ {
		sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{{Char: "0123456789"}}})
	}
	if sb.Len() != 100 {
		t.Fatalf("non-positive byte budget must disable byte trimming: len=%d", sb.Len())
	}
}

// historyBenchSink 防止编译器把基准里的分配优化掉。
var historyBenchSink []HistoryLine

type historySampleStyle int

const (
	// samplePlainASCII：纯 ASCII 输出；颜色对象跨 cell/跨行共享
	// （对应 headlessterm parser 的 template 复用，见 terminal.go writeChar）。
	samplePlainASCII historySampleStyle = iota
	// sampleWideCJK：宽字符输出，每个 cell 一个 3 字节 UTF-8 字符串。
	sampleWideCJK
	// sampleRichStyled：每个 cell 独立的前景/背景色对象（SGR 频繁切换的输出）。
	sampleRichStyled
)

// fillBenchmarkCells 按 headlessterm 解析输出的形态填充一行 cell：
// Char 是每个 cell 新分配的字符串（terminal.go: cluster := string(t.pendingInput)），
// 颜色对象在同一样式段内共享、样式切换时重新分配（handler.go resolveColor）。
func fillBenchmarkCells(cells []headlessterm.Cell, style historySampleStyle, seed int, sharedFg, sharedBg color.Color) {
	for i := range cells {
		cell := headlessterm.Cell{Fg: sharedFg, Bg: sharedBg}
		switch style {
		case sampleWideCJK:
			cell.Char = string([]rune{rune('世') + rune((seed+i)%256)})
			cell.SetFlag(headlessterm.CellFlagWideChar)
		case sampleRichStyled:
			cell.Char = string(rune('a' + (seed+i)%26))
			cell.Fg = &headlessterm.IndexedColor{Index: (seed + i) % 256}
			cell.Bg = &headlessterm.NamedColor{Name: headlessterm.NamedColorBackground}
		default:
			cell.Char = string(rune('a' + (seed+i)%26))
		}
		cells[i] = cell
	}
}

func buildBenchmarkLine(cols int, style historySampleStyle, seed int, sharedFg, sharedBg color.Color) headlessterm.ScrollbackLine {
	cells := make([]headlessterm.Cell, cols)
	fillBenchmarkCells(cells, style, seed, sharedFg, sharedBg)
	return headlessterm.ScrollbackLine{Cells: cells}
}

// measureRetainedHeap 测量 build 产物的驻留堆大小。所有中间产物在测量期间
// 保持可达，避免 GC 清扫时机污染 HeapInuse 差值。
func measureRetainedHeap(build func() []HistoryLine, repeats int) int64 {
	runtime.GC()
	runtime.GC()
	var before runtime.MemStats
	runtime.ReadMemStats(&before)
	var all [][]HistoryLine
	for r := 0; r < repeats; r++ {
		all = append(all, build())
	}
	runtime.GC()
	runtime.GC()
	var after runtime.MemStats
	runtime.ReadMemStats(&after)
	historyBenchSink = all[repeats-1]
	for i := range all {
		all[i] = nil
	}
	return int64(after.HeapInuse) - int64(before.HeapInuse)
}

// BenchmarkHistoryLineMemory 实测历史行的典型堆占用，用于校准
// estimateHistoryLineBytes 的常量。构造路径与 TrackedScrollback.Push 相同。
// 它不是 CI 门禁，手动运行：
//
//	go test ./internal/terminalengine/ -run '^$' -bench HistoryLineMemory -benchtime 3x
func BenchmarkHistoryLineMemory(b *testing.B) {
	const linesPerRun = 1024
	cases := []struct {
		name  string
		cols  int
		style historySampleStyle
	}{
		{"80col-plain-ascii", 80, samplePlainASCII},
		{"200col-plain-ascii", 200, samplePlainASCII},
		{"80col-wide-cjk", 80, sampleWideCJK},
		{"200col-wide-cjk", 200, sampleWideCJK},
		{"80col-rich-styled", 80, sampleRichStyled},
		{"200col-rich-styled", 200, sampleRichStyled},
	}
	b.ReportMetric(float64(unsafe.Sizeof(headlessterm.Cell{})), "cell-struct-B")
	b.ReportMetric(float64(unsafe.Sizeof(HistoryLine{})), "line-struct-B")
	for _, tc := range cases {
		b.Run(tc.name, func(b *testing.B) {
			// 共享颜色对象在测量窗口外分配，模拟 parser template 跨行复用。
			sharedFg := color.Color(&headlessterm.NamedColor{Name: headlessterm.NamedColorForeground})
			sharedBg := color.Color(&headlessterm.NamedColor{Name: headlessterm.NamedColorBackground})
			sample := buildBenchmarkLine(tc.cols, tc.style, 0, sharedFg, sharedBg)
			estimate := estimateHistoryLineBytes(sample.Cells)
			b.ReportMetric(float64(estimate), "estimate-B/line")
			var measured float64
			for n := 0; n < b.N; n++ {
				// 直接填充驻留数组，等价于 TrackedScrollback.Push 复制后保留的
				// 对象图；先构造临时行再复制会产生大量瞬时垃圾，污染
				// HeapInuse 差值（大对象 span 清扫滞后）。
				delta := measureRetainedHeap(func() []HistoryLine {
					lines := make([]HistoryLine, linesPerRun)
					for i := 0; i < linesPerRun; i++ {
						cells := make([]headlessterm.Cell, tc.cols)
						fillBenchmarkCells(cells, tc.style, i, sharedFg, sharedBg)
						lines[i] = HistoryLine{ID: uint64(i + 1), Cells: cells, bytes: estimateHistoryLineBytes(cells)}
					}
					return lines
				}, 1)
				measured = float64(delta) / linesPerRun
			}
			b.ReportMetric(measured, "measured-B/line")
			b.ReportMetric(measured/float64(estimate), "measured÷estimate")
		})
	}
}

// pushBlankLines 向 scrollback 推入 n 行空白行（ID 从 1 起连续分配）。
func pushBlankLines(sb *TrackedScrollback, n int) {
	for i := 0; i < n; i++ {
		sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	}
}

func windowIDs(w ScrollbackWindow) []uint64 {
	ids := make([]uint64, len(w.Lines))
	for i, hl := range w.Lines {
		ids[i] = hl.ID
	}
	return ids
}

func TestTrackedScrollback_LinesAfter(t *testing.T) {
	sb := NewTrackedScrollback(10000, nil)
	pushBlankLines(sb, 10)

	w := sb.LinesAfter(3, 100)
	if w.FirstID != 1 || w.LastID != 10 {
		t.Fatalf("bounds FirstID=%d LastID=%d, want 1/10", w.FirstID, w.LastID)
	}
	if got := windowIDs(w); len(got) != 7 || got[0] != 4 || got[6] != 10 {
		t.Fatalf("LinesAfter(3) ids=%v, want 4..10", got)
	}

	// limit 保留最新段。
	w = sb.LinesAfter(3, 2)
	if got := windowIDs(w); len(got) != 2 || got[0] != 9 || got[1] != 10 {
		t.Fatalf("LinesAfter(3, 2) ids=%v, want [9 10]", got)
	}

	// lastLineID 已是最新：只返回边界。
	w = sb.LinesAfter(10, 100)
	if len(w.Lines) != 0 || w.LastID != 10 {
		t.Fatalf("LinesAfter(10) lines=%v lastID=%d, want none/10", windowIDs(w), w.LastID)
	}

	// lastLineID=0：从头开始，受 limit 约束取最新段。
	w = sb.LinesAfter(0, 4)
	if got := windowIDs(w); len(got) != 4 || got[0] != 7 {
		t.Fatalf("LinesAfter(0, 4) ids=%v, want 7..10", got)
	}

	// 空历史：Lines 为 nil，LastID = FirstID-1。
	empty := NewTrackedScrollback(10000, nil)
	w = empty.LinesAfter(0, 10)
	if w.Lines != nil || w.FirstID != 1 || w.LastID != 0 {
		t.Fatalf("empty LinesAfter: %+v", w)
	}

	// limit<=0：只返回边界。
	w = sb.LinesAfter(3, 0)
	if w.Lines != nil || w.LastID != 10 {
		t.Fatalf("LinesAfter limit=0: lines=%v lastID=%d", windowIDs(w), w.LastID)
	}
}

func TestTrackedScrollback_Window(t *testing.T) {
	sb := NewTrackedScrollback(10000, nil)
	pushBlankLines(sb, 10)

	w := sb.Window(3)
	if got := windowIDs(w); len(got) != 3 || got[0] != 8 || got[2] != 10 {
		t.Fatalf("Window(3) ids=%v, want 8..10", got)
	}
	if w.FirstID != 1 || w.LastID != 10 {
		t.Fatalf("Window bounds FirstID=%d LastID=%d, want 1/10", w.FirstID, w.LastID)
	}

	w = sb.Window(100)
	if len(w.Lines) != 10 {
		t.Fatalf("Window(100) returned %d lines, want 10", len(w.Lines))
	}

	w = sb.Window(0)
	if w.Lines != nil {
		t.Fatalf("Window(0) lines=%v, want nil", windowIDs(w))
	}

	empty := NewTrackedScrollback(10000, nil)
	w = empty.Window(300)
	if w.Lines != nil || w.FirstID != 1 || w.LastID != 0 {
		t.Fatalf("empty Window: %+v", w)
	}
}

func TestTrackedScrollback_LinesAfterExposesTrimDiscontinuity(t *testing.T) {
	sb := NewTrackedScrollback(3, nil)
	pushBlankLines(sb, 5) // 行数上限 3：firstID 推进到 3

	// baseline 的最后行（1）已被驱逐：FirstID=3 > 1+1，调用方据此判定不连续。
	w := sb.LinesAfter(1, 10)
	if w.FirstID != 3 || w.LastID != 5 {
		t.Fatalf("bounds FirstID=%d LastID=%d, want 3/5", w.FirstID, w.LastID)
	}
	if w.FirstID <= 1+1 {
		t.Fatal("expected discontinuity signal: FirstID > lastLineID+1")
	}
	if got := windowIDs(w); len(got) != 3 || got[0] != 3 {
		t.Fatalf("ids=%v, want 3..5", got)
	}

	// 恰好连续：FirstID == lastLineID+1。
	w = sb.LinesAfter(2, 10)
	if w.FirstID != 3 || len(w.Lines) != 3 {
		t.Fatalf("LinesAfter(2): %+v", w)
	}

	// 窗口中间：返回 lastLineID 之后的行。
	w = sb.LinesAfter(4, 10)
	if got := windowIDs(w); len(got) != 1 || got[0] != 5 {
		t.Fatalf("LinesAfter(4) ids=%v, want [5]", got)
	}
}

func TestTrackedScrollback_LinesAfterPopLowersLastID(t *testing.T) {
	sb := NewTrackedScrollback(10000, nil)
	pushBlankLines(sb, 3)
	sb.Pop()

	// Pop 移除了 ID 3：LastID 回落到 2，调用方据此判定缓存失效。
	w := sb.LinesAfter(3, 10)
	if w.LastID != 2 || w.Lines != nil {
		t.Fatalf("after Pop: lastID=%d lines=%v, want 2/nil", w.LastID, windowIDs(w))
	}
	if w.LastID >= 3 {
		t.Fatal("expected LastID < cached lastID after Pop")
	}

	w = sb.Window(10)
	if got := windowIDs(w); len(got) != 2 || got[1] != 2 {
		t.Fatalf("Window after Pop ids=%v, want [1 2]", got)
	}
}

func TestTrackedScrollback_QueriesRemainValidAfterPopCreatesIDGap(t *testing.T) {
	sb := NewTrackedScrollback(10000, nil)
	pushBlankLines(sb, 3)
	sb.Pop()              // 移除 ID 3，nextID 保持 4。
	pushBlankLines(sb, 2) // 新增 ID 4、5，驻留序列为 1、2、4、5。

	if line, ok := sb.LineByID(4); !ok || line.ID != 4 {
		t.Fatalf("LineByID(4)=(%+v,%v), want ID 4", line, ok)
	}
	if _, ok := sb.LineByID(3); ok {
		t.Fatal("popped ID 3 must remain absent")
	}
	if got := windowIDs(sb.LinesAfter(2, 10)); len(got) != 2 || got[0] != 4 || got[1] != 5 {
		t.Fatalf("LinesAfter(2)=%v, want [4 5]", got)
	}
	page := sb.PageBefore(5, 10)
	if len(page) != 3 || page[0].ID != 1 || page[2].ID != 4 {
		t.Fatalf("PageBefore(5) IDs=%v, want [1 2 4]", historyIDs(page))
	}
	idx := sb.IndexAfter(2)
	if len(idx.IDs) != 2 || idx.IDs[0] != 4 || idx.IDs[1] != 5 {
		t.Fatalf("IndexAfter(2)=%v, want [4 5]", idx.IDs)
	}
}

func historyIDs(lines []HistoryLine) []uint64 {
	ids := make([]uint64, len(lines))
	for i, line := range lines {
		ids[i] = line.ID
	}
	return ids
}
