package control

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/testutil"
)

func TestConnectionTestDirectAvailable(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	application := app.New(config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0"},
	}, "test")

	result := testConnection(context.Background(), application.Status(), application.Config(), false)
	if !result.OK || result.Mode != config.ModeDirect {
		t.Fatalf("result = %#v, want direct ok", result)
	}
}

func TestConnectionTestDirectOccupied(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	defer listener.Close()

	application := app.New(config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: listener.Addr().String()},
	}, "test")

	result := testConnection(context.Background(), application.Status(), application.Config(), false)
	if result.OK {
		t.Fatalf("result = %#v, want occupied failure", result)
	}
}

func TestConnectionTestDirectAlreadyListening(t *testing.T) {
	cfg := config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:18080"},
	}
	application := app.New(cfg, "test")
	application.SetDirectListening(true)

	result := testConnection(context.Background(), application.Status(), cfg, false)
	if !result.OK || result.Message != "direct addr is already listening" {
		t.Fatalf("result = %#v, want already listening", result)
	}
}

func TestConnectionTestRelayValidation(t *testing.T) {
	application := app.New(config.Config{
		Mode:  config.ModeRelay,
		Relay: config.RelayConfig{URL: "https://relay.example", Secret: "secret"},
	}, "test")

	result := testConnection(context.Background(), application.Status(), application.Config(), false)
	if !result.OK || result.Live {
		t.Fatalf("result = %#v, want relay validation ok without live", result)
	}

	cfg := application.Config()
	cfg.Relay.Secret = ""
	result = testConnection(context.Background(), application.Status(), cfg, false)
	if result.OK || result.Error == "" {
		t.Fatalf("result = %#v, want missing secret error", result)
	}
}

func TestConnectionTestRelayLiveRegistration(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	ctx := context.Background()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Fatalf("Accept: %v", err)
		}
		defer conn.Close(websocket.StatusNormalClosure, "")
		_, data, err := conn.Read(ctx)
		if err != nil {
			t.Fatalf("Read: %v", err)
		}
		var register map[string]any
		if err := json.Unmarshal(data, &register); err != nil {
			t.Fatalf("decode register: %v", err)
		}
		if register["type"] != agentRegisterMessage || register["credential"] != "secret" || register["test"] != true {
			t.Fatalf("unexpected register: %#v", register)
		}
		bytes, _ := json.Marshal(map[string]any{"type": agentRegisteredMessage, "deviceId": "test-device"})
		if err := conn.Write(ctx, websocket.MessageText, bytes); err != nil {
			t.Fatalf("Write: %v", err)
		}
	}))
	defer server.Close()

	application := app.New(config.Config{
		Mode:  config.ModeRelay,
		Relay: config.RelayConfig{URL: "ws" + server.URL[len("http"):], Secret: "secret"},
	}, "test")

	result := testConnection(context.Background(), application.Status(), application.Config(), true)
	if !result.OK || !result.Live {
		t.Fatalf("result = %#v, want live relay ok", result)
	}
}

func TestControlConnectionTestEndpoint(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	application := app.New(config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0"},
	}, "test")
	server := New("127.0.0.1:0", application)

	request := httptest.NewRequest(http.MethodPost, "/control/connection/test", bytes.NewBufferString(`{"mode":"direct"}`))
	response := httptest.NewRecorder()
	server.handleConnectionTest(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d body=%s", response.Code, response.Body.String())
	}
	var result connectionTestResult
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatalf("decode result: %v", err)
	}
	if !result.OK || result.Mode != config.ModeDirect {
		t.Fatalf("result = %#v, want direct ok", result)
	}
}

func TestRelayAgentWebSocketURL(t *testing.T) {
	tests := map[string]string{
		"http://relay.example":       "ws://relay.example/ws/agent",
		"https://relay.example/base": "wss://relay.example/base/ws/agent",
		"ws://relay.example/":        "ws://relay.example/ws/agent",
	}
	for input, want := range tests {
		got, err := relayAgentWebSocketURL(input)
		if err != nil {
			t.Fatalf("relayAgentWebSocketURL(%q) returned error: %v", input, err)
		}
		if got != want {
			t.Fatalf("relayAgentWebSocketURL(%q) = %q, want %q", input, got, want)
		}
	}
}
