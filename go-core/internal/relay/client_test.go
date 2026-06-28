package relay

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
	"webterm/go-core/internal/testutil"
)

func TestAgentWebSocketURL(t *testing.T) {
	tests := map[string]string{
		"http://relay.example":       "ws://relay.example/ws/agent",
		"https://relay.example/base": "wss://relay.example/base/ws/agent",
		"ws://relay.example/":        "ws://relay.example/ws/agent",
	}
	for input, want := range tests {
		got, err := agentWebSocketURL(input)
		if err != nil {
			t.Fatalf("agentWebSocketURL(%q) returned error: %v", input, err)
		}
		if got != want {
			t.Fatalf("agentWebSocketURL(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestSelectedProtocolPrefersScreenThenBinary(t *testing.T) {
	if got := selectedProtocol([]string{protocol.JSONSubprotocol, protocol.ScreenSubprotocol, protocol.BinarySubprotocol}); got != protocol.ScreenSubprotocol {
		t.Fatalf("selectedProtocol with screen = %q, want %q", got, protocol.ScreenSubprotocol)
	}
	if got := selectedProtocol([]string{protocol.JSONSubprotocol, protocol.BinarySubprotocol}); got != protocol.BinarySubprotocol {
		t.Fatalf("selectedProtocol with binary = %q, want %q", got, protocol.BinarySubprotocol)
	}
	if got := selectedProtocol(nil); got != protocol.JSONSubprotocol {
		t.Fatalf("selectedProtocol nil = %q, want %q", got, protocol.JSONSubprotocol)
	}
}

func TestRouteMemoryAPISessionCRUD(t *testing.T) {
	cfg := config.Config{
		Mode:  config.ModeRelay,
		Relay: config.RelayConfig{URL: "ws://relay.example", Secret: "secret"},
		Shell: config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	client := New(cfg.Relay, application)

	status, body, err := client.routeMemoryAPI(http.MethodPost, "/api/sessions", []byte(`{"name":"relay-test","cwd":"."}`))
	if err != nil {
		t.Fatalf("POST /api/sessions returned error: %v", err)
	}
	if status != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201", status)
	}
	var created session.Info
	if err := json.Unmarshal(body, &created); err != nil {
		t.Fatalf("decode created session: %v", err)
	}
	if created.ID != "s1" {
		t.Fatalf("created id = %q, want s1", created.ID)
	}
	defer application.Sessions().Close(created.ID)

	status, body, err = client.routeMemoryAPI(http.MethodGet, "/api/sessions", nil)
	if err != nil {
		t.Fatalf("GET /api/sessions returned error: %v", err)
	}
	if status != http.StatusOK {
		t.Fatalf("GET status = %d, want 200", status)
	}
	var list []session.Info
	if err := json.Unmarshal(body, &list); err != nil {
		t.Fatalf("decode session list: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("list length = %d, want 1", len(list))
	}

	status, body, err = client.routeMemoryAPI(http.MethodPatch, "/api/sessions/s1?device=d1", []byte(`{"name":"renamed"}`))
	if err != nil {
		t.Fatalf("PATCH returned error: %v", err)
	}
	if status != http.StatusOK {
		t.Fatalf("PATCH status = %d, want 200", status)
	}
	var renamed session.Info
	if err := json.Unmarshal(body, &renamed); err != nil {
		t.Fatalf("decode renamed session: %v", err)
	}
	if renamed.Name != "renamed" {
		t.Fatalf("renamed name = %q, want renamed", renamed.Name)
	}

	status, _, err = client.routeMemoryAPI(http.MethodDelete, "/api/sessions/s1", nil)
	if err != nil {
		t.Fatalf("DELETE returned error: %v", err)
	}
	if status != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204", status)
	}
}

func TestRelayClientRegistersAndHandlesHTTPRequest(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var relayURL string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/ws/agent" {
			t.Fatalf("path = %s, want /ws/agent", r.URL.Path)
		}
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Fatalf("Accept: %v", err)
		}
		defer conn.Close(websocket.StatusNormalClosure, "")

		_, registerBytes, err := conn.Read(ctx)
		if err != nil {
			t.Fatalf("read register: %v", err)
		}
		var register map[string]any
		if err := json.Unmarshal(registerBytes, &register); err != nil {
			t.Fatalf("decode register: %v", err)
		}
		if register["type"] != protocol.AgentRegister || register["secret"] != "secret" {
			t.Fatalf("bad register: %#v", register)
		}
		writeRelayJSON(t, ctx, conn, map[string]any{"type": protocol.Registered, "deviceId": "d1"})
		writeRelayJSON(t, ctx, conn, map[string]any{
			"type":      protocol.HTTPRequest,
			"requestId": "req_1",
			"method":    http.MethodGet,
			"path":      "/api/sessions",
		})

		_, responseBytes, err := conn.Read(ctx)
		if err != nil {
			t.Fatalf("read response: %v", err)
		}
		var response map[string]any
		if err := json.Unmarshal(responseBytes, &response); err != nil {
			t.Fatalf("decode response: %v", err)
		}
		if response["type"] != protocol.HTTPResponse || response["requestId"] != "req_1" {
			t.Fatalf("bad response: %#v", response)
		}
		if response["bodyEncoding"] != "base64" {
			t.Fatalf("bodyEncoding = %#v", response["bodyEncoding"])
		}
		body, err := base64.StdEncoding.DecodeString(response["body"].(string))
		if err != nil {
			t.Fatalf("decode body: %v", err)
		}
		var sessions []session.Info
		if err := json.Unmarshal(body, &sessions); err != nil {
			t.Fatalf("decode sessions: %v", err)
		}
		cancel()
	}))
	defer server.Close()
	relayURL = "ws" + server.URL[len("http"):]

	cfg := config.Config{
		Mode:  config.ModeRelay,
		Relay: config.RelayConfig{URL: relayURL, Secret: "secret", DeviceName: "test-device"},
		Shell: config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	client := New(cfg.Relay, application)
	err := client.Run(ctx)
	if err != context.Canceled {
		t.Fatalf("Run returned %v, want context.Canceled", err)
	}
	status := application.Status()
	if status.Relay.DeviceID != "d1" {
		t.Fatalf("relay device id = %q, want d1", status.Relay.DeviceID)
	}
}

