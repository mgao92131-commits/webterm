package screenprojection

import (
	"fmt"
	"reflect"
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"
	"webterm/go-core/internal/terminalengine"
)

func testLine(id, version uint64, row int, text string) terminalengine.Line {
	return terminalengine.Line{
		ID: id, Version: version, Row: row,
		Runs: []terminalengine.CellRun{{
			Col: 0, Cells: []terminalengine.Cell{{Text: text, Width: 1}},
		}},
	}
}

func mapPtr(m map[uint64]int) uintptr {
	return reflect.ValueOf(m).Pointer()
}

func TestProjectedStateSingleDirtyRowUpdatesPersistentIndex(t *testing.T) {
	state := projectedState{
		valid: true,
		rows:  3,
		cols:  8,
		screen: []terminalengine.Line{
			testLine(1, 1, 0, "a"),
			testLine(2, 1, 1, "b"),
			testLine(3, 1, 2, "c"),
		},
		rowByLineID: map[uint64]int{1: 0, 2: 1, 3: 2},
	}
	exp := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	cells := make([]headlessterm.Cell, 8)
	cells[0].Char = "B"
	proj := headlessterm.ProjectionRead{
		Rows: 3,
		Cols: 8,
		DirtyRows: []headlessterm.ProjectionRow{{
			Index: 1, LineID: 2, LineVersion: 2, Cells: cells,
		}},
	}
	before := mapPtr(state.rowByLineID)
	state.merge(proj, exp)
	if mapPtr(state.rowByLineID) != before {
		t.Fatalf("merge allocated a new LineID index map")
	}
	if !state.validateLineIndex() {
		t.Fatalf("line index invalid after single dirty merge")
	}
}

func TestProjectedStateReplacingLineIdRemovesOldBinding(t *testing.T) {
	state := projectedState{
		valid: true,
		rows:  2,
		cols:  4,
		screen: []terminalengine.Line{
			testLine(10, 1, 0, "a"),
			testLine(11, 1, 1, "b"),
		},
		rowByLineID: map[uint64]int{10: 0, 11: 1},
	}
	exp := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	cells := make([]headlessterm.Cell, 4)
	cells[0].Char = "Z"
	proj := headlessterm.ProjectionRead{
		Rows: 2,
		Cols: 4,
		DirtyRows: []headlessterm.ProjectionRow{{
			Index: 0, LineID: 99, LineVersion: 1, Cells: cells,
		}},
	}
	state.merge(proj, exp)
	if _, ok := state.rowByLineID[10]; ok {
		t.Fatalf("old line id binding was not removed")
	}
	if state.rowByLineID[99] != 0 {
		t.Fatalf("new line id binding missing: %+v", state.rowByLineID)
	}
	if !state.validateLineIndex() {
		t.Fatalf("line index invalid after replace")
	}
}

func TestProjectedStateFullScreenScrollDoesNotCreateDuplicateBindings(t *testing.T) {
	state := projectedState{
		valid: true,
		rows:  3,
		cols:  4,
		screen: []terminalengine.Line{
			testLine(1, 1, 0, "a"),
			testLine(2, 1, 1, "b"),
			testLine(3, 1, 2, "c"),
		},
		rowByLineID: map[uint64]int{1: 0, 2: 1, 3: 2},
	}
	exp := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	mk := func(index int, id uint64, ch string) headlessterm.ProjectionRow {
		cells := make([]headlessterm.Cell, 4)
		cells[0].Char = ch
		return headlessterm.ProjectionRow{
			Index: index, LineID: id, LineVersion: 1, Cells: cells,
		}
	}
	proj := headlessterm.ProjectionRead{
		Rows: 3, Cols: 4,
		DirtyRows: []headlessterm.ProjectionRow{
			mk(0, 2, "b"), mk(1, 3, "c"), mk(2, 4, "d"),
		},
	}
	state.merge(proj, exp)
	if !state.validateLineIndex() {
		t.Fatalf("duplicate or inconsistent bindings after scroll merge: %+v", state.rowByLineID)
	}
	if state.rowByLineID[2] != 0 || state.rowByLineID[3] != 1 || state.rowByLineID[4] != 2 {
		t.Fatalf("unexpected bindings: %+v", state.rowByLineID)
	}
}

