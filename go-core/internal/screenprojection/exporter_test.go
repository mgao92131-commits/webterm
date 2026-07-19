package screenprojection

import (
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"
	"webterm/go-core/internal/terminalengine"
)

func TestExportSnapshot_ExportsPlainText(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	if err := engine.Write([]byte("hello\nworld\n")); err != nil {
		t.Fatal(err)
	}

	frame := ExportSnapshot(engine, sb, "s1", "i1", 0, 1)
	if frame.SessionID != "s1" || frame.InstanceID != "i1" {
		t.Fatalf("session/instance id mismatch")
	}
	if frame.Rows != 5 || frame.Cols != 10 {
		t.Fatalf("geometry mismatch: %dx%d", frame.Rows, frame.Cols)
	}
	if len(frame.Screen) != 5 {
		t.Fatalf("expected 5 screen rows, got %d", len(frame.Screen))
	}
	if len(frame.Screen[0].Runs) == 0 {
		t.Fatal("expected row 0 to have content")
	}
	if frame.Screen[0].Runs[0].Cells[0].Text != "h" {
		t.Fatalf("expected 'h', got %q", frame.Screen[0].Runs[0].Cells[0].Text)
	}
}

func TestExportSnapshot_WideChars(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	if err := engine.Write([]byte("中文\n")); err != nil {
		t.Fatal(err)
	}

	frame := ExportSnapshot(engine, sb, "s1", "i1", 0, 1)
	first := frame.Screen[0].Runs[0].Cells[0]
	if first.Text != "中" || first.Width != 2 {
		t.Fatalf("expected 中 width=2, got text=%q width=%d", first.Text, first.Width)
	}
	second := frame.Screen[0].Runs[0].Cells[1]
	if second.Text != "文" || second.Width != 2 {
		t.Fatalf("expected 文 width=2, got text=%q width=%d", second.Text, second.Width)
	}
}

func TestExportCells_StaleSoftCursorPreservesFollowingColumn(t *testing.T) {
	exporter := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	a := headlessterm.NewCell()
	a.Char = "a"
	staleCursor := headlessterm.NewCell()
	staleCursor.SetFlag(headlessterm.CellFlagReverse)
	b := headlessterm.NewCell()
	b.Char = "b"

	runs := exporter.exportCells([]headlessterm.Cell{a, staleCursor, b}, 0, 1, 0)
	if len(runs) != 2 {
		t.Fatalf("runs=%d, want 2; stale cursor gap must not compress columns: %#v", len(runs), runs)
	}
	if runs[0].Col != 0 || len(runs[0].Cells) != 1 || runs[0].Cells[0].Text != "a" {
		t.Fatalf("first run=%#v, want col 0 containing a", runs[0])
	}
	if runs[1].Col != 2 || len(runs[1].Cells) != 1 || runs[1].Cells[0].Text != "b" {
		t.Fatalf("second run=%#v, want col 2 containing b", runs[1])
	}
}

func TestExportCells_SpacerPreservesFollowingColumn(t *testing.T) {
	exporter := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	a := headlessterm.NewCell()
	a.Char = "a"
	spacer := headlessterm.NewCell()
	spacer.Char = ""
	spacer.SetFlag(headlessterm.CellFlagWideCharSpacer)
	b := headlessterm.NewCell()
	b.Char = "b"

	runs := exporter.exportCells([]headlessterm.Cell{a, spacer, b}, 0, 1, 0)
	if len(runs) != 2 || runs[0].Col != 0 || runs[1].Col != 2 {
		t.Fatalf("runs=%#v, want independent runs at columns 0 and 2", runs)
	}
}

func TestExportCells_ContiguousSameStyleCellsStayInOneRun(t *testing.T) {
	exporter := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	a := headlessterm.NewCell()
	a.Char = "a"
	b := headlessterm.NewCell()
	b.Char = "b"

	runs := exporter.exportCells([]headlessterm.Cell{a, b}, 0, 1, 0)
	if len(runs) != 1 || runs[0].Col != 0 || len(runs[0].Cells) != 2 {
		t.Fatalf("runs=%#v, want one contiguous run at column 0", runs)
	}
}

func TestExportSnapshot_HistoryWindow(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(3, 10, sb)
	for i := 0; i < 5; i++ {
		if err := engine.Write([]byte("line\n")); err != nil {
			t.Fatal(err)
		}
	}

	frame := ExportSnapshot(engine, sb, "s1", "i1", 0, 1)
	if len(frame.History.Lines) == 0 {
		t.Fatal("expected history lines")
	}
	if frame.History.FirstAvailableHistorySeq != 1 {
		t.Fatalf("FirstAvailableHistorySeq=1, got %d", frame.History.FirstAvailableHistorySeq)
	}
}

func TestExportSnapshot_AlternateScreenExcludesMainScrollback(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(3, 10, sb)
	for i := 0; i < 5; i++ {
		if err := engine.Write([]byte("main\n")); err != nil {
			t.Fatal(err)
		}
	}
	if err := engine.Write([]byte("\x1b[?1049h\x1b[Halt")); err != nil {
		t.Fatal(err)
	}

	frame := ExportSnapshot(engine, sb, "s1", "i1", 0, 1)
	if frame.ActiveBuffer != terminalengine.BufferAlternate {
		t.Fatalf("expected alternate buffer, got %v", frame.ActiveBuffer)
	}
	if len(frame.History.Lines) != 0 {
		t.Fatalf("alternate snapshot leaked %d main-history lines", len(frame.History.Lines))
	}
}

func TestExportSnapshot_DropsStaleClaudeSoftCursor(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(3, 10, sb)
	// Row 2 receives an old reverse-space cursor. Row 1 is the live cursor.
	if err := engine.Write([]byte("\x1b[2;4H\x1b[7m \x1b[0m\x1b[1;3H\x1b[7m \x1b[0m\x1b[1D")); err != nil {
		t.Fatal(err)
	}
	frame := ExportSnapshot(engine, sb, "s1", "i1", 0, 1)
	if got := styleAt(frame.Screen[0], 2); got == 0 {
		t.Fatal("expected live reverse-space cursor to retain a non-default style")
	}
	if got := styleAt(frame.Screen[1], 3); got != 0 {
		t.Fatalf("stale reverse-space cursor leaked into exported row: style=%d", got)
	}
}

func styleAt(line terminalengine.Line, col int) uint32 {
	for _, run := range line.Runs {
		current := run.Col
		for _, cell := range run.Cells {
			width := int(cell.Width)
			if width < 1 {
				width = 1
			}
			if col >= current && col < current+width {
				return cell.StyleID
			}
			current += width
		}
	}
	return 0
}
