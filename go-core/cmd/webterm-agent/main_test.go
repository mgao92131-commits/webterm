package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// root 与 run 子命令共用同一套参数，四种调用形式应解析到相同的变量。
func TestRootAndRunShareFlags(t *testing.T) {
	cases := []struct {
		name         string
		args         []string
		wantConfig   string
		wantEndpoint string
		wantMode     string
	}{
		{"root 带 config", []string{"--config", "agent.json"}, "agent.json", "", ""},
		{"run 带 config", []string{"run", "--config", "agent.json"}, "agent.json", "", ""},
		{"root 带 ipc-endpoint", []string{"--config", "agent.json", "--ipc-endpoint", "unix:/tmp/webterm.sock"}, "agent.json", "unix:/tmp/webterm.sock", ""},
		{"run 带 ipc-endpoint", []string{"run", "--config", "agent.json", "--ipc-endpoint", "npipe://./pipe/webterm-agent"}, "agent.json", "npipe://./pipe/webterm-agent", ""},
		{"socket 兼容参数", []string{"run", "--socket", "unix:/tmp/legacy.sock"}, "", "unix:/tmp/legacy.sock", ""},
		{"ipc-endpoint 优先", []string{"--socket", "unix:/tmp/legacy.sock", "--ipc-endpoint", "unix:/tmp/new.sock"}, "", "unix:/tmp/new.sock", ""},
		{"root 带 mode", []string{"--mode", "direct"}, "", "", "direct"},
		{"run 带 mode", []string{"run", "--mode", "relay"}, "", "", "relay"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var gotConfig, gotEndpoint, gotMode string
			root := newRootCommand(func(configPath, ipcEndpoint, mode string) error {
				gotConfig, gotEndpoint, gotMode = configPath, ipcEndpoint, mode
				return nil
			})
			root.SetArgs(tc.args)
			if err := root.Execute(); err != nil {
				t.Fatalf("Execute(%v) 失败: %v", tc.args, err)
			}
			if gotConfig != tc.wantConfig || gotEndpoint != tc.wantEndpoint || gotMode != tc.wantMode {
				t.Fatalf("解析结果 config=%q endpoint=%q mode=%q，期望 config=%q endpoint=%q mode=%q", gotConfig, gotEndpoint, gotMode, tc.wantConfig, tc.wantEndpoint, tc.wantMode)
			}
		})
	}
}

// --socket 作为隐藏兼容参数保留，--ipc-endpoint 为正式参数。
func TestSocketFlagHiddenCompat(t *testing.T) {
	root := newRootCommand(func(string, string, string) error { return nil })
	socket := root.PersistentFlags().Lookup("socket")
	if socket == nil {
		t.Fatal("缺少 --socket 兼容参数")
	}
	if !socket.Hidden {
		t.Fatal("--socket 兼容参数应为隐藏 flag")
	}
	if root.PersistentFlags().Lookup("ipc-endpoint") == nil {
		t.Fatal("缺少 --ipc-endpoint 参数")
	}
}

func TestConfigInitDirectWritesModeSpecificTemplate(t *testing.T) {
	path := filepath.Join(t.TempDir(), "direct.json")
	root := newRootCommand(func(string, string, string) error { return nil })
	root.SetArgs([]string{"config", "init", "--mode", "direct", "--path", path})
	if err := root.Execute(); err != nil {
		t.Fatalf("config init: %v", err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	for _, want := range []string{`"mode": "direct"`, `"password": ""`, `"allowInsecureRemote": false`, `"scrollback"`, `"upload"`} {
		if !strings.Contains(text, want) {
			t.Fatalf("template missing %s: %s", want, text)
		}
	}
	if strings.Contains(text, `"relay"`) {
		t.Fatalf("direct template contains relay: %s", text)
	}
	if err := os.Chmod(path, 0o644); err != nil {
		t.Fatal(err)
	}

	root = newRootCommand(func(string, string, string) error { return nil })
	root.SetArgs([]string{"config", "init", "--mode", "direct", "--path", path})
	if err := root.Execute(); err == nil || !strings.Contains(err.Error(), "已存在") {
		t.Fatalf("second init error = %v", err)
	}
	afterNoForce, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(afterNoForce) != text || info.Mode().Perm() != 0o644 {
		t.Fatalf("no-force init changed existing file: mode=%o contentChanged=%t", info.Mode().Perm(), string(afterNoForce) != text)
	}

	root = newRootCommand(func(string, string, string) error { return nil })
	root.SetArgs([]string{"config", "init", "--mode", "direct", "--path", path, "--force"})
	if err := root.Execute(); err != nil {
		t.Fatalf("forced init: %v", err)
	}
	info, err = os.Stat(path)
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("forced template permissions: info=%v err=%v", info, err)
	}
}

func TestInitRunCommandUsesModeOnlyForDefaultPath(t *testing.T) {
	if got := initRunCommand("direct", "", "/config/WebTerm Agent/direct.json"); got != "webterm-agent run --mode direct" {
		t.Fatalf("default command = %q", got)
	}
	got := initRunCommand("direct", "./my-direct.json", "my direct.json")
	if got != `webterm-agent run --config "my direct.json"` {
		t.Fatalf("custom command = %q", got)
	}
}
