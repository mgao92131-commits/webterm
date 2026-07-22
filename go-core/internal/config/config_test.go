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
	if _, err := loadStrict(missing, false, ""); err == nil {
		t.Fatal("missing config should fail even without an explicit path")
	}
	if _, err := loadStrict(missing, true, ""); err == nil {
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
			Protocol:   RelayProtocolV2,
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
		Relay: RelayConfig{URL: "https://relay.example", Secret: "secret", DeviceName: "mac", Protocol: RelayProtocolV2},
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
		"DEVICE_NAME", "WEBTERM_RELAY_PROTOCOL", "WEBTERM_SHELL", "WEBTERM_MAX_UPLOAD_BYTES",
		"WEBTERM_AGENT_CONFIG", "WEBTERM_AGENT_RELAY_URL", "WEBTERM_AGENT_RELAY_SECRET",
		"WEBTERM_AGENT_DEVICE_NAME", "WEBTERM_AGENT_SOCKET_PATH", "WEBTERM_AGENT_SHELL",
		"WEBTERM_AGENT_SHELL_CWD", "WEBTERM_AGENT_SCROLLBACK_MAX_LINES",
		"WEBTERM_AGENT_SCROLLBACK_MAX_BYTES", "WEBTERM_AGENT_UPLOAD_MAX_BYTES",
		"WEBTERM_AGENT_MODE", "WEBTERM_AGENT_DIRECT_ADDR",
		"WEBTERM_AGENT_DIRECT_USERNAME", "WEBTERM_AGENT_DIRECT_PASSWORD",
		"WEBTERM_AGENT_DIRECT_ALLOW_INSECURE_REMOTE",
	} {
		t.Setenv(key, "")
	}
}

