package screenprotocolv2

import (
	"testing"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/terminalengine"
)

func TestEncodeBaselineCarriesIndependentHistoryExtentAndGeneration(t *testing.T) {
	frame := terminalengine.ScreenFrame{
		Kind: terminalengine.FrameSnapshot, SessionID: "s1", InstanceID: "i1",
		Epoch: 3, Seq: 9, Rows: 1, Cols: 2,
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: 4,
			LastIncludedHistorySeq:   3,
		},
		Screen: []terminalengine.Line{{
			ID: 7, Version: 1,
			Runs: []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{
				{Text: "x", Width: 1}, {Text: " ", Width: 1},
			}}},
		}},
	}
	wire, err := EncodeBaseline(frame, 5)
	if err != nil {
		t.Fatal(err)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(wire, &env); err != nil {
		t.Fatal(err)
	}
	baseline := env.GetBaseline()
	if env.GetProtocolVersion() != 2 || baseline.GetStreamGeneration() != 5 {
		t.Fatalf("version/generation = %d/%d", env.GetProtocolVersion(), baseline.GetStreamGeneration())
	}
	if got := baseline.GetHistoryExtent(); got.GetFirstSeq() != 4 || got.GetLastSeq() != 3 {
		t.Fatalf("empty extent = %d..%d, want 4..3", got.GetFirstSeq(), got.GetLastSeq())
	}
	if baseline.GetScreenLines()[0].GetRuns()[0].GetCells()[0].GetText() != "x" {
		t.Fatal("baseline line content was not encoded")
	}
}

func TestHandlerValidatesClosedHistoryRange(t *testing.T) {
	called := false
	handler := NewHandler(WithHistoryRangeCallback(func(req *pb.HistoryRangeRequest) {
		called = req.GetFromSeq() == 10 && req.GetToSeq() == 20
	}))
	env := &pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_HistoryRangeRequest{
			HistoryRangeRequest: &pb.HistoryRangeRequest{
				RequestId: "r1", InstanceId: "i1", LayoutEpoch: 2,
				FromSeq: 10, ToSeq: 20,
			},
		},
	}
	wire, _ := proto.Marshal(env)
	if err := handler.HandleMessage(wire); err != nil {
		t.Fatal(err)
	}
	if !called {
		t.Fatal("history range callback was not called")
	}
	env.GetHistoryRangeRequest().ToSeq = 300
	wire, _ = proto.Marshal(env)
	if err := handler.HandleMessage(wire); err == nil {
		t.Fatal("range over 256 lines must be rejected")
	}
}

func TestHandlerRequiresExplicitFrozenIdentity(t *testing.T) {
	env := &pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{
			ClientInstanceId: "c1", StreamGeneration: 1,
			DesiredMode:         pb.ScreenStreamMode_SCREEN_STREAM_MODE_FROZEN,
			HasFrozenProjection: true,
			DesiredGeometry:     &pb.Geometry{Rows: 24, Cols: 80},
		}},
	}
	wire, _ := proto.Marshal(env)
	if err := NewHandler().HandleMessage(wire); err == nil {
		t.Fatal("frozen projection without instance/epoch must be rejected")
	}
}

