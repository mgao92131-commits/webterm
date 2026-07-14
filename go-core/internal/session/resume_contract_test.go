package session

// 本文件冻结 screen 增量恢复的 Go 初始同步契约
// （docs/superpowers/plans/2026-07-14-screen-state-delta-resume.md Task 4）。

import (
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generated"
	"webterm/go-core/internal/terminalsession"
)

func TestResumeContract_ColdHelloGetsSnapshot(t *testing.T) {
	terminal, _ := newScreenTestTerminal(t)
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	client.handleBinary(resumeHello(false, "", 0, 0))
	if env := waitInitialSyncEnvelope(t, client); env.GetSnapshot() == nil {
		t.Fatalf("cold hello payload=%T, want snapshot", env.Payload)
	}
}

func TestResumeContract_InstanceMismatchGetsSnapshot(t *testing.T) {
	terminal, _ := newScreenTestTerminal(t)
	info := terminal.ScreenRuntime().Info()
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	client.handleBinary(resumeHello(true, "stale-instance", info.LayoutEpoch, info.ScreenRevision))
	if env := waitInitialSyncEnvelope(t, client); env.GetSnapshot() == nil {
		t.Fatalf("instance mismatch payload=%T, want snapshot", env.Payload)
	}
}

func TestResumeContract_EpochMismatchGetsSnapshot(t *testing.T) {
	terminal, _ := newScreenTestTerminal(t)
	info := terminal.ScreenRuntime().Info()
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	client.handleBinary(resumeHello(true, info.InstanceID, info.LayoutEpoch+1, info.ScreenRevision))
	if env := waitInitialSyncEnvelope(t, client); env.GetSnapshot() == nil {
		t.Fatalf("epoch mismatch payload=%T, want snapshot", env.Payload)
	}
}

func TestResumeContract_FutureRevisionGetsSnapshot(t *testing.T) {
	terminal, _ := newScreenTestTerminal(t)
	info := terminal.ScreenRuntime().Info()
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	client.handleBinary(resumeHello(true, info.InstanceID, info.LayoutEpoch, info.ScreenRevision+1))
	if env := waitInitialSyncEnvelope(t, client); env.GetSnapshot() == nil {
		t.Fatalf("future revision payload=%T, want snapshot", env.Payload)
	}
}

func TestResumeContract_BarrierCrossedGetsSnapshot(t *testing.T) {
	terminal, ptyOut := newScreenTestTerminal(t)
	runtime := terminal.ScreenRuntime()
	base := runtime.Info()
	cold := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	cold.handleBinary(resumeHello(false, "", 0, 0))
	waitInitialSyncEnvelope(t, cold)
	terminal.DetachScreenClient(cold.screenClientID)

	// main -> alternate buffer 是持久 snapshot barrier。
	if _, err := ptyOut.Write([]byte("\x1b[?1049h")); err != nil {
		t.Fatal(err)
	}
	waitForRevisionAfter(t, runtime, base.ScreenRevision)
	resumed := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	resumed.handleBinary(resumeHello(true, base.InstanceID, base.LayoutEpoch, base.ScreenRevision))
	if env := waitInitialSyncEnvelope(t, resumed); env.GetSnapshot() == nil {
		t.Fatalf("barrier resume payload=%T, want snapshot", env.Payload)
	}
}

func TestResumeContract_ExactResumeGetsResumeAck(t *testing.T) {
	terminal, _ := newScreenTestTerminal(t)
	info := terminal.ScreenRuntime().Info()
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	client.handleBinary(resumeHello(true, info.InstanceID, info.LayoutEpoch, info.ScreenRevision))
	env := waitInitialSyncEnvelope(t, client)
	ack := env.GetResumeAck()
	if ack == nil {
		t.Fatalf("exact resume payload=%T, want ResumeAck", env.Payload)
	}
	if ack.InstanceId != info.InstanceID || ack.LayoutEpoch != info.LayoutEpoch || ack.ScreenRevision != info.ScreenRevision {
		t.Fatalf("ack=%+v, want current version %+v", ack, info)
	}
}

