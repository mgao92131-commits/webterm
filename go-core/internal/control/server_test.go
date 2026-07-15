package control

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
)

func TestControlStatus(t *testing.T) {
	cfg := config.Load(config.Options{})
	application := app.New(cfg, "test-version")
	server := New("127.0.0.1:0", application)

	request := httptest.NewRequest(http.MethodGet, "/control/status", nil)
	response := httptest.NewRecorder()
	server.handleStatus(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status code = %d, want 200", response.Code)
	}
	var body app.Status
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("failed to decode body: %v", err)
	}
	if body.Version != "test-version" {
		t.Fatalf("version = %q, want test-version", body.Version)
	}
}

func TestControlConfigRedactsSecrets(t *testing.T) {
	cfg := config.Config{
		Relay:   config.RelayConfig{Secret: "relay-secret"},
		Control: config.ControlConfig{Addr: "127.0.0.1:0"},
	}
	application := app.New(cfg, "test-version")
	server := New("127.0.0.1:0", application)

	request := httptest.NewRequest(http.MethodGet, "/control/config", nil)
	response := httptest.NewRecorder()
	server.handleConfig(response, request)

	body := response.Body.String()
	if body == "" {
		t.Fatalf("empty body")
	}
	var decoded config.Config
	if err := json.Unmarshal(response.Body.Bytes(), &decoded); err != nil {
		t.Fatalf("failed to decode body: %v", err)
	}
	if decoded.Relay.Secret != "********" {
		t.Fatalf("relay secret = %q, want redacted", decoded.Relay.Secret)
	}
}

func TestControlPutConfigPersistsAndMarksRestartRequired(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	cfg := config.Config{
		Relay:   config.RelayConfig{Secret: "relay-secret", DeviceName: "mac"},
		Control: config.ControlConfig{Addr: "127.0.0.1:0"},
	}
	application := app.New(cfg, "test-version")
	server := NewWithConfigPath("127.0.0.1:0", application, path)

	body := bytes.NewBufferString(`{
		"relay":{"url":"https://relay.example","secret":"********"}
	}`)
	request := httptest.NewRequest(http.MethodPut, "/control/config", body)
	response := httptest.NewRecorder()
	server.handleConfig(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status code = %d, want 200; body=%s", response.Code, response.Body.String())
	}
	var saved config.Config
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile saved config: %v", err)
	}
	if err := json.Unmarshal(data, &saved); err != nil {
		t.Fatalf("decode saved config: %v", err)
	}
	if saved.Relay.URL != "https://relay.example" {
		t.Fatalf("saved config = %#v", saved)
	}
	if saved.Relay.Secret != "relay-secret" {
		t.Fatalf("secrets were not preserved: %#v", saved)
	}
	status := application.Status()
	if !status.RestartRequired {
		t.Fatalf("RestartRequired = false, want true")
	}
	var decoded struct {
		Config config.Config `json:"config"`
		Saved  bool          `json:"saved"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &decoded); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if !decoded.Saved {
		t.Fatalf("response saved = false")
	}
	if decoded.Config.Relay.Secret != config.RedactedSecret {
		t.Fatalf("response relay secret = %q, want redacted", decoded.Config.Relay.Secret)
	}
}

func TestControlPutConfigRestartsRuntimeWhenConfigured(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	cfg := config.Config{
		Control: config.ControlConfig{Addr: "127.0.0.1:0"},
	}
	application := app.New(cfg, "test-version")
	runtime := &fakeRuntimeController{}
	server := NewWithRuntime("127.0.0.1:0", application, path, runtime)

	body := bytes.NewBufferString(`{"relay":{"url":"https://relay.example"}}`)
	request := httptest.NewRequest(http.MethodPut, "/control/config", body)
	response := httptest.NewRecorder()
	server.handleConfig(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status code = %d, want 200; body=%s", response.Code, response.Body.String())
	}
	if runtime.restarts != 1 {
		t.Fatalf("restart count = %d, want 1", runtime.restarts)
	}
	var decoded struct {
		Restarted       bool `json:"restarted"`
		RestartRequired bool `json:"restartRequired"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &decoded); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if !decoded.Restarted {
		t.Fatalf("response restarted = false")
	}
}

