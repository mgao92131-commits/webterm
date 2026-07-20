package webtermcmd

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
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

func TestHookBackoffEscalatesAndCaps(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sess")
	wantDelays := []int64{1, 2, 4, 8, 15, 30, 30}
	for i, want := range wantDelays {
		before := time.Now().Unix()
		recordHookFailure(path)
		next, failures := readHookBackoff(path)
		if failures != i+1 {
			t.Fatalf("failure #%d: failures=%d, want %d", i+1, failures, i+1)
		}
		if delta := next - before; delta < want-1 || delta > want+1 {
			t.Fatalf("failure #%d: backoff delta=%ds, want ~%ds", i+1, delta, want)
		}
	}
}

func TestReadHookBackoffIgnoresCorruptState(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sess")
	if err := os.WriteFile(path, []byte("not-a-number xx\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	next, failures := readHookBackoff(path)
	if next != 0 || failures != 0 {
		t.Fatalf("corrupt state read as next=%d failures=%d, want 0/0", next, failures)
	}
	if withinHookBackoff(path) {
		t.Fatal("corrupt state must not be treated as active backoff")
	}
}

func TestWithinHookBackoff(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sess")
	if err := os.WriteFile(path, []byte(fmt.Sprintf("%d %d\n", time.Now().Unix()+60, 3)), 0o600); err != nil {
		t.Fatal(err)
	}
	if !withinHookBackoff(path) {
		t.Fatal("future nextRetryAt must be within backoff")
	}
	if err := os.WriteFile(path, []byte(fmt.Sprintf("%d %d\n", time.Now().Unix()-1, 3)), 0o600); err != nil {
		t.Fatal(err)
	}
	if withinHookBackoff(path) {
		t.Fatal("past nextRetryAt must allow retry")
	}
}

// hook-mode 在 IPC 不可用时必须快速失败、静默返回 nil，并记录退避；退避窗口内的
// 后续调用直接跳过、不再推进状态。
func TestHookModeFailureRecordsBackoffAndStaysSilent(t *testing.T) {
	stateDir := t.TempDir()
	t.Setenv("WEBTERM_HOOK_STATE_DIR", stateDir)
	t.Setenv("WEBTERM_SESSION_ID", "sess-1")
	t.Setenv("WEBTERM_IPC_ENDPOINT", "unix:"+filepath.Join(t.TempDir(), "missing.sock"))
	statePath := filepath.Join(stateDir, "sess-1")

	start := time.Now()
	if err := runSessionUpdateHookMode("sess-1", ""); err != nil {
		t.Fatalf("hook mode must not surface errors to the shell: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("hook mode failure took %s, want fast (<2s)", elapsed)
	}
	if _, failures := readHookBackoff(statePath); failures != 1 {
		t.Fatalf("failures=%d after one failure, want 1", failures)
	}

	next, _ := readHookBackoff(statePath)
	if err := runSessionUpdateHookMode("sess-1", ""); err != nil {
		t.Fatal(err)
	}
	if got, failures := readHookBackoff(statePath); got != next || failures != 1 {
		t.Fatalf("call within backoff advanced state: next=%d failures=%d", got, failures)
	}
}

// hook-mode 上报成功后必须清除退避状态，使后续 prompt 恢复正常上报。
func TestHookModeSuccessClearsBackoff(t *testing.T) {
	// Unix socket 路径有长度上限，使用短临时目录存放 socket。
	sockDir, err := os.MkdirTemp("", "wt")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(sockDir) })
	socket := filepath.Join(sockDir, "a.sock")
	listener, err := localipc.Listen(socket)
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
				scanner := bufio.NewScanner(conn)
				scanner.Buffer(make([]byte, 4096), 1<<20)
				if !scanner.Scan() {
					return
				}
				response, _ := json.Marshal(localipc.Envelope{
					Version: localipc.Version,
					Kind:    localipc.KindResponse,
					Type:    localipc.TypeSessionUpdate,
					Payload: json.RawMessage(`{"status":"ok"}`),
				})
				_, _ = conn.Write(append(response, '\n'))
			}(conn)
		}
	}()

	stateDir := t.TempDir()
	t.Setenv("WEBTERM_HOOK_STATE_DIR", stateDir)
	t.Setenv("WEBTERM_SESSION_ID", "sess-2")
	t.Setenv("WEBTERM_IPC_ENDPOINT", socket)
	statePath := filepath.Join(stateDir, "sess-2")
	// 预置一个已过期的退避状态：应当尝试上报并在成功后清除。
	if err := os.WriteFile(statePath, []byte(fmt.Sprintf("%d %d\n", time.Now().Unix()-1, 4)), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := runSessionUpdateHookMode("sess-2", ""); err != nil {
		t.Fatalf("hook mode success must return nil: %v", err)
	}
	if _, err := os.Stat(statePath); !os.IsNotExist(err) {
		t.Fatalf("backoff state must be cleared after success, stat err=%v", err)
	}
}
