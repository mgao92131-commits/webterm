package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestLoadRelayOnlyDefaults(t *testing.T) {
	clearConfigEnv(t)
	cfg := Load(Options{})
	if cfg.Control.Addr != "127.0.0.1:18081" {
		t.Fatalf("Control.Addr = %q", cfg.Control.Addr)
	}
	if cfg.Relay.Protocol != RelayProtocolV2 {
		t.Fatalf("Relay.Protocol = %q", cfg.Relay.Protocol)
	}
	if cfg.Scrollback.MaxLines != DefaultScrollbackMaxLines ||
		cfg.Scrollback.MaxBytes != DefaultScrollbackMaxBytes {
		t.Fatalf("Scrollback = %#v", cfg.Scrollback)
	}
}

func TestLoadEnvOverridesDefaults(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("WEBTERM_CONTROL_ADDR", "127.0.0.1:19000")
	t.Setenv("RELAY_URL", "https://relay.example")
	t.Setenv("RELAY_SECRET", "secret")
	t.Setenv("DEVICE_NAME", "test-mac")
	t.Setenv("WEBTERM_RELAY_PROTOCOL", "legacy")
	t.Setenv("WEBTERM_MAX_UPLOAD_BYTES", "2048")

	cfg := Load(Options{})
	if cfg.Relay.URL != "https://relay.example" || cfg.Relay.Secret != "secret" ||
		cfg.Relay.DeviceName != "test-mac" {
		t.Fatalf("Relay = %#v", cfg.Relay)
	}
	if cfg.Relay.Protocol != RelayProtocolV2 {
		t.Fatalf("Relay.Protocol = %q", cfg.Relay.Protocol)
	}
	if cfg.Control.Addr != "127.0.0.1:19000" || cfg.Upload.MaxBytes != 2048 {
		t.Fatalf("Control/Upload = %#v %#v", cfg.Control, cfg.Upload)
	}
}

func TestRedactedMasksRelaySecret(t *testing.T) {
	cfg := Config{Relay: RelayConfig{Secret: "relay-secret"}}
	redacted := cfg.Redacted()
	if redacted.Relay.Secret != RedactedSecret {
		t.Fatalf("redacted relay secret = %q", redacted.Relay.Secret)
	}
	if cfg.Relay.Secret != "relay-secret" {
		t.Fatal("Redacted mutated original config")
	}
}

func TestMergeEditablePreservesRedactedSecretAndLimits(t *testing.T) {
	current := Config{
		Relay:      RelayConfig{URL: "https://old", Secret: "secret"},
		Scrollback: ScrollbackConfig{MaxLines: 5000, MaxBytes: 1 << 20},
	}
	merged := MergeEditable(current, Config{
		Relay: RelayConfig{URL: "https://new", Secret: RedactedSecret},
	})
	if merged.Relay.URL != "https://new" || merged.Relay.Secret != "secret" {
		t.Fatalf("Relay = %#v", merged.Relay)
	}
	if merged.Scrollback != current.Scrollback {
		t.Fatalf("Scrollback = %#v", merged.Scrollback)
	}
}

func TestSaveAndLoadConfigFile(t *testing.T) {
	clearConfigEnv(t)
	path := filepath.Join(t.TempDir(), "WebTerm Agent", "config.json")
	want := Config{
		Relay:   RelayConfig{URL: "https://relay.example", Secret: "secret", DeviceName: "mac", Protocol: RelayProtocolV2},
		Control: ControlConfig{Addr: "127.0.0.1:18081"},
		Shell:   ShellConfig{Command: "/bin/sh", CWD: "/tmp"},
	}
	if err := Save(path, want); err != nil {
		t.Fatalf("Save: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("saved config permissions: info=%v err=%v", info, err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	var raw Config
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatalf("Unmarshal: %v", err)
	}
	if raw.Relay.Secret != want.Relay.Secret {
		t.Fatalf("saved config = %#v", raw)
	}
	loaded := Load(Options{ConfigPath: path})
	if loaded.Relay.URL != want.Relay.URL {
		t.Fatalf("loaded config = %#v", loaded)
	}
}

func TestInvalidScrollbackFallsBackToDefaults(t *testing.T) {
	clearConfigEnv(t)
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{"scrollback":{"maxLines":-1,"maxBytes":0}}`), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	cfg := Load(Options{ConfigPath: path})
	if cfg.Scrollback.MaxLines != DefaultScrollbackMaxLines ||
		cfg.Scrollback.MaxBytes != DefaultScrollbackMaxBytes {
		t.Fatalf("Scrollback = %#v", cfg.Scrollback)
	}
}

func clearConfigEnv(t *testing.T) {
	t.Helper()
	for _, key := range []string{
		"WEBTERM_SOCKET_PATH", "WEBTERM_CONTROL_ADDR", "RELAY_URL", "RELAY_SECRET",
		"DEVICE_NAME", "WEBTERM_RELAY_PROTOCOL", "WEBTERM_SHELL", "WEBTERM_MAX_UPLOAD_BYTES",
	} {
		t.Setenv(key, "")
	}
}
