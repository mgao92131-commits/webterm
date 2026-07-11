package screenprotocol

import (
	"testing"

	"google.golang.org/protobuf/proto"
	"webterm/go-core/internal/screenprojection"
	pb "webterm/go-core/internal/screenprotocol/generated"
	"webterm/go-core/internal/terminalengine"
)

func TestEncodeFrame_SnapshotRoundTrip(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	engine.Write([]byte("hello\nworld\n"))

	frame := screenprojection.ExportSnapshot(engine, sb, "s1", "i1", 0, 1)
	data, err := EncodeFrame(frame)
	if err != nil {
		t.Fatal(err)
	}

	var envelope pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &envelope); err != nil {
		t.Fatal(err)
	}

	snapshot, ok := envelope.Payload.(*pb.ScreenEnvelope_Snapshot)
	if !ok {
		t.Fatalf("expected snapshot, got %T", envelope.Payload)
	}
	if snapshot.Snapshot.SessionId != "s1" {
		t.Fatalf("session id mismatch")
	}
	if len(snapshot.Snapshot.Screen) != 5 {
		t.Fatalf("expected 5 rows, got %d", len(snapshot.Snapshot.Screen))
	}
}

func TestEncodeFrame_PatchRoundTrip(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	engine.Write([]byte("hello\n"))

	frame := screenprojection.ExportSnapshot(engine, sb, "s1", "i1", 0, 1)
	frame.BaseRevision = 1
	frame.Seq = 2
	frame.Screen = frame.Screen[:1]
	frame.Screen[0].Row = 3

	data, err := EncodeFrame(frame)
	if err != nil {
		t.Fatal(err)
	}

	var envelope pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &envelope); err != nil {
		t.Fatal(err)
	}

	patch, ok := envelope.Payload.(*pb.ScreenEnvelope_Patch)
	if !ok {
		t.Fatalf("expected patch, got %T", envelope.Payload)
	}
	if patch.Patch.BaseRevision != 1 {
		t.Fatalf("base revision mismatch")
	}
	if patch.Patch.InstanceId != "i1" {
		t.Fatalf("instance id mismatch: %q", patch.Patch.InstanceId)
	}
	if got := patch.Patch.ScreenRows[0].Row; got != 3 {
		t.Fatalf("patch row mismatch: got %d want 3", got)
	}
}
