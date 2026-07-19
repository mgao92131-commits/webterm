package session

import (
	"context"
	"testing"
	"time"

	"webterm/go-core/internal/terminalengine"
)

type countingChannelSink struct {
	written int
}

func (sink *countingChannelSink) WriteFrame(_ context.Context, payload []byte, _ bool) error {
	sink.written += len(payload)
	return nil
}

func waitForFrameCount(t *testing.T, client *terminalChannelRuntime, want uint64) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if client.ScreenWireSnapshot().FrameCount >= want {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("frameCount=%d, want >= %d", client.ScreenWireSnapshot().FrameCount, want)
}

func TestTerminalChannelRuntimeRecordsSnapshotAndPatchBytes(t *testing.T) {
	manager := NewManager(TerminalDefaults{Command: "/bin/sh", CWD: "."})
	terminal, err := manager.Create(".")
	if err != nil {
		t.Fatalf("create terminal: %v", err)
	}
	defer manager.Close(terminal.ID())

	sink := &countingChannelSink{}
	client := newOwnedTerminalChannelRuntime(terminal, sink, "")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go client.writeLoop(ctx)

	snapshot := terminalengine.ScreenFrame{
		Kind:       terminalengine.FrameSnapshot,
		SessionID:  terminal.ID(),
		InstanceID: terminal.ScreenRuntime().Info().InstanceID,
		Epoch:      1,
		Seq:        1,
		Rows:       5,
		Cols:       10,
	}
	client.sendScreenFrameNow(snapshot, snapshot)

	patch := terminalengine.ScreenFrame{
		Kind:         terminalengine.FramePatch,
		SessionID:    terminal.ID(),
		InstanceID:   snapshot.InstanceID,
		Epoch:        1,
		BaseRevision: 1,
		Seq:          2,
		Rows:         5,
		Cols:         10,
	}
	client.sendScreenFrameNow(patch, patch)

	waitForFrameCount(t, client, 2)
	snap := client.ScreenWireSnapshot()
	if snap.FrameCount != 2 {
		t.Fatalf("frameCount=%d, want 2", snap.FrameCount)
	}
	if snap.WireBytes == 0 {
		t.Fatal("wireBytes must be > 0")
	}
	if snap.SnapshotBytes == 0 {
		t.Fatal("snapshotBytes must be > 0")
	}
	if snap.PatchBytes == 0 {
		t.Fatal("patchBytes must be > 0")
	}
	if snap.HistoryPageBytes != 0 || snap.OtherBytes != 0 {
		t.Fatalf("unexpected historyPage=%d other=%d", snap.HistoryPageBytes, snap.OtherBytes)
	}
	if snap.WireBytes != snap.SnapshotBytes+snap.PatchBytes {
		t.Fatalf("wireBytes=%d != snapshot+patch=%d", snap.WireBytes, snap.SnapshotBytes+snap.PatchBytes)
	}
}

func TestTerminalChannelRuntimeRecordsHistoryPageAsOther(t *testing.T) {
	manager := NewManager(TerminalDefaults{Command: "/bin/sh", CWD: "."})
	terminal, err := manager.Create(".")
	if err != nil {
		t.Fatalf("create terminal: %v", err)
	}
	defer manager.Close(terminal.ID())

	sink := &countingChannelSink{}
	client := newOwnedTerminalChannelRuntime(terminal, sink, "")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go client.writeLoop(ctx)

	client.sendScreenHistory("req-1", 1, 1, terminalengine.HistoryPageData{})
	client.SendInfo()

	waitForFrameCount(t, client, 2)
	snap := client.ScreenWireSnapshot()
	if snap.FrameCount != 2 {
		t.Fatalf("frameCount=%d, want 2", snap.FrameCount)
	}
	if snap.HistoryPageBytes == 0 {
		t.Fatal("historyPageBytes must be > 0")
	}
	if snap.OtherBytes == 0 {
		t.Fatal("otherBytes must be > 0")
	}
}

func TestTerminalChannelRuntimeWriteMessageRecordsTotalBytes(t *testing.T) {
	manager := NewManager(TerminalDefaults{Command: "/bin/sh", CWD: "."})
	terminal, err := manager.Create(".")
	if err != nil {
		t.Fatalf("create terminal: %v", err)
	}
	defer manager.Close(terminal.ID())

	sink := &countingChannelSink{}
	client := newOwnedTerminalChannelRuntime(terminal, sink, "")

	payload := []byte("hello")
	client.writeMessage(context.Background(), outboundMessage{binary: payload})

	snap := client.ScreenWireSnapshot()
	if snap.FrameCount != 1 {
		t.Fatalf("frameCount=%d, want 1", snap.FrameCount)
	}
	if snap.WireBytes != uint64(len(payload)) {
		t.Fatalf("wireBytes=%d, want %d", snap.WireBytes, len(payload))
	}
}

func TestTerminalChannelRuntimeConcurrentEnqueueDoesNotLoseBytes(t *testing.T) {
	manager := NewManager(TerminalDefaults{Command: "/bin/sh", CWD: "."})
	terminal, err := manager.Create(".")
	if err != nil {
		t.Fatalf("create terminal: %v", err)
	}
	defer manager.Close(terminal.ID())

	sink := &countingChannelSink{}
	client := newOwnedTerminalChannelRuntime(terminal, sink, "")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go client.writeLoop(ctx)

	const goroutines = 4
	const iterations = 20
	var sent uint64
	done := make(chan struct{})
	for i := 0; i < goroutines; i++ {
		go func() {
			defer func() { done <- struct{}{} }()
			for j := 0; j < iterations; j++ {
				client.sendScreenHistory("req", 1, 1, terminalengine.HistoryPageData{})
			}
		}()
	}
	for i := 0; i < goroutines; i++ {
		select {
		case <-done:
			sent += iterations
		case <-time.After(5 * time.Second):
			t.Fatal("concurrent enqueue timed out")
		}
	}

	waitForFrameCount(t, client, sent)
	snap := client.ScreenWireSnapshot()
	if snap.FrameCount != sent {
		t.Fatalf("frameCount=%d, want %d (some frames dropped)", snap.FrameCount, sent)
	}
	if snap.HistoryPageBytes == 0 {
		t.Fatal("historyPageBytes must be > 0")
	}
}
