//go:build windows

package pty

import (
	"bytes"
	"io"
	"os/exec"
	"testing"
	"time"
)

// TEMP-DIAG: 定位 windows-latest runner 上 ConPTY 子进程 0xC0000142
// （STATUS_DLL_INIT_FAILED）的根因，修复后删除本文件与 backend_windows.go
// 中的 WEBTERM_PTY_DEBUG_* 开关。
//
// 对照维度：是否经过 ConPTY、cmd vs powershell、是否挂 Job、是否传自定义环境块。
func TestConPTYLaunchDiagnostics(t *testing.T) {
	// 1. 不经 ConPTY 直接启动 powershell（验证 runner 上控制台子进程本身可用）。
	out, err := exec.Command("powershell.exe", "-NoProfile", "-Command", "Write-Output plain-ps-ok").CombinedOutput()
	t.Logf("plain-exec-powershell: err=%v out=%q", err, bytes.TrimSpace(out))
	out, err = exec.Command("cmd.exe", "/c", "echo plain-cmd-ok").CombinedOutput()
	t.Logf("plain-exec-cmd: err=%v out=%q", err, bytes.TrimSpace(out))

	// 2. 经 ConPTY 启动，覆盖 cmd/powershell × 默认/无 Job/无自定义 env。
	run := func(name string, command string, args []string, env map[string]string) {
		for k, v := range env {
			t.Setenv(k, v)
		}
		p, err := Start(Options{Command: command, Args: args, CWD: ".", Cols: 100, Rows: 30})
		if err != nil {
			t.Logf("%s: start err=%v", name, err)
			return
		}
		output := make(chan []byte, 1)
		go func() { data, _ := io.ReadAll(p); output <- data }()
		code, waitErr := p.Wait()
		_ = p.Close()
		var data []byte
		select {
		case data = <-output:
		case <-time.After(5 * time.Second):
			data = []byte("<read timeout>")
		}
		t.Logf("%s: exit=%d waitErr=%v out=%q", name, code, waitErr, bytes.TrimSpace(data))
	}

	run("conpty-cmd", "cmd.exe", []string{"/c", "echo conpty-cmd-ok"}, nil)
	run("conpty-powershell", "powershell.exe", []string{"-NoProfile", "-Command", "Write-Output conpty-ps-ok"}, nil)
	run("conpty-powershell-nojob", "powershell.exe", []string{"-NoProfile", "-Command", "Write-Output conpty-ps-ok"}, map[string]string{"WEBTERM_PTY_DEBUG_NOJOB": "1"})
	run("conpty-powershell-noenv", "powershell.exe", []string{"-NoProfile", "-Command", "Write-Output conpty-ps-ok"}, map[string]string{"WEBTERM_PTY_DEBUG_NOENV": "1"})
	run("conpty-powershell-nojob-noenv", "powershell.exe", []string{"-NoProfile", "-Command", "Write-Output conpty-ps-ok"}, map[string]string{"WEBTERM_PTY_DEBUG_NOJOB": "1", "WEBTERM_PTY_DEBUG_NOENV": "1"})
}
