package app

import (
	"archive/zip"
	"encoding/json"
	"io"
	"os"
	"strings"
	"testing"

	"webterm/go-core/internal/config"
	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/session"
)

// newTestApp 直接构造 App，避免 New() 安装 shell hook 等副作用。
func newTestApp(cfg config.Config) *App {
	return &App{
		cfg:         cfg,
		version:     "test-version",
		logger:      logs.New(logs.DefaultCapacity),
		sessions:    session.NewManager(session.TerminalDefaults{}),
		ipcEndpoint: "unix:/tmp/webterm-app-test.sock",
	}
}

func TestDiagnosticsSummaryRedactsSecret(t *testing.T) {
	cfg := config.Default()
	cfg.Relay.Secret = "super-secret"
	application := newTestApp(cfg)

	summary := application.DiagnosticsSummary(false)

	cfgSection, ok := summary["config"].(config.Config)
	if !ok {
		t.Fatalf("config section missing or wrong type: %T", summary["config"])
	}
	if cfgSection.Relay.Secret != config.RedactedSecret {
		t.Errorf("relay secret not redacted: %q", cfgSection.Relay.Secret)
	}
	if cfgSection.Relay.Secret == "super-secret" {
		t.Errorf("raw relay secret leaked into diagnostics summary")
	}
}

func TestDiagnosticsSummaryOmitsInputBody(t *testing.T) {
	application := newTestApp(config.Default())
	summary := application.DiagnosticsSummary(false)

	sessions, ok := summary["sessions"].(map[string]any)
	if !ok {
		t.Fatalf("sessions section missing")
	}
	list, _ := sessions["list"].([]any)
	for _, item := range list {
		entry, ok := item.(map[string]any)
		if !ok {
			continue
		}
		if _, has := entry["recentInputLines"]; has {
			t.Errorf("session summary must not include recentInputLines")
		}
		if _, has := entry["lastCommand"]; has {
			t.Errorf("session summary must not include lastCommand")
		}
	}
}

func TestExportDiagnosticsProducesArchive(t *testing.T) {
	application := newTestApp(config.Default())
	application.logger.Add("info", "test", "hello diagnostics")

	dir := t.TempDir()
	path, err := application.ExportDiagnostics(dir, false)
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if path == "" {
		t.Fatal("empty export path")
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("exported archive missing: %v", err)
	}
	if info.Size() == 0 {
		t.Error("exported archive is empty")
	}
}

// TestSessionSummaryRedaction 默认输出不得包含完整 id/termTitle/cwd；
// includePaths 显式开启后恢复完整值。
func TestSessionSummaryRedaction(t *testing.T) {
	info := session.Info{
		ID:         "session-abc",
		InstanceID: "inst-1",
		TermTitle:  "topsecret-title",
		CWD:        "/home/user/secret-project",
		Status:     "running",
	}
	redacted := sessionSummary(info, false)
	encoded, err := json.Marshal(redacted)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	for _, leaked := range []string{"session-abc", "topsecret-title", "/home/user"} {
		if strings.Contains(string(encoded), leaked) {
			t.Errorf("redacted session summary leaks %q: %s", leaked, encoded)
		}
	}
	if _, has := redacted["cwd"]; has {
		t.Error("redacted summary must not include full cwd")
	}
	if redacted["cwdBaseName"] != "secret-project" {
		t.Errorf("cwdBaseName = %v, want secret-project", redacted["cwdBaseName"])
	}
	if redacted["cwdHash"] != logs.HashID(info.CWD) {
		t.Errorf("cwdHash = %v, want %v", redacted["cwdHash"], logs.HashID(info.CWD))
	}
	if redacted["id"] != logs.HashID(info.ID) || redacted["termTitle"] != logs.HashID(info.TermTitle) {
		t.Errorf("id/termTitle should be hashed: %v / %v", redacted["id"], redacted["termTitle"])
	}

	full := sessionSummary(info, true)
	if full["id"] != info.ID || full["termTitle"] != info.TermTitle || full["cwd"] != info.CWD {
		t.Errorf("includePaths should restore full values: %v", full)
	}
}

