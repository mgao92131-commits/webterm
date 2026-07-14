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
	frame.Kind = terminalengine.FramePatch
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

// 帧类型只由 Kind 决定：patch 即使 BaseRevision 为 0 也按 patch 编码，
// snapshot 即使携带非零 BaseRevision 也按 snapshot 编码；Kind 未设置必须报错。
func TestEncodeFrame_KindDrivesFrameType(t *testing.T) {
	base := terminalengine.ScreenFrame{
		Version: 1, SessionID: "s1", InstanceID: "i1", Epoch: 1, Seq: 2,
		Rows: 5, Cols: 10,
	}

	patchFrame := base
	patchFrame.Kind = terminalengine.FramePatch
	patchFrame.BaseRevision = 0 // 旧惯例下会被误判为 snapshot
	data, err := EncodeFrame(patchFrame)
	if err != nil {
		t.Fatal(err)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		t.Fatal(err)
	}
	if env.GetPatch() == nil {
		t.Fatalf("Kind=FramePatch must encode as patch, got %T", env.Payload)
	}

	snapshotFrame := base
	snapshotFrame.Kind = terminalengine.FrameSnapshot
	snapshotFrame.BaseRevision = 7 // snapshot 的 base 不参与语义
	data, err = EncodeFrame(snapshotFrame)
	if err != nil {
		t.Fatal(err)
	}
	env = pb.ScreenEnvelope{}
	if err := proto.Unmarshal(data, &env); err != nil {
		t.Fatal(err)
	}
	if env.GetSnapshot() == nil {
		t.Fatalf("Kind=FrameSnapshot must encode as snapshot, got %T", env.Payload)
	}

	if _, err := EncodeFrame(base); err == nil {
		t.Fatal("Kind 未设置的帧必须报错")
	}
}

// patch 帧 title/cwd 三态：未变化（字段 absent）、变为空串（present 且为空）、
// 非空新值。
func TestEncodeFrame_PatchTitleWorkingDirPresence(t *testing.T) {
	encode := func(frame terminalengine.ScreenFrame) *pb.ScreenPatch {
		t.Helper()
		data, err := EncodeFrame(frame)
		if err != nil {
			t.Fatal(err)
		}
		var env pb.ScreenEnvelope
		if err := proto.Unmarshal(data, &env); err != nil {
			t.Fatal(err)
		}
		patch := env.GetPatch()
		if patch == nil {
			t.Fatalf("expected patch, got %T", env.Payload)
		}
		return patch
	}
	patchFrame := func() terminalengine.ScreenFrame {
		return terminalengine.ScreenFrame{
			Version: 1, Kind: terminalengine.FramePatch, InstanceID: "i1",
			Epoch: 1, Seq: 2, BaseRevision: 1, Rows: 5, Cols: 10,
		}
	}

	// 未变化：字段不出现在 wire 上。
	patch := encode(patchFrame())
	if patch.Title != nil || patch.WorkingDirectory != nil {
		t.Fatalf("unchanged title/cwd must be absent, got title=%v cwd=%v",
			patch.Title, patch.WorkingDirectory)
	}
	if patch.GetTitle() != "" || patch.GetWorkingDirectory() != "" {
		t.Fatal("absent optional fields must read as empty via getters")
	}

	// 变为空串：字段 present 且为空。
	frame := patchFrame()
	frame.TitleChanged = true
	frame.WorkingDirChanged = true
	patch = encode(frame)
	if patch.Title == nil || *patch.Title != "" {
		t.Fatalf("cleared title must be present and empty, got %v", patch.Title)
	}
	if patch.WorkingDirectory == nil || *patch.WorkingDirectory != "" {
		t.Fatalf("cleared cwd must be present and empty, got %v", patch.WorkingDirectory)
	}

	// 非空新值：字段 present 且值正确。
	frame = patchFrame()
	frame.Title = "new title"
	frame.TitleChanged = true
	frame.WorkingDir = "/tmp/work"
	frame.WorkingDirChanged = true
	patch = encode(frame)
	if patch.Title == nil || *patch.Title != "new title" {
		t.Fatalf("title mismatch: %v", patch.Title)
	}
	if patch.WorkingDirectory == nil || *patch.WorkingDirectory != "/tmp/work" {
		t.Fatalf("cwd mismatch: %v", patch.WorkingDirectory)
	}
}

// ResumeAck 在 Go 与 wire 间可正确 marshal/unmarshal。
func TestResumeAck_WireRoundTrip(t *testing.T) {
	data, err := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_ResumeAck{ResumeAck: &pb.ResumeAck{
			InstanceId:     "i1",
			LayoutEpoch:    3,
			ScreenRevision: 42,
		}},
	})
	if err != nil {
		t.Fatal(err)
	}

	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		t.Fatal(err)
	}
	ack := env.GetResumeAck()
	if ack == nil {
		t.Fatalf("expected resume ack, got %T", env.Payload)
	}
	if ack.GetInstanceId() != "i1" || ack.GetLayoutEpoch() != 3 || ack.GetScreenRevision() != 42 {
		t.Fatalf("resume ack mismatch: %+v", ack)
	}
}

// ResumeAck 是服务端→客户端专属消息；Go 服务端收到时与 inbound
// snapshot/patch 一样按协议错误处理。
func TestHandleMessage_ResumeAckInboundRejected(t *testing.T) {
	data, err := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_ResumeAck{ResumeAck: &pb.ResumeAck{
			InstanceId: "i1", LayoutEpoch: 1, ScreenRevision: 1,
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := NewHandler().HandleMessage(data); err == nil {
		t.Fatal("inbound ResumeAck must be rejected as unsupported payload")
	}
}
