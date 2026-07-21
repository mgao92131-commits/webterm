package app

import (
	"os"
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

	summary := application.DiagnosticsSummary()

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
	summary := application.DiagnosticsSummary()

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
	path, err := application.ExportDiagnostics(dir)
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
