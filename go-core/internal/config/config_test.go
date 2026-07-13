package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestLoadDefaultsToDirectMode(t *testing.T) {
	clearConfigEnv(t)
	cfg := Load(Options{})
	if cfg.Mode != ModeDirect {
		t.Fatalf("Mode = %q, want %q", cfg.Mode, ModeDirect)
	}
	if cfg.Direct.Addr != "127.0.0.1:8080" {
		t.Fatalf("Direct.Addr = %q, want default addr", cfg.Direct.Addr)
	}
	if cfg.Control.Addr != "127.0.0.1:18081" {
		t.Fatalf("Control.Addr = %q, want default control addr", cfg.Control.Addr)
	}
}

func TestLoadEnvOverridesDefaults(t *testing.T) {
	t.Setenv("WEBTERM_MODE", ModeRelay)
	t.Setenv("WEBTERM_ADDR", "100.64.0.1:8080")
	t.Setenv("WEBTERM_USER", "gao")
	t.Setenv("WEBTERM_WEB_ROOT", "/tmp/webterm-web")
	t.Setenv("WEBTERM_CONTROL_ADDR", "127.0.0.1:19000")
	t.Setenv("RELAY_URL", "https://relay.example")
	t.Setenv("DEVICE_NAME", "test-mac")
	t.Setenv("WEBTERM_RELAY_PROTOCOL", "v1")

	cfg := Load(Options{})
	if cfg.Mode != ModeRelay {
		t.Fatalf("Mode = %q, want relay", cfg.Mode)
	}
	if cfg.Direct.Addr != "100.64.0.1:8080" {
		t.Fatalf("Direct.Addr = %q", cfg.Direct.Addr)
	}
	if cfg.Direct.User != "gao" {
		t.Fatalf("Direct.User = %q", cfg.Direct.User)
	}
	if cfg.Direct.WebRoot != "/tmp/webterm-web" {
		t.Fatalf("Direct.WebRoot = %q", cfg.Direct.WebRoot)
	}
	if cfg.Relay.URL != "https://relay.example" {
		t.Fatalf("Relay.URL = %q", cfg.Relay.URL)
	}
	if cfg.Relay.DeviceName != "test-mac" {
		t.Fatalf("Relay.DeviceName = %q", cfg.Relay.DeviceName)
	}
	if cfg.Relay.Protocol != RelayProtocolV2 {
		t.Fatalf("Relay.Protocol = %q", cfg.Relay.Protocol)
	}
	if cfg.Control.Addr != "127.0.0.1:19000" {
		t.Fatalf("Control.Addr = %q", cfg.Control.Addr)
	}
}

func TestLoadDefaultsRelayProtocolToV2(t *testing.T) {
	clearConfigEnv(t)
	cfg := Load(Options{})
	if cfg.Relay.Protocol != RelayProtocolV2 {
		t.Fatalf("Relay.Protocol = %q, want v2", cfg.Relay.Protocol)
	}
}

func TestLoadFlagModeOverridesEnv(t *testing.T) {
	t.Setenv("WEBTERM_MODE", ModeRelay)
	cfg := Load(Options{Mode: ModeDirect})
	if cfg.Mode != ModeDirect {
		t.Fatalf("Mode = %q, want direct", cfg.Mode)
	}
}

func TestLoadNormalizesLegacyAgentMode(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("WEBTERM_MODE", ModeLegacyAgent)
	cfg := Load(Options{})
	if cfg.Mode != ModeRelay {
		t.Fatalf("Mode = %q, want relay", cfg.Mode)
	}

	cfg = Load(Options{Mode: ModeLegacyAgent})
	if cfg.Mode != ModeRelay {
		t.Fatalf("flag Mode = %q, want relay", cfg.Mode)
	}
}

func TestRedactedMasksSecrets(t *testing.T) {
	cfg := Config{
		Direct: DirectConfig{Password: "direct-password"},
		Relay:  RelayConfig{Secret: "relay-secret"},
	}

	redacted := cfg.Redacted()
	if redacted.Direct.Password != "********" {
		t.Fatalf("redacted direct password = %q", redacted.Direct.Password)
	}
	if redacted.Relay.Secret != "********" {
		t.Fatalf("redacted relay secret = %q", redacted.Relay.Secret)
	}
	if cfg.Direct.Password != "direct-password" || cfg.Relay.Secret != "relay-secret" {
		t.Fatalf("Redacted mutated the original config")
	}
}

func TestMergeEditablePreservesRedactedSecrets(t *testing.T) {
	current := Config{
		Mode:   ModeDirect,
		Direct: DirectConfig{Addr: "127.0.0.1:8080", User: "admin", Password: "direct-secret"},
		Relay:  RelayConfig{URL: "https://old-relay", Secret: "relay-secret", DeviceName: "old-device"},
	}
	next := Config{
		Mode:   ModeLegacyAgent,
		Direct: DirectConfig{Password: RedactedSecret},
		Relay:  RelayConfig{URL: "https://new-relay", Secret: RedactedSecret},
	}

	merged := MergeEditable(current, next)
	if merged.Mode != ModeRelay {
		t.Fatalf("Mode = %q, want relay", merged.Mode)
	}
	if merged.Direct.Password != "direct-secret" {
		t.Fatalf("Direct.Password = %q, want preserved", merged.Direct.Password)
	}
	if merged.Relay.Secret != "relay-secret" {
		t.Fatalf("Relay.Secret = %q, want preserved", merged.Relay.Secret)
	}
	if merged.Relay.URL != "https://new-relay" {
		t.Fatalf("Relay.URL = %q", merged.Relay.URL)
	}
}