func TestRelayClientRejectsP2POfferQuickly(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Fatalf("Accept: %v", err)
		}
		defer conn.Close(websocket.StatusNormalClosure, "")

		readRelayJSON(t, ctx, conn)
		writeRelayJSON(t, ctx, conn, map[string]any{"type": protocol.Registered, "deviceId": "d1"})
		writeRelayJSON(t, ctx, conn, map[string]any{
			"type":     "p2p-offer",
			"from":     "client_1",
			"sdp":      "fake-offer",
			"username": "tester",
		})

		response := readRelayJSON(t, ctx, conn)
		if response["type"] != "p2p-unavailable" || response["to"] != "client_1" {
			t.Fatalf("bad p2p response: %#v", response)
		}
		if response["message"] == "" {
			t.Fatalf("p2p-unavailable missing message: %#v", response)
		}
		cancel()
	}))
	defer server.Close()

	cfg := config.Config{
		Mode:  config.ModeRelay,
		Relay: config.RelayConfig{URL: "ws" + server.URL[len("http"):], Secret: "secret", DeviceName: "test-device"},
		Shell: config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	client := New(cfg.Relay, application)
	err := client.Run(ctx)
	if err != context.Canceled {
		t.Fatalf("Run returned %v, want context.Canceled", err)
	}
}

func TestRelayClientHandlesManagerWebSocketTunnel(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Fatalf("Accept: %v", err)
		}
		defer conn.Close(websocket.StatusNormalClosure, "")
		readRelayJSON(t, ctx, conn)
		writeRelayJSON(t, ctx, conn, map[string]any{"type": protocol.Registered, "deviceId": "d1"})
		writeRelayJSON(t, ctx, conn, map[string]any{
			"type":               protocol.WSConnect,
			"tunnelConnectionId": "tc_manager",
			"path":               "/ws/sessions",
			"protocols":          []string{},
		})

		connected := readRelayJSON(t, ctx, conn)
		if connected["type"] != protocol.WSConnected {
			t.Fatalf("connected message = %#v", connected)
		}
		frame := readTunnelFrame(t, ctx, conn)
		if frame.MsgType != protocol.MsgTypeWSData || frame.ID != "tc_manager" || frame.ExtraByte != protocol.WSDataText {
			t.Fatalf("initial tunnel frame = %#v", frame)
		}
		if !strings.Contains(string(frame.Payload), `"type":"sessions"`) {
			t.Fatalf("initial manager payload = %s", frame.Payload)
		}

		writeRelayJSON(t, ctx, conn, map[string]any{
			"type":         protocol.HTTPRequest,
			"requestId":    "req_create",
			"method":       http.MethodPost,
			"path":         "/api/sessions",
			"body":         base64.StdEncoding.EncodeToString([]byte(`{"name":"relay-manager","cwd":"."}`)),
			"bodyEncoding": "base64",
		})
		readRelayJSON(t, ctx, conn) // http-response

		update := readTunnelFrame(t, ctx, conn)
		if update.ID != "tc_manager" || update.ExtraByte != protocol.WSDataText {
			t.Fatalf("manager update frame = %#v", update)
		}
		if !strings.Contains(string(update.Payload), `"type":"session"`) || !strings.Contains(string(update.Payload), "relay-manager") {
			t.Fatalf("manager update payload = %s", update.Payload)
		}
		cancel()
	}))
	defer server.Close()

	cfg := config.Config{
		Mode:  config.ModeRelay,
		Relay: config.RelayConfig{URL: "ws" + server.URL[len("http"):], Secret: "secret", DeviceName: "test-device"},
		Shell: config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	client := New(cfg.Relay, application)
	err := client.Run(ctx)
	if err != context.Canceled {
		t.Fatalf("Run returned %v, want context.Canceled", err)
	}
}

