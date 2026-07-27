package app

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"webterm/go-core/internal/config"
)

func newLifecycleTestApp(t *testing.T, cfg config.Config, buildInfo BuildInfo) *App {
	t.Helper()
	tmp := t.TempDir()
	cfg.IPCEndpoint = "unix:" + filepath.Join(tmp, "agent.sock")
	application := NewWithBuildInfo(cfg, buildInfo)
	t.Cleanup(application.Shutdown)
	return application
}

func TestAppLifecycleRunIDAndBuildInfo(t *testing.T) {
	cfg := config.Default()
	cfg.Relay.URL = "wss://relay.example.test"
	application := newLifecycleTestApp(t, cfg, BuildInfo{Version: "9.9.9", GitCommit: "abc123", BuildTime: "2026"})

	if application.RunID() == "" {
		t.Error("runID should not be empty")
	}
	if got := application.BuildInfo().Version; got != "9.9.9" {
		t.Errorf("buildInfo.version = %q, want 9.9.9", got)
	}

	summary := application.DiagnosticsSummary(false)
	agent, _ := summary["agent"].(map[string]any)
	if agent["runId"] == nil || agent["runId"] == "" {
		t.Error("summary agent.runId missing")
	}
	if agent["gitCommit"] != "abc123" {
		t.Errorf("summary agent.gitCommit = %v, want abc123", agent["gitCommit"])
	}
}

func TestAppRelayStateTransitions(t *testing.T) {
	cfg := config.Default()
	cfg.Relay.URL = "wss://relay.example.test"
	application := newLifecycleTestApp(t, cfg, BuildInfo{Version: "1.0.0"})

	if got := application.DiagnosticsState(false).Relay.State; got != "disconnected" {
		t.Errorf("initial relay state = %q, want disconnected", got)
	}

	application.SetRelayConnected(true, "device-1", RelayErrorNone)
	if got := application.DiagnosticsState(false).Relay.State; got != "connected" {
		t.Errorf("relay state = %q, want connected", got)
	}

	application.SetRelayConnected(false, "", RelayErrorDialFailed)
	state := application.DiagnosticsState(false).Relay
	if state.State != "disconnected" {
		t.Errorf("relay state after disconnect = %q, want disconnected", state.State)
	}
}

func TestAppRelayStateUnconfiguredWhenNoURL(t *testing.T) {
	cfg := config.Default()
	cfg.Relay.URL = ""
	application := newLifecycleTestApp(t, cfg, BuildInfo{Version: "1.0.0"})
	if got := application.DiagnosticsState(false).Relay.State; got != "unconfigured" {
		t.Errorf("relay state = %q, want unconfigured", got)
	}
}

func TestAppDoesNotCreatePersistentLogFiles(t *testing.T) {
	cfg := config.Default()
	tmp := t.TempDir()
	cfg.IPCEndpoint = "unix:" + filepath.Join(tmp, "agent.sock")
	logDir := filepath.Join(tmp, "logs")
	application := NewWithBuildInfo(cfg, BuildInfo{Version: "1.0.0"})
	t.Cleanup(application.Shutdown)

	application.Log("info", "test", "hello lifecycle")
	if _, err := os.Stat(filepath.Join(logDir, "agent.jsonl")); !os.IsNotExist(err) {
		t.Errorf("memory-only logging must not create agent.jsonl (stat err=%v)", err)
	}
}

func TestAppShutdownIdempotent(t *testing.T) {
	cfg := config.Default()
	tmp := t.TempDir()
	cfg.IPCEndpoint = "unix:" + filepath.Join(tmp, "agent.sock")
	application := NewWithBuildInfo(cfg, BuildInfo{Version: "1.0.0"})
	application.Shutdown()
	application.Shutdown() // 第二次不应 panic
}

func TestAppExportDiagnosticsSucceeds(t *testing.T) {
	cfg := config.Default()
	application := newLifecycleTestApp(t, cfg, BuildInfo{Version: "1.0.0", GitCommit: "abc"})
	path, err := application.ExportDiagnostics(t.TempDir(), false)
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if path == "" {
		t.Fatal("empty export path")
	}
	if _, err := os.Stat(path); err != nil {
		t.Errorf("exported archive missing: %v", err)
	}
}

func TestExportIncludesOnlyCurrentProcessRing(t *testing.T) {
	cfg := config.Default()
	application := newLifecycleTestApp(t, cfg, BuildInfo{Version: "1.0.0"})
	application.logger.Event("info", "test", "RING_MARKER", nil)

	path, err := application.ExportDiagnostics(t.TempDir(), false)
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	events := readZipEntry(t, path, "events.jsonl")
	if !strings.Contains(events, "RING_MARKER") {
		t.Errorf("export must include current Ring events:\n%s", events)
	}
}

func TestNewWithBuildInfoDoesNotCreatePersistentLog(t *testing.T) {
	cfg := config.Default()
	tmp := t.TempDir()
	cfg.IPCEndpoint = "unix:" + filepath.Join(tmp, "agent.sock")
	application := NewWithBuildInfo(cfg, BuildInfo{Version: "1.0.0"})
	t.Cleanup(application.Shutdown)
	application.Log("info", "test", "should not persist")

	logDir := filepath.Join(tmp, "logs")
	if err := os.MkdirAll(logDir, 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if _, err := os.Stat(filepath.Join(logDir, "agent.jsonl")); !os.IsNotExist(err) {
		t.Errorf("NewWithBuildInfo must not create a persistent log (stat err=%v)", err)
	}
}
