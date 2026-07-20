package webtermcmd

import (
	"errors"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"webterm/go-core/internal/localipc"
)

func TestExpandPath(t *testing.T) {
	home, err := os.UserHomeDir()
	if err != nil {
		t.Fatalf("获取用户主目录失败：%v", err)
	}
	cases := []struct {
		name  string
		input string
		want  string
	}{
		{"波浪号本身", "~", home},
		{"Unix 风格前缀", "~/Documents/file", filepath.Join(home, "Documents/file")},
		{"Windows 风格前缀", `~\Documents\file`, filepath.Join(home, `Documents\file`)},
		{"Windows 绝对路径原样返回", `C:\path\file`, `C:\path\file`},
		{"UNC 路径原样返回", `\\server\share\file`, `\\server\share\file`},
		{"普通相对路径原样返回", "Documents/file", "Documents/file"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := expandPath(tc.input); got != tc.want {
				t.Fatalf("expandPath(%q) = %q，期望 %q", tc.input, got, tc.want)
			}
		})
	}
}

// TestRequestShortTimeout 用一个接受连接但不响应的本地 listener 模拟 Agent 卡死，
// 验证短请求在约 5 秒内返回 net.Error 超时错误，而不是永久阻塞。
func TestRequestShortTimeout(t *testing.T) {
	socket := filepath.Join(t.TempDir(), "agent.sock")
	listener, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatalf("监听 unix socket 失败：%v", err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		// 接受连接但永远不响应，保持到测试结束。
		defer conn.Close()
		time.Sleep(30 * time.Second)
	}()

	env, err := localipc.NewRequest(localipc.KindCommand, localipc.TypeDevices, "req_timeout_test", localipc.DevicesRequest{})
	if err != nil {
		t.Fatalf("构造请求失败：%v", err)
	}

	type result struct {
		err     error
		elapsed time.Duration
	}
	done := make(chan result, 1)
	go func() {
		start := time.Now()
		_, err := request(socket, env)
		done <- result{err: err, elapsed: time.Since(start)}
	}()

	select {
	case res := <-done:
		if res.err == nil {
			t.Fatal("Agent 不响应时期望返回错误，实际为 nil")
		}
		var netErr net.Error
		if !errors.As(res.err, &netErr) || !netErr.Timeout() {
			t.Fatalf("期望 net.Error 超时错误，实际：%v", res.err)
		}
		if res.elapsed >= 10*time.Second {
			t.Fatalf("短请求耗时 %v，超出预期超时（约 5 秒）", res.elapsed)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("request 在 20 秒兜底时间内未返回，疑似永久阻塞")
	}
}
