package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"webterm/go-core/internal/logs"
)

func TestLoadRelayOnlyDefaults(t *testing.T) {
	clearConfigEnv(t)
	cfg := Load(Options{})
	if cfg.Scrollback.MaxLines != DefaultScrollbackMaxLines ||
		cfg.Scrollback.MaxBytes != DefaultScrollbackMaxBytes {
		t.Fatalf("Scrollback = %#v", cfg.Scrollback)
	}
}

func TestLoadEnvOverridesDefaults(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("RELAY_URL", "https://relay.example")
	t.Setenv("RELAY_SECRET", "secret")
	t.Setenv("DEVICE_NAME", "test-mac")
	t.Setenv("WEBTERM_MAX_UPLOAD_BYTES", "2048")

	cfg := Load(Options{})
	if cfg.Relay.URL != "https://relay.example" || cfg.Relay.Secret != "secret" ||
		cfg.Relay.DeviceName != "test-mac" {
		t.Fatalf("Relay = %#v", cfg.Relay)
	}
	if cfg.Upload.MaxBytes != 2048 {
		t.Fatalf("Upload = %#v", cfg.Upload)
	}
}

func TestLoadNewEnvironmentNamesOverrideDeprecatedNames(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("RELAY_URL", "https://legacy.example")
	t.Setenv("RELAY_SECRET", "legacy-secret")
	t.Setenv("WEBTERM_AGENT_RELAY_URL", "https://relay.example")
	t.Setenv("WEBTERM_AGENT_RELAY_SECRET", "new-secret")
	t.Setenv("WEBTERM_AGENT_DEVICE_NAME", "windows-agent")
	cfg := Load(Options{})
	if cfg.Relay.URL != "https://relay.example" || cfg.Relay.Secret != "new-secret" || cfg.Relay.DeviceName != "windows-agent" {
		t.Fatalf("Relay = %#v", cfg.Relay)
	}
}

