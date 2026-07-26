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
		Epoch: 3, Seq: 9, Rows: 1, Cols: 2, DictionaryGeneration: 5, HistoryGeneration: 7,
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
	if env.GetProtocolVersion() != 2 || baseline.GetDictionaryGeneration() != 5 || baseline.GetHistoryGeneration() != 7 {
		t.Fatalf("version/dictionary/history generation = %d/%d/%d", env.GetProtocolVersion(), baseline.GetDictionaryGeneration(), baseline.GetHistoryGeneration())
	}
	if got := baseline.GetHistoryExtent(); got.GetFirstSeq() != 4 || got.GetLastSeq() != 3 {
		t.Fatalf("empty extent = %d..%d, want 4..3", got.GetFirstSeq(), got.GetLastSeq())
	}
	if string(baseline.GetScreenLines()[0].GetUtf8Text()) != "x" {
		t.Fatal("default trailing blank was not trimmed")
	}
}

func TestEncodeHistoryRangeStatusesAlwaysCarryGenerationAndExtent(t *testing.T) {
	extent := terminalengine.HistoryExtent{FirstSeq: 10, LastSeq: 20}
	staleWire, err := EncodeStaleHistoryRange("stale", "i1", 2, 7, extent)
	if err != nil {
		t.Fatal(err)
	}
	var staleEnv pb.ScreenEnvelope
	if err := proto.Unmarshal(staleWire, &staleEnv); err != nil {
		t.Fatal(err)
	}
	stale := staleEnv.GetHistoryRangeResponse()
	if stale.GetHistoryGeneration() != 7 ||
		stale.GetAvailableExtent().GetFirstSeq() != 10 ||
		stale.GetAvailableExtent().GetLastSeq() != 20 {
		t.Fatalf("stale response lost identity/extent: %+v", stale)
	}

	retryWire, err := EncodeHistoryRangeResponse("retry", "i1", 2,
		terminalengine.HistoryRangeData{
			Status: terminalengine.HistoryRangeRetryable, Extent: extent,
			RetryAfterMS: 250, HistoryGeneration: 7,
		})
	if err != nil {
		t.Fatal(err)
	}
	var retryEnv pb.ScreenEnvelope
	if err := proto.Unmarshal(retryWire, &retryEnv); err != nil {
		t.Fatal(err)
	}
	retry := retryEnv.GetHistoryRangeResponse()
	if retry.GetHistoryGeneration() != 7 ||
		retry.GetAvailableExtent().GetFirstSeq() != 10 ||
		retry.GetAvailableExtent().GetLastSeq() != 20 ||
		retry.GetRetryAfterMs() != 250 {
		t.Fatalf("retry response lost generation/extent/backoff: %+v", retry)
	}
}

func TestEncodeBaselineHonorsColdTailAndPreservePolicy(t *testing.T) {
	history := make([]terminalengine.Line, 200)
	for i := range history {
		seq := uint64(i + 1)
		history[i] = terminalengine.Line{
			ID: seq + 1000, Version: 1, HistorySeq: seq,
			Runs: []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{
				{Text: "x", Width: 1},
			}}},
		}
	}
	frame := terminalengine.ScreenFrame{
		Kind: terminalengine.FrameSnapshot, SessionID: "s", InstanceID: "i",
		Epoch: 1, Seq: 1, Rows: 1, Cols: 1,
		DictionaryGeneration: 1, HistoryGeneration: 1,
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: 1, FirstIncludedHistorySeq: 1,
			LastIncludedHistorySeq: 200, Lines: history,
		},
		Screen: []terminalengine.Line{{ID: 1, Version: 1}},
	}
	for _, tc := range []struct {
		name      string
		requested uint32
		want      int
	}{
		{"sixteen", 16, 16},
		{"sixty-four", 64, 64},
		{"clamped", 999, 128},
		{"default", 0, 128},
	} {
		t.Run(tc.name, func(t *testing.T) {
			wire, err := EncodeBaseline(frame, tc.requested)
			if err != nil {
				t.Fatal(err)
			}
			var env pb.ScreenEnvelope
			if err := proto.Unmarshal(wire, &env); err != nil {
				t.Fatal(err)
			}
			baseline := env.GetBaseline()
			if got := len(baseline.GetHistoryTail().GetLines()); got != tc.want {
				t.Fatalf("tail lines=%d, want %d", got, tc.want)
			}
			if baseline.GetHistoryExtent().GetFirstSeq() != 1 ||
				baseline.GetHistoryExtent().GetLastSeq() != 200 {
				t.Fatalf("extent changed by per-client tail policy: %+v",
					baseline.GetHistoryExtent())
			}
		})
	}
	if len(frame.History.Lines) != 200 {
		t.Fatalf("shared canonical history mutated: %d", len(frame.History.Lines))
	}
	short := frame
	short.History.Lines = short.History.Lines[:5]
	short.History.LastIncludedHistorySeq = 5
	wire, err := EncodeBaseline(short, 16)
	if err != nil {
		t.Fatal(err)
	}
	var shortEnv pb.ScreenEnvelope
	if err := proto.Unmarshal(wire, &shortEnv); err != nil {
		t.Fatal(err)
	}
	if got := len(shortEnv.GetBaseline().GetHistoryTail().GetLines()); got != 5 {
		t.Fatalf("short history tail=%d, want existing 5", got)
	}
	frame.PreserveCompatibleHistory = true
	wire, err = EncodeBaseline(frame, 16)
	if err != nil {
		t.Fatal(err)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(wire, &env); err != nil {
		t.Fatal(err)
	}
	if got := len(env.GetBaseline().GetHistoryTail().GetLines()); got != 0 {
		t.Fatalf("preserve baseline repeated %d history bodies", got)
	}
	if env.GetBaseline().GetHistoryExtent().GetLastSeq() != 200 {
		t.Fatal("preserve baseline lost authoritative extent")
	}
}