func TestRelayClientHandlesTerminalWebSocketTunnel(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Fatalf("Accept: %v", err)
		}
		defer conn.Close(websocket.StatusNormalClosure, "")
		readRelayJSON(t, ctx, conn)
		writeRelayJSON(t, ctx, conn, map[string]any{"type": protocol.Registered, "deviceId": "d1"})

		writeRelayJSON(t, ctx, conn, map[string]any{
			"type":         protocol.HTTPRequest,
			"requestId":    "req_create",
			"method":       http.MethodPost,
			"path":         "/api/sessions",
			"body":         base64.StdEncoding.EncodeToString([]byte(`{"name":"relay-terminal","cwd":"."}`)),
			"bodyEncoding": "base64",
		})
		readRelayJSON(t, ctx, conn) // http-response

		writeRelayJSON(t, ctx, conn, map[string]any{
			"type":               protocol.WSConnect,
			"tunnelConnectionId": "tc_terminal",
			"path":               "/ws/sessions/s1",
			"protocols":          []string{protocol.BinarySubprotocol},
		})
		connected := readRelayJSON(t, ctx, conn)
		if connected["type"] != protocol.WSConnected {
			t.Fatalf("connected message = %#v", connected)
		}

		helloPayload, _ := json.Marshal(map[string]any{"lastSeq": 0, "cols": 80, "rows": 24})
		helloFrame, err := protocol.EncodeTunnelFrame(
			protocol.MsgTypeWSData,
			"tc_terminal",
			protocol.WSDataBinary,
			append([]byte{protocol.MsgHello}, helloPayload...),
		)
		if err != nil {
			t.Fatalf("encode hello frame: %v", err)
		}
		if err := conn.Write(ctx, websocket.MessageBinary, helloFrame); err != nil {
			t.Fatalf("write hello frame: %v", err)
		}
		inputFrame, err := protocol.EncodeTunnelFrame(
			protocol.MsgTypeWSData,
			"tc_terminal",
			protocol.WSDataBinary,
			append([]byte{protocol.MsgInput}, []byte("printf WEBTERM_RELAY_WS_OK\\n\r")...),
		)
		if err != nil {
			t.Fatalf("encode input frame: %v", err)
		}
		if err := conn.Write(ctx, websocket.MessageBinary, inputFrame); err != nil {
			t.Fatalf("write input frame: %v", err)
		}

		deadline := time.Now().Add(8 * time.Second)
		for time.Now().Before(deadline) {
			frame := readTunnelFrame(t, ctx, conn)
			if frame.ID != "tc_terminal" || frame.ExtraByte != protocol.WSDataBinary {
				continue
			}
			payload := frame.Payload
			if len(payload) > 9 && payload[0] == protocol.MsgOutput {
				if strings.Contains(string(payload[9:]), "WEBTERM_RELAY_WS_OK") {
					cancel()
					return
				}
			}
		}
		t.Fatalf("did not receive WEBTERM_RELAY_WS_OK")
	}))
	defer server.Close()

	cfg := config.Config{
		Mode:  config.ModeRelay,
		Relay: config.RelayConfig{URL: "ws" + server.URL[len("http"):], Secret: "secret", DeviceName: "test-device"},
		Shell: config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	client := New(cfg.Relay, application)
	err := client.Run(ctx)
	if err != context.Canceled {
		t.Fatalf("Run returned %v, want context.Canceled", err)
	}
}

func writeRelayJSON(t *testing.T, ctx context.Context, conn *websocket.Conn, value any) {
	t.Helper()
	bytes, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if err := conn.Write(ctx, websocket.MessageText, bytes); err != nil {
		t.Fatalf("write relay json: %v", err)
	}
}

func readRelayJSON(t *testing.T, ctx context.Context, conn *websocket.Conn) map[string]any {
	t.Helper()
	messageType, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read relay json: %v", err)
	}
	if messageType != websocket.MessageText {
		t.Fatalf("message type = %v, want text", messageType)
	}
	var msg map[string]any
	if err := json.Unmarshal(data, &msg); err != nil {
		t.Fatalf("decode relay json: %v data=%s", err, data)
	}
	return msg
}

func readTunnelFrame(t *testing.T, ctx context.Context, conn *websocket.Conn) protocol.TunnelFrame {
	t.Helper()
	messageType, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read tunnel frame: %v", err)
	}
	if messageType != websocket.MessageBinary {
		t.Fatalf("message type = %v, want binary", messageType)
	}
	frame, err := protocol.DecodeTunnelFrame(data)
	if err != nil {
		t.Fatalf("decode tunnel frame: %v", err)
	}
	return frame
}