func TestResumeContract_RuntimeKillSwitchForcesSnapshot(t *testing.T) {
	t.Setenv("WEBTERM_SCREEN_RESUME", "0")
	terminal, _ := newScreenTestTerminal(t)
	info := terminal.ScreenRuntime().Info()
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	client.handleBinary(resumeHello(true, info.InstanceID, info.LayoutEpoch, info.ScreenRevision))
	if env := waitInitialSyncEnvelope(t, client); env.GetSnapshot() == nil {
		t.Fatalf("disabled resume payload=%T, want snapshot", env.Payload)
	}
	metrics := terminal.ScreenRuntime().ResumeMetrics()
	if metrics.Snapshot != 1 || metrics.Exact != 0 || metrics.Patch != 0 {
		t.Fatalf("resume metrics=%+v, want one snapshot", metrics)
	}
}

func TestResumeContract_CumulativePatchSkipsIntermediateRevisions(t *testing.T) {
	terminal, ptyOut := newScreenTestTerminal(t)
	runtime := terminal.ScreenRuntime()
	base := runtime.Info()

	// 先让 Projector 在 base revision 建立权威投影与 barrier。
	cold := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	cold.handleBinary(resumeHello(false, "", 0, 0))
	if env := waitInitialSyncEnvelope(t, cold); env.GetSnapshot() == nil {
		t.Fatalf("baseline payload=%T, want snapshot", env.Payload)
	}
	terminal.DetachScreenClient(cold.screenClientID)

	if _, err := ptyOut.Write([]byte("x")); err != nil {
		t.Fatal(err)
	}
	waitForRevisionAfter(t, runtime, base.ScreenRevision)
	current := runtime.Info()

	resumed := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	resumed.handleBinary(resumeHello(true, base.InstanceID, base.LayoutEpoch, base.ScreenRevision))
	env := waitInitialSyncEnvelope(t, resumed)
	patch := env.GetPatch()
	if patch == nil {
		t.Fatalf("cumulative resume payload=%T, want patch", env.Payload)
	}
	if patch.BaseRevision != base.ScreenRevision || patch.ScreenRevision != current.ScreenRevision {
		t.Fatalf("patch base=%d revision=%d, want %d -> %d",
			patch.BaseRevision, patch.ScreenRevision, base.ScreenRevision, current.ScreenRevision)
	}
}

func TestResumeContract_AttachAndResyncDoNotAdvanceRevision(t *testing.T) {
	terminal, _ := newScreenTestTerminal(t)
	runtime := terminal.ScreenRuntime()
	before := runtime.Info()
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	client.handleBinary(resumeHello(false, "", 0, 0))
	waitInitialSyncEnvelope(t, client)
	if after := runtime.Info().ScreenRevision; after != before.ScreenRevision {
		t.Fatalf("attach advanced revision %d -> %d", before.ScreenRevision, after)
	}

	runtime.Resync(client.screenClientID)
	if env := waitInitialSyncEnvelope(t, client); env.GetSnapshot() == nil {
		t.Fatalf("resync payload=%T, want snapshot", env.Payload)
	}
	if after := runtime.Info().ScreenRevision; after != before.ScreenRevision {
		t.Fatalf("resync advanced revision %d -> %d", before.ScreenRevision, after)
	}
}

func resumeHello(hasProjection bool, instanceID string, epoch, revision uint64) []byte {
	hello := &pb.Hello{
		Version:        1,
		Cols:           20,
		Rows:           10,
		HasProjection:  hasProjection,
		InstanceId:     instanceID,
		LayoutEpoch:    epoch,
		ScreenRevision: revision,
	}
	if hasProjection {
		hello.Capabilities = &pb.CapabilitySet{RowPatches: true}
	}
	data, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload:         &pb.ScreenEnvelope_Hello{Hello: hello},
	})
	return data
}

func waitInitialSyncEnvelope(t *testing.T, client *Client) *pb.ScreenEnvelope {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		data := readClientBinary(t, client)
		var env pb.ScreenEnvelope
		if err := proto.Unmarshal(data, &env); err != nil {
			continue
		}
		if env.GetSnapshot() != nil || env.GetPatch() != nil || env.GetResumeAck() != nil {
			return &env
		}
	}
	t.Fatal("timed out waiting for initial screen sync")
	return nil
}

func waitForRevisionAfter(t *testing.T, runtime *terminalsession.Runtime, revision uint64) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if runtime.Info().ScreenRevision > revision {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("screen revision did not advance beyond %d", revision)
}