func TestControlRestartAndStopEndpoints(t *testing.T) {
	cfg := config.Config{
		Control: config.ControlConfig{Addr: "127.0.0.1:0"},
	}
	application := app.New(cfg, "test-version")
	runtime := &fakeRuntimeController{}
	server := NewWithRuntime("127.0.0.1:0", application, "", runtime)

	restartResponse := httptest.NewRecorder()
	server.handleRestart(restartResponse, httptest.NewRequest(http.MethodPost, "/control/restart", nil))
	if restartResponse.Code != http.StatusOK {
		t.Fatalf("restart status = %d body=%s", restartResponse.Code, restartResponse.Body.String())
	}
	if runtime.restarts != 1 {
		t.Fatalf("restart count = %d, want 1", runtime.restarts)
	}

	stopResponse := httptest.NewRecorder()
	server.handleStop(stopResponse, httptest.NewRequest(http.MethodPost, "/control/stop", nil))
	if stopResponse.Code != http.StatusOK {
		t.Fatalf("stop status = %d body=%s", stopResponse.Code, stopResponse.Body.String())
	}
	if runtime.stops != 1 {
		t.Fatalf("stop count = %d, want 1", runtime.stops)
	}
}

func TestControlLogsReturnsRecentEntries(t *testing.T) {
	cfg := config.Load(config.Options{})
	application := app.New(cfg, "test-version")
	application.Log("info", "test", "first")
	application.Log("warn", "test", "second")
	server := New("127.0.0.1:0", application)

	request := httptest.NewRequest(http.MethodGet, "/control/logs?limit=1", nil)
	response := httptest.NewRecorder()
	server.handleLogs(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status code = %d, want 200", response.Code)
	}
	var decoded []struct {
		Message string `json:"message"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &decoded); err != nil {
		t.Fatalf("decode logs: %v", err)
	}
	if len(decoded) != 1 || decoded[0].Message != "second" {
		t.Fatalf("decoded logs = %#v", decoded)
	}
}

func TestControlLogsStreamWritesExistingEntries(t *testing.T) {
	cfg := config.Load(config.Options{})
	application := app.New(cfg, "test-version")
	application.Log("info", "test", "stream-entry")
	server := New("127.0.0.1:0", application)

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	request := httptest.NewRequest(http.MethodGet, "/control/logs/stream", nil).WithContext(ctx)
	response := httptest.NewRecorder()
	server.handleLogsStream(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status code = %d, want 200", response.Code)
	}
	if got := response.Header().Get("Content-Type"); !strings.HasPrefix(got, "text/event-stream") {
		t.Fatalf("content-type = %q", got)
	}
	body := response.Body.String()
	if !strings.Contains(body, "event: log") || !strings.Contains(body, "stream-entry") {
		t.Fatalf("unexpected sse body: %s", body)
	}
}

type fakeRuntimeController struct {
	restarts int
	stops    int
}

func (runtime *fakeRuntimeController) Restart(context.Context) error {
	runtime.restarts++
	return nil
}

func (runtime *fakeRuntimeController) Stop(context.Context) error {
	runtime.stops++
	return nil
}

func TestControlScreenSnapshot(t *testing.T) {
	cfg := config.Load(config.Options{})
	cfg.Shell.Command = "/bin/sh"
	application := app.New(cfg, "test-version")
	server := New("127.0.0.1:0", application)
	terminal, err := application.Sessions().Create(".")
	if err != nil {
		t.Fatalf("failed to create session: %v", err)
	}
	defer terminal.Close()

	request := httptest.NewRequest(http.MethodGet, "/control/sessions/s1/screen", nil)
	response := httptest.NewRecorder()
	server.handleSessionDetail(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status code = %d, want 200; body=%s", response.Code, response.Body.String())
	}
	var decoded struct {
		Rows int `json:"rows"`
		Cols int `json:"cols"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &decoded); err != nil {
		t.Fatalf("failed to decode body: %v", err)
	}
	if decoded.Rows != 30 || decoded.Cols != 100 {
		t.Fatalf("unexpected screen size: %#v", decoded)
	}
}

func TestControlLegacyScreenDeltaEndpointNotFound(t *testing.T) {
	cfg := config.Load(config.Options{})
	application := app.New(cfg, "test-version")
	server := New("127.0.0.1:0", application)

	request := httptest.NewRequest(http.MethodGet, "/control/sessions/missing/screen/delta", nil)
	response := httptest.NewRecorder()
	server.handleSessionDetail(response, request)

	if response.Code != http.StatusNotFound {
		t.Fatalf("status code = %d, want 404", response.Code)
	}
}
