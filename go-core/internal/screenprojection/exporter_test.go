package screenprojection

import (
	"testing"

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
	if frame.History.FirstAvailableLineID != 1 {
		t.Fatalf("FirstAvailableLineID=1, got %d", frame.History.FirstAvailableLineID)
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