func TestLoadStrictAllowsAbsentDefaultButRejectsAbsentExplicitConfig(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("WEBTERM_AGENT_RELAY_URL", "https://relay.example")
	t.Setenv("WEBTERM_AGENT_RELAY_SECRET", "secret")
	missing := filepath.Join(t.TempDir(), "missing.json")
	if _, err := loadStrict(missing, false); err != nil {
		t.Fatalf("non-explicit missing config: %v", err)
	}
	if _, err := loadStrict(missing, true); err == nil {
		t.Fatal("explicit missing config did not fail")
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

func diagnosticsTestConfig() Config {
	return Config{
		IPCEndpoint: "unix:/run/webterm/agent.sock",
		SocketPath:  "/run/webterm/legacy.sock",
		Control:     &LegacyControlConfig{Addr: "127.0.0.1:9999"},
		Relay: RelayConfig{
			URL:        "wss://relay.secret-host.example:8443/agent",
			Secret:     "super-secret",
			DeviceName: "my-secret-device",
		},
		Shell: ShellConfig{Command: "/usr/local/bin/fish", CWD: "/home/user/confidential"},
	}
}

// TestDiagnosticsViewDefaultRedacts 默认视图脱敏 URL/DeviceName/CWD/Command/IPC，
// 且不输出 Control.Addr；Secret 永远脱敏。
func TestDiagnosticsViewDefaultRedacts(t *testing.T) {
	view := diagnosticsTestConfig().DiagnosticsView(false)
	encoded, err := json.Marshal(view)
	if err != nil {
		t.Fatalf("marshal view: %v", err)
	}
	text := string(encoded)
	for _, leaked := range []string{
		"relay.secret-host.example", "my-secret-device", "/home/user/confidential",
		"/usr/local/bin/fish", "/run/webterm/agent.sock", "/run/webterm/legacy.sock",
		"127.0.0.1:9999", "super-secret",
	} {
		if strings.Contains(text, leaked) {
			t.Errorf("default diagnostics view leaks %q: %s", leaked, text)
		}
	}

	relay := view["relay"].(map[string]any)
	if relay["url"] != "wss" {
		t.Errorf("relay.url default = %v, want scheme-only wss", relay["url"])
	}
	if relay["secret"] != RedactedSecret {
		t.Errorf("relay.secret = %v, want redacted", relay["secret"])
	}
	if relay["deviceName"] != logs.HashID("my-secret-device") {
		t.Errorf("relay.deviceName should be hashed: %v", relay["deviceName"])
	}
	if view["ipcEndpoint"] != "unix" {
		t.Errorf("ipcEndpoint default = %v, want type-only unix", view["ipcEndpoint"])
	}
	shell := view["shell"].(map[string]any)
	if shell["cwd"] != logs.HashID("/home/user/confidential") {
		t.Errorf("shell.cwd should be hashed: %v", shell["cwd"])
	}
	if shell["command"] != logs.HashID("/usr/local/bin/fish") {
		t.Errorf("shell.command should be hashed: %v", shell["command"])
	}
	if _, has := view["control"]; has {
		t.Error("default view must not output control addr")
	}
}

// TestDiagnosticsViewIncludePathsRestores includePaths 恢复完整值，但 Secret 仍脱敏，
// Control.Addr 也在 includePaths 下恢复。
func TestDiagnosticsViewIncludePathsRestores(t *testing.T) {
	view := diagnosticsTestConfig().DiagnosticsView(true)
	relay := view["relay"].(map[string]any)
	if relay["url"] != "wss://relay.secret-host.example:8443/agent" {
		t.Errorf("includePaths should restore relay url: %v", relay["url"])
	}
	if relay["deviceName"] != "my-secret-device" {
		t.Errorf("includePaths should restore deviceName: %v", relay["deviceName"])
	}
	if relay["secret"] != RedactedSecret {
		t.Errorf("secret must stay redacted even with includePaths: %v", relay["secret"])
	}
	shell := view["shell"].(map[string]any)
	if shell["cwd"] != "/home/user/confidential" || shell["command"] != "/usr/local/bin/fish" {
		t.Errorf("includePaths should restore shell: %v", shell)
	}
	if view["ipcEndpoint"] != "unix:/run/webterm/agent.sock" {
		t.Errorf("includePaths should restore ipcEndpoint: %v", view["ipcEndpoint"])
	}
	control, has := view["control"].(map[string]any)
	if !has || control["addr"] != "127.0.0.1:9999" {
		t.Errorf("includePaths should restore control addr: %v", view["control"])
	}
}

// TestDiagnosticsViewEmptyCommandIsDefault 空 shell command 折叠为 "default"。
func TestDiagnosticsViewEmptyCommandIsDefault(t *testing.T) {
	cfg := Config{Shell: ShellConfig{Command: ""}}
	view := cfg.DiagnosticsView(false)
	shell := view["shell"].(map[string]any)
	if shell["command"] != "default" {
		t.Errorf("empty command default = %v, want default", shell["command"])
	}
}

func TestSaveAndLoadConfigFile(t *testing.T) {
	clearConfigEnv(t)
	path := filepath.Join(t.TempDir(), "WebTerm Agent", "config.json")
	want := Config{
		Relay: RelayConfig{URL: "https://relay.example", Secret: "secret", DeviceName: "mac"},
		Shell: ShellConfig{Command: "/bin/sh", CWD: "/tmp"},
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
		"WEBTERM_IPC_ENDPOINT", "WEBTERM_SOCKET_PATH", "RELAY_URL", "RELAY_SECRET",
		"DEVICE_NAME", "WEBTERM_SHELL", "WEBTERM_MAX_UPLOAD_BYTES",
		"WEBTERM_AGENT_CONFIG", "WEBTERM_AGENT_RELAY_URL", "WEBTERM_AGENT_RELAY_SECRET",
		"WEBTERM_AGENT_DEVICE_NAME", "WEBTERM_AGENT_SOCKET_PATH", "WEBTERM_AGENT_SHELL",
		"WEBTERM_AGENT_SHELL_CWD", "WEBTERM_AGENT_SCROLLBACK_MAX_LINES",
		"WEBTERM_AGENT_SCROLLBACK_MAX_BYTES", "WEBTERM_AGENT_UPLOAD_MAX_BYTES",
	} {
		t.Setenv(key, "")
	}
}

func TestLoadStrictRejectsInvalidNumericEnv(t *testing.T) {
	clearConfigEnv(t)
	missing := filepath.Join(t.TempDir(), "missing.json")
	for _, key := range []string{
		"WEBTERM_AGENT_SCROLLBACK_MAX_LINES",
		"WEBTERM_AGENT_SCROLLBACK_MAX_BYTES",
		"WEBTERM_AGENT_UPLOAD_MAX_BYTES",
	} {
		for _, value := range []string{"abc", "0", "-5"} {
			t.Run(key+"="+value, func(t *testing.T) {
				t.Setenv(key, value)
				_, err := loadStrict(missing, false)
				if err == nil {
					t.Fatalf("%s=%s was accepted", key, value)
				}
				if !strings.Contains(err.Error(), key) || !strings.Contains(err.Error(), value) {
					t.Fatalf("error %q should name %s and %q", err, key, value)
				}
			})
		}
	}
}

func TestLoadStrictRejectsInvalidLegacyUploadEnv(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("WEBTERM_MAX_UPLOAD_BYTES", "abc")
	_, err := loadStrict(filepath.Join(t.TempDir(), "missing.json"), false)
	if err == nil || !strings.Contains(err.Error(), "WEBTERM_MAX_UPLOAD_BYTES") || !strings.Contains(err.Error(), "abc") {
		t.Fatalf("err = %v", err)
	}
}

func TestLoadInvalidNumericEnvStillFallsBackToDefaults(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("WEBTERM_AGENT_SCROLLBACK_MAX_LINES", "abc")
	t.Setenv("WEBTERM_AGENT_UPLOAD_MAX_BYTES", "0")
	cfg := Load(Options{})
	if cfg.Scrollback.MaxLines != DefaultScrollbackMaxLines ||
		cfg.Upload.MaxBytes != DefaultMaxUploadBytes {
		t.Fatalf("lenient Load = %#v", cfg)
	}
}

func TestLoadStrictRelayURLSchemeWhitelist(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("WEBTERM_AGENT_RELAY_SECRET", "secret")
	missing := filepath.Join(t.TempDir(), "missing.json")
	for _, scheme := range []string{"http", "https", "ws", "wss"} {
		t.Setenv("WEBTERM_AGENT_RELAY_URL", scheme+"://relay.example")
		if _, err := loadStrict(missing, false); err != nil {
			t.Fatalf("%s:// rejected: %v", scheme, err)
		}
	}
	for _, rawURL := range []string{"ftp://relay.example", "file://relay.example/agent"} {
		t.Setenv("WEBTERM_AGENT_RELAY_URL", rawURL)
		_, err := loadStrict(missing, false)
		if err == nil || !strings.Contains(err.Error(), "配置无效") {
			t.Fatalf("%s should be rejected with 配置无效, got %v", rawURL, err)
		}
	}
}

// TestLoadStrictAcceptsLegacyRelayProtocol 旧版配置携带 "protocol":"v2" 时，
// 严格加载必须成功（RelayConfig 保留只读兼容字段以通过 DisallowUnknownFields）。
// 该字段运行时从不读取、也不经 mergeConfig 传播，因此这里只断言加载成功，
// 不断言其值——值为空恰好保证旧配置一旦被重新保存会因 omitempty 丢弃 protocol。
func TestLoadStrictAcceptsLegacyRelayProtocol(t *testing.T) {
	clearConfigEnv(t)

	path := filepath.Join(t.TempDir(), "agent.json")
	data := `{
		"relay": {
			"url": "https://relay.example",
			"secret": "secret",
			"deviceName": "pc",
			"protocol": "v2"
		},
		"scrollback": {"maxLines": 1000, "maxBytes": 1048576},
		"upload": {"maxBytes": 104857600}
	}`
	if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := LoadStrict(Options{ConfigPath: path}); err != nil {
		t.Fatalf("legacy relay.protocol must remain readable: %v", err)
	}
}

// TestDefaultConfigDoesNotPersistRelayProtocol 新生成的配置（config init）不得
// 再包含 protocol 键：字段默认空值 + omitempty 会被 marshal 省略。
func TestDefaultConfigDoesNotPersistRelayProtocol(t *testing.T) {
	data, err := json.Marshal(Default())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), `"protocol"`) {
		t.Fatalf("new config must not persist relay.protocol: %s", data)
	}
}

// TestLoadStrictStillRejectsUnknownRelayFields 兼容修复不得弱化严格检查：
// relay 中出现真正的未知字段仍应报错。
func TestLoadStrictStillRejectsUnknownRelayFields(t *testing.T) {
	clearConfigEnv(t)

	path := filepath.Join(t.TempDir(), "agent.json")
	data := `{
		"relay": {
			"url": "https://relay.example",
			"secret": "secret",
			"deviceName": "pc",
			"unknownField": true
		}
	}`
	if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := LoadStrict(Options{ConfigPath: path}); err == nil {
		t.Fatal("unknown relay field must be rejected")
	}
}