func TestFrameDeriverReusesBaselineIndex(t *testing.T) {
	deriver := &FrameDeriver{}
	state := terminalengine.ScreenFrame{
		Seq: 1,
		Screen: []terminalengine.Line{
			testLine(1, 1, 0, "a"),
			testLine(2, 1, 1, "b"),
		},
	}
	deriver.SeedAfterSuccessfulWrite(state)
	first := mapPtr(deriver.baselineRowByID)
	deriver.SeedAfterSuccessfulWrite(terminalengine.ScreenFrame{
		Seq: 2,
		Screen: []terminalengine.Line{
			testLine(2, 1, 0, "b"),
			testLine(3, 1, 1, "c"),
		},
	})
	if mapPtr(deriver.baselineRowByID) != first {
		t.Fatalf("SeedAfterSuccessfulWrite allocated a new baseline index map")
	}
	if deriver.baselineRowByID[2] != 0 || deriver.baselineRowByID[3] != 1 {
		t.Fatalf("baseline index not updated: %+v", deriver.baselineRowByID)
	}
}

func TestDeriveFullScreenScrollUsesProvidedIndex(t *testing.T) {
	oldScreen := []terminalengine.Line{
		testLine(1, 1, 0, "a"), testLine(2, 1, 1, "b"), testLine(3, 1, 2, "c"),
	}
	newScreen := []terminalengine.Line{
		testLine(2, 1, 0, "b"), testLine(3, 1, 1, "c"), testLine(4, 1, 2, "d"),
	}
	index := map[uint64]int{1: 0, 2: 1, 3: 2}
	got := deriveFullScreenScroll(oldScreen, newScreen, index)
	if got == nil || got.DeltaRows != 1 {
		t.Fatalf("got %+v, want delta=1", got)
	}
}

func TestProjectedStateValidateLineIndex(t *testing.T) {
	state := projectedState{
		rows: 2,
		screen: []terminalengine.Line{
			testLine(1, 1, 0, "a"),
			testLine(2, 1, 1, "b"),
		},
		rowByLineID: map[uint64]int{1: 0, 2: 1},
	}
	if !state.validateLineIndex() {
		t.Fatal("expected valid index")
	}
	state.rowByLineID[1] = 1
	if state.validateLineIndex() {
		t.Fatal("expected invalid index after corruption")
	}
}

func TestDuplicateCandidateDoesNotPublishPartialState(t *testing.T) {
	state := projectedState{
		valid: true,
		rows:  2,
		cols:  4,
		screen: []terminalengine.Line{
			testLine(1, 5, 0, "a"),
			testLine(2, 7, 1, "b"),
		},
		rowByLineID: map[uint64]int{1: 0, 2: 1},
	}
	originalScreen := append([]terminalengine.Line(nil), state.screen...)
	exp := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	cells := make([]headlessterm.Cell, 4)
	cells[0].Char = "X"
	// 把 id=2 写到 row0，而 row1 仍占用 id=2 → 冲突。
	proj := headlessterm.ProjectionRead{
		Rows: 2, Cols: 4,
		DirtyRows: []headlessterm.ProjectionRow{{
			Index: 0, LineID: 2, LineVersion: 1, Cells: cells,
		}},
	}
	state.merge(proj, exp)
	if state.valid {
		if !state.validateLineIndex() {
			t.Fatalf("valid state must pass validateLineIndex: screen=%+v index=%+v",
				state.screen, state.rowByLineID)
		}
	} else {
		if state.screen[0].ID != originalScreen[0].ID || state.screen[1].ID != originalScreen[1].ID {
			t.Fatalf("invalidated merge mutated screen to partial state: %+v", state.screen)
		}
		if state.screen[0].Version != originalScreen[0].Version {
			t.Fatalf("partial version publish: got %d want %d",
				state.screen[0].Version, originalScreen[0].Version)
		}
	}
}

func TestFallbackRebuildPreservesPriorLineVersion(t *testing.T) {
	previous := []terminalengine.Line{
		testLine(10, 9, 0, "same"),
		testLine(11, 3, 1, "other"),
	}
	index := indexRowsByLineID(previous)
	candidate := testLine(10, 1, 0, "same")
	got := reconcileFromSnapshot(previous, index, candidate)
	if got.Version != 9 {
		t.Fatalf("version=%d want 9 from prior snapshot", got.Version)
	}
	changed := testLine(10, 1, 0, "diff")
	got = reconcileFromSnapshot(previous, index, changed)
	if got.Version != 10 {
		t.Fatalf("version=%d want 10 after content change", got.Version)
	}
}

