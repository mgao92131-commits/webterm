package session

import (
	"bytes"
	"context"
	"errors"
	"testing"
	"time"

	"webterm/go-core/internal/terminalengine"
	"webterm/go-core/internal/terminalsession"
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
	shellCommand, _ := testShellCommand()
	manager := NewManager(TerminalDefaults{Command: shellCommand, CWD: "."})
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
	shellCommand, _ := testShellCommand()
	manager := NewManager(TerminalDefaults{Command: shellCommand, CWD: "."})
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
	shellCommand, _ := testShellCommand()
	manager := NewManager(TerminalDefaults{Command: shellCommand, CWD: "."})
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
	shellCommand, _ := testShellCommand()
	manager := NewManager(TerminalDefaults{Command: shellCommand, CWD: "."})
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

// sizedEncode 返回一个按 Frame.Kind 决定输出长度的 encode 函数，
// 用于在不依赖真实编码器的情况下测试 initial-sync 的 snapshot/patch 分类与 80% 阈值。
func sizedEncode(patchLen, snapshotLen int) func(terminalengine.ScreenFrame) ([]byte, error) {
	return func(frame terminalengine.ScreenFrame) ([]byte, error) {
		switch frame.Kind {
		case terminalengine.FrameSnapshot:
			return bytes.Repeat([]byte{0xAA}, snapshotLen), nil
		case terminalengine.FramePatch:
			return bytes.Repeat([]byte{0xBB}, patchLen), nil
		default:
			return nil, nil
		}
	}
}

func baseInitialSyncMessage() terminalsession.InitialSync {
	return terminalsession.InitialSync{
		State: terminalengine.ScreenFrame{
			InstanceID: "inst-1",
			Epoch:      7,
			Seq:        42,
		},
	}
}

// cold snapshot：Frame 本身即 FrameSnapshot，必须分类为 snapshot。
// 这正是修复前被错误统计成 patch 的场景。
func TestEncodeInitialScreenSync_ColdSnapshotClassifiedAsSnapshot(t *testing.T) {
	syncMessage := baseInitialSyncMessage()
	syncMessage.Frame = terminalengine.ScreenFrame{Kind: terminalengine.FrameSnapshot}

	payload, kind, err := encodeInitialScreenSyncWith(syncMessage, sizedEncode(0, 32))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if kind != "snapshot" {
		t.Fatalf("cold snapshot classified as %q, want %q", kind, "snapshot")
	}
	if len(payload) != 32 {
		t.Fatalf("payload len = %d, want 32 (snapshot bytes)", len(payload))
	}
}

// forced resync：runtime 强制下发 snapshot 帧，同样必须分类为 snapshot。
func TestEncodeInitialScreenSync_ForcedResyncClassifiedAsSnapshot(t *testing.T) {
	syncMessage := baseInitialSyncMessage()
	syncMessage.Frame = terminalengine.ScreenFrame{
		Kind: terminalengine.FrameSnapshot,
		Seq:  99,
	}
	syncMessage.Reason = "forced_resync"

	_, kind, err := encodeInitialScreenSyncWith(syncMessage, sizedEncode(0, 64))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if kind != "snapshot" {
		t.Fatalf("forced resync classified as %q, want %q", kind, "snapshot")
	}
}

// resume patch 较小：Patch 明显小于 Snapshot 的 80%，保留 patch。
func TestEncodeInitialScreenSync_SmallPatchStaysPatch(t *testing.T) {
	syncMessage := baseInitialSyncMessage()
	syncMessage.Frame = terminalengine.ScreenFrame{Kind: terminalengine.FramePatch, BaseRevision: 41}

	// patch=10, snapshot=100 → 10*10=100 < 100*8=800，保留 patch。
	payload, kind, err := encodeInitialScreenSyncWith(syncMessage, sizedEncode(10, 100))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if kind != "patch" {
		t.Fatalf("small patch classified as %q, want %q", kind, "patch")
	}
	if len(payload) != 10 {
		t.Fatalf("payload len = %d, want 10 (patch bytes)", len(payload))
	}
}

// resume patch ≥ 80%：Patch 达到 Snapshot 的 80%，改发自包含 Snapshot。
func TestEncodeInitialScreenSync_LargePatchUpgradedToSnapshot(t *testing.T) {
	syncMessage := baseInitialSyncMessage()
	syncMessage.Frame = terminalengine.ScreenFrame{Kind: terminalengine.FramePatch, BaseRevision: 41}

	// patch=80, snapshot=100 → 80*10=800 >= 100*8=800，升级为 snapshot。
	payload, kind, err := encodeInitialScreenSyncWith(syncMessage, sizedEncode(80, 100))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if kind != "snapshot" {
		t.Fatalf("large patch classified as %q, want %q", kind, "snapshot")
	}
	if len(payload) != 100 {
		t.Fatalf("payload len = %d, want 100 (snapshot bytes)", len(payload))
	}
}

// exact resume：Exact=true 时发送 ResumeAck，分类为 other，不调用 encode。
func TestEncodeInitialScreenSync_ExactResumeIsOther(t *testing.T) {
	syncMessage := baseInitialSyncMessage()
	syncMessage.Exact = true

	encodeCalled := false
	encode := func(terminalengine.ScreenFrame) ([]byte, error) {
		encodeCalled = true
		return nil, nil
	}

	_, kind, err := encodeInitialScreenSyncWith(syncMessage, encode)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if kind != "other" {
		t.Fatalf("exact resume classified as %q, want %q", kind, "other")
	}
	if encodeCalled {
		t.Fatalf("exact resume must not invoke frame encode")
	}
}

// encode 失败：返回错误且不掩盖为 patch。
func TestEncodeInitialScreenSync_EncodeErrorPropagates(t *testing.T) {
	syncMessage := baseInitialSyncMessage()
	syncMessage.Frame = terminalengine.ScreenFrame{Kind: terminalengine.FrameSnapshot}

	wantErr := errors.New("encode boom")
	encode := func(terminalengine.ScreenFrame) ([]byte, error) {
		return nil, wantErr
	}

	payload, kind, err := encodeInitialScreenSyncWith(syncMessage, encode)
	if !errors.Is(err, wantErr) {
		t.Fatalf("err = %v, want %v", err, wantErr)
	}
	if payload != nil {
		t.Fatalf("payload = %v, want nil on encode error", payload)
	}
	if kind != "" {
		t.Fatalf("kind = %q, want empty on encode error", kind)
	}
}
