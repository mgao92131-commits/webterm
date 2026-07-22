package app

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/agenthooks"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/logs"
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

// seedDiskLog 在 dir/agent.jsonl 预置一条“其他 run”的磁盘日志，返回该目录。
func seedDiskLog(t *testing.T, dir, event string) {
	t.Helper()
	entry := logs.Entry{RunID: "other-run", Seq: 1, Time: time.Now().UTC(), Level: "info", Source: "test", Event: event}
	line, err := json.Marshal(entry)
	if err != nil {
		t.Fatalf("marshal seed entry: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, "agent.jsonl"), append(line, '\n'), 0o600); err != nil {
		t.Fatalf("seed disk log: %v", err)
	}
}

// 通用构造函数默认无副作用：NewWithBuildInfo 不落盘，避免污染正式日志目录。
func TestNewWithBuildInfoDoesNotCreatePersistentLog(t *testing.T) {
	cfg := config.Default()
	tmp := t.TempDir()
	cfg.IPCEndpoint = "unix:" + filepath.Join(tmp, "agent.sock")
	application := NewWithBuildInfo(cfg, BuildInfo{Version: "1.0.0"})
	t.Cleanup(application.Shutdown)
	application.Log("info", "test", "should not persist")

	logDir := filepath.Join(agenthooks.RuntimeBaseDir(application.IPCEndpoint()), "logs")
	if _, err := os.Stat(filepath.Join(logDir, "agent.jsonl")); !os.IsNotExist(err) {
		t.Errorf("NewWithBuildInfo must not create a persistent log (stat err=%v)", err)
	}
}

// ReadDiskLogs=false 时导出只携带内存 Ring，忽略磁盘上属于其他 run 的历史日志。
func TestExportWithReadDiskLogsFalseIgnoresExistingDiskLogs(t *testing.T) {
	diskDir := t.TempDir()
	seedDiskLog(t, diskDir, "SEEDED_DISK_MARKER")

	cfg := config.Default()
	tmp := t.TempDir()
	cfg.IPCEndpoint = "unix:" + filepath.Join(tmp, "agent.sock")
	application := NewWithBuildInfoAndOptions(cfg, BuildInfo{Version: "1.0.0"}, Options{
		LogDir:         diskDir,
		PersistentLogs: false,
		ReadDiskLogs:   false,
	})
	t.Cleanup(application.Shutdown)

	application.logger.Event("info", "test", "RING_MARKER", nil)

	path, err := application.ExportDiagnostics(t.TempDir(), false)
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	events := readZipEntry(t, path, "events.jsonl")
	if strings.Contains(events, "SEEDED_DISK_MARKER") {
		t.Errorf("ReadDiskLogs=false export must not include disk history:\n%s", events)
	}
	if !strings.Contains(events, "RING_MARKER") {
		t.Errorf("export must include current Ring events:\n%s", events)
	}
}

// 生产配置（PersistentLogs+ReadDiskLogs）既落盘又在导出时读取磁盘历史日志。
func TestProductionOptionsReadAndWriteDiskLogs(t *testing.T) {
	logDir := t.TempDir()
	seedDiskLog(t, logDir, "SEEDED_HISTORY")

	cfg := config.Default()
	tmp := t.TempDir()
	cfg.IPCEndpoint = "unix:" + filepath.Join(tmp, "agent.sock")
	application := NewWithBuildInfoAndOptions(cfg, BuildInfo{Version: "1.0.0"}, Options{
		LogDir:         logDir,
		PersistentLogs: true,
		ReadDiskLogs:   true,
	})
	t.Cleanup(application.Shutdown)

	// 写：FileSink 以 append 打开，预置文件仍在且新事件可写。
	if _, err := os.Stat(filepath.Join(logDir, "agent.jsonl")); err != nil {
		t.Fatalf("persistent log missing: %v", err)
	}
	application.logger.Event("info", "test", "PROD_MARKER", nil)

	// 读：导出应同时包含磁盘历史（仅磁盘有）与当前事件。
	path, err := application.ExportDiagnostics(t.TempDir(), false)
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	events := readZipEntry(t, path, "events.jsonl")
	if !strings.Contains(events, "SEEDED_HISTORY") {
		t.Errorf("production export (ReadDiskLogs=true) must include disk history:\n%s", events)
	}
	if !strings.Contains(events, "PROD_MARKER") {
		t.Errorf("production export must include current events:\n%s", events)
	}
}
