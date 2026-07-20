//go:build windows

package pty

import (
	"bytes"
	"io"
	"sync"
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

// 子进程退出后调用 BeginDrain 关闭伪控制台，输出管道应产生真正的 EOF：readLoop
// 先读完残留尾部数据再收到 EOF。BeginDrain 必须幂等。
func TestConPTYBeginDrainProducesEOFAndIsIdempotent(t *testing.T) {
	p, err := Start(Options{Command: "powershell.exe", Args: []string{"-NoProfile", "-Command", "[Console]::OutputEncoding=[Text.UTF8Encoding]::new();Write-Output '中文'"}, CWD: ".", Cols: 100, Rows: 30})
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()

	if _, err := p.Wait(); err != nil {
		t.Fatal(err)
	}

	buf := &bytes.Buffer{}
	readDone := make(chan error, 1)
	go func() {
		_, copyErr := io.Copy(buf, p)
		readDone <- copyErr
	}()

	if err := p.BeginDrain(); err != nil {
		t.Fatalf("BeginDrain: %v", err)
	}
	if err := p.BeginDrain(); err != nil {
		t.Fatalf("BeginDrain must be idempotent: %v", err)
	}

	select {
	case copyErr := <-readDone:
		if copyErr != nil {
			t.Fatalf("read after BeginDrain: %v", copyErr)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("ConPTY output did not reach EOF after BeginDrain")
	}
	if !bytes.Contains(buf.Bytes(), []byte("中文")) {
		t.Fatalf("output=%q missing 中文", buf.String())
	}
}

// BeginDrain 与 Close 可能并发调用（退出排空与强制关闭路径），两者对伪控制台/管道
// 句柄的关闭必须互斥且幂等，不能双重关闭。在 -race 下验证无数据竞争。
func TestConPTYBeginDrainAndCloseConcurrent(t *testing.T) {
	p, err := Start(Options{Command: "powershell.exe", Args: []string{"-NoProfile", "-Command", "Write-Output 'x'"}, CWD: ".", Cols: 100, Rows: 30})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := p.Wait(); err != nil {
		t.Fatal(err)
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); _ = p.BeginDrain() }()
	go func() { defer wg.Done(); _ = p.Close() }()
	wg.Wait()

	// 再次调用均应为幂等 no-op。
	_ = p.BeginDrain()
	if err := p.Close(); err != nil {
		t.Fatalf("Close after BeginDrain+Close: %v", err)
	}
}
