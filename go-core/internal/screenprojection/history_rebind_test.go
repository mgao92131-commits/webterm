package screenprojection

import (
	"strings"
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"
	"webterm/go-core/internal/terminalengine"
)

func TestHistoryChangeIndexDetectsSameSeqRebind(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10, nil)
	sb.Push(headlessterm.ScrollbackLine{
		LineID: 1001, LineVersion: 1,
		Cells: []headlessterm.Cell{headlessterm.NewCell()},
	})
	var index HistoryChangeIndex
	index.sync(sb, 10)

	sb.Pop()
	sb.Push(headlessterm.ScrollbackLine{
		LineID: 2001, LineVersion: 1,
		Cells: []headlessterm.Cell{headlessterm.NewCell()},
	})
	index.sync(sb, 20)

	if len(index.Changes) != 1 {
		t.Fatalf("changes=%d, want 1", len(index.Changes))
	}
	change := index.Changes[0]
	if change.HistorySeq != 1 || change.LineID != 2001 || change.CreatedRevision != 20 {
		t.Fatalf("rebound change=%+v", change)
	}
}

func TestHistoryChangeIndexRecordsTrimWatermarkRevision(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(1, nil)
	var index HistoryChangeIndex
	sb.Push(headlessterm.ScrollbackLine{
		LineID: 1, LineVersion: 1,
		Cells: []headlessterm.Cell{headlessterm.NewCell()},
	})
	index.sync(sb, 10)
	sb.Push(headlessterm.ScrollbackLine{
		LineID: 2, LineVersion: 1,
		Cells: []headlessterm.Cell{headlessterm.NewCell()},
	})
	index.sync(sb, 20)
	if index.WatermarkChangedRevision != 20 {
		t.Fatalf("watermark revision=%d, want 20", index.WatermarkChangedRevision)
	}
}

func TestHistoryChangeIndexPureScreenRevisionsDoNotAllocateWithTwentyThousandLines(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(20_000, nil)
	for i := 0; i < 20_000; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
		})
	}
	var index HistoryChangeIndex
	index.sync(sb, 1)

	revision := uint64(1)
	allocs := testing.AllocsPerRun(1_000, func() {
		revision++
		if index.sync(sb, revision) {
			t.Fatal("unchanged history reported a gap")
		}
	})
	if allocs != 0 {
		t.Fatalf("unchanged 20k history allocs/revision=%f, want 0", allocs)
	}
}

func BenchmarkHistoryChangeIndexPureScreenRevision20000(b *testing.B) {
	sb := terminalengine.NewTrackedScrollback(20_000, nil)
	for i := 0; i < 20_000; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
		})
	}
	var index HistoryChangeIndex
	index.sync(sb, 1)
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		index.sync(sb, uint64(i+2))
	}
}

func BenchmarkDiffToPatchPureScreenRevisionWith20000History(b *testing.B) {
	lineage := make([]terminalengine.HistoryPush, 20_000)
	for i := range lineage {
		seq := uint64(i + 1)
		lineage[i] = terminalengine.HistoryPush{
			HistorySeq: seq, LineID: seq, LineVersion: 1,
		}
	}
	old := terminalengine.ScreenFrame{
		InstanceID: "i1", Epoch: 1, Seq: 1,
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: 1, LastIncludedHistorySeq: 20_000,
		},
		ScrollbackLineage:     lineage,
		HistoryLineageVersion: 20_001,
		Screen:                []terminalengine.Line{{ID: 30_001, Version: 1}},
	}
	next := old
	next.Seq = 2
	next.Cursor = terminalengine.Cursor{Row: 1}

	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = diffToPatch(old, next)
	}
}

func TestDiffToPatchEmitsFiveThousandRebindings(t *testing.T) {
	old := terminalengine.ScreenFrame{
		InstanceID: "i1", Epoch: 1, Seq: 1,
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: 1, LastIncludedHistorySeq: 5000,
		},
		ScrollbackLineage: make([]terminalengine.HistoryPush, 5000),
	}
	next := old
	next.Seq = 2
	next.ScrollbackLineage = make([]terminalengine.HistoryPush, 5000)
	for i := 0; i < 5000; i++ {
		seq := uint64(i + 1)
		old.ScrollbackLineage[i] = terminalengine.HistoryPush{
			HistorySeq: seq, LineID: seq, LineVersion: 1,
		}
		next.ScrollbackLineage[i] = terminalengine.HistoryPush{
			HistorySeq: seq, LineID: 10_000 + seq, LineVersion: 1,
		}
	}
	patch := diffToPatch(old, next)
	if len(patch.HistoryPushes) != 5000 {
		t.Fatalf("pushes=%d, want 5000", len(patch.HistoryPushes))
	}
}

func TestResumeAfterTrimUsesBaseline(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(3, nil)
	engine := terminalengine.NewEngine(2, 20, sb)
	projector := NewProjector(engine, sb, "s1", "i1")
	if err := engine.Write([]byte(strings.Repeat("before\r\n", 5))); err != nil {
		t.Fatal(err)
	}
	old := projector.ExportState(1, 10)
	token := resumeTokenFor(old)
	if err := engine.Write([]byte(strings.Repeat("after\r\n", 10))); err != nil {
		t.Fatal(err)
	}
	if got := projector.Resume(token, 1, 20, false).Kind; got != ResumeBaseline {
		t.Fatalf("resume kind=%v, want baseline after trim", got)
	}
}
