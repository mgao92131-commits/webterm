package session

import (
	"fmt"
	"testing"

	"google.golang.org/protobuf/proto"

	pb "webterm/go-core/internal/screenprotocol/generatedv3"
	"webterm/go-core/internal/terminalengine"
	"webterm/go-core/internal/terminalsession"
)

func TestInitialResumeSyncComparesActualCommitAndBodylessPreserveBaseline(t *testing.T) {
	state := resumeSyncState(200, 80, 128)
	small := state
	small.Kind = terminalengine.FramePatch
	small.BaseRevision = 1
	small.Seq = 2
	small.Screen = []terminalengine.Line{state.Screen[199]}
	small.Screen[0].Version++

	client := &terminalChannelRuntime{}
	payload, kind, err := client.encodeInitialScreenSync(terminalsession.InitialSync{
		State: state, Projection: small,
	})
	if err != nil {
		t.Fatal(err)
	}
	if kind != "commit" {
		t.Fatalf("small resume candidate kind=%q, want commit", kind)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(payload, &env); err != nil {
		t.Fatal(err)
	}
	if env.GetTerminalCommit() == nil {
		t.Fatal("small resume candidate did not encode TerminalCommit")
	}

	large := state
	large.Kind = terminalengine.FramePatch
	large.BaseRevision = 1
	large.Seq = 2
	large.Screen = append([]terminalengine.Line(nil), state.Screen...)
	for i := range large.Screen {
		large.Screen[i].Version++
	}
	payload, kind, err = client.encodeInitialScreenSync(terminalsession.InitialSync{
		State: state, Projection: large,
	})
	if err != nil {
		t.Fatal(err)
	}
	if kind != "commit" {
		t.Fatalf("large resume candidate kind=%q, want commit with catalog-bearing baseline cost", kind)
	}
	env.Reset()
	if err := proto.Unmarshal(payload, &env); err != nil {
		t.Fatal(err)
	}
	if env.GetTerminalCommit() == nil {
		t.Fatal("large resume candidate did not encode TerminalCommit")
	}
}

func TestBaselineNeverCarriesHistoryBodiesAndDoesNotMutateCanonicalState(t *testing.T) {
	state := resumeSyncState(2, 20, 100)
	client := &terminalChannelRuntime{}
	payload, kind, err := client.encodeInitialScreenSync(terminalsession.InitialSync{Projection: state})
	if err != nil {
		t.Fatal(err)
	}
	if kind != "baseline" {
		t.Fatalf("kind=%q, want baseline", kind)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(payload, &env); err != nil {
		t.Fatal(err)
	}
	if env.GetBaseline().GetHistoryExtent().GetLastSeq() != 100 {
		t.Fatalf("extent=%+v", env.GetBaseline().GetHistoryExtent())
	}
	if got := len(state.History.Lines); got != 100 {
		t.Fatalf("per-client encoding mutated canonical history: %d", got)
	}
}

func resumeSyncState(rows, cols, historyLines int) terminalengine.ScreenFrame {
	state := terminalengine.ScreenFrame{
		Kind: terminalengine.FrameSnapshot, SessionID: "s", InstanceID: "i",
		Epoch: 1, Seq: 2, Rows: rows, Cols: cols,
		ActiveBuffer:         terminalengine.BufferMain,
		DictionaryGeneration: 1, HistoryGeneration: 1,
	}
	for row := 0; row < rows; row++ {
		state.Screen = append(state.Screen, terminalengine.Line{
			ID: uint64(row + 1), Version: 1, Row: row, PhysicalColumns: cols,
			Runs: []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{
				{Text: fmt.Sprintf("row-%02d", row), Width: 1},
			}}},
		})
	}
	state.History = terminalengine.HistoryWindow{
		FirstAvailableHistorySeq: 1,
		FirstIncludedHistorySeq:  1,
		LastIncludedHistorySeq:   uint64(historyLines),
	}
	for seq := 1; seq <= historyLines; seq++ {
		state.History.Lines = append(state.History.Lines, terminalengine.Line{
			ID: uint64(1000 + seq), Version: 1, HistorySeq: uint64(seq), Row: -1,
			PhysicalColumns: cols,
			Runs: []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{
				{Text: fmt.Sprintf("history-%03d", seq), Width: 1},
			}}},
		})
		state.ScrollbackLineage = append(
			state.ScrollbackLineage,
			terminalengine.HistoryPush{
				HistorySeq:  uint64(seq),
				LineID:      uint64(1000 + seq),
				LineVersion: 1,
			})
	}
	return state
}
