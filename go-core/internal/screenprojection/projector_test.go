package screenprojection

import (
	"testing"

	"webterm/go-core/internal/terminalengine"
)

func TestProjector_FirstFrameIsSnapshot(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	engine.Write([]byte("hello\n"))

	p := NewProjector(engine, sb, "s1", "i1")
	frame := p.FrameForClient("c1", 0, 1)
	if frame.BaseRevision != 0 {
		t.Fatalf("expected snapshot, got patch base=%d", frame.BaseRevision)
	}
}

func TestProjector_PatchAfterBaseline(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	engine.Write([]byte("hello\n"))

	p := NewProjector(engine, sb, "s1", "i1")
	p.FrameForClient("c1", 0, 1)

	engine.Write([]byte("world\n"))
	frame := p.FrameForClient("c1", 0, 2)
	if frame.BaseRevision != 1 {
		t.Fatalf("expected patch base=1, got %d", frame.BaseRevision)
	}
}

func TestProjector_ClearLineIsExportedAsScreenPatch(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(3, 12, sb)
	if err := engine.Write([]byte("stale-block")); err != nil {
		t.Fatal(err)
	}
	p := NewProjector(engine, sb, "s1", "i1")
	p.FrameForClient("c1", 0, 1)

	// Typical TUI redraw: return to the line, erase it, then draw a shorter value.
	if err := engine.Write([]byte("\r\x1b[2Kok")); err != nil {
		t.Fatal(err)
	}
	patch := p.FrameForClient("c1", 0, 2)
	if patch.BaseRevision != 1 {
		t.Fatalf("expected patch after baseline, got base=%d", patch.BaseRevision)
	}
	if len(patch.Screen) != 1 {
		t.Fatalf("expected one changed screen row after erase, got %d", len(patch.Screen))
	}
	if got := patch.Screen[0].Runs[0].Cells[0].Text; got != "o" {
		t.Fatalf("cleared row did not contain replacement text: %q", got)
	}
}

func TestProjector_LayoutEpochChangeIsSnapshot(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	engine.Write([]byte("hello\n"))

	p := NewProjector(engine, sb, "s1", "i1")
	p.FrameForClient("c1", 0, 1)

	engine.Resize(6, 12)
	frame := p.FrameForClient("c1", 1, 2)
	if frame.BaseRevision != 0 {
		t.Fatalf("expected snapshot after epoch change, got patch base=%d", frame.BaseRevision)
	}
}
