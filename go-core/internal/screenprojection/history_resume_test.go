package screenprojection

import (
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"
	"webterm/go-core/internal/terminalengine"
)

func newHistoryResumeFixture() (*terminalengine.TrackedScrollback, *Projector) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 20, sb)
	return sb, NewProjector(engine, sb, "s1", "i1")
}

func pushHistoryLines(sb *terminalengine.TrackedScrollback, count int) {
	for i := 0; i < count; i++ {
		sb.Push(headlessterm.ScrollbackLine{Cells: []headlessterm.Cell{headlessterm.NewCell()}})
	}
}

func TestHistoryChangeIndex_ResumePatchCarriesCreatedLinesAndWatermark(t *testing.T) {
	sb, p := newHistoryResumeFixture()
	p.ExportState(1, 1)

	pushHistoryLines(sb, 3)
	state := p.ExportState(1, 2)
	d := p.DeriveResumeFrame(state, 1)
	if d.Outcome != ResumeOutcomePatch {
		t.Fatalf("outcome=%v reason=%q, want patch", d.Outcome, d.Reason)
	}
	if !d.Frame.FirstAvailableHistoryLineIDChanged {
		t.Fatal("resume patch must carry history watermark presence")
	}
	if got := d.Frame.History.FirstAvailableLineID; got != 1 {
		t.Fatalf("watermark=%d, want 1", got)
	}
	if got := len(d.Frame.History.Lines); got != 3 {
		t.Fatalf("history append lines=%d, want 3", got)
	}
	for i, line := range d.Frame.History.Lines {
		if want := uint64(i + 1); line.ID != want {
			t.Fatalf("history[%d].ID=%d, want %d", i, line.ID, want)
		}
	}
}

func TestHistoryChangeIndex_TrimUsesActualWatermarkAndDropsOldIndex(t *testing.T) {
	sb, p := newHistoryResumeFixture()
	pushHistoryLines(sb, 2)
	p.ExportState(1, 1)

	sb.SetMaxLines(2)
	pushHistoryLines(sb, 1) // trim ID 1，实际水位推进到 2。
	state := p.ExportState(1, 2)
	d := p.DeriveResumeFrame(state, 1)
	if d.Outcome != ResumeOutcomePatch {
		t.Fatalf("outcome=%v reason=%q, want patch", d.Outcome, d.Reason)
	}
	if got := d.Frame.History.FirstAvailableLineID; got != 2 {
		t.Fatalf("watermark=%d, want actual first ID 2", got)
	}
	if len(d.Frame.History.Lines) != 1 || d.Frame.History.Lines[0].ID != 3 {
		t.Fatalf("history append=%v, want only ID 3", historyLineIDs(d.Frame.History.Lines))
	}
	for _, change := range p.historyChangeIndex.Changes {
		if change.LineID < 2 {
			t.Fatalf("trimmed history index survived: %+v", change)
		}
	}
}

func TestHistoryChangeIndex_TailRegressionAdvancesBarrier(t *testing.T) {
	sb, p := newHistoryResumeFixture()
	pushHistoryLines(sb, 3)
	p.ExportState(1, 1)
	sb.Pop()
	state := p.ExportState(1, 2)

	d := p.DeriveResumeFrame(state, 1)
	if d.Outcome != ResumeOutcomeSnapshot || d.Reason != ResumeReasonBarrier {
		t.Fatalf("outcome=%v reason=%q, want snapshot/barrier", d.Outcome, d.Reason)
	}
	if !state.ForceSnapshot {
		t.Fatal("tail regression must force same-revision snapshot for online clients")
	}
}

func TestHistoryChangeIndex_AppendOverProtocolLimitFallsBack(t *testing.T) {
	sb, p := newHistoryResumeFixture()
	p.ExportState(1, 1)
	pushHistoryLines(sb, maxResumeHistoryAppend+1)
	state := p.ExportState(1, 2)

	d := p.DeriveResumeFrame(state, 1)
	if d.Outcome != ResumeOutcomeSnapshot || d.Reason != ResumeReasonPatchCost {
		t.Fatalf("outcome=%v reason=%q, want snapshot/patch_cost", d.Outcome, d.Reason)
	}
}

func historyLineIDs(lines []terminalengine.Line) []uint64 {
	ids := make([]uint64, len(lines))
	for i, line := range lines {
		ids[i] = line.ID
	}
	return ids
}
