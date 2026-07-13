package relay

import (
	"bytes"
	"encoding/json"
	"net/http"
	"testing"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/fileupload"
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

func TestNewV2InjectsFileUploadService(t *testing.T) {
	cfg := config.Config{
		Mode:  config.ModeRelay,
		Relay: config.RelayConfig{URL: "ws://relay.example", Secret: "secret"},
		Shell: config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	client := NewV2(cfg.Relay, application)

	header := http.Header{}
	header.Set("X-File-Name", "demo.txt")
	header.Set("X-File-Size", "4")
	result, err := client.router.RouteHTTPv2(
		http.MethodPost,
		"/api/sessions/missing/upload",
		header,
		bytes.NewReader([]byte("data")),
	)
	if err != nil {
		t.Fatalf("upload route returned error: %v", err)
	}
	if result.StatusCode != http.StatusNotFound {
		t.Fatalf("upload status = %d, want 404; body=%s", result.StatusCode, result.Data)
	}
	var response struct {
		Code string `json:"code"`
	}
	if err := json.Unmarshal(result.Data, &response); err != nil {
		t.Fatalf("decode upload response: %v", err)
	}
	if response.Code != string(fileupload.CodeSessionNotFound) {
		t.Fatalf("upload code = %q, want %q", response.Code, fileupload.CodeSessionNotFound)
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

	status, body, err := client.router.RouteHTTP(http.MethodPost, "/api/sessions", []byte(`{"name":"relay-test","cwd":"."}`))
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

	status, body, err = client.router.RouteHTTP(http.MethodGet, "/api/sessions", nil)
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

	status, body, err = client.router.RouteHTTP(http.MethodPatch, "/api/sessions/s1?device=d1", []byte(`{"name":"renamed"}`))
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

	status, _, err = client.router.RouteHTTP(http.MethodDelete, "/api/sessions/s1", nil)
	if err != nil {
		t.Fatalf("DELETE returned error: %v", err)
	}
	if status != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204", status)
	}
}
