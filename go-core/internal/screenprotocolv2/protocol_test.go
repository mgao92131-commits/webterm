package screenprotocolv2

import (
	"strings"
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

func TestEncodeBaselineNeverCarriesHistoryBodies(t *testing.T) {
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
	wire, err := EncodeBaseline(frame, 0)
	if err != nil {
		t.Fatal(err)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(wire, &env); err != nil {
		t.Fatal(err)
	}
	baseline := env.GetBaseline()
	if baseline.GetHistoryExtent().GetFirstSeq() != 1 ||
		baseline.GetHistoryExtent().GetLastSeq() != 200 {
		t.Fatalf("extent changed: %+v", baseline.GetHistoryExtent())
	}
	if baseline.ProtoReflect().Descriptor().Fields().ByName("history_tail") != nil {
		t.Fatal("Baseline schema still carries history_tail")
	}
	if len(frame.History.Lines) != 200 {
		t.Fatalf("shared canonical history mutated: %d", len(frame.History.Lines))
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

func TestHandlerRequiresCompleteResumeIdentity(t *testing.T) {
	env := &pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{
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

func TestHandlerAcceptsBestEffortInputWithoutDeliveryIdentity(t *testing.T) {
	called := false
	handler := NewHandler(WithInputCallback(func(input *pb.TerminalInput) {
		called = input.GetLeaseId() == "lease-1" && input.GetText().GetData() == "echo now\n"
	}))
	wire, err := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: ProtocolVersion,
		Payload: &pb.ScreenEnvelope_Input{Input: &pb.TerminalInput{
			LeaseId: "lease-1",
			Input:   &pb.TerminalInput_Text{Text: &pb.TextInput{Data: "echo now\n"}},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := handler.HandleMessage(wire); err != nil {
		t.Fatalf("best-effort input rejected: %v", err)
	}
	if !called {
		t.Fatal("best-effort input was not delivered to handler")
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

func TestEncodeTerminalCommitCarriesScreenAndHistoryAtomically(t *testing.T) {
	frame := terminalengine.ScreenFrame{
		Kind: terminalengine.FramePatch, InstanceID: "i1", Epoch: 1,
		BaseRevision: 4, Seq: 9, Rows: 3, Cols: 1,
		ScreenScroll: &terminalengine.ScreenScroll{TopRow: 0, BottomRowExclusive: 3, DeltaRows: 1},
		Screen:       []terminalengine.Line{{ID: 9, Version: 1, Row: 2}},
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: 1, LastIncludedHistorySeq: 1000,
		},
		HistoryPushes:                   []terminalengine.HistoryPush{{HistorySeq: 1000, LineID: 8, LineVersion: 1}},
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
		len(commit.GetHistory().GetPushes()) != 1 {
		t.Fatalf("history mutation missing: %+v", commit.GetHistory())
	}
	push := commit.GetHistory().GetPushes()[0]
	if push.GetHistorySeq() != 1000 || push.GetLineId() != 8 || push.GetLineVersion() != 1 {
		t.Fatalf("history push=%+v", push)
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

func TestEncodeTerminalCommitCarriesAllHistoryPushesWithoutBodies(t *testing.T) {
	pushes := make([]terminalengine.HistoryPush, 5000)
	for i := range pushes {
		pushes[i] = terminalengine.HistoryPush{
			HistorySeq: uint64(i + 1), LineID: uint64(i + 1001), LineVersion: 1,
		}
	}
	frame := terminalengine.ScreenFrame{
		Kind: terminalengine.FramePatch, InstanceID: "i1", Epoch: 1,
		BaseRevision: 1, Seq: 2, Rows: 1, Cols: 1,
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: 1, LastIncludedHistorySeq: 10000,
		},
		HistoryPushes:                   pushes,
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
	if history.GetFinalExtent().GetLastSeq() != 10000 || len(history.GetPushes()) != 5000 {
		t.Fatalf("encoded pushes=%d extent=%d, want 5000/10000",
			len(history.GetPushes()), history.GetFinalExtent().GetLastSeq())
	}
	if history.ProtoReflect().Descriptor().Fields().ByName("appended_lines") != nil {
		t.Fatal("HistoryMutation schema still carries appended_lines")
	}
}

func TestEncodeHistoryRangeHasIdentityPhysicalColumnsAndNoByteLimit(t *testing.T) {
	lines := make([]terminalengine.Line, 5000)
	for i := range lines {
		lines[i] = terminalengine.Line{
			ID: uint64(10_000 + i), Version: 1, HistorySeq: uint64(i + 1),
			PhysicalColumns: 200,
			Runs: []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{
				Text: strings.Repeat("x", 300), Width: 1,
			}}}},
		}
	}
	wire, err := EncodeHistoryRangeResponse(
		pb.HistoryRangeStatus_HISTORY_RANGE_STATUS_OK,
		terminalengine.HistoryRangeData{
			Status: terminalengine.HistoryRangeOK, InstanceID: "i1", LayoutEpoch: 7,
			HistoryGeneration: 3, Extent: terminalengine.HistoryExtent{FirstSeq: 1, LastSeq: 5000},
			Lines: lines,
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(wire) <= 1<<20 {
		t.Fatalf("wire=%d, want >1MiB", len(wire))
	}
	var response pb.HistoryRangeResponse
	if err := proto.Unmarshal(wire, &response); err != nil {
		t.Fatal(err)
	}
	if response.GetInstanceId() != "i1" || response.GetLayoutEpoch() != 7 ||
		len(response.GetLines()) != 5000 ||
		response.GetLines()[0].GetPhysicalColumns() != 200 {
		t.Fatalf("response identity/lines mismatch: instance=%q epoch=%d lines=%d columns=%d",
			response.GetInstanceId(), response.GetLayoutEpoch(), len(response.GetLines()),
			response.GetLines()[0].GetPhysicalColumns())
	}
}