func TestLoadStrictRejectsInvalidNumericEnv(t *testing.T) {
	clearConfigEnv(t)
	path := writeConfigFile(t, relayConfigJSON)
	for _, key := range []string{
		"WEBTERM_AGENT_SCROLLBACK_MAX_LINES",
		"WEBTERM_AGENT_SCROLLBACK_MAX_BYTES",
		"WEBTERM_AGENT_UPLOAD_MAX_BYTES",
	} {
		for _, value := range []string{"abc", "0", "-5"} {
			t.Run(key+"="+value, func(t *testing.T) {
				t.Setenv(key, value)
				_, err := loadStrict(path, true, "")
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
	_, err := loadStrict(writeConfigFile(t, relayConfigJSON), true, "")
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
	path := writeConfigFile(t, relayConfigJSON)
	for _, scheme := range []string{"http", "https", "ws", "wss"} {
		t.Setenv("WEBTERM_AGENT_RELAY_URL", scheme+"://relay.example")
		if _, err := loadStrict(path, true, ""); err != nil {
			t.Fatalf("%s:// rejected: %v", scheme, err)
		}
	}
	for _, rawURL := range []string{"ftp://relay.example", "file://relay.example/agent"} {
		t.Setenv("WEBTERM_AGENT_RELAY_URL", rawURL)
		_, err := loadStrict(path, true, "")
		if err == nil || !strings.Contains(err.Error(), "配置无效") {
			t.Fatalf("%s should be rejected with 配置无效, got %v", rawURL, err)
		}
	}
}

// writeConfigFile 写入一个 strict 可解析的配置文件并返回其路径。
func writeConfigFile(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "agent.json")
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	return path
}

const directConfigJSON = `{"mode":"direct","direct":{"addr":"127.0.0.1:8080","username":"admin","password":"pw"}}`

const relayConfigJSON = `{"mode":"relay","relay":{"url":"https://relay.example","secret":"secret","deviceName":"pc"}}`

// mode 是运行配置的必填字段，不再默认 relay。
func TestMissingModeRejected(t *testing.T) {
	clearConfigEnv(t)
	path := writeConfigFile(t, `{"relay":{"url":"https://relay.example","secret":"secret"}}`)
	if _, err := loadStrict(path, true, ""); err == nil || !strings.Contains(err.Error(), "mode") {
		t.Fatalf("missing mode error = %v", err)
	}
}

func TestLegacyRelayConfigWithoutModeRejected(t *testing.T) {
	clearConfigEnv(t)
	path := writeConfigFile(t, `{
		"relay": {"url": "wss://relay.example", "secret": "secret", "deviceName": "my-pc", "protocol": "v2"},
		"shell": {"command": "", "cwd": "/tmp"},
		"scrollback": {"maxLines": 10000, "maxBytes": 16777216},
		"upload": {"maxBytes": 104857600}
	}`)
	if _, err := loadStrict(path, true, ""); err == nil || !strings.Contains(err.Error(), "mode") {
		t.Fatalf("missing mode error = %v", err)
	}
}

// direct 模式不要求 relay.url / relay.secret。
func TestDirectDoesNotRequireRelayConfig(t *testing.T) {
	clearConfigEnv(t)
	path := writeConfigFile(t, directConfigJSON)
	cfg, err := loadStrict(path, true, "")
	if err != nil {
		t.Fatalf("direct config without relay rejected: %v", err)
	}
	if cfg.Mode != ModeDirect {
		t.Fatalf("Mode = %q, want direct", cfg.Mode)
	}
	if cfg.Direct.Addr != "127.0.0.1:8080" || cfg.Direct.Username != "admin" || cfg.Direct.Password != "pw" {
		t.Fatalf("Direct = %#v", cfg.Direct)
	}
}

// relay 模式不校验 direct 字段。
func TestRelayDoesNotRequireDirectConfig(t *testing.T) {
	clearConfigEnv(t)
	path := writeConfigFile(t, relayConfigJSON)
	cfg, err := loadStrict(path, true, "")
	if err != nil {
		t.Fatalf("relay config without direct rejected: %v", err)
	}
	if cfg.Mode != ModeRelay {
		t.Fatalf("Mode = %q, want relay", cfg.Mode)
	}
}

// direct 模式缺少 addr/username/password 任一项都必须失败。
func TestDirectRequiresFields(t *testing.T) {
	clearConfigEnv(t)
	// addr 有默认值（127.0.0.1:8080），不再强制要求；username/password 仍必填。
	cases := []struct {
		name string
		body string
		want string
	}{
		{"username", `{"mode":"direct","direct":{"addr":"127.0.0.1:8080","password":"pw"}}`, "direct.username"},
		{"password", `{"mode":"direct","direct":{"addr":"127.0.0.1:8080","username":"admin"}}`, "direct.password"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			clearConfigEnv(t)
			path := writeConfigFile(t, tc.body)
			_, err := loadStrict(path, true, "")
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want mention of %s", err, tc.want)
			}
		})
	}
}

// TestDirectDefaultsAddr 未设置 addr 时使用默认回环地址。
func TestDirectDefaultsAddr(t *testing.T) {
	clearConfigEnv(t)
	path := writeConfigFile(t, `{"mode":"direct","direct":{"username":"admin","password":"pw"}}`)
	cfg, err := loadStrict(path, true, "")
	if err != nil {
		t.Fatalf("direct config without addr rejected: %v", err)
	}
	if cfg.Direct.Addr != DefaultDirectAddr {
		t.Fatalf("Direct.Addr = %q, want default %q", cfg.Direct.Addr, DefaultDirectAddr)
	}
}

// TestDirectInsecureRemoteGate 非回环明文监听必须显式 allowInsecureRemote。
func TestDirectInsecureRemoteGate(t *testing.T) {
	clearConfigEnv(t)
	// 回环地址默认允许。
	loopback := writeConfigFile(t, `{"mode":"direct","direct":{"addr":"127.0.0.1:8080","username":"admin","password":"pw"}}`)
	if _, err := loadStrict(loopback, true, ""); err != nil {
		t.Fatalf("loopback listen rejected: %v", err)
	}
	// 非回环地址未显式允许时被拒绝。
	wildcard := writeConfigFile(t, `{"mode":"direct","direct":{"addr":"0.0.0.0:8080","username":"admin","password":"pw"}}`)
	if _, err := loadStrict(wildcard, true, ""); err == nil || !strings.Contains(err.Error(), "allowInsecureRemote") {
		t.Fatalf("wildcard listen should require allowInsecureRemote, got %v", err)
	}
	lan := writeConfigFile(t, `{"mode":"direct","direct":{"addr":"192.168.1.20:8080","username":"admin","password":"pw"}}`)
	if _, err := loadStrict(lan, true, ""); err == nil {
		t.Fatal("LAN listen should require allowInsecureRemote")
	}
	// 显式允许后通过。
	allowed := writeConfigFile(t, `{"mode":"direct","direct":{"addr":"0.0.0.0:8080","username":"admin","password":"pw","allowInsecureRemote":true}}`)
	if _, err := loadStrict(allowed, true, ""); err != nil {
		t.Fatalf("wildcard listen with allowInsecureRemote rejected: %v", err)
	}
}

func TestDirectAllowInsecureRemoteEnvOverridesTrueWithFalse(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("WEBTERM_AGENT_DIRECT_ALLOW_INSECURE_REMOTE", "false")
	path := writeConfigFile(t, `{"mode":"direct","direct":{"addr":"0.0.0.0:8080","username":"admin","password":"pw","allowInsecureRemote":true}}`)
	if _, err := loadStrict(path, true, ""); err == nil || !strings.Contains(err.Error(), "allowInsecureRemote") {
		t.Fatalf("false environment override should disable insecure remote, got %v", err)
	}
}

func TestDirectAllowInsecureRemoteEnvOverridesFalseWithTrue(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("WEBTERM_AGENT_DIRECT_ALLOW_INSECURE_REMOTE", "true")
	path := writeConfigFile(t, `{"mode":"direct","direct":{"addr":"0.0.0.0:8080","username":"admin","password":"pw","allowInsecureRemote":false}}`)
	if _, err := loadStrict(path, true, ""); err != nil {
		t.Fatalf("true environment override rejected: %v", err)
	}
}

func TestDirectAllowInsecureRemoteEnvAcceptsZeroAndOne(t *testing.T) {
	for _, test := range []struct {
		name  string
		value string
		ok    bool
	}{
		{name: "zero", value: "0", ok: false},
		{name: "one", value: "1", ok: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			clearConfigEnv(t)
			t.Setenv("WEBTERM_AGENT_DIRECT_ALLOW_INSECURE_REMOTE", test.value)
			path := writeConfigFile(t, `{"mode":"direct","direct":{"addr":"0.0.0.0:8080","username":"admin","password":"pw","allowInsecureRemote":false}}`)
			_, err := loadStrict(path, true, "")
			if test.ok && err != nil {
				t.Fatalf("1 should enable insecure remote: %v", err)
			}
			if !test.ok && (err == nil || !strings.Contains(err.Error(), "allowInsecureRemote")) {
				t.Fatalf("0 should disable insecure remote, got %v", err)
			}
		})
	}
}

