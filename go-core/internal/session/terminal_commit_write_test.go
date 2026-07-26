package session

import (
	"context"
	"errors"
	"testing"

	"webterm/go-core/internal/terminalengine"
)

type terminalCommitFailingSink struct{}

func (terminalCommitFailingSink) WriteFrame(context.Context, []byte, bool) error {
	return errors.New("write failed")
}

type terminalCommitRecordingSink struct {
	writes int
}

func (s *terminalCommitRecordingSink) WriteFrame(context.Context, []byte, bool) error {
	s.writes++
	return nil
}

func TestTerminalCommitWriteFailureDoesNotAdvanceBaseline(t *testing.T) {
	baseline := terminalengine.ScreenFrame{
		Version: 1, InstanceID: "i", Epoch: 1, Seq: 1, Rows: 1, Cols: 1,
		Screen:  []terminalengine.Line{{ID: 1, Version: 1, Row: 0}},
		History: terminalengine.HistoryWindow{FirstAvailableHistorySeq: 1},
	}
	next := baseline
	next.Seq = 2
	next.Screen = []terminalengine.Line{{ID: 1, Version: 2, Row: 0}}

	client := newOwnedTerminalChannelRuntime(nil, terminalCommitFailingSink{}, "")
	client.streamGeneration.Store(1)
	client.screenDeriver.Seed(baseline)
	client.screenPending = next
	client.hasScreenData = true
	if client.writeLatestScreenState(context.Background()) {
		t.Fatal("commit write unexpectedly succeeded")
	}

	frame := client.screenDeriver.DeriveForState(next)
	if frame.Kind != terminalengine.FramePatch || frame.BaseRevision != 1 || frame.Seq != 2 {
		t.Fatalf("failed write advanced baseline: kind=%d base=%d revision=%d",
			frame.Kind, frame.BaseRevision, frame.Seq)
	}
}

func TestTerminalCommitEncodeFailureSeedsOnlyAfterBaselineFallbackWrite(t *testing.T) {
	baseline := terminalengine.ScreenFrame{
		Version: 1, InstanceID: "i", Epoch: 1, Seq: 1, Rows: 1, Cols: 1,
		Screen:  []terminalengine.Line{{ID: 1, Version: 1, Row: 0}},
		History: terminalengine.HistoryWindow{FirstAvailableHistorySeq: 1},
	}
	next := baseline
	next.Seq = 2
	next.Screen = []terminalengine.Line{{ID: 1, Version: 2, Row: 0}}

	sink := &terminalCommitRecordingSink{}
	client := newOwnedTerminalChannelRuntime(nil, sink, "")
	client.streamGeneration.Store(1)
	client.screenDeriver.Seed(baseline)
	client.screenPending = next
	client.hasScreenData = true
	encodeCalls := 0
	client.encodeFrame = func(frame terminalengine.ScreenFrame) ([]byte, error) {
		encodeCalls++
		if encodeCalls == 1 {
			return nil, errors.New("commit encode failed")
		}
		if frame.Kind != terminalengine.FrameSnapshot || frame.Seq != next.Seq {
			t.Fatalf("fallback frame kind=%d revision=%d", frame.Kind, frame.Seq)
		}
		return []byte{1}, nil
	}

	if !client.writeLatestScreenState(context.Background()) {
		t.Fatal("baseline fallback write failed")
	}
	if sink.writes != 1 {
		t.Fatalf("physical writes=%d, want 1", sink.writes)
	}
	if frame := client.screenDeriver.DeriveForState(next); frame.Kind != 0 {
		t.Fatalf("fallback write did not seed state: kind=%d", frame.Kind)
	}
}
