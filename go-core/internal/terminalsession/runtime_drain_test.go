package terminalsession

import (
	"context"
	"fmt"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/terminalengine"
)

// drainFrameCollector 用旧调用路径（SendInitial=nil）收集 runtime 广播的帧。
type drainFrameCollector struct {
	mu     sync.Mutex
	frames []terminalengine.ScreenFrame
}

func (c *drainFrameCollector) send(frame terminalengine.ScreenFrame) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.frames = append(c.frames, frame)
}

func (c *drainFrameCollector) snapshot() []terminalengine.ScreenFrame {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]terminalengine.ScreenFrame(nil), c.frames...)
}

func drainFrameText(frame terminalengine.ScreenFrame) string {
	var builder strings.Builder
	for _, line := range frame.Screen {
		for _, run := range line.Runs {
			for _, cell := range run.Cells {
				builder.WriteString(cell.Text)
			}
		}
		builder.WriteByte('\n')
	}
	return builder.String()
}

// 进程退出（PTY EOF）后，DrainAndClose 必须等待 readLoop 读完尾部数据、
// actor 应用完所有输出并强制最后一次投影：最终帧必须包含最后输出的标记。
func TestRuntimeDrainAndCloseFlushesTailOutput(t *testing.T) {
	outR, outW := io.Pipe()
	inR, inW := io.Pipe()
	pty := &benchFakePTY{reader: outR, writer: inW}
	discardDone := make(chan struct{})
	go func() {
		_, _ = io.Copy(io.Discard, inR)
		close(discardDone)
	}()

	r := NewRuntime("drain-tail", pty, 30, 100)
	t.Cleanup(func() {
		_ = r.Close()
		_ = outW.Close()
		_ = inW.Close()
		<-discardDone
	})

	collector := &drainFrameCollector{}
	r.AttachClient(&ScreenClient{ID: "c1", Send: collector.send})
	// 等待初始同步帧到达，确认 client 已进入 synced 状态。
	deadline := time.Now().Add(2 * time.Second)
	for len(collector.snapshot()) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("initial snapshot not received")
		}
		time.Sleep(5 * time.Millisecond)
	}

	var script strings.Builder
	for i := 1; i <= 200; i++ {
		fmt.Fprintf(&script, "line-%d\r\n", i)
	}
	script.WriteString("__END_MARKER__\r\n")
	totalBytes := uint64(script.Len())

	// 生产者持续写入尾部数据；DrainAndClose 与写入并发开始，必须等到
	// EOF 且全部输出应用完毕后才返回。
	go func() {
		_, _ = io.WriteString(outW, script.String())
		_ = outW.Close()
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := r.DrainAndClose(ctx); err != nil {
		t.Fatalf("DrainAndClose: %v", err)
	}

	events, bytes := r.PTYOutputSnapshot()
	if events == 0 || bytes != totalBytes {
		t.Fatalf("pty output events=%d bytes=%d, want bytes=%d（尾部数据未全部应用）", events, bytes, totalBytes)
	}

	frames := collector.snapshot()
	if len(frames) == 0 {
		t.Fatal("no frames received")
	}
	last := frames[len(frames)-1]
	if text := drainFrameText(last); !strings.Contains(text, "__END_MARKER__") {
		t.Fatalf("final frame missing __END_MARKER__; got:\n%s", text)
	}
}

// PTY 一直不 EOF 时（如子进程树占用），DrainAndClose 必须在 ctx 超时后
// 返回错误并退化为立即关闭，不能永久阻塞 waitLoop。
func TestRuntimeDrainAndCloseTimeoutFallsBackToClose(t *testing.T) {
	outR, outW := io.Pipe()
	inR, inW := io.Pipe()
	pty := &benchFakePTY{reader: outR, writer: inW}
	discardDone := make(chan struct{})
	go func() {
		_, _ = io.Copy(io.Discard, inR)
		close(discardDone)
	}()

	r := NewRuntime("drain-timeout", pty, 30, 100)
	t.Cleanup(func() {
		_ = r.Close()
		_ = outW.Close()
		_ = inW.Close()
		<-discardDone
	})

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if err := r.DrainAndClose(ctx); err == nil {
		t.Fatal("DrainAndClose with open PTY must time out, got nil")
	}

	// 超时后 runtime 已关闭：后续事件投递被丢弃，重复 Close 无副作用。
	if err := r.Close(); err != nil {
		t.Fatalf("Close after drain timeout: %v", err)
	}
}
