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

func TestPatchDictionaryIsMessageLocalAndOnlyContainsReferencedEntries(t *testing.T) {
	frame := terminalengine.ScreenFrame{
		Kind: terminalengine.FramePatch, InstanceID: "i1", Epoch: 1,
		BaseRevision: 4, Seq: 5, Rows: 1, Cols: 1,
		Screen: []terminalengine.Line{{
			ID: 2, Version: 2,
			Runs: []terminalengine.CellRun{{Cells: []terminalengine.Cell{{
				Text: "x", Width: 1, StyleID: 5,
			}}}},
		}},
		Styles: []terminalengine.TerminalStyle{{ID: 5}, {ID: 6}},
	}
	wire, err := EncodeScreenPatch(frame, 3)
	if err != nil {
		t.Fatal(err)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(wire, &env); err != nil {
		t.Fatal(err)
	}
	styles := env.GetScreenPatch().GetDictionary().GetStyles()
	if len(styles) != 1 || styles[0].GetId() != 5 {
		t.Fatalf("patch dictionary styles = %+v, want only id 5", styles)
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
