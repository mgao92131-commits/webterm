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

func TestResumeCommitDoesNotRepeatClientContiguousHistoryTail(t *testing.T) {
	engine, _, projector := newHistoryRig(t, 3, 20)
	writeScrollLines(t, engine, 0, 12)
	old := projector.ExportState(1, 3)
	if old.History.LastIncludedHistorySeq == 0 {
		t.Fatal("test requires history")
	}
	token := resumeTokenFor(old)

	writeScrollLines(t, engine, 12, 5)
	result := projector.Resume(token, 1, 9)
	if result.Kind != ResumeCommit {
		t.Fatalf("kind=%v, want ResumeCommit", result.Kind)
	}
	for _, line := range result.Frame.History.Lines {
		if line.HistorySeq <= token.ContiguousHistoryTailLastSeq {
			t.Fatalf("resume repeated cached history seq %d (tail ended at %d)",
				line.HistorySeq, token.ContiguousHistoryTailLastSeq)
		}
	}
	if len(result.Frame.History.Lines) == 0 {
		t.Fatal("resume omitted all newly appended history")
	}
}

func TestResumeCommitWithCurrentTailSendsNoHistoryBody(t *testing.T) {
	engine, _, projector := newHistoryRig(t, 3, 20)
	writeScrollLines(t, engine, 0, 12)
	old := projector.ExportState(1, 3)
	token := resumeTokenFor(old)

	// Change an active row without scrolling history.
	if err := engine.Write([]byte("\x1b[1;1Hchanged")); err != nil {
		t.Fatal(err)
	}
	result := projector.Resume(token, 1, 8)
	if result.Kind != ResumeCommit {
		t.Fatalf("kind=%v, want ResumeCommit", result.Kind)
	}
	if got := len(result.Frame.History.Lines); got != 0 {
		t.Fatalf("resume repeated %d cached history lines", got)
	}
}

func TestResumeEmptyHistoryTailMaySendNewBodiesAndPromotionsRemainBodyless(t *testing.T) {
	engine, _, projector := newHistoryRig(t, 3, 20)
	old := projector.ExportState(1, 1)
	token := resumeTokenFor(old)
	writeScrollLines(t, engine, 0, 8)

	result := projector.Resume(token, 1, 7)
	if result.Kind != ResumeCommit {
		t.Fatalf("kind=%v, want ResumeCommit", result.Kind)
	}
	if len(result.Frame.History.Lines) == 0 {
		t.Fatal("empty client history should permit bounded new history bodies")
	}
	bodyIDs := make(map[uint64]struct{}, len(result.Frame.History.Lines))
	for _, line := range result.Frame.History.Lines {
		bodyIDs[line.ID] = struct{}{}
	}
	for _, promotion := range result.Frame.HistoryPromotions {
		if _, duplicate := bodyIDs[promotion.LineID]; duplicate {
			t.Fatalf("promotion line %d also repeated as body", promotion.LineID)
		}
	}
}

func TestResumeTailOutsideCurrentExtentUsesCompatibleBaseline(t *testing.T) {
	engine, _, projector := newHistoryRig(t, 3, 20)
	writeScrollLines(t, engine, 0, 8)
	state := projector.ExportState(1, 3)
	token := resumeTokenFor(state)
	token.ContiguousHistoryTailLastSeq = state.History.LastIncludedHistorySeq + 1

	result := projector.Resume(token, 1, 4)
	if result.Kind != ResumeBaseline || !result.State.PreserveCompatibleHistory {
		t.Fatalf("invalid tail result=%+v, want compatible baseline", result)
	}

	token = resumeTokenFor(state)
	token.HistoryGeneration++
	result = projector.Resume(token, 1, 4)
	if result.Kind != ResumeBaseline || result.State.PreserveCompatibleHistory {
		t.Fatalf("generation mismatch result=%+v, want reset baseline", result)
	}
}
