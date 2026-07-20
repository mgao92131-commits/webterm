//go:build windows

package pty

import (
	"bytes"
	"testing"
	"time"
)

func TestConPTYRunsPowerShellAndReleasesOnExit(t *testing.T) {
	p, err := Start(Options{Command: "powershell.exe", Args: []string{"-NoProfile", "-Command", "[Console]::OutputEncoding=[Text.UTF8Encoding]::new();Write-Output '中文'"}, CWD: ".", Cols: 100, Rows: 30})
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	if err := p.Resize(120, 40); err != nil {
		t.Fatal(err)
	}
	// 分块读管道并先等到期望输出：ConPTY 输出管道不随子进程退出 EOF，
	// Wait→Close 可能抢在读 goroutine 排空管道尾部之前关闭它。
	chunks := make(chan []byte, 16)
	go func() {
		buf := make([]byte, 4096)
		for {
			n, readErr := p.Read(buf)
			if n > 0 {
				chunks <- append([]byte(nil), buf[:n]...)
			}
			if readErr != nil {
				close(chunks)
				return
			}
		}
	}()
	var captured []byte
	deadline := time.Now().Add(15 * time.Second)
	for !bytes.Contains(captured, []byte("中文")) {
		select {
		case chunk, ok := <-chunks:
			if !ok {
				t.Fatalf("pipe closed before output arrived: output=%q", captured)
			}
			captured = append(captured, chunk...)
		case <-time.After(time.Until(deadline)):
			t.Fatalf("output=%q", captured)
		}
	}
	if _, err := p.Wait(); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(); err != nil {
		t.Fatal(err)
	}
}
