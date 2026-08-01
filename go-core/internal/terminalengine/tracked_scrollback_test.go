package terminalengine

import (
	"image/color"
	"reflect"
	"runtime"
	"sync"
	"testing"
	"unsafe"

	headlessterm "github.com/danielgatis/go-headless-term"
)

func TestIndexWindowIsAtomicDuringConcurrentTrim(t *testing.T) {
	sb := NewTrackedScrollback(32, nil)
	var wg sync.WaitGroup
	wg.Add(2)
	start := make(chan struct{})
	writerDone := make(chan struct{})
	go func() {
		defer wg.Done()
		<-start
		for i := 1; i <= 2_000; i++ {
			sb.Push(headlessterm.ScrollbackLine{
				LineID: uint64(i),
			})
		}
		close(writerDone)
	}()
	go func() {
		defer wg.Done()
		<-start
		for {
			window, _ := sb.IndexWindowIfChanged(0)
			expected := uint64(0)
			if window.LastSeq >= window.FirstSeq {
				expected = window.LastSeq - window.FirstSeq + 1
			}
			if uint64(len(window.Entries)) != expected {
				t.Errorf("atomic window entries=%d extent=%d..%d",
					len(window.Entries), window.FirstSeq, window.LastSeq)
				return
			}
			for index, entry := range window.Entries {
				want := window.FirstSeq + uint64(index)
				if entry.HistorySeq != want {
					t.Errorf("entry[%d].seq=%d want=%d", index, entry.HistorySeq, want)
					return
				}
			}
			select {
			case <-writerDone:
				return
			default:
			}
		}
	}()
	close(start)
	wg.Wait()
}

func TestTrackedScrollback_LineID(t *testing.T) {
	sb := NewTrackedScrollback(10000, nil)
	for i := 0; i < 5; i++ {
		sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	}
	if got := sb.FirstSeq(); got != 1 {
		t.Fatalf("FirstSeq=1, got %d", got)
	}
	if got := sb.NextSeq(); got != 6 {
		t.Fatalf("NextSeq=6, got %d", got)
	}
	line, ok := sb.LineByID(3)
	if !ok {
		t.Fatal("expected line 3")
	}
	if line.LineID != 3 {
		t.Fatalf("line.LineID=3, got %d", line.LineID)
	}
}

func TestTrackedScrollbackPromotionKeepsTransferredLineBody(t *testing.T) {
	sb := NewTrackedScrollback(10, nil)
	line := headlessterm.ScrollbackLine{
		LineID: 77,
		Cells: []headlessterm.Cell{headlessterm.NewCell()},
	}
	sb.Push(line)
	stored, ok := sb.LineByHistorySeq(1)
	if !ok {
		t.Fatal("promoted line missing")
	}
	if len(stored.Cells) != 1 || &stored.Cells[0] != &line.Cells[0] {
		t.Fatal("promotion copied terminal body instead of retaining transferred Line cells")
	}
}

