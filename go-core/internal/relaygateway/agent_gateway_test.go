package relaygateway

import (
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
	"webterm/go-core/internal/testutil"
)

func TestAgentGatewayRegistersPresenceAndRemovesOnClose(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	store := relaystore.NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	device, credential, err := store.CreateDevice(user.ID, "Stored Name")
	if err != nil {
		t.Fatalf("CreateDevice returned error: %v", err)
	}
	registry := relayrouter.NewRegistry()
	gateway := NewAgentGateway(store, registry, relayrouter.NewStreamManager())
	gateway.heartbeatInterval = 20 * time.Millisecond
	gateway.heartbeatTimeout = time.Second
	server := httptest.NewServer(gateway)
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, handshakeResponse, err := websocket.Dial(ctx, wsURL(server.URL), &websocket.DialOptions{
		CompressionMode: websocket.CompressionNoContextTakeover,
	})
	if err != nil {
		t.Fatalf("Dial returned error: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	if handshakeResponse == nil || !strings.Contains(handshakeResponse.Header.Get("Sec-WebSocket-Extensions"), "permessage-deflate") {
		t.Fatalf("permessage-deflate was not negotiated: %#v", handshakeResponse)
	}

	writeJSON(t, ctx, conn, map[string]any{
		"type":       AgentRegisterMessage,
		"credential": credential,
		"deviceName": "Runtime Name",
	})
	response := readJSON(t, ctx, conn)
	if response["type"] != AgentRegisteredMessage || response["deviceId"] != device.ID {
		t.Fatalf("registered response = %#v", response)
	}

	presence, ok := registry.GetAgentForUser(user.ID, device.ID)
	if !ok {
		t.Fatalf("presence was not registered")
	}
	if presence.DeviceName != "Runtime Name" || !presence.Online {
		t.Fatalf("presence = %#v, want runtime online presence", presence)
	}
	time.Sleep(60 * time.Millisecond)
	if _, ok := registry.GetAgentForUser(user.ID, device.ID); !ok {
		t.Fatalf("presence was removed while heartbeat should keep healthy connection alive")
	}

	if err := conn.Close(websocket.StatusNormalClosure, "test done"); err != nil {
		t.Fatalf("close websocket: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, ok := registry.GetAgentForUser(user.ID, device.ID); !ok {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("presence was not removed after close")
}

func TestAgentGatewayRejectsInvalidCredential(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	store := relaystore.NewMemoryStore()
	registry := relayrouter.NewRegistry()
	server := httptest.NewServer(NewAgentGateway(store, registry, relayrouter.NewStreamManager()))
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, wsURL(server.URL), nil)
	if err != nil {
		t.Fatalf("Dial returned error: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	writeJSON(t, ctx, conn, map[string]any{
		"type":       AgentRegisterMessage,
		"credential": "wrong",
	})
	response := readJSON(t, ctx, conn)
	if response["type"] != AgentErrorMessage {
		t.Fatalf("error response = %#v, want %s", response, AgentErrorMessage)
	}
}

func TestWebSocketAgentSenderQueueBackpressureAndClose(t *testing.T) {
	sender := &websocketAgentSender{
		queue:  make(chan []byte, 1),
		closed: make(chan struct{}),
	}
	frame := relaycore.NewFrame(relaycore.FrameTypePing, "s1", 0, []byte("one"))
	if err := sender.SendFrame(context.Background(), frame); err != nil {
		t.Fatalf("first SendFrame returned error: %v", err)
	}
	if err := sender.SendFrame(context.Background(), frame); !errors.Is(err, relaycore.ErrBackpressure) {
		t.Fatalf("full queue SendFrame error = %v, want ErrBackpressure", err)
	}
	sender.Close()
	if err := sender.SendFrame(context.Background(), frame); !errors.Is(err, relaycore.ErrConnectionClosed) {
		t.Fatalf("closed SendFrame error = %v, want ErrConnectionClosed", err)
	}
}

func wsURL(httpURL string) string {
	return "ws" + strings.TrimPrefix(httpURL, "http")
}

func writeJSON(t *testing.T, ctx context.Context, conn *websocket.Conn, value any) {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if err := conn.Write(ctx, websocket.MessageText, data); err != nil {
		t.Fatalf("write json: %v", err)
	}
}

func readJSON(t *testing.T, ctx context.Context, conn *websocket.Conn) map[string]any {
	t.Helper()
	messageType, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read json: %v", err)
	}
	if messageType != websocket.MessageText {
		t.Fatalf("message type = %v, want text", messageType)
	}
	var out map[string]any
	if err := json.Unmarshal(data, &out); err != nil {
		t.Fatalf("decode json: %v", err)
	}
	return out
}
