package session

import (
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/terminalengine"
)

func TestScreenV2HelloGetsBaselineWithMatchingGeneration(t *testing.T) {
	terminal, ptyOut := newScreenTestTerminal(t)
	if _, err := ptyOut.Write([]byte("v2-ready")); err != nil {
		t.Fatal(err)
	}
	time.Sleep(100 * time.Millisecond)

	client := newTestTerminalChannelRuntime(
		&testSocket{protocolName: "webterm.screen.v2"}, terminal, ClientModeScreen)
	hello, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{
			ClientInstanceId: "android-1",
			DesiredMode:      pb.ScreenStreamMode_SCREEN_STREAM_MODE_LIVE,
			StreamGeneration: 7,
			DesiredGeometry:  &pb.Geometry{Rows: 10, Cols: 20},
		}},
	})
	client.handleBinary(hello)

	deadline := time.After(time.Second)
	for {
		select {
		case message := <-client.send:
			var env pb.ScreenEnvelope
			if proto.Unmarshal(message.binary, &env) != nil || env.GetBaseline() == nil {
				continue
			}
			if env.GetBaseline().GetStreamGeneration() != 7 {
				t.Fatalf("generation = %d, want 7", env.GetBaseline().GetStreamGeneration())
			}
			if env.GetBaseline().GetInstanceId() == "" {
				t.Fatal("Baseline must carry terminal identity")
			}
			return
		case <-deadline:
			t.Fatal("timed out waiting for screen.v2 Baseline")
		}
	}
}

func TestScreenV2FrozenSuppressesProjectionUntilNewLiveBaseline(t *testing.T) {
	terminal, ptyOut := newScreenTestTerminal(t)
	client := newTestTerminalChannelRuntime(
		&testSocket{protocolName: "webterm.screen.v2"}, terminal, ClientModeScreen)
	sendV2Envelope(t, client, &pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{
			ClientInstanceId: "android-2",
			DesiredMode:      pb.ScreenStreamMode_SCREEN_STREAM_MODE_LIVE,
			StreamGeneration: 1,
			DesiredGeometry:  &pb.Geometry{Rows: 10, Cols: 20},
		}},
	})
	waitV2Payload(t, client, time.Second, func(env *pb.ScreenEnvelope) bool {
		return env.GetBaseline() != nil
	})

	sendV2Envelope(t, client, &pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_SetStreamMode{SetStreamMode: &pb.SetStreamMode{
			Mode:             pb.ScreenStreamMode_SCREEN_STREAM_MODE_FROZEN,
			StreamGeneration: 2,
		}},
	})
	if _, err := ptyOut.Write([]byte("output-while-frozen")); err != nil {
		t.Fatal(err)
	}
	foundTail := false
	until := time.After(400 * time.Millisecond)
collect:
	for {
		select {
		case message := <-client.send:
			var env pb.ScreenEnvelope
			if proto.Unmarshal(message.binary, &env) != nil {
				continue
			}
			if env.GetScreenPatch() != nil || env.GetBaseline() != nil {
				t.Fatal("FROZEN client received a projection frame")
			}
			if env.GetTailStatus() != nil && env.GetTailStatus().GetStreamGeneration() == 2 {
				foundTail = true
			}
		case <-until:
			break collect
		}
	}
	if !foundTail {
		t.Fatal("FROZEN client did not receive TailStatus")
	}

	sendV2Envelope(t, client, &pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_SetStreamMode{SetStreamMode: &pb.SetStreamMode{
			Mode:             pb.ScreenStreamMode_SCREEN_STREAM_MODE_LIVE,
			StreamGeneration: 3,
		}},
	})
	waitV2Payload(t, client, time.Second, func(env *pb.ScreenEnvelope) bool {
		return env.GetBaseline() != nil && env.GetBaseline().GetStreamGeneration() == 3
	})
}

func TestFrozenTailStatusCoalescesToLatestPerClient(t *testing.T) {
	terminal, _ := newScreenTestTerminal(t)
	client := newTestTerminalChannelRuntime(
		&testSocket{protocolName: "webterm.screen.v2"}, terminal, ClientModeScreen)
	client.writerStarted.Store(true)

	client.sendTailStatus("i1", 1, 2, 10,
		terminalengine.HistoryExtent{FirstSeq: 1, LastSeq: 10})
	client.sendTailStatus("i1", 1, 2, 12,
		terminalengine.HistoryExtent{FirstSeq: 3, LastSeq: 12})

	client.tailMu.Lock()
	pending := append([]byte(nil), client.tailPending...)
	has := client.tailHas
	client.tailMu.Unlock()
	if !has {
		t.Fatal("latest TailStatus was not retained")
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(pending, &env); err != nil {
		t.Fatal(err)
	}
	status := env.GetTailStatus()
	if status.GetLatestScreenRevision() != 12 ||
		status.GetLatestHistoryExtent().GetFirstSeq() != 3 ||
		status.GetLatestHistoryExtent().GetLastSeq() != 12 {
		t.Fatalf("coalesced TailStatus = %+v, want latest revision/extent", status)
	}
}

func sendV2Envelope(t *testing.T, client *terminalChannelRuntime, env *pb.ScreenEnvelope) {
	t.Helper()
	wire, err := proto.Marshal(env)
	if err != nil {
		t.Fatal(err)
	}
	client.handleBinary(wire)
}

func waitV2Payload(t *testing.T, client *terminalChannelRuntime, timeout time.Duration,
	match func(*pb.ScreenEnvelope) bool) {
	t.Helper()
	deadline := time.After(timeout)
	for {
		select {
		case message := <-client.send:
			var env pb.ScreenEnvelope
			if proto.Unmarshal(message.binary, &env) == nil && match(&env) {
				return
			}
		case <-deadline:
			t.Fatal("timed out waiting for screen.v2 payload")
		}
	}
}
