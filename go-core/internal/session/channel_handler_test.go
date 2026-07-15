package session

import (
	"context"
	"testing"
	"time"
)

type recordingChannelSink struct {
	frames chan []byte
}

func (sink *recordingChannelSink) WriteFrame(_ context.Context, payload []byte, binary bool) error {
	if binary {
		copied := append([]byte(nil), payload...)
		sink.frames <- copied
	}
	return nil
}

func TestTerminalChannelHandlerDirectFrameBoundary(t *testing.T) {
	manager := NewManager(TerminalDefaults{Command: "/bin/sh", CWD: "."})
	terminal, err := manager.Create(".")
	if err != nil {
		t.Fatalf("create terminal: %v", err)
	}
	defer manager.Close(terminal.ID())

	sink := &recordingChannelSink{frames: make(chan []byte, 8)}
	handler := NewTerminalChannelHandler(terminal, sink)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go handler.Run(ctx)

	handler.HandleFrame(resumeHello(false, "", 0, 0), true)

	select {
	case <-sink.frames:
		// Hello 后第一个输出是 info 或 initial sync；两者都证明帧已经
		// 不经任何伪造的 Read 队列，直接进入 Runtime 客户端链路。
	case <-time.After(2 * time.Second):
		t.Fatal("terminal channel handler produced no frame")
	}

	handler.Close()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if terminal.Info().Clients == 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("terminal clients = %d, want 0", terminal.Info().Clients)
}
