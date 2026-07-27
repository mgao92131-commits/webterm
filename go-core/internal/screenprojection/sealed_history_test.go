package screenprojection

import (
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"

	"webterm/go-core/internal/historysegment"
	"webterm/go-core/internal/terminalengine"
)

func TestDiffToPatchPublishesSealOnlyHistoryChange(t *testing.T) {
	old := terminalengine.ScreenFrame{
		Kind: terminalengine.FrameSnapshot, InstanceID: "i1", Epoch: 1, Seq: 1,
		Rows: 2, Cols: 2, HistoryGeneration: 1,
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: 1, FirstIncludedHistorySeq: 1,
			LastIncludedHistorySeq: 127, SealedThroughSeq: 0,
		},
		Screen: []terminalengine.Line{{ID: 1, Version: 1, Row: 0}, {ID: 2, Version: 1, Row: 1}},
	}
	next := old
	next.Seq = 2
	next.History.LastIncludedHistorySeq = 128
	next.History.SealedThroughSeq = 128
	patch := diffToPatch(old, next, 128, 1<<20)
	if !patch.FirstAvailableHistorySeqChanged {
		t.Fatal("seal-only advance must mark history changed")
	}
	if patch.History.SealedThroughSeq != 128 {
		t.Fatalf("SealedThroughSeq=%d", patch.History.SealedThroughSeq)
	}
	if !hasHistoryChanges(patch) {
		t.Fatal("hasHistoryChanges must be true for seal-only")
	}
}

func TestExportStateCarriesSealedThroughSeq(t *testing.T) {
	store := historysegment.NewMemoryStore()
	sb := terminalengine.NewTrackedScrollback(10_000, nil)
	sb.AttachSegmentStore(store)
	engine := terminalengine.NewEngine(4, 8, sb)
	for i := 0; i < 128; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	p := NewProjector(engine, sb, "s1", "i1")
	state := p.ExportState(1, 1)
	if state.History.SealedThroughSeq != 128 {
		t.Fatalf("ExportState SealedThroughSeq=%d, want 128", state.History.SealedThroughSeq)
	}
}
