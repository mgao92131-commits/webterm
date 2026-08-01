package terminalengine

import (
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"
)

func TestBuildCanonicalLine_PreservesLiveSoftCursorOnScreen(t *testing.T) {
	cells := []headlessterm.Cell{
		plainCell("a"),
		softCursorCell(),
		plainCell("b"),
	}
	body := BuildCanonicalLine(1, cells, false, CursorContext{Present: true, Col: 1})
	if !body.Cells[1].Style.Attrs.Reverse {
		t.Fatal("live software caret must be preserved on screen")
	}
	if body.Cells[1].Text != " " {
		t.Fatalf("caret text = %q", body.Cells[1].Text)
	}
}

func TestBuildCanonicalLine_RemovesStaleSoftCursorOnScreen(t *testing.T) {
	cells := []headlessterm.Cell{
		softCursorCell(),
		plainCell("x"),
		softCursorCell(),
	}
	body := BuildCanonicalLine(1, cells, false, CursorContext{Present: true, Col: 1})
	if body.Cells[0].Style.Attrs.Reverse || body.Cells[2].Style.Attrs.Reverse {
		t.Fatal("stale software carets must be normalized away")
	}
	if body.Cells[0].Text != " " || body.Cells[2].Text != " " {
		t.Fatal("normalized cells should be default spaces")
	}
}

func TestBuildCanonicalLine_HistoryRemovesAllSoftCursors(t *testing.T) {
	cells := []headlessterm.Cell{
		plainCell("a"),
		softCursorCell(),
		plainCell("b"),
	}
	screen := BuildCanonicalLine(9, cells, false, CursorContext{Present: true, Col: 1})
	history := BuildCanonicalLine(9, cells, false, CursorContext{Present: false})
	if CanonicalBodiesEqual(&screen, &history) {
		t.Fatal("history normalization of live caret must change body")
	}
	if history.Cells[1].Style.Attrs.Reverse {
		t.Fatal("history must remove software caret")
	}

	store := NewLineStore()
	screenRec, _ := store.Commit(9, screen)
	historyRec, created := store.Commit(9, history)
	if !created {
		t.Fatal("caret normalization must mint a new LineKey")
	}
	if historyRec.Key.Version != screenRec.Key.Version+1 {
		t.Fatalf("version = %d, want %d", historyRec.Key.Version, screenRec.Key.Version+1)
	}
}

func TestBuildCanonicalLine_WideCharSpacerPreserved(t *testing.T) {
	wide := plainCell("中")
	wide.SetFlag(headlessterm.CellFlagWideChar)
	spacer := headlessterm.Cell{Char: ""}
	spacer.SetFlag(headlessterm.CellFlagWideCharSpacer)
	cells := []headlessterm.Cell{wide, spacer, plainCell("!")}

	body := BuildCanonicalLine(2, cells, false, CursorContext{})
	if body.PhysicalColumns != 3 {
		t.Fatalf("physical columns = %d, want 3", body.PhysicalColumns)
	}
	if body.Cells[0].Width != 2 || body.Cells[0].Text != "中" {
		t.Fatalf("wide cell = %+v", body.Cells[0])
	}
	if body.Cells[1].Width != 0 || body.Cells[1].Text != "" {
		t.Fatalf("spacer = %+v", body.Cells[1])
	}
	if body.Cells[2].Text != "!" {
		t.Fatalf("trailing cell = %+v", body.Cells[2])
	}
}

func TestBuildCanonicalLineAtRow_OnlyCursorRowKeepsCaret(t *testing.T) {
	cells := []headlessterm.Cell{softCursorCell(), plainCell("z")}
	cursor := CursorContext{Present: true, Row: 5, Col: 0}

	onRow := BuildCanonicalLineAtRow(1, 5, cells, false, cursor)
	offRow := BuildCanonicalLineAtRow(1, 4, cells, false, cursor)
	if !onRow.Cells[0].Style.Attrs.Reverse {
		t.Fatal("cursor row should keep live caret")
	}
	if offRow.Cells[0].Style.Attrs.Reverse {
		t.Fatal("non-cursor row must normalize soft caret")
	}
}

func TestBuildCanonicalLine_ScreenAndHistoryAgreeWithoutSoftCursor(t *testing.T) {
	cells := []headlessterm.Cell{plainCell("o"), plainCell("k")}
	screen := BuildCanonicalLine(3, cells, true, CursorContext{Present: true, Col: 0})
	history := BuildCanonicalLine(3, cells, true, CursorContext{Present: false})
	if !CanonicalBodiesEqual(&screen, &history) {
		t.Fatal("identical source without soft cursor must canonicalize equally")
	}
}

func plainCell(ch string) headlessterm.Cell {
	cell := headlessterm.NewCell()
	cell.Char = ch
	return cell
}

func softCursorCell() headlessterm.Cell {
	cell := headlessterm.NewCell()
	cell.Char = " "
	cell.SetFlag(headlessterm.CellFlagReverse)
	return cell
}
