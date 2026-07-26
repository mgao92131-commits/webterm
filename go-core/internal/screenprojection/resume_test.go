package screenprojection

import (
	"testing"

	"webterm/go-core/internal/terminalengine"
)

func resumeTokenFor(state terminalengine.ScreenFrame) *ResumeToken {
	rows := make([]ResumeScreenLine, len(state.Screen))
	for i, line := range state.Screen {
		rows[i] = ResumeScreenLine{LineID: line.ID, LineVersion: line.Version}
	}
	first, last := state.History.FirstAvailableHistorySeq, state.History.LastIncludedHistorySeq
	if last < first {
		first, last = 0, 0
	}
	return &ResumeToken{
		InstanceID: state.InstanceID, LayoutEpoch: state.Epoch,
		ScreenRevision: state.Seq, DictionaryGeneration: state.DictionaryGeneration,
		HistoryGeneration: state.HistoryGeneration, ActiveBuffer: state.ActiveBuffer,
		ContiguousHistoryTailFirstSeq: first, ContiguousHistoryTailLastSeq: last,
		ActiveRows: rows,
	}
}

func TestResumeUnchangedIsAccepted(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(100, nil)
	engine := terminalengine.NewEngine(3, 12, sb)
	if err := engine.Write([]byte("ready")); err != nil {
		t.Fatal(err)
	}
	projector := NewProjector(engine, sb, "session", "instance")
	state := projector.ExportState(1, 7)

	result := projector.Resume(resumeTokenFor(state), 1, 7)
	if result.Kind != ResumeAccepted {
		t.Fatalf("kind=%v, want ResumeAccepted", result.Kind)
	}
}

func TestResumeChangedCanSkipRevisionsWithCommit(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(100, nil)
	engine := terminalengine.NewEngine(3, 12, sb)
	if err := engine.Write([]byte("a")); err != nil {
		t.Fatal(err)
	}
	projector := NewProjector(engine, sb, "session", "instance")
	old := projector.ExportState(1, 3)
	token := resumeTokenFor(old)
	if err := engine.Write([]byte("b")); err != nil {
		t.Fatal(err)
	}
	projector.ExportState(1, 4)

	result := projector.Resume(token, 1, 9)
	if result.Kind != ResumeCommit {
		t.Fatalf("kind=%v, want ResumeCommit", result.Kind)
	}
	if result.Frame.BaseRevision != 3 || result.Frame.Seq != 9 {
		t.Fatalf("revision %d..%d, want 3..9", result.Frame.BaseRevision, result.Frame.Seq)
	}
}

func TestResumeInvalidIdentityAndMalformedTailUseBaseline(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(100, nil)
	engine := terminalengine.NewEngine(3, 12, sb)
	projector := NewProjector(engine, sb, "session", "instance")
	state := projector.ExportState(1, 1)

	identity := resumeTokenFor(state)
	identity.ActiveRows[0].LineID = 0
	if got := projector.Resume(identity, 1, 2).Kind; got != ResumeBaseline {
		t.Fatalf("invalid active identity kind=%v, want baseline", got)
	}

	tail := resumeTokenFor(state)
	tail.ContiguousHistoryTailFirstSeq = 10
	tail.ContiguousHistoryTailLastSeq = 0
	if got := projector.Resume(tail, 1, 2).Kind; got != ResumeBaseline {
		t.Fatalf("malformed tail kind=%v, want baseline", got)
	}
}