func TestDirectAllowInsecureRemoteEnvRejectsInvalidValue(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("WEBTERM_AGENT_DIRECT_ALLOW_INSECURE_REMOTE", "abc")
	path := writeConfigFile(t, `{"mode":"direct","direct":{"username":"admin","password":"pw"}}`)
	if _, err := loadStrict(path, true, ""); err == nil || !strings.Contains(err.Error(), "WEBTERM_AGENT_DIRECT_ALLOW_INSECURE_REMOTE") {
		t.Fatalf("invalid boolean environment should fail, got %v", err)
	}
}

func TestDirectAllowInsecureRemoteEnvUnsetPreservesFileValue(t *testing.T) {
	clearConfigEnv(t)
	path := writeConfigFile(t, `{"mode":"direct","direct":{"addr":"0.0.0.0:8080","username":"admin","password":"pw","allowInsecureRemote":true}}`)
	cfg, err := loadStrict(path, true, "")
	if err != nil {
		t.Fatalf("file allowInsecureRemote rejected when env unset: %v", err)
	}
	if !cfg.Direct.AllowInsecureRemote {
		t.Fatal("unset environment should preserve file allowInsecureRemote=true")
	}
}

// hybrid、auto、direct+relay 等未知模式必须失败，并提示支持的模式。
func TestUnknownModeRejected(t *testing.T) {
	for _, mode := range []string{"hybrid", "auto", "direct+relay", "p2p"} {
		t.Run(mode, func(t *testing.T) {
			clearConfigEnv(t)
			path := writeConfigFile(t, `{"mode":"`+mode+`"}`)
			_, err := loadStrict(path, true, "")
			if err == nil {
				t.Fatalf("mode %q was accepted", mode)
			}
			if !strings.Contains(err.Error(), mode) || !strings.Contains(err.Error(), "direct") || !strings.Contains(err.Error(), "relay") {
				t.Fatalf("error %q should name the bad mode and the supported modes", err)
			}
		})
	}
}

// direct.password 与 relay.secret 一样必须脱敏。
func TestDirectPasswordRedacted(t *testing.T) {
	cfg := Config{Mode: ModeDirect, Direct: DirectConfig{Addr: "127.0.0.1:8080", Username: "admin", Password: "direct-pw"}}
	redacted := cfg.Redacted()
	if redacted.Direct.Password != RedactedSecret {
		t.Fatalf("redacted direct password = %q", redacted.Direct.Password)
	}
	if cfg.Direct.Password != "direct-pw" {
		t.Fatal("Redacted mutated original config")
	}
	view := cfg.DiagnosticsView(true)
	direct := view["direct"].(map[string]any)
	if direct["password"] != RedactedSecret {
		t.Fatalf("diagnostics direct password = %v, want redacted even with includePaths", direct["password"])
	}
	if view["mode"] != "direct" {
		t.Fatalf("diagnostics mode = %v, want direct", view["mode"])
	}
}

// CLI --mode 只选择文件，不能覆盖文件内部的 mode。
func TestModeFlagMismatchRejected(t *testing.T) {
	clearConfigEnv(t)
	// 配置同时包含 direct 与 relay，文件声明 relay。
	path := writeConfigFile(t, `{
		"mode": "relay",
		"direct": {"addr": "127.0.0.1:8080", "username": "admin", "password": "pw"},
		"relay": {"url": "https://relay.example", "secret": "secret"}
	}`)
	byFile, err := loadStrict(path, true, "")
	if err != nil || byFile.Mode != ModeRelay {
		t.Fatalf("byFile Mode=%q err=%v, want relay", byFile.Mode, err)
	}
	_, err = loadStrict(path, true, "direct")
	if err == nil || !strings.Contains(err.Error(), "模式不匹配") {
		t.Fatalf("mode mismatch error = %v", err)
	}
}

// 环境变量 WEBTERM_AGENT_MODE 作为文件选择结果时必须与文件 mode 一致。
func TestModeEnvSelectsDirect(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("WEBTERM_AGENT_MODE", "direct")
	path := writeConfigFile(t, directConfigJSON)
	cfg, err := loadStrict(path, true, "")
	if err != nil {
		t.Fatalf("env direct config rejected: %v", err)
	}
	if cfg.Mode != ModeDirect || cfg.Direct.Addr != "127.0.0.1:8080" {
		t.Fatalf("cfg = %#v", cfg)
	}
}
