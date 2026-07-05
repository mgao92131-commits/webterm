package session

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestScreenClientSendsSnapshotOnHello(t *testing.T) {
	terminal := newTestTerminal()
	terminal.PushOutput([]byte("hello"))
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)

	client.handleHello(0, 20, 4)

	state := readClientJSON(t, client)
	if state["type"] != "screen-state" {
		t.Fatalf("message type = %#v, want screen-state", state["type"])
	}
	snapshot, ok := state["snapshot"].(map[string]any)
	if !ok {
		t.Fatalf("snapshot missing: %#v", state)
	}
	lines, ok := snapshot["lines"].([]any)
	if !ok || len(lines) == 0 {
		t.Fatalf("snapshot lines missing: %#v", snapshot)
	}
	firstLine := lines[0].(map[string]any)
	if firstLine["text"] != "hello" {
		t.Fatalf("first line text = %#v, want hello", firstLine["text"])
	}

	info := readClientJSON(t, client)
	if info["type"] != "info" {
		t.Fatalf("second message type = %#v, want info", info["type"])
	}
}

func TestScreenClientSendsDirtyDeltaOnOutput(t *testing.T) {
	terminal := newTestTerminal()
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	client.ready.Store(true)
	terminal.clients[client] = struct{}{}

	frame := terminal.PushOutput([]byte("ab"))
	terminal.broadcastOutput(frame)

	message := readClientJSON(t, client)
	if message["type"] != "screen-delta" {
		t.Fatalf("message type = %#v, want screen-delta", message["type"])
	}
	if message["seq"].(float64) != float64(frame.Seq) {
		t.Fatalf("seq = %#v, want %d", message["seq"], frame.Seq)
	}
	cells := message["cells"].([]any)
	if len(cells) < 2 {
		t.Fatalf("expected at least two dirty cells; got %#v", cells)
	}
	first := cells[0].(map[string]any)
	if first["row"].(float64) != 0 || first["col"].(float64) != 0 || first["char"] != "a" {
		t.Fatalf("unexpected first dirty cell: %#v", first)
	}
}

func newTestTerminal() *TerminalSession {
	return &TerminalSession{
		id:        "s1",
		instance:  "i1",
		name:      "test",
		status:    StatusRunning,
		cols:      20,
		rows:      4,
		createdAt: time.Now().UTC(),
		activeAt:  time.Now().UTC(),
		ring:      NewEventRing(0, 0),
		screen:    NewScreenState(4, 20, nil, nil),
		clients:   make(map[*Client]struct{}),
	}
}

func readClientJSON(t *testing.T, client *Client) map[string]any {
	t.Helper()
	select {
	case message := <-client.send:
		if message.text == nil {
			t.Fatalf("expected text message, got binary=%v", message.binary)
		}
		var decoded map[string]any
		if err := json.Unmarshal(message.text, &decoded); err != nil {
			t.Fatalf("decode message: %v; data=%s", err, message.text)
		}
		return decoded
	case <-time.After(time.Second):
		t.Fatalf("timed out waiting for client message")
		return nil
	}
}

type testSocket struct {
	protocolName string
}

func (socket *testSocket) Read(context.Context) (MessageType, []byte, error) {
	return 0, nil, context.Canceled
}

func (socket *testSocket) Write(context.Context, MessageType, []byte) error {
	return nil
}

func (socket *testSocket) Close() error {
	return nil
}

func (socket *testSocket) Subprotocol() string {
	return socket.protocolName
}
