package app

import (
	"os"
	"path/filepath"
	"sync"
	"testing"

	"webterm/go-core/internal/config"
)

// newLifecycleTestApp 以临时 IPC endpoint 构造 App，默认关闭日志落盘，
// 避免测试污染真实 runtime 目录。需要验证落盘的测试自行传入 TempDir。
func newLifecycleTestApp(t *testing.T, cfg config.Config, buildInfo BuildInfo) *App {
	t.Helper()
	tmp := t.TempDir()
	cfg.IPCEndpoint = "unix:" + filepath.Join(tmp, "agent.sock")
	application := NewWithBuildInfoAndOptions(cfg, buildInfo, Options{PersistentLogs: false})
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

func TestAppFileSinkPersistsLogs(t *testing.T) {
	cfg := config.Default()
	tmp := t.TempDir()
	cfg.IPCEndpoint = "unix:" + filepath.Join(tmp, "agent.sock")
	logDir := t.TempDir()
	application := NewWithBuildInfoAndOptions(cfg, BuildInfo{Version: "1.0.0"}, Options{
		PersistentLogs: true,
		LogDir:         logDir,
	})
	t.Cleanup(application.Shutdown)

	application.Log("info", "test", "hello lifecycle")
	if _, err := os.Stat(filepath.Join(logDir, "agent.jsonl")); err != nil {
		t.Errorf("expected log file under %s: %v", logDir, err)
	}
}

func TestAppShutdownIdempotent(t *testing.T) {
	cfg := config.Default()
	tmp := t.TempDir()
	cfg.IPCEndpoint = "unix:" + filepath.Join(tmp, "agent.sock")
	application := NewWithBuildInfoAndOptions(cfg, BuildInfo{Version: "1.0.0"}, Options{PersistentLogs: false})
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

// TestAppShutdownLateLogsDoNotReopenFile Shutdown 后（含并发）迟到的日志
// 只进内存 Ring：既不再写文件，也不经 openCurrent 重开文件。
func TestAppShutdownLateLogsDoNotReopenFile(t *testing.T) {
	cfg := config.Default()
	tmp := t.TempDir()
	cfg.IPCEndpoint = "unix:" + filepath.Join(tmp, "agent.sock")
	logDir := t.TempDir()
	application := NewWithBuildInfoAndOptions(cfg, BuildInfo{Version: "1.0.0"}, Options{
		PersistentLogs: true,
		LogDir:         logDir,
	})

	application.Log("info", "test", "before shutdown")
	logPath := filepath.Join(logDir, "agent.jsonl")
	before, err := os.Stat(logPath)
	if err != nil {
		t.Fatalf("log file missing before shutdown: %v", err)
	}

	application.Shutdown()

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				application.Log("info", "test", "late log after shutdown")
			}
		}()
	}
	wg.Wait()
	application.Shutdown() // 幂等：第二次不应 panic

	after, err := os.Stat(logPath)
	if err != nil {
		t.Fatalf("log file missing after shutdown: %v", err)
	}
	if after.Size() != before.Size() {
		t.Errorf("log file grew after shutdown: %d -> %d bytes", before.Size(), after.Size())
	}
}
