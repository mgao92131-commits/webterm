package session

import (
	"context"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generatedv3"
)

func TestScreenV2HelloGetsBaselineFromWriter(t *testing.T) {
	terminal, ptyOut := newScreenTestTerminal(t)
	if _, err := ptyOut.Write([]byte("v2-ready")); err != nil {
		t.Fatal(err)
	}
	time.Sleep(100 * time.Millisecond)

	socket := &testSocket{protocolName: "webterm.screen.v3", writes: make(chan []byte, 8)}
	client := newTestTerminalChannelRuntime(socket, terminal, ClientModeScreen)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go client.run(ctx)
	hello, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 3,
		Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{
			DesiredGeometry: &pb.Geometry{Rows: 10, Cols: 20},
		}},
	})
	client.handleBinary(hello)

	select {
	case wire := <-socket.writes:
		var env pb.ScreenEnvelope
		if err := proto.Unmarshal(wire, &env); err != nil {
			t.Fatal(err)
		}
		baseline := env.GetBaseline()
		if baseline == nil || baseline.GetInstanceId() == "" {
			t.Fatalf("writer did not produce authoritative Baseline: %+v", &env)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for screen.v2 Baseline")
	}
}