func TestCommitDictionaryIsMessageLocalAndOnlyContainsReferencedEntries(t *testing.T) {
	frame := terminalengine.ScreenFrame{
		Kind: terminalengine.FramePatch, InstanceID: "i1", Epoch: 1,
		BaseRevision: 4, Seq: 5, Rows: 1, Cols: 1,
		Screen: []terminalengine.Line{{
			ID: 2, Version: 2, Row: 0,
			Runs: []terminalengine.CellRun{{Cells: []terminalengine.Cell{{
				Text: "x", Width: 1, StyleID: 5,
			}}}},
		}},
		Styles: []terminalengine.TerminalStyle{{ID: 5}, {ID: 6}},
	}
	wire, err := EncodeTerminalCommit(frame, 3)
	if err != nil {
		t.Fatal(err)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(wire, &env); err != nil {
		t.Fatal(err)
	}
	styles := env.GetTerminalCommit().GetDictionary().GetStyles()
	if len(styles) != 1 || styles[0].GetId() != 5 {
		t.Fatalf("commit dictionary styles = %+v, want only id 5", styles)
	}
}

func TestEncodeRetryableHistoryRangeCarriesBackoff(t *testing.T) {
	wire, err := EncodeHistoryRangeResponse("r1", "i1", 2, terminalengine.HistoryRangeData{
		Status:       terminalengine.HistoryRangeRetryable,
		Extent:       terminalengine.HistoryExtent{FirstSeq: 10, LastSeq: 20},
		RetryAfterMS: 375,
	})
	if err != nil {
		t.Fatal(err)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(wire, &env); err != nil {
		t.Fatal(err)
	}
	response := env.GetHistoryRangeResponse()
	if response.GetStatus() != pb.HistoryRangeStatus_HISTORY_RANGE_STATUS_RETRYABLE ||
		response.GetRetryAfterMs() != 375 {
		t.Fatalf("retry response = status:%v delay:%d",
			response.GetStatus(), response.GetRetryAfterMs())
	}
}

func TestEncodeTerminalCommitCarriesScreenAndHistoryAtomically(t *testing.T) {
	frame := terminalengine.ScreenFrame{
		Kind: terminalengine.FramePatch, InstanceID: "i1", Epoch: 1,
		BaseRevision: 4, Seq: 9, Rows: 3, Cols: 1,
		ScreenScroll: &terminalengine.ScreenScroll{TopRow: 0, BottomRowExclusive: 3, DeltaRows: 1},
		Screen:       []terminalengine.Line{{ID: 9, Version: 1, Row: 2}},
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: 1, LastIncludedHistorySeq: 1000,
			Lines: []terminalengine.Line{{ID: 8, Version: 1, HistorySeq: 1000}},
		},
		FirstAvailableHistorySeqChanged: true,
	}
	wire, err := EncodeTerminalCommit(frame, 3)
	if err != nil {
		t.Fatal(err)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(wire, &env); err != nil {
		t.Fatal(err)
	}
	commit := env.GetTerminalCommit()
	if commit == nil || commit.GetBaseRevision() != 4 || commit.GetRevision() != 9 {
		t.Fatalf("commit revision missing: %+v", commit)
	}
	if commit.GetScreen().GetScroll().GetDeltaRows() != 1 || len(commit.GetScreen().GetWrites()) != 1 {
		t.Fatalf("screen mutation missing: %+v", commit.GetScreen())
	}
	if commit.GetHistory().GetFinalExtent().GetLastSeq() != 1000 ||
		len(commit.GetHistory().GetAppendedLines()) != 1 {
		t.Fatalf("history mutation missing: %+v", commit.GetHistory())
	}
}

func TestTerminalCommitAndBaselineDoNotCarryTitleOrWorkingDirectory(t *testing.T) {
	frame := terminalengine.ScreenFrame{
		Kind: terminalengine.FramePatch, InstanceID: "i1", Epoch: 1,
		BaseRevision: 1, Seq: 2, Rows: 1, Cols: 1,
		Title: "secret-title", WorkingDir: "/secret/path",
		TitleChanged: true, WorkingDirChanged: true,
		CursorChanged: true,
	}
	wire, err := EncodeTerminalCommit(frame, 1)
	if err != nil {
		t.Fatal(err)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(wire, &env); err != nil {
		t.Fatal(err)
	}
	commitFields := env.GetTerminalCommit().ProtoReflect().Descriptor().Fields()
	if commitFields.ByName("title") != nil || commitFields.ByName("working_directory") != nil {
		t.Fatal("TerminalCommit schema unexpectedly carries title/cwd")
	}

	frame.Kind = terminalengine.FrameSnapshot
	frame.BaseRevision = 0
	wire, err = EncodeBaseline(frame, 1)
	if err != nil {
		t.Fatal(err)
	}
	if err := proto.Unmarshal(wire, &env); err != nil {
		t.Fatal(err)
	}
	baselineFields := env.GetBaseline().ProtoReflect().Descriptor().Fields()
	if baselineFields.ByName("title") != nil || baselineFields.ByName("working_directory") != nil {
		t.Fatal("Baseline schema unexpectedly carries title/cwd")
	}
}

func TestEncodeTerminalCommitBoundsHistoryBodiesButKeepsExtent(t *testing.T) {
	lines := make([]terminalengine.Line, 200)
	for i := range lines {
		lines[i] = terminalengine.Line{ID: uint64(i + 1), Version: 1, HistorySeq: uint64(i + 1)}
	}
	frame := terminalengine.ScreenFrame{
		Kind: terminalengine.FramePatch, InstanceID: "i1", Epoch: 1,
		BaseRevision: 1, Seq: 2, Rows: 1, Cols: 1,
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: 1, LastIncludedHistorySeq: 10000, Lines: lines,
		},
		FirstAvailableHistorySeqChanged: true,
	}
	wire, err := EncodeTerminalCommit(frame, 1)
	if err != nil {
		t.Fatal(err)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(wire, &env); err != nil {
		t.Fatal(err)
	}
	history := env.GetTerminalCommit().GetHistory()
	if history.GetFinalExtent().GetLastSeq() != 10000 || len(history.GetAppendedLines()) != 128 {
		t.Fatalf("bounded history=%d extent=%d, want 128/10000",
			len(history.GetAppendedLines()), history.GetFinalExtent().GetLastSeq())
	}
}
