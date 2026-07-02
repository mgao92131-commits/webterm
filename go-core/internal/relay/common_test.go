package relay

import (
	"encoding/json"
	"net/http"
	"testing"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/session"
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

func TestV2RouteMemoryAPISessionCRUD(t *testing.T) {
	cfg := config.Config{
		Mode:  config.ModeRelay,
		Relay: config.RelayConfig{URL: "ws://relay.example", Secret: "secret"},
		Shell: config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	client := NewV2(cfg.Relay, application)

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
