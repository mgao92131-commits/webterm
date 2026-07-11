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
