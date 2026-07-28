package screenprojection

import (
	"fmt"
	"strings"
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"
	"webterm/go-core/internal/terminalengine"
)

func newHistoryRig(t *testing.T, rows, cols int) (*terminalengine.Engine, *terminalengine.TrackedScrollback, *Projector) {
	t.Helper()
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(rows, cols, sb)
	return engine, sb, NewProjector(engine, sb, "s1", "i1")
}

func writeScrollLines(t *testing.T, engine *terminalengine.Engine, start, n int) {
	t.Helper()
	var buf strings.Builder
	for i := start; i < start+n; i++ {
		fmt.Fprintf(&buf, "line%04d\r\n", i)
	}
	if err := engine.Write([]byte(buf.String())); err != nil {
		t.Fatal(err)
	}
}

func fillScreenStable(t *testing.T, engine *terminalengine.Engine, rows int) {
	t.Helper()
	var buf strings.Builder
	for row := 1; row <= rows; row++ {
		fmt.Fprintf(&buf, "\x1b[%d;1Hrow%02d stable", row, row)
	}
	if err := engine.Write([]byte(buf.String())); err != nil {
		t.Fatal(err)
	}
}

func regionScrollLines(t *testing.T, engine *terminalengine.Engine, count int) {
	t.Helper()
	var buf strings.Builder
	buf.WriteString("\x1b[1;2r")
	for i := 0; i < count; i++ {
		buf.WriteString("\x1b[2;1HX\r\n")
	}
	buf.WriteString("\x1b[r")
	if err := engine.Write([]byte(buf.String())); err != nil {
		t.Fatal(err)
	}
}

func TestProjector_HistoryStateCarriesExtentAndBodylessPushes(t *testing.T) {
	engine, _, projector := newHistoryRig(t, 24, 20)
	fillScreenStable(t, engine, 24)

	var deriver FrameDeriver
	baseline := projector.ExportState(0, 1)
	if len(baseline.History.Lines) != 0 {
		t.Fatalf("baseline carried %d history bodies", len(baseline.History.Lines))
	}
	deriver.deriveAndSeedForTest(baseline)

	const count = 7
	regionScrollLines(t, engine, count)
	state := projector.ExportState(0, 2)
	patch := deriver.deriveAndSeedForTest(state)
	if patch.Kind != terminalengine.FramePatch {
		t.Fatalf("kind=%v, want patch", patch.Kind)
	}
	if len(patch.History.Lines) != 0 {
		t.Fatalf("patch carried %d history bodies", len(patch.History.Lines))
	}
	if len(patch.HistoryPushes) != count {
		t.Fatalf("pushes=%d, want %d", len(patch.HistoryPushes), count)
	}
	for index, push := range patch.HistoryPushes {
		if index > 0 && push.HistorySeq <= patch.HistoryPushes[index-1].HistorySeq {
			t.Fatalf("pushes not strictly increasing: %+v", patch.HistoryPushes)
		}
	}
	if state.History.LastIncludedHistorySeq != patch.History.LastIncludedHistorySeq {
		t.Fatalf("patch extent=%+v, state extent=%+v", patch.History, state.History)
	}
}

func TestProjector_TailPopUsesExtentRollbackWithoutSnapshotBarrier(t *testing.T) {
	engine, scrollback, projector := newHistoryRig(t, 5, 20)
	writeScrollLines(t, engine, 0, 20)

	var deriver FrameDeriver
	before := projector.ExportState(0, 1)
	deriver.deriveAndSeedForTest(before)
	scrollback.Pop()

	after := projector.ExportState(0, 2)
	if after.ForceSnapshot {
		t.Fatal("ordinary tail Pop must not create a snapshot barrier")
	}
	patch := deriver.deriveAndSeedForTest(after)
	if patch.Kind != terminalengine.FramePatch {
		t.Fatalf("kind=%v, want extent-only patch", patch.Kind)
	}
	if patch.History.LastIncludedHistorySeq+1 != before.History.LastIncludedHistorySeq {
		t.Fatalf("lastSeq %d -> %d, want rollback by one",
			before.History.LastIncludedHistorySeq, patch.History.LastIncludedHistorySeq)
	}
	if len(patch.HistoryPushes) != 0 || len(patch.History.Lines) != 0 {
		t.Fatalf("tail Pop emitted payload: pushes=%+v lines=%+v",
			patch.HistoryPushes, patch.History.Lines)
	}
}

func TestProjector_HistoryRangeExportsExactArbitraryBounds(t *testing.T) {
	_, scrollback, projector := newHistoryRig(t, 5, 20)
	for seq := 1; seq <= 1100; seq++ {
		scrollback.Push(headlessterm.ScrollbackLine{
			LineID: uint64(10_000 + seq), LineVersion: 3,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}

	result := projector.HistoryRange(937, 1012)
	if result.HistoryGeneration != scrollback.Generation() {
		t.Fatalf("generation=%d, want %d", result.HistoryGeneration, scrollback.Generation())
	}
	if len(result.Lines) != 76 {
		t.Fatalf("lines=%d, want 76", len(result.Lines))
	}
	if result.Lines[0].HistorySeq != 937 || result.Lines[len(result.Lines)-1].HistorySeq != 1012 {
		t.Fatalf("range=%d..%d, want 937..1012",
			result.Lines[0].HistorySeq, result.Lines[len(result.Lines)-1].HistorySeq)
	}
}
