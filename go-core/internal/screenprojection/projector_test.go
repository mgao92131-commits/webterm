package screenprojection

import (
	"fmt"
	"strings"
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

func TestProjector_OneExportFeedsIndependentClientBaselines(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	p := NewProjector(engine, sb, "s1", "i1")

	if err := engine.Write([]byte("hello\n")); err != nil {
		t.Fatal(err)
	}
	initial := p.ExportState(0, 1)
	if first := p.FrameForClientState("c1", initial); first.BaseRevision != 0 {
		t.Fatalf("c1 first frame must be snapshot, got base=%d", first.BaseRevision)
	}
	if first := p.FrameForClientState("c2", initial); first.BaseRevision != 0 {
		t.Fatalf("c2 first frame must be snapshot, got base=%d", first.BaseRevision)
	}

	if err := engine.Write([]byte("world\n")); err != nil {
		t.Fatal(err)
	}
	next := p.ExportState(0, 2)
	for _, clientID := range []string{"c1", "c2"} {
		if patch := p.FrameForClientState(clientID, next); patch.BaseRevision != 1 {
			t.Fatalf("%s patch base=%d, want 1", clientID, patch.BaseRevision)
		}
	}
}

func TestProjector_PatchOnlyCarriesNewDictionaryEntries(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(3, 12, sb)
	p := NewProjector(engine, sb, "s1", "i1")
	p.FrameForClient("c1", 0, 1)

	if err := engine.Write([]byte("\x1b[31mred")); err != nil {
		t.Fatal(err)
	}
	firstStyledPatch := p.FrameForClient("c1", 0, 2)
	if len(firstStyledPatch.Styles) == 0 {
		t.Fatal("first styled patch omitted its new style")
	}

	if err := engine.Write([]byte(" more")); err != nil {
		t.Fatal(err)
	}
	reusedStylePatch := p.FrameForClient("c1", 0, 3)
	if len(reusedStylePatch.Styles) != 0 {
		t.Fatalf("reused style was resent in patch: %d entries", len(reusedStylePatch.Styles))
	}
}

func TestFrameDeriver_CanSkipIntermediateStatesWithoutRevisionGap(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(4, 12, sb)
	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver

	if err := engine.Write([]byte("one")); err != nil {
		t.Fatal(err)
	}
	first := deriver.FrameForState(p.ExportState(0, 1))
	if first.BaseRevision != 0 {
		t.Fatalf("first frame must be snapshot, got base=%d", first.BaseRevision)
	}

	if err := engine.Write([]byte(" two")); err != nil {
		t.Fatal(err)
	}
	_ = p.ExportState(0, 2) // This state is deliberately coalesced away.
	if err := engine.Write([]byte(" three")); err != nil {
		t.Fatal(err)
	}
	latest := deriver.FrameForState(p.ExportState(0, 3))
	if latest.BaseRevision != 1 {
		t.Fatalf("coalesced frame base=%d, want the last sent revision 1", latest.BaseRevision)
	}
	if len(latest.Screen) == 0 {
		t.Fatal("coalesced frame omitted the latest screen change")
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

// A dictionary rotation invalidates every client's style/link baseline, but
// the rotation frame only carries the single-frame ForceSnapshot hint. A
// single-slot mailbox can overwrite that frame with a later ordinary state,
// so the durable DictionaryGeneration on every state must force a snapshot.
func TestProjector_DictionaryGenerationForcesSnapshotAfterMailboxOverwrite(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(100, nil)
	engine := terminalengine.NewEngine(3, 24, sb)
	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver

	if err := engine.Write([]byte("start")); err != nil {
		t.Fatal(err)
	}
	first := deriver.FrameForState(p.ExportState(0, 1))
	if first.BaseRevision != 0 {
		t.Fatalf("first frame must be snapshot, got base=%d", first.BaseRevision)
	}
	if first.DictionaryGeneration != 0 {
		t.Fatalf("initial dictionary generation=%d, want 0", first.DictionaryGeneration)
	}

	// Rewrite one row with fresh RGB colors per export. The dictionary only
	// grows, so after >4096 lookups the exporter rotates. The visible window
	// stays tiny (one 12-cell row), keeping the post-rotation re-export well
	// under the 4096-entry protocol limit.
	seq := uint64(2)
	styleCounter := 0
	repaint := func() {
		var buf strings.Builder
		buf.WriteByte('\r')
		for c := 0; c < 12; c++ {
			styleCounter++
			fmt.Fprintf(&buf, "\x1b[38;2;%d;%d;%dmX", styleCounter&0xFF, (styleCounter>>8)&0xFF, (styleCounter>>16)&0xFF)
		}
		if err := engine.Write([]byte(buf.String())); err != nil {
			t.Fatal(err)
		}
	}

	var rotated terminalengine.ScreenFrame
	for i := 0; i < 2000; i++ {
		repaint()
		state := p.ExportState(0, seq)
		seq++
		if state.ForceSnapshot {
			rotated = state
			break
		}
		// Keep the deriver baseline current with every delivered state; a
		// single changed row must stay on the patch path before rotation.
		if frame := deriver.FrameForState(state); frame.BaseRevision == 0 {
			t.Fatalf("pre-rotation frame seq=%d unexpectedly became snapshot", state.Seq)
		}
	}
	if !rotated.ForceSnapshot {
		t.Fatalf("dictionary never rotated after %d styles", styleCounter)
	}
	if rotated.DictionaryGeneration == first.DictionaryGeneration {
		t.Fatal("dictionary rotation did not advance the generation")
	}

	// Simulate the single-slot mailbox dropping the rotation frame: never
	// deliver `rotated` to the deriver, then submit the next ordinary state.
	repaint()
	next := p.ExportState(0, seq)
	seq++
	if next.DictionaryGeneration != rotated.DictionaryGeneration {
		t.Fatalf("ordinary state generation=%d, want rotated generation %d",
			next.DictionaryGeneration, rotated.DictionaryGeneration)
	}
	frame := deriver.FrameForState(next)
	if frame.BaseRevision != 0 {
		t.Fatalf("frame after dropped rotation must be snapshot, got patch base=%d rev=%d",
			frame.BaseRevision, frame.Seq)
	}

	// Within the same generation, later changes may be patches again.
	repaint()
	later := p.ExportState(0, seq)
	patch := deriver.FrameForState(later)
	if patch.BaseRevision == 0 {
		t.Fatal("same-generation frame after the snapshot should be a patch")
	}
	if patch.BaseRevision != frame.Seq {
		t.Fatalf("patch base=%d, want last delivered revision %d", patch.BaseRevision, frame.Seq)
	}
}

func TestProjector_LayoutEpochChangeAdvancesDictionaryGeneration(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(100, nil)
	engine := terminalengine.NewEngine(3, 12, sb)
	p := NewProjector(engine, sb, "s1", "i1")

	before := p.ExportState(0, 1)
	engine.Resize(4, 12)
	after := p.ExportState(1, 2)
	if after.DictionaryGeneration != before.DictionaryGeneration+1 {
		t.Fatalf("epoch change generation=%d, want %d",
			after.DictionaryGeneration, before.DictionaryGeneration+1)
	}
}
