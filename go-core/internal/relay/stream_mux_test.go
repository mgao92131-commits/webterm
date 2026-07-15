package relay

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/application"
	coremux "webterm/go-core/internal/mux"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/session"
)

type noopFrameWriter struct{}

func (noopFrameWriter) writeFrame(context.Context, *websocket.Conn, relaycore.Frame) {}
func (noopFrameWriter) writeRaw(context.Context, *websocket.Conn, []byte) error      { return nil }

func streamOpenFrame(id string) relaycore.Frame {
	payload, _ := json.Marshal(relaycore.StreamRoute{
		Path:        "/ws/sessions",
		Subprotocol: protocol.MuxSubprotocol,
	})
	return relaycore.NewFrame(relaycore.FrameTypeStreamOpen, id, 0, payload)
}

func TestCloseRelayMuxStreamReleasesTerminalClient(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{})
	terminal, err := manager.Create(".")
	if err != nil {
		t.Fatalf("create terminal: %v", err)
	}
	defer manager.Close(terminal.ID())

	router := application.NewSessionRouterWithMux(manager, coremux.MuxServeAdapter)
	streams := NewStreamMultiplexer(router, noopFrameWriter{}, nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	streams.OpenStream(ctx, nil, streamOpenFrame("stream-real"))

	payload, err := json.Marshal(map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "term-real",
		"path":               "/ws/sessions/" + terminal.ID(),
		"protocols":          []string{protocol.ScreenSubprotocol},
	})
	if err != nil {
		t.Fatalf("marshal ws-connect: %v", err)
	}
	streams.DeliverWS(relaycore.NewFrame(
		relaycore.FrameTypeWSText, "stream-real", 0, payload,
	))
	waitRelayTerminalClients(t, terminal, 1)

	// Relay 的 stream close 必须一路关闭 mux 和 screen channel handler；如果这里
	// 回到 0，服务端固定超时本身不会在 Agent 端遗留旧 terminal client。
	streams.CloseStream("stream-real", false)
	waitRelayTerminalClients(t, terminal, 0)
}

func TestCloseAllForConnectionReleasesTerminalClients(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{})
	terminal, err := manager.Create(".")
	if err != nil {
		t.Fatalf("create terminal: %v", err)
	}
	defer manager.Close(terminal.ID())
	router := application.NewSessionRouterWithMux(manager, coremux.MuxServeAdapter)
	streams := NewStreamMultiplexer(router, noopFrameWriter{}, nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	streams.OpenStream(ctx, nil, streamOpenFrame("stream-physical"))
	payload, _ := json.Marshal(map[string]any{"type": protocol.WSConnect, "tunnelConnectionId": "term-physical", "path": "/ws/sessions/" + terminal.ID(), "protocols": []string{protocol.ScreenSubprotocol}})
	streams.DeliverWS(relaycore.NewFrame(relaycore.FrameTypeWSText, "stream-physical", 0, payload))
	waitRelayTerminalClients(t, terminal, 1)
	streams.CloseAllForConnection(nil)
	waitRelayTerminalClients(t, terminal, 0)
}

func waitRelayTerminalClients(t *testing.T, terminal interface{ Info() session.Info }, want int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if terminal.Info().Clients == want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("terminal clients = %d, want %d", terminal.Info().Clients, want)
}
