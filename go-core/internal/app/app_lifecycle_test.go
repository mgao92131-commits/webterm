package app

import (
	"os"
	"path/filepath"
	"testing"

	"webterm/go-core/internal/agenthooks"
	"webterm/go-core/internal/config"
)

// newLifecycleTestApp 以临时 IPC endpoint 构造 App，避免污染真实 runtime 目录。
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

	summary := application.DiagnosticsSummary()
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

	if got := application.DiagnosticsState().Relay.State; got != "disconnected" {
		t.Errorf("initial relay state = %q, want disconnected", got)
	}

	application.SetRelayConnected(true, "device-1", "")
	if got := application.DiagnosticsState().Relay.State; got != "connected" {
		t.Errorf("relay state = %q, want connected", got)
	}

	application.SetRelayConnected(false, "", "boom")
	state := application.DiagnosticsState().Relay
	if state.State != "disconnected" {
		t.Errorf("relay state after disconnect = %q, want disconnected", state.State)
	}
}

func TestAppRelayStateUnconfiguredWhenNoURL(t *testing.T) {
	cfg := config.Default()
	cfg.Relay.URL = ""
	application := newLifecycleTestApp(t, cfg, BuildInfo{Version: "1.0.0"})
	if got := application.DiagnosticsState().Relay.State; got != "unconfigured" {
		t.Errorf("relay state = %q, want unconfigured", got)
	}
}

func TestAppFileSinkPersistsLogs(t *testing.T) {
	cfg := config.Default()
	application := newLifecycleTestApp(t, cfg, BuildInfo{Version: "1.0.0"})
	application.Log("info", "test", "hello lifecycle")
	logDir := filepath.Join(agenthooks.RuntimeBaseDir(application.IPCEndpoint()), "logs")
	if _, err := os.Stat(filepath.Join(logDir, "agent.jsonl")); err != nil {
		t.Errorf("expected log file under %s: %v", logDir, err)
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
	path, err := application.ExportDiagnostics(t.TempDir())
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