func TestSaveAndLoadConfigFile(t *testing.T) {
	clearConfigEnv(t)
	path := filepath.Join(t.TempDir(), "WebTerm Agent", "config.json")
	want := Config{
		Mode:    ModeRelay,
		Direct:  DirectConfig{Addr: "127.0.0.1:8088", User: "admin", Password: "pw"},
		Relay:   RelayConfig{URL: "https://relay.example", Secret: "secret", DeviceName: "mac", Protocol: RelayProtocolV2},
		Control: ControlConfig{Addr: "127.0.0.1:18081"},
		Shell:   ShellConfig{Command: "/bin/sh", CWD: "/tmp"},
	}
	if err := Save(path, want); err != nil {
		t.Fatalf("Save returned error: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("Stat saved config: %v", err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("config mode = %o, want 600", info.Mode().Perm())
	}
	var raw Config
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatalf("Unmarshal saved config: %v", err)
	}
	if raw.Mode != want.Mode || raw.Relay.Secret != want.Relay.Secret {
		t.Fatalf("saved config = %#v, want %#v", raw, want)
	}
	loaded := Load(Options{ConfigPath: path})
	if loaded.Mode != want.Mode || loaded.Relay.URL != want.Relay.URL {
		t.Fatalf("loaded config = %#v", loaded)
	}
}

func TestLoadScrollbackDefaults(t *testing.T) {
	clearConfigEnv(t)
	cfg := Load(Options{})
	if cfg.Scrollback.MaxLines != DefaultScrollbackMaxLines {
		t.Fatalf("Scrollback.MaxLines = %d, want default %d", cfg.Scrollback.MaxLines, DefaultScrollbackMaxLines)
	}
	if cfg.Scrollback.MaxBytes != DefaultScrollbackMaxBytes {
		t.Fatalf("Scrollback.MaxBytes = %d, want default %d", cfg.Scrollback.MaxBytes, DefaultScrollbackMaxBytes)
	}
	// dry-run 输出（Redacted）必须展示生效中的 scrollback 上限。
	redacted := cfg.Redacted()
	if redacted.Scrollback != cfg.Scrollback {
		t.Fatalf("Redacted changed scrollback limits: %#v", redacted.Scrollback)
	}
}

func TestLoadScrollbackFromFile(t *testing.T) {
	clearConfigEnv(t)
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{"scrollback":{"maxLines":5000,"maxBytes":1048576}}`), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	cfg := Load(Options{ConfigPath: path})
	if cfg.Scrollback.MaxLines != 5000 {
		t.Fatalf("Scrollback.MaxLines = %d, want 5000", cfg.Scrollback.MaxLines)
	}
	if cfg.Scrollback.MaxBytes != 1048576 {
		t.Fatalf("Scrollback.MaxBytes = %d, want 1048576", cfg.Scrollback.MaxBytes)
	}
}

func TestLoadScrollbackInvalidFallsBackToDefaults(t *testing.T) {
	clearConfigEnv(t)
	for _, body := range []string{
		`{"scrollback":{"maxLines":-1,"maxBytes":-4096}}`,
		`{"scrollback":{"maxLines":0,"maxBytes":0}}`,
		`{}`,
	} {
		path := filepath.Join(t.TempDir(), "config.json")
		if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
			t.Fatalf("WriteFile: %v", err)
		}
		cfg := Load(Options{ConfigPath: path})
		if cfg.Scrollback.MaxLines != DefaultScrollbackMaxLines {
			t.Fatalf("%s: Scrollback.MaxLines = %d, want default %d", body, cfg.Scrollback.MaxLines, DefaultScrollbackMaxLines)
		}
		if cfg.Scrollback.MaxBytes != DefaultScrollbackMaxBytes {
			t.Fatalf("%s: Scrollback.MaxBytes = %d, want default %d", body, cfg.Scrollback.MaxBytes, DefaultScrollbackMaxBytes)
		}
	}
}

func TestMergeEditablePreservesScrollback(t *testing.T) {
	current := Config{
		Mode:       ModeDirect,
		Scrollback: ScrollbackConfig{MaxLines: 5000, MaxBytes: 1048576},
	}
	// 不带 scrollback 的编辑必须保留现值。
	merged := MergeEditable(current, Config{Direct: DirectConfig{Addr: "127.0.0.1:9999"}})
	if merged.Scrollback != current.Scrollback {
		t.Fatalf("Scrollback = %#v, want preserved %#v", merged.Scrollback, current.Scrollback)
	}
	// 显式设置必须生效。
	merged = MergeEditable(current, Config{Scrollback: ScrollbackConfig{MaxLines: 20000, MaxBytes: 2 << 20}})
	if merged.Scrollback.MaxLines != 20000 || merged.Scrollback.MaxBytes != 2<<20 {
		t.Fatalf("Scrollback = %#v, want updated", merged.Scrollback)
	}
}

func clearConfigEnv(t *testing.T) {
	t.Helper()
	for _, key := range []string{
		"WEBTERM_MODE",
		"WEBTERM_ADDR",
		"WEBTERM_USER",
		"WEBTERM_PASSWORD",
		"WEBTERM_WEB_ROOT",
		"WEBTERM_CONTROL_ADDR",
		"RELAY_URL",
		"RELAY_SECRET",
		"DEVICE_NAME",
		"WEBTERM_RELAY_PROTOCOL",
		"WEBTERM_SHELL",
	} {
		t.Setenv(key, "")
	}
}