func TestEncodeLineTrimsOnlySemanticallyDefaultTrailingCells(t *testing.T) {
	line := terminalengine.Line{
		ID: 1, Version: 1,
		Runs: []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{
			{Text: "a", Width: 1},
			{Text: " ", Width: 1},
			{Text: "b", Width: 1},
			{Text: " ", Width: 1},
			{Text: " ", Width: 1},
		}}},
	}
	wire := encodeLine(line)
	if got := string(wire.GetUtf8Text()); got != "a b" {
		t.Fatalf("trimmed text=%q, want internal blank preserved", got)
	}
	if len(wire.GetGlyphMeta()) != 3 {
		t.Fatalf("glyph count=%d, want 3", len(wire.GetGlyphMeta()))
	}

	line.Runs[0].Cells[4].StyleID = 1
	styled := encodeLine(line)
	if got := string(styled.GetUtf8Text()); got != "a b  " {
		t.Fatalf("styled trailing blank was trimmed: %q", got)
	}
	if len(styled.GetStyleSpans()) != 1 ||
		styled.GetStyleSpans()[0].GetStartCol() != 4 ||
		styled.GetStyleSpans()[0].GetEndCol() != 5 {
		t.Fatalf("styled span=%+v", styled.GetStyleSpans())
	}

	line.Runs[0].Cells[4].StyleID = 0
	line.Runs[0].Cells[4].LinkID = 1
	linked := encodeLine(line)
	if got := string(linked.GetUtf8Text()); got != "a b  " {
		t.Fatalf("linked trailing blank was trimmed: %q", got)
	}
}

func TestEncodeLinePreservesWideEmojiCombiningAndColumnHoles(t *testing.T) {
	line := terminalengine.Line{
		ID: 1, Version: 1,
		Runs: []terminalengine.CellRun{
			{Col: 0, Cells: []terminalengine.Cell{
				{Text: "你", Width: 2},
			}},
			{Col: 3, Cells: []terminalengine.Cell{
				{Text: "e\u0301", Width: 1},
				{Text: "👩‍💻", Width: 2},
			}},
		},
	}
	wire := encodeLine(line)
	if got := string(wire.GetUtf8Text()); got != "你 e\u0301👩‍💻" {
		t.Fatalf("wide/hole/combining text=%q", got)
	}
	if len(wire.GetGlyphMeta()) != 4 {
		t.Fatalf("glyph metadata entries=%d, want 4", len(wire.GetGlyphMeta()))
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
				FromSeq: 10, ToSeq: 20, HistoryGeneration: 1,
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

func TestHandlerRequiresCompleteResumeIdentity(t *testing.T) {
	env := &pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{
			ClientInstanceId: "c1",
			Resume: &pb.ResumeToken{InstanceId: "i1", LayoutEpoch: 1, ScreenRevision: 2,
				DictionaryGeneration: 1, HistoryGeneration: 1, ActiveBuffer: pb.BufferKind_BUFFER_KIND_MAIN},
			DesiredGeometry: &pb.Geometry{Rows: 24, Cols: 80},
		}},
	}
	wire, _ := proto.Marshal(env)
	if err := NewHandler().HandleMessage(wire); err == nil {
		t.Fatal("resume token without active rows must be rejected")
	}
}

func TestCommitCarriesCanonicalDictionaryAdditions(t *testing.T) {
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
	styles := env.GetTerminalCommit().GetDictionaryAdditions().GetStyles()
	if len(styles) != 2 || styles[0].GetId() != 5 || styles[1].GetId() != 6 {
		t.Fatalf("commit dictionary additions = %+v, want ids 5 and 6", styles)
	}
}

func TestEncodeRetryableHistoryRangeCarriesBackoff(t *testing.T) {
	wire, err := EncodeHistoryRangeResponse("r1", "i1", 2, terminalengine.HistoryRangeData{
		Status:            terminalengine.HistoryRangeRetryable,
		Extent:            terminalengine.HistoryExtent{FirstSeq: 10, LastSeq: 20},
		RetryAfterMS:      375,
		HistoryGeneration: 9,
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

func TestEncodeTerminalCommitDoesNotSilentlyTruncateDerivedHistoryBodies(t *testing.T) {
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
	if history.GetFinalExtent().GetLastSeq() != 10000 || len(history.GetAppendedLines()) != 200 {
		t.Fatalf("encoded history=%d extent=%d, want 200/10000",
			len(history.GetAppendedLines()), history.GetFinalExtent().GetLastSeq())
	}
}
