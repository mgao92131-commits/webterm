//go:build windows

package pty

import (
	"bytes"
	"io"
	"testing"
	"time"
)

// TEMP-DIAG: 验证 ConPTY 子进程 std handles 是否被错误继承（应为伪控制台）。
// 现象：powershell 的 Write-Output/PSReadLine 输出出现在 go test 的 stdout
// 而非 ConPTY 输出管道，疑似子进程继承了 go test 的管道 std handles。
// 对照：默认 vs STARTF_USESTDHANDLES（空 handles，参考 UserExistsError/conpty）。
// 定位后删除本文件与 backend_windows.go 中的 WEBTERM_PTY_DEBUG_STDHANDLES 开关。
func TestConPTYStdHandleDiagnostics(t *testing.T) {
	probe := "'IsOutRedirected=' + [Console]::IsOutputRedirected + ' IsInRedirected=' + [Console]::IsInputRedirected; " +
		"Write-Output 'MARKER_STDOUT'; [Console]::Write('MARKER_CONSOLE'); Start-Sleep -Milliseconds 300"

	run := func(name string, env map[string]string) {
		for k, v := range env {
			t.Setenv(k, v)
		}
		p, err := Start(Options{Command: "powershell.exe", Args: []string{"-NoProfile", "-Command", probe}, CWD: ".", Cols: 100, Rows: 30})
		if err != nil {
			t.Logf("%s: start err=%v", name, err)
			return
		}
		buf := &bytes.Buffer{}
		done := make(chan struct{})
		go func() { _, _ = io.Copy(buf, p); close(done) }()
		// 给输出充分时间到达管道后再结束进程。
		time.Sleep(3 * time.Second)
		code, waitErr := p.Wait()
		_ = p.Close()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
		}
		t.Logf("%s: exit=%d waitErr=%v pipe=%q", name, code, waitErr, buf.String())
	}

	run("default", nil)
	run("stdhandles", map[string]string{"WEBTERM_PTY_DEBUG_STDHANDLES": "1"})
}
