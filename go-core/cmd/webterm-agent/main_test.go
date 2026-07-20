package main

import "testing"

// root 与 run 子命令共用同一套参数，四种调用形式应解析到相同的变量。
func TestRootAndRunShareFlags(t *testing.T) {
	cases := []struct {
		name         string
		args         []string
		wantConfig   string
		wantEndpoint string
	}{
		{"root 带 config", []string{"--config", "agent.json"}, "agent.json", ""},
		{"run 带 config", []string{"run", "--config", "agent.json"}, "agent.json", ""},
		{"root 带 ipc-endpoint", []string{"--config", "agent.json", "--ipc-endpoint", "unix:/tmp/webterm.sock"}, "agent.json", "unix:/tmp/webterm.sock"},
		{"run 带 ipc-endpoint", []string{"run", "--config", "agent.json", "--ipc-endpoint", "npipe://./pipe/webterm-agent"}, "agent.json", "npipe://./pipe/webterm-agent"},
		{"socket 兼容参数", []string{"run", "--socket", "unix:/tmp/legacy.sock"}, "", "unix:/tmp/legacy.sock"},
		{"ipc-endpoint 优先", []string{"--socket", "unix:/tmp/legacy.sock", "--ipc-endpoint", "unix:/tmp/new.sock"}, "", "unix:/tmp/new.sock"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var gotConfig, gotEndpoint string
			root := newRootCommand(func(configPath, ipcEndpoint string) error {
				gotConfig, gotEndpoint = configPath, ipcEndpoint
				return nil
			})
			root.SetArgs(tc.args)
			if err := root.Execute(); err != nil {
				t.Fatalf("Execute(%v) 失败: %v", tc.args, err)
			}
			if gotConfig != tc.wantConfig || gotEndpoint != tc.wantEndpoint {
				t.Fatalf("解析结果 config=%q endpoint=%q，期望 config=%q endpoint=%q", gotConfig, gotEndpoint, tc.wantConfig, tc.wantEndpoint)
			}
		})
	}
}

// --socket 作为隐藏兼容参数保留，--ipc-endpoint 为正式参数。
func TestSocketFlagHiddenCompat(t *testing.T) {
	root := newRootCommand(func(string, string) error { return nil })
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
