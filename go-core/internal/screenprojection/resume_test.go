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
	return &ResumeToken{
		InstanceID: state.InstanceID, LayoutEpoch: state.Epoch,
		ScreenRevision: state.Seq, DictionaryGeneration: state.DictionaryGeneration,
		HistoryGeneration: state.HistoryGeneration, ActiveBuffer: state.ActiveBuffer,
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
	if got := projector.Resume(resumeTokenFor(state), 1, 7, false).Kind; got != ResumeAccepted {
		t.Fatalf("kind=%v, want ResumeAccepted", got)
	}
}

func TestResumeChangedCarriesOnlyNewHistoryPushes(t *testing.T) {
	engine, _, projector := newHistoryRig(t, 3, 20)
	writeScrollLines(t, engine, 0, 12)
	old := projector.ExportState(1, 3)
	token := resumeTokenFor(old)
	writeScrollLines(t, engine, 12, 5)

	result := projector.Resume(token, 1, 9, false)
	if result.Kind != ResumeCommit {
		t.Fatalf("kind=%v, want ResumeCommit", result.Kind)
	}
	if len(result.Frame.HistoryPushes) == 0 || len(result.Frame.History.Lines) != 0 {
		t.Fatalf("pushes=%d bodies=%d", len(result.Frame.HistoryPushes), len(result.Frame.History.Lines))
	}
	for _, push := range result.Frame.HistoryPushes {
		if push.HistorySeq <= old.History.LastIncludedHistorySeq {
			t.Fatalf("resume repeated old history position %d", push.HistorySeq)
		}
	}
}

func TestResumeInvalidIdentityOrGenerationUsesBaseline(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(100, nil)
	engine := terminalengine.NewEngine(3, 12, sb)
	projector := NewProjector(engine, sb, "session", "instance")
	state := projector.ExportState(1, 1)

	identity := resumeTokenFor(state)
	identity.ActiveRows[0].LineID = 0
	if got := projector.Resume(identity, 1, 2, false).Kind; got != ResumeBaseline {
		t.Fatalf("invalid active identity kind=%v", got)
	}
	generation := resumeTokenFor(state)
	generation.HistoryGeneration++
	if got := projector.Resume(generation, 1, 2, false).Kind; got != ResumeBaseline {
		t.Fatalf("stale generation kind=%v", got)
	}
}
