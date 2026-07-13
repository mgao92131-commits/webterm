package terminalengine

import (
	"math"
	"testing"

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