// TestEndpointKind 只保留 endpoint 类型，不带具体路径。
func TestEndpointKind(t *testing.T) {
	if got := endpointKind("unix:/tmp/agent.sock"); got != "unix" {
		t.Errorf("unix endpoint kind = %q", got)
	}
	if got := endpointKind(`npipe:\\.\pipe\webterm`); got != "npipe" {
		t.Errorf("npipe endpoint kind = %q", got)
	}
}

// TestRelayDisconnectedLogsOnlyKind 断连日志与状态只出现 RelayErrorKind 枚举。
func TestRelayDisconnectedLogsOnlyKind(t *testing.T) {
	application := newTestApp(config.Default())
	application.SetRelayConnected(false, "", RelayErrorAuthRejected)

	if got := application.DiagnosticsState(false).Relay.LastError; got != string(RelayErrorAuthRejected) {
		t.Errorf("relay lastError = %q, want auth_rejected", got)
	}
	summary := application.DiagnosticsSummary(false)
	relay, _ := summary["relay"].(map[string]any)
	if relay["lastError"] != string(RelayErrorAuthRejected) {
		t.Errorf("summary relay.lastError = %v", relay["lastError"])
	}

	entries := application.Logs().Recent(0)
	if len(entries) == 0 {
		t.Fatal("expected a relay_disconnected event")
	}
	last := entries[len(entries)-1]
	if last.Event != "relay_disconnected" {
		t.Fatalf("last event = %q, want relay_disconnected", last.Event)
	}
	if last.Fields["reason"] != string(RelayErrorAuthRejected) {
		t.Errorf("event reason = %v, want auth_rejected", last.Fields["reason"])
	}
	if last.Message != "" {
		t.Errorf("relay disconnect must not use free-text message, got %q", last.Message)
	}
}

// TestExportDiagnosticsRedactsTerminalIdentity 默认导出的 state.json 中
// 会话 id 已哈希，原文搜索不到；includePaths 导出恢复完整 id。
func TestExportDiagnosticsRedactsTerminalIdentity(t *testing.T) {
	application := newTestApp(config.Default())
	application.sessions = session.NewManager(session.TerminalDefaults{Command: "/bin/sh", CWD: "."})
	terminal, err := application.Sessions().Create(t.TempDir())
	if err != nil {
		t.Fatalf("create terminal: %v", err)
	}
	sessionID := terminal.ID()
	defer application.Sessions().Close(sessionID)

	path, err := application.ExportDiagnostics(t.TempDir(), false)
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	state := readZipEntry(t, path, "state.json")
	if strings.Contains(state, sessionID) {
		t.Errorf("redacted state.json leaks raw session id %q", sessionID)
	}

	fullPath, err := application.ExportDiagnostics(t.TempDir(), true)
	if err != nil {
		t.Fatalf("export includePaths: %v", err)
	}
	fullState := readZipEntry(t, fullPath, "state.json")
	if !strings.Contains(fullState, sessionID) {
		t.Errorf("includePaths export should contain raw session id %q", sessionID)
	}
}

// readZipEntry 读取诊断包中单个文件的文本内容。
func readZipEntry(t *testing.T, zipPath string, name string) string {
	t.Helper()
	reader, err := zip.OpenReader(zipPath)
	if err != nil {
		t.Fatalf("open zip: %v", err)
	}
	defer reader.Close()
	for _, file := range reader.File {
		if file.Name != name {
			continue
		}
		rc, err := file.Open()
		if err != nil {
			t.Fatalf("open %s: %v", name, err)
		}
		data, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			t.Fatalf("read %s: %v", name, err)
		}
		return string(data)
	}
	t.Fatalf("zip missing %s", name)
	return ""
}
