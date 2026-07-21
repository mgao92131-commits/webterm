//go:build windows

package pty_test

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/agenthooks"
	"webterm/go-core/internal/infrastructure/pty"
	"webterm/go-core/internal/localipc"
)

// TestPowerShellSessionHookReportsPromptOverIPC 端到端验证 Windows Session Hook 链路：
// ConPTY 启动 PowerShell 时注入 webterm-shell-hook.ps1，prompt 函数每次触发时调用
// webterm internal session-update，通过本地 IPC（Named Pipe）上报会话状态。
// 测试需要本机 go toolchain 构建真实的 webterm CLI；不可用时报 Skip。
func TestPowerShellSessionHookReportsPromptOverIPC(t *testing.T) {
	goTool, err := exec.LookPath("go")
	if err != nil {
		t.Skipf("go toolchain 不可用，跳过集成测试: %v", err)
	}

	tempDir := t.TempDir()

	// 构建真实 webterm CLI，hook 脚本内的 session-update 调用直接执行它。
	webtermBin := filepath.Join(tempDir, "webterm.exe")
	moduleRoot, err := filepath.Abs(filepath.Join("..", "..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	build := exec.Command(goTool, "build", "-o", webtermBin, "./cmd/webterm")
	build.Dir = moduleRoot
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("构建 webterm CLI 失败: %v\n%s", err, out)
	}

	// 在隔离 runtime 目录安装 hook，取出 PowerShell hook 脚本路径。
	runtimeDir := filepath.Join(tempDir, "runtime")
	if _, _, err := agenthooks.InstallShellHookAt(runtimeDir, webtermBin); err != nil {
		t.Fatalf("安装 shell hook 失败: %v", err)
	}
	hookPath := filepath.Join(runtimeDir, "bin", "webterm-shell-hook.ps1")
	if info, err := os.Stat(hookPath); err != nil || info.IsDir() {
		t.Fatalf("PowerShell hook 脚本不存在: %s", hookPath)
	}

	// CI 诊断：在 hook 脚本中注入调试埋点，定位"第二次上报为何消失"。
	// 记录每次 Invoke 的时间与捕获到的 last 命令、子进程 spawn 结果、catch 到的异常。
	hookDebugLog := filepath.Join(tempDir, "hook-debug.log")
	instrumentPowerShellHook(t, hookPath, hookDebugLog)
	hookStateDir := filepath.Join(tempDir, "hookstate")

	// 起本地 IPC 服务端接收 session_update 信封。
	endpoint := fmt.Sprintf("npipe://./pipe/webterm-hook-test-%d", os.Getpid())
	listener, err := localipc.Listen(endpoint)
	if err != nil {
		t.Fatalf("监听 IPC endpoint 失败: %v", err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	updates := serveSessionUpdates(listener)

	// 显式 powershell.exe 走显式注入路径；Env 中带上 hook 脚本和 IPC endpoint。
	proc, err := pty.Start(pty.Options{
		Command: "powershell.exe",
		Args:    []string{"-NoProfile"},
		CWD:     tempDir,
		Cols:    100,
		Rows:    30,
		Env: map[string]string{
			"WEBTERM_POWERSHELL_HOOK": hookPath,
			"WEBTERM_INTEGRATION":     "1",
			"WEBTERM_SESSION_ID":      "hook-e2e-test",
			"WEBTERM_IPC_ENDPOINT":    endpoint,
			"WEBTERM_HOOK_STATE_DIR":  hookStateDir,
			"WEBTERM_HOOK_DEBUG":      hookDebugLog,
		},
	})
	if err != nil {
		t.Fatalf("启动 PowerShell 失败: %v", err)
	}
	t.Cleanup(func() { _ = proc.Close() })
	// 持续排空 ConPTY 输出，避免管道缓冲写满后 PowerShell 阻塞。
	// 同时保留一份输出用于失败诊断（runner 上无法本地复现，超时时需要看到画面）。
	var screenBuf lockedBuffer
	go func() { _, _ = io.Copy(io.MultiWriter(io.Discard, &screenBuf), proc) }()

	// CI runner 上 Windows PowerShell 首次启动较慢，放宽整体超时。
	start := time.Now()
	deadline := time.After(90 * time.Second)
	sentEcho := false
	for {
		select {
		case update := <-updates:
			t.Logf("%.1fs 收到 update: shell_state=%s input_kind=%s last_input=%q",
				time.Since(start).Seconds(), update.ShellState, update.InputKind, update.LastInput)
			if !sentEcho {
				// 收到初始 prompt 的上报即证明 hook 注入成功，此时写入命令。
				sentEcho = true
				if _, err := proc.Write([]byte("echo hello\r")); err != nil {
					t.Fatalf("写入 PTY 失败: %v", err)
				}
				t.Logf("%.1fs 已写入 echo hello", time.Since(start).Seconds())
				continue
			}
			if update.LastInput == "" {
				continue
			}
			if update.ShellState != "prompt" {
				t.Fatalf("shell_state=%q，期望 prompt", update.ShellState)
			}
			if update.InputKind != "shell" {
				t.Fatalf("input_kind=%q，期望 shell", update.InputKind)
			}
			if update.CWD == "" {
				t.Fatal("cwd 为空")
			}
			t.Logf("收到 session_update: shell_state=%s input_kind=%s cwd=%s last_input=%q",
				update.ShellState, update.InputKind, update.CWD, update.LastInput)
			return
		case <-deadline:
			t.Logf("超时前 ConPTY 画面尾部:\n%s", screenBuf.Tail(4<<10))
			if data, err := os.ReadFile(hookDebugLog); err == nil {
				t.Logf("hook 调试日志:\n%s", data)
			} else {
				t.Logf("hook 调试日志不可读: %v", err)
			}
			if entries, err := os.ReadDir(hookStateDir); err == nil {
				for _, e := range entries {
					data, _ := os.ReadFile(filepath.Join(hookStateDir, e.Name()))
					t.Logf("hook 退避状态 %s: %s", e.Name(), data)
				}
			} else {
				t.Logf("hook 退避状态目录不可读: %v", err)
			}
			if !sentEcho {
				t.Fatal("超时：未收到初始 prompt 的 session_update，hook 注入可能未生效")
			}
			t.Fatal("超时：未收到 last_input 非空的 session_update")
		}
	}
}

// instrumentPowerShellHook 在生成的 hook 脚本里注入 CI 调试埋点（仅测试使用）。
// 埋点经 WEBTERM_HOOK_DEBUG 环境变量指定的文件输出，避免污染 ConPTY 画面。
func instrumentPowerShellHook(t *testing.T, hookPath, debugLog string) {
	t.Helper()
	data, err := os.ReadFile(hookPath)
	if err != nil {
		t.Fatalf("读取 hook 脚本失败: %v", err)
	}
	content := string(data)

	// 探针 1：prompt 函数入口，区分"prompt 未再触发"与"Invoke 内部失败"。
	old := "  function global:prompt {\n    Invoke-WebTermSessionUpdate"
	if !strings.Contains(content, old) {
		t.Fatalf("hook 脚本缺少 prompt 函数定义，模板可能已变化")
	}
	content = strings.Replace(content, old,
		"  function global:prompt {\n    if ($env:WEBTERM_HOOK_DEBUG) { Add-Content -Path $env:WEBTERM_HOOK_DEBUG -Value (\"{0:HH:mm:ss.fff} prompt fired\" -f (Get-Date)) }\n    Invoke-WebTermSessionUpdate", 1)

	old = "  if ($null -ne $history) { $last = $history.CommandLine }"
	if !strings.Contains(content, old) {
		t.Fatalf("hook 脚本缺少 history 捕获行，模板可能已变化")
	}
	content = strings.Replace(content, old, old+
		"\n  if ($env:WEBTERM_HOOK_DEBUG) { Add-Content -Path $env:WEBTERM_HOOK_DEBUG -Value (\"{0:HH:mm:ss.fff} invoke last=[{1}] bin=[{2}]\" -f (Get-Date), $last, $script:WebTermBin) }", 1)

	old = "    if ($null -ne $proc) { $proc.Dispose() }"
	if !strings.Contains(content, old) {
		t.Fatalf("hook 脚本缺少 spawn 行，模板可能已变化")
	}
	content = strings.Replace(content, old,
		"    if ($env:WEBTERM_HOOK_DEBUG) { Add-Content -Path $env:WEBTERM_HOOK_DEBUG -Value (\"{0:HH:mm:ss.fff} spawned\" -f (Get-Date)) }\n"+old, 1)

	old = "  } catch { }"
	if !strings.Contains(content, old) {
		t.Fatalf("hook 脚本缺少 catch 行，模板可能已变化")
	}
	content = strings.Replace(content, old,
		"  } catch { if ($env:WEBTERM_HOOK_DEBUG) { Add-Content -Path $env:WEBTERM_HOOK_DEBUG -Value (\"{0:HH:mm:ss.fff} catch: $($_.Exception.Message)\" -f (Get-Date)) } }", 1)

	if err := os.WriteFile(hookPath, []byte(content), 0o600); err != nil {
		t.Fatalf("写入插桩 hook 脚本失败: %v", err)
	}
	t.Logf("hook 调试日志路径: %s", debugLog)
}

// lockedBuffer 收集 ConPTY 输出，供超时诊断读取；并发写安全。
type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	// 只保留尾部，避免长跑测试内存膨胀。
	const maxKeep = 64 << 10
	if b.buf.Len()+len(p) > maxKeep {
		drop := b.buf.Len() + len(p) - maxKeep
		if drop > b.buf.Len() {
			drop = b.buf.Len()
		}
		b.buf.Next(drop)
	}
	return b.buf.Write(p)
}

func (b *lockedBuffer) Tail(n int) string {
	b.mu.Lock()
	defer b.mu.Unlock()
	data := b.buf.Bytes()
	if len(data) > n {
		data = data[len(data)-n:]
	}
	return string(data)
}

// serveSessionUpdates 接受 IPC 连接，解码 session_update 信封并回 OK 响应。
func serveSessionUpdates(listener net.Listener) <-chan localipc.SessionUpdate {
	updates := make(chan localipc.SessionUpdate, 16)
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go handleSessionUpdateConn(conn, updates)
		}
	}()
	return updates
}

func handleSessionUpdateConn(conn net.Conn, updates chan<- localipc.SessionUpdate) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	scanner := bufio.NewScanner(conn)
	scanner.Buffer(make([]byte, 4096), 1<<20)
	if !scanner.Scan() {
		return
	}
	var envelope localipc.Envelope
	if err := json.Unmarshal(scanner.Bytes(), &envelope); err != nil {
		return
	}
	if envelope.Type != localipc.TypeSessionUpdate {
		return
	}
	var update localipc.SessionUpdate
	if err := localipc.DecodePayload(envelope.Payload, &update); err != nil {
		return
	}
	updates <- update
	response, err := json.Marshal(localipc.Envelope{
		Version: localipc.Version,
		Kind:    localipc.KindResponse,
		Type:    localipc.TypeSessionUpdate,
		Payload: json.RawMessage(`{"status":"ok"}`),
	})
	if err != nil {
		return
	}
	_, _ = conn.Write(append(response, '\n'))
}
