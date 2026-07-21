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

	// Secret 在默认与 includePaths 两种模式下都不能出现。
	for _, includePaths := range []bool{false, true} {
		summary := application.DiagnosticsSummary(includePaths)
		encoded, err := json.Marshal(summary)
		if err != nil {
			t.Fatalf("marshal summary: %v", err)
		}
		if strings.Contains(string(encoded), "super-secret") {
			t.Errorf("raw relay secret leaked (includePaths=%v): %s", includePaths, encoded)
		}
		cfgSection, ok := summary["config"].(map[string]any)
		if !ok {
			t.Fatalf("config section missing or wrong type: %T", summary["config"])
		}
		relay, _ := cfgSection["relay"].(map[string]any)
		if relay["secret"] != config.RedactedSecret {
			t.Errorf("relay secret not redacted (includePaths=%v): %v", includePaths, relay["secret"])
		}
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

// TestDiagnosticsSummaryDefaultRedaction 验证默认摘要不泄露 Relay URL、DeviceName、
// Shell CWD、IPC 完整路径、自由文本日志中的路径以及项目目录名；
// includePaths 模式下恢复允许显示的完整值，但 Secret 任何模式下都不出现。
func TestDiagnosticsSummaryDefaultRedaction(t *testing.T) {
	cfg := config.Default()
	cfg.Relay.URL = "wss://relay.secret-host.example:8443/agent"
	cfg.Relay.Secret = "super-secret"
	cfg.Relay.DeviceName = "my-secret-device-name"
	cfg.Shell.CWD = "/home/user/confidential-project"
	cfg.Shell.Command = "/usr/local/bin/fish"
	cfg.IPCEndpoint = "unix:/run/webterm/agent.sock"

	application := newTestApp(cfg)
	application.ipcEndpoint = "unix:/run/webterm/agent.sock"
	// 自由文本日志包含路径、socket 与 Relay 地址。
	application.logger.Add("error", "test",
		"open /home/user/confidential-project/data.sock failed, dial wss://relay.secret-host.example:8443/agent")

	leaks := []string{
		"relay.secret-host.example",       // Relay URL 主机
		"my-secret-device-name",           // DeviceName
		"/home/user/confidential-project", // Shell CWD / 日志路径
		"/run/webterm/agent.sock",         // IPC 完整路径
		"/usr/local/bin/fish",             // Shell Command
		"data.sock",                       // 自由文本日志中的路径
		"super-secret",                    // Secret（任何模式都不允许）
	}

	defaultSummary := application.DiagnosticsSummary(false)
	defaultJSON, err := json.Marshal(defaultSummary)
	if err != nil {
		t.Fatalf("marshal default summary: %v", err)
	}
	for _, leaked := range leaks {
		if strings.Contains(string(defaultJSON), leaked) {
			t.Errorf("default summary leaks %q", leaked)
		}
	}

	// includePaths 恢复完整值（Secret 除外）。
	fullSummary := application.DiagnosticsSummary(true)
	fullJSON, err := json.Marshal(fullSummary)
	if err != nil {
		t.Fatalf("marshal full summary: %v", err)
	}
	for _, restored := range []string{
		"relay.secret-host.example",
		"my-secret-device-name",
		"/home/user/confidential-project",
		"/run/webterm/agent.sock",
		"/usr/local/bin/fish",
	} {
		if !strings.Contains(string(fullJSON), restored) {
			t.Errorf("includePaths summary should restore %q", restored)
		}
	}
	if strings.Contains(string(fullJSON), "super-secret") {
		t.Error("includePaths summary must never restore relay secret")
	}
}

// TestDiagnosticsSummaryRedactsFreeTextLogMessage 单独确认默认模式下自由文本
// Message 被占位符替换，且结构化 Event 字段保留。
func TestDiagnosticsSummaryRedactsFreeTextLogMessage(t *testing.T) {
	application := newTestApp(config.Default())
	application.logger.Add("error", "test", "sensitive body /var/lib/secret/file.txt")
	application.logger.Event("info", "test", "relay_connected", map[string]any{"attempt": 3})

	summary := application.DiagnosticsSummary(false)
	logSection, _ := summary["logs"].(map[string]any)
	recent, _ := logSection["recent"].([]logs.Entry)
	if len(recent) != 2 {
		t.Fatalf("recent entries = %d, want 2", len(recent))
	}
	for _, entry := range recent {
		if strings.Contains(entry.Message, "/var/lib/secret") {
			t.Errorf("free-text message leaked path: %q", entry.Message)
		}
		if entry.Event == "relay_connected" {
			if entry.Message != "" {
				t.Errorf("structured event should keep empty message, got %q", entry.Message)
			}
			if entry.Fields["attempt"] != 3 {
				t.Errorf("structured event field lost: %v", entry.Fields)
			}
		}
	}
	// 自由文本条目必须被占位符替换。
	found := false
	for _, entry := range recent {
		if entry.Message == logs.RedactedMessagePlaceholder {
			found = true
		}
	}
	if !found {
		t.Error("expected a redacted free-text placeholder in default summary")
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
	if _, has := redacted["cwdBaseName"]; has {
		t.Error("redacted summary must not include cwd basename (project dir name is sensitive)")
	}
	if strings.Contains(string(encoded), "secret-project") {
		t.Errorf("redacted session summary leaks cwd basename: %s", encoded)
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

// TestExportSessionTrafficRedaction 默认导出的 session-traffic.json 中会话 ID 已哈希，
// includePaths 恢复完整 ID；任何模式下都不含 LastCommand/RecentInputLines/终端正文，
// 但保留 PTY 与 ScreenWire 计量字段。
func TestExportSessionTrafficRedaction(t *testing.T) {
	application := newTestApp(config.Default())
	application.sessions = session.NewManager(session.TerminalDefaults{Command: "/bin/sh", CWD: "."})
	terminal, err := application.Sessions().Create(t.TempDir())
	if err != nil {
		t.Fatalf("create terminal: %v", err)
	}
	sessionID := terminal.ID()
	defer application.Sessions().Close(sessionID)
	hashedID := logs.HashID(sessionID)

	// 默认：会话 ID 哈希，原文不出现。
	path, err := application.ExportDiagnostics(t.TempDir(), false)
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	traffic := readZipEntry(t, path, "session-traffic.json")
	if strings.Contains(traffic, sessionID) {
		t.Errorf("redacted session-traffic.json leaks raw session id %q", sessionID)
	}
	if !strings.Contains(traffic, hashedID) {
		t.Errorf("redacted session-traffic.json should contain hashed id %q: %s", hashedID, traffic)
	}
	for _, forbidden := range []string{"lastCommand", "recentInputLines", "termTitle"} {
		if strings.Contains(traffic, forbidden) {
			t.Errorf("session-traffic.json must not contain %q", forbidden)
		}
	}

	// 字段存在性：PTY 与 ScreenWire 计量。
	var decoded []map[string]any
	if err := json.Unmarshal([]byte(traffic), &decoded); err != nil {
		t.Fatalf("session-traffic.json not parseable: %v", err)
	}
	if len(decoded) != 1 {
		t.Fatalf("decoded traffic len=%d, want 1", len(decoded))
	}
	for _, key := range []string{"ptyOutputEvents", "ptyOutputBytes", "screenWireByClient"} {
		if _, ok := decoded[0][key]; !ok {
			t.Errorf("session-traffic.json missing field %q", key)
		}
	}

	// includePaths：恢复完整会话 ID。
	fullPath, err := application.ExportDiagnostics(t.TempDir(), true)
	if err != nil {
		t.Fatalf("export includePaths: %v", err)
	}
	fullTraffic := readZipEntry(t, fullPath, "session-traffic.json")
	if !strings.Contains(fullTraffic, sessionID) {
		t.Errorf("includePaths session-traffic.json should contain raw id %q", sessionID)
	}
}

// TestDiagnosticsSessionTrafficEmpty 无活跃会话时返回空（非 nil）切片，JSON 为 []。
func TestDiagnosticsSessionTrafficEmpty(t *testing.T) {
	application := newTestApp(config.Default())
	traffic := application.DiagnosticsSessionTraffic(false)
	if traffic == nil || len(traffic) != 0 {
		t.Fatalf("empty sessions should yield empty non-nil slice, got %#v", traffic)
	}
	data, err := json.Marshal(traffic)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if string(data) != "[]" {
		t.Errorf("empty traffic JSON = %s, want []", data)
	}
}

// TestDiagnosticsRelayDeviceIDRedaction relay deviceId 默认哈希，includePaths 恢复；
// 空 deviceId 保持为空（omitempty），不哈希成占位值。
func TestDiagnosticsRelayDeviceIDRedaction(t *testing.T) {
	application := newTestApp(config.Default())
	application.SetRelayConnected(true, "my-identifiable-device", RelayErrorNone)

	if got := application.DiagnosticsState(false).Relay.DeviceID; got != logs.HashID("my-identifiable-device") {
		t.Errorf("default relay deviceId = %q, want hashed", got)
	}
	if got := application.DiagnosticsState(true).Relay.DeviceID; got != "my-identifiable-device" {
		t.Errorf("includePaths relay deviceId = %q, want raw", got)
	}
	// 摘要 relay 段同样默认脱敏。
	summary := application.DiagnosticsSummary(false)
	relay, _ := summary["relay"].(map[string]any)
	if relay["deviceId"] == "my-identifiable-device" {
		t.Errorf("summary relay deviceId leaks raw value: %v", relay["deviceId"])
	}

	// 空 deviceId 保持空。
	empty := newTestApp(config.Default())
	if got := empty.DiagnosticsState(false).Relay.DeviceID; got != "" {
		t.Errorf("empty deviceId = %q, want empty", got)
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