func TestUpdateScratchDoesNotRetainLines(t *testing.T) {
	state := projectedState{
		valid: true,
		rows:  2,
		cols:  4,
		screen: []terminalengine.Line{
			testLine(1, 1, 0, "a"),
			testLine(2, 1, 1, "b"),
		},
		rowByLineID: map[uint64]int{1: 0, 2: 1},
	}
	exp := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	cells := make([]headlessterm.Cell, 4)
	cells[0].Char = "B"
	proj := headlessterm.ProjectionRead{
		Rows: 2, Cols: 4,
		DirtyRows: []headlessterm.ProjectionRow{{
			Index: 1, LineID: 2, LineVersion: 2, Cells: cells,
		}},
	}
	state.merge(proj, exp)
	if len(state.updateScratch) != 0 {
		t.Fatalf("scratch len=%d want 0", len(state.updateScratch))
	}
	for i, update := range state.updateScratch[:cap(state.updateScratch)] {
		if update.line.ID != 0 || update.line.Runs != nil {
			t.Fatalf("scratch[%d] retained line refs: %+v", i, update)
		}
	}
}

func BenchmarkProjectedStateSingleDirtyRow(b *testing.B) {
	for _, rows := range []int{24, 80, 200} {
		b.Run(fmt.Sprintf("rows_%d", rows), func(b *testing.B) {
			state := benchProjectedState(rows)
			exp := newExporter(
				terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
				terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
			)
			cells := make([]headlessterm.Cell, 80)
			cells[0].Char = "X"
			dirty := rows / 2
			proj := headlessterm.ProjectionRead{
				Rows: rows, Cols: 80,
				DirtyRows: []headlessterm.ProjectionRow{{
					Index: dirty, LineID: uint64(dirty + 1), LineVersion: 2, Cells: cells,
				}},
			}
			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				state.merge(proj, exp)
			}
		})
	}
}

func BenchmarkDeriveFullScreenScroll(b *testing.B) {
	for _, rows := range []int{24, 80, 200} {
		b.Run(fmt.Sprintf("rows_%d", rows), func(b *testing.B) {
			oldScreen := make([]terminalengine.Line, rows)
			newScreen := make([]terminalengine.Line, rows)
			index := make(map[uint64]int, rows)
			for i := 0; i < rows; i++ {
				oldScreen[i] = testLine(uint64(i+1), 1, i, "x")
				index[uint64(i+1)] = i
			}
			for i := 0; i < rows-1; i++ {
				newScreen[i] = oldScreen[i+1]
			}
			newScreen[rows-1] = testLine(uint64(rows+1), 1, rows-1, "y")
			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				_ = deriveFullScreenScroll(oldScreen, newScreen, index)
			}
		})
	}
}

func BenchmarkFrameDeriverScrolling(b *testing.B) {
	for _, rows := range []int{24, 80, 200} {
		b.Run(fmt.Sprintf("rows_%d", rows), func(b *testing.B) {
			deriver := &FrameDeriver{}
			baseline := terminalengine.ScreenFrame{
				Seq: 1, Rows: rows, Cols: 80, Kind: terminalengine.FrameSnapshot,
				Screen: make([]terminalengine.Line, rows),
			}
			for i := 0; i < rows; i++ {
				baseline.Screen[i] = testLine(uint64(i+1), 1, i, "x")
			}
			deriver.SeedAfterSuccessfulWrite(baseline)
			next := baseline
			next.Seq = 2
			next.Screen = make([]terminalengine.Line, rows)
			for i := 0; i < rows-1; i++ {
				next.Screen[i] = baseline.Screen[i+1]
			}
			next.Screen[rows-1] = testLine(uint64(rows+1), 1, rows-1, "y")
			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				_ = deriver.DeriveForState(next)
			}
		})
	}
}

func benchProjectedState(rows int) projectedState {
	screen := make([]terminalengine.Line, rows)
	index := make(map[uint64]int, rows)
	for i := 0; i < rows; i++ {
		screen[i] = testLine(uint64(i+1), 1, i, "x")
		index[uint64(i+1)] = i
	}
	return projectedState{
		valid: true, rows: rows, cols: 80,
		screen: screen, rowByLineID: index,
	}
}
