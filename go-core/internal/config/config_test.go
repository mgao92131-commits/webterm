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