func TestTrackedScrollback_PreservesNonMonotonicLineIDsAndAssignsHistorySeq(t *testing.T) {
	sb := NewTrackedScrollback(100, nil)
	for _, id := range []uint64{100, 7, 55} {
		sb.Push(headlessterm.ScrollbackLine{LineID: id,
			Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	}
	result := sb.Range(1, 3)
	if got := historyIDs(result.Lines); !reflect.DeepEqual(got, []uint64{100, 7, 55}) {
		t.Fatalf("LineIDs were rewritten: got %v", got)
	}
	for i, line := range result.Lines {
		if want := uint64(i + 1); line.HistorySeq != want {
			t.Fatalf("entry %d HistorySeq=%d, want %d", i, line.HistorySeq, want)
		}
	}
}

func TestTrackedScrollback_Trim(t *testing.T) {
	var trim ScrollbackTrimEvent
	sb := NewTrackedScrollback(3, func(ev ScrollbackTrimEvent) { trim = ev })
	for i := 0; i < 5; i++ {
		sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	}
	if got := sb.FirstSeq(); got != 3 {
		t.Fatalf("FirstSeq=3 after trim, got %d", got)
	}
	if trim.FirstAvailableSeq != 3 {
		t.Fatalf("trim event FirstAvailableSeq=3, got %d", trim.FirstAvailableSeq)
	}
	if _, ok := sb.LineByID(2); ok {
		t.Fatal("line 2 should have been trimmed")
	}
}

func TestTrackedScrollback_ClearKeepsLineIDsMonotonicAndFiresTrim(t *testing.T) {
	var trims []ScrollbackTrimEvent
	sb := NewTrackedScrollback(10000, func(ev ScrollbackTrimEvent) { trims = append(trims, ev) })
	for i := 0; i < 3; i++ {
		sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	}

	sb.Clear()

	// Clear 保留单调位置轴（3 行后 nextSeq=4），不再做固定段对齐。
	const wantSeq = 4
	if got := sb.Len(); got != 0 {
		t.Fatalf("Len after Clear = %d, want 0", got)
	}
	if got := sb.FirstSeq(); got != wantSeq {
		t.Fatalf("FirstSeq after Clear = %d, want %d", got, wantSeq)
	}
	if got := sb.NextSeq(); got != wantSeq {
		t.Fatalf("NextSeq after Clear = %d, want %d", got, wantSeq)
	}
	if len(trims) != 1 || trims[0].FirstAvailableSeq != wantSeq {
		t.Fatalf("trim events after Clear = %+v, want watermark %d", trims, wantSeq)
	}

	sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	line, ok := sb.LineByID(wantSeq)
	if !ok || line.LineID != wantSeq {
		t.Fatalf("first line after Clear = (%+v, %v), want ID %d", line, ok, wantSeq)
	}
	if _, ok := sb.LineByID(1); ok {
		t.Fatal("cleared LineID 1 must not become available again")
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
	if got := sb.FirstSeq(); got != 1 {
		t.Fatalf("FirstSeq=1, got %d", got)
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
	if got := sb.FirstSeq(); got != 1 {
		t.Fatalf("FirstSeq reset to 1, got %d", got)
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
	if last.FirstAvailableSeq != sb.FirstSeq() {
		t.Fatalf("trim event FirstAvailableSeq=%d, want %d", last.FirstAvailableSeq, sb.FirstSeq())
	}
	if _, ok := sb.LineByID(last.FirstAvailableSeq - 1); ok {
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
	if sb.Len() != 1 || sb.FirstSeq() != 2 {
		t.Fatalf("expected only the newest line kept: len=%d firstSeq=%d", sb.Len(), sb.FirstSeq())
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
var historyBenchSink []ScrollbackEntry

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
func measureRetainedHeap(build func() []ScrollbackEntry, repeats int) int64 {
	runtime.GC()
	runtime.GC()
	var before runtime.MemStats
	runtime.ReadMemStats(&before)
	var all [][]ScrollbackEntry
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

// BenchmarkScrollbackEntryMemory 实测历史行的典型堆占用，用于校准
// estimateScrollbackEntryBytes 的常量。构造路径与 TrackedScrollback.Push 相同。
// 它不是 CI 门禁，手动运行：
//
//	go test ./internal/terminalengine/ -run '^$' -bench ScrollbackEntryMemory -benchtime 3x
func BenchmarkScrollbackEntryMemory(b *testing.B) {
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
	b.ReportMetric(float64(unsafe.Sizeof(ScrollbackEntry{})), "line-struct-B")
	for _, tc := range cases {
		b.Run(tc.name, func(b *testing.B) {
			// 共享颜色对象在测量窗口外分配，模拟 parser template 跨行复用。
			sharedFg := color.Color(&headlessterm.NamedColor{Name: headlessterm.NamedColorForeground})
			sharedBg := color.Color(&headlessterm.NamedColor{Name: headlessterm.NamedColorBackground})
			sample := buildBenchmarkLine(tc.cols, tc.style, 0, sharedFg, sharedBg)
			estimate := estimateScrollbackEntryBytes(sample.Cells)
			b.ReportMetric(float64(estimate), "estimate-B/line")
			var measured float64
			for n := 0; n < b.N; n++ {
				// 直接填充驻留数组，等价于 TrackedScrollback.Push 复制后保留的
				// 对象图；先构造临时行再复制会产生大量瞬时垃圾，污染
				// HeapInuse 差值（大对象 span 清扫滞后）。
				delta := measureRetainedHeap(func() []ScrollbackEntry {
					lines := make([]ScrollbackEntry, linesPerRun)
					for i := 0; i < linesPerRun; i++ {
						cells := make([]headlessterm.Cell, tc.cols)
						fillBenchmarkCells(cells, tc.style, i, sharedFg, sharedBg)
						lines[i] = ScrollbackEntry{LineID: uint64(i + 1), Cells: cells, bytes: estimateScrollbackEntryBytes(cells)}
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

func TestTrackedScrollback_PopThenPushReusesHistorySeq(t *testing.T) {
	sb := NewTrackedScrollback(10000, nil)
	line := headlessterm.ScrollbackLine{LineID: 100,
		Cells: []headlessterm.Cell{headlessterm.NewCell()}}
	sb.Push(line)
	if got, ok := sb.LineByHistorySeq(1); !ok || got.LineID != 100 {
		t.Fatalf("first push=(%+v,%v), want HistorySeq=1 LineID=100", got, ok)
	}

	sb.Pop()
	sb.Push(line)
	got, ok := sb.LineByHistorySeq(1)
	if !ok || got.LineID != 100 || got.HistorySeq != 1 {
		t.Fatalf("second push=(%+v,%v), want HistorySeq=1 LineID=100", got, ok)
	}
	if next := sb.NextSeq(); next != 2 {
		t.Fatalf("NextSeq=%d, want 2", next)
	}
	result := sb.Range(1, 1)
	if result.Extent.FirstSeq != 1 || result.Extent.LastSeq != 1 || len(result.Lines) != 1 {
		t.Fatalf("history Range after Pop→Push=%+v, want only seq 1", result)
	}
}

func TestTrackedScrollback_ExtentPreservesEmptyTrimWatermark(t *testing.T) {
	sb := NewTrackedScrollback(100, nil)
	pushBlankLines(sb, 3)
	sb.Clear()

	// 3 行后 Clear 保持 nextSeq=4；空窗口 LastSeq+1==FirstSeq。
	got := sb.Extent()
	if got.FirstSeq != 4 || got.LastSeq != 3 || !got.Empty() {
		t.Fatalf("Extent after clear = %+v, want empty 4..3", got)
	}
}

func TestTrackedScrollback_RangeReportsTrimmedPrefixAndSurvivingLines(t *testing.T) {
	sb := NewTrackedScrollback(3, nil)
	pushBlankLines(sb, 5)

	got := sb.Range(1, 5)
	if got.Status != HistoryRangeOK {
		t.Fatalf("status = %v, want OK intersection", got.Status)
	}
	if got.Extent.FirstSeq != 3 || got.Extent.LastSeq != 5 {
		t.Fatalf("extent = %+v, want 3..5", got.Extent)
	}
	if len(got.Lines) != 3 || got.Lines[0].HistorySeq != 3 || got.Lines[2].HistorySeq != 5 {
		t.Fatalf("lines = %+v, want seq 3..5", got.Lines)
	}
}

func TestTrackedScrollback_RebaseForLayoutEpochMakesRetainedHistoryDense(t *testing.T) {
	sb := NewTrackedScrollback(100, nil)
	pushBlankLines(sb, 3)
	sb.Pop()
	pushBlankLines(sb, 2) // 1,2,4,5

	sb.RebaseForLayoutEpoch(7)

	got := sb.Range(1, 4)
	if got.Status != HistoryRangeOK || got.Extent.FirstSeq != 1 || got.Extent.LastSeq != 4 {
		t.Fatalf("range after rebase = %+v", got)
	}
	for i, line := range got.Lines {
		if want := uint64(i + 1); line.HistorySeq != want {
			t.Fatalf("line %d seq=%d, want %d", i, line.HistorySeq, want)
		}
	}
	if sb.NextSeq() != 5 || sb.LayoutEpoch() != 7 {
		t.Fatalf("next=%d epoch=%d, want 5/7", sb.NextSeq(), sb.LayoutEpoch())
	}
}

func historyIDs(lines []ScrollbackEntry) []uint64 {
	ids := make([]uint64, len(lines))
	for i, line := range lines {
		ids[i] = line.LineID
	}
	return ids
}
