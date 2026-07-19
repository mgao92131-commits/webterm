package session

import (
	"fmt"
	"math"
	"sync"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generated"
	"webterm/go-core/internal/terminalengine"
	"webterm/go-core/internal/terminalsession"
)

func TestScreenHistoryRequestReturnsPage(t *testing.T) {
	terminal, ptyOut := newScreenTestTerminal(t)
	for i := 0; i < 12; i++ {
		if _, err := ptyOut.Write([]byte("history line\r\n")); err != nil {
			t.Fatal(err)
		}
	}
	time.Sleep(200 * time.Millisecond)
	client := newTestTerminalChannelRuntime(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	client.ready.Store(true)
	helloBytes, _ := proto.Marshal(&pb.ScreenEnvelope{ProtocolVersion: 1, Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{Version: 1, Cols: 20, Rows: 10}}})
	client.handleBinary(helloBytes)
	consumeInitialScreenSnapshot(t, client)
	reqBytes, _ := proto.Marshal(&pb.ScreenEnvelope{ProtocolVersion: 1, Payload: &pb.ScreenEnvelope_HistoryRequest{HistoryRequest: &pb.HistoryRequest{RequestId: "page-1", BeforeHistorySeq: math.MaxUint64, Limit: 5}}})
	client.handleBinary(reqBytes)
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		var env pb.ScreenEnvelope
		if proto.Unmarshal(readClientBinary(t, client), &env) == nil && env.GetHistoryPage() != nil {
			page := env.GetHistoryPage()
			if page.RequestId != "page-1" || len(page.Lines) == 0 {
				t.Fatalf("invalid history page: %+v", page)
			}
			return
		}
	}
	t.Fatal("expected history page")
}

// TestScreenPatchSequenceMonotonic 验证连续输出产生的 patch seq 单调递增，
// 且 patch 的 BaseRevision 等于上一帧的 Seq。
func TestScreenPatchSequenceMonotonic(t *testing.T) {
	terminal, ptyOut := newScreenTestTerminal(t)
	socket := &testSocket{protocolName: "webterm.screen.v1"}
	client := newTestTerminalChannelRuntime(socket, terminal, ClientModeScreen)
	client.ready.Store(true)

	hello := &pb.Hello{Version: 1, Cols: 20, Rows: 10}
	helloBytes, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload:         &pb.ScreenEnvelope_Hello{Hello: hello},
	})
	client.handleBinary(helloBytes)

	consumeInitialScreenSnapshot(t, client)

	// 连续写入两段输出。
	if _, err := ptyOut.Write([]byte("first\n")); err != nil {
		t.Fatalf("write pty: %v", err)
	}
	if _, err := ptyOut.Write([]byte("second\n")); err != nil {
		t.Fatalf("write pty: %v", err)
	}
	time.Sleep(300 * time.Millisecond)

	var prevSeq uint64
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		msg := readClientBinary(t, client)
		seq, base, ok := frameRevision(msg)
		if !ok {
			continue
		}
		if prevSeq != 0 && base != prevSeq {
			t.Fatalf("patch base %d does not match previous seq %d", base, prevSeq)
		}
		if prevSeq != 0 && seq <= prevSeq {
			t.Fatalf("seq should increase: prev=%d current=%d", prevSeq, seq)
		}
		prevSeq = seq
		if screenContains(msg, "second") {
			return
		}
	}
	t.Fatalf("expected patch sequence containing 'second'")
}

// TestScreenResyncSendsSnapshot 验证客户端发送 ResyncRequest 后，
// 服务端会重新发送完整 snapshot，且基线 revision 为 0。
func TestScreenResyncSendsSnapshot(t *testing.T) {
	terminal, ptyOut := newScreenTestTerminal(t)
	socket := &testSocket{protocolName: "webterm.screen.v1"}
	client := newTestTerminalChannelRuntime(socket, terminal, ClientModeScreen)
	client.ready.Store(true)

	hello := &pb.Hello{Version: 1, Cols: 20, Rows: 10}
	helloBytes, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload:         &pb.ScreenEnvelope_Hello{Hello: hello},
	})
	client.handleBinary(helloBytes)
	consumeInitialScreenSnapshot(t, client)

	if _, err := ptyOut.Write([]byte("after hello\n")); err != nil {
		t.Fatalf("write pty: %v", err)
	}
	time.Sleep(200 * time.Millisecond)
	// 消费可能产生的 patch。
	drainFrames(t, client, 500*time.Millisecond)

	resyncBytes, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload:         &pb.ScreenEnvelope_Resync{Resync: &pb.ResyncRequest{Reason: "packet-loss"}},
	})
	client.handleBinary(resyncBytes)

	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		msg := readClientBinary(t, client)
		if isSnapshot(msg) {
			seq, base, ok := frameRevision(msg)
			if !ok || base != 0 {
				t.Fatalf("expected resync snapshot with base=0, got seq=%d base=%d", seq, base)
			}
			if !screenContains(msg, "after hello") {
				t.Fatalf("resync snapshot did not contain latest screen content")
			}
			return
		}
	}
	t.Fatalf("expected snapshot after resync request")
}

// TestScreenReconnectAfterDetachSendsSnapshot 验证客户端断连后重新 attach，
// 服务端会发送完整 snapshot 而不是基于旧基线的 patch。
func TestScreenReconnectAfterDetachSendsSnapshot(t *testing.T) {
	terminal, ptyOut := newScreenTestTerminal(t)
	socket := &testSocket{protocolName: "webterm.screen.v1"}
	client := newTestTerminalChannelRuntime(socket, terminal, ClientModeScreen)
	client.ready.Store(true)

	hello := &pb.Hello{Version: 1, Cols: 20, Rows: 10}
	helloBytes, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload:         &pb.ScreenEnvelope_Hello{Hello: hello},
	})
	client.handleBinary(helloBytes)
	consumeInitialScreenSnapshot(t, client)

	// 模拟断连：服务端分离 screen client 并清空基线。
	terminal.DetachScreenClient(client.screenClientID)
	time.Sleep(100 * time.Millisecond)

	// 断连期间产生新输出。
	if _, err := ptyOut.Write([]byte("after detach")); err != nil {
		t.Fatalf("write pty: %v", err)
	}
	time.Sleep(500 * time.Millisecond)

	// 重新 attach，应收到包含最新内容的 snapshot。
	terminal.AttachScreenClient(&terminalsession.ScreenClient{
		ID:              client.screenClientID,
		Send:            client.sendScreenState,
		ResetProjection: client.resetScreenProjection,
	})

	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		msg := readClientBinary(t, client)
		if isSnapshot(msg) {
			if !screenContains(msg, "after detach") {
				dumpScreen(t, msg)
				t.Fatalf("reconnect snapshot did not contain latest content")
			}
			return
		}
	}
	t.Fatalf("expected snapshot after reconnect")
}

// Resize changes the live grid geometry, but must never discard the
// authoritative main-buffer scrollback. Android recreates its View on
// background return and may send the same geometry again, so an identical
// resize is a strict no-op while a real resize produces a fresh snapshot that
// still carries the history tail.
func TestScreenResizePreservesHistoryAndIgnoresIdenticalGeometry(t *testing.T) {
	var mu sync.Mutex
	resizeCalls := 0
	terminal, ptyOut := newScreenTestTerminalWithResizer(t, func(cols, rows int) error {
		mu.Lock()
		defer mu.Unlock()
		resizeCalls++
		return nil
	})
	for i := 0; i < 32; i++ {
		if _, err := ptyOut.Write([]byte(fmt.Sprintf("history-%02d\r\n", i))); err != nil {
			t.Fatal(err)
		}
	}
	waitForProjectedHistory(t, terminal)

	frames := make(chan terminalengine.ScreenFrame, 4)
	runtime := terminal.ScreenRuntime()
	runtime.AttachClient(&terminalsession.ScreenClient{
		ID:   "resize-test-client",
		Send: func(frame terminalengine.ScreenFrame) { frames <- frame },
	})
	initial := waitForRuntimeFrame(t, frames)
	if len(initial.History.Lines) == 0 {
		t.Fatal("initial snapshot omitted scrollback")
	}
	leaseID, granted := runtime.AcquireLayout("resize-test-client", true)
	if !granted || leaseID == "" {
		t.Fatal("expected layout lease")
	}

	before := runtime.Info()
	runtime.Resize("resize-test-client", leaseID, 20, 4)
	time.Sleep(100 * time.Millisecond)
	afterSame := runtime.Info()
	if afterSame.LayoutEpoch != before.LayoutEpoch {
		t.Fatalf("identical resize changed epoch: before=%d after=%d", before.LayoutEpoch, afterSame.LayoutEpoch)
	}
	mu.Lock()
	if resizeCalls != 0 {
		mu.Unlock()
		t.Fatalf("identical resize called PTY resizer %d times", resizeCalls)
	}
	mu.Unlock()
	waitForProjectedHistory(t, terminal)

	runtime.Resize("resize-test-client", leaseID, 24, 6)
	var resized terminalengine.ScreenFrame
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		candidate := waitForRuntimeFrame(t, frames)
		if candidate.Epoch > before.LayoutEpoch {
			resized = candidate
			break
		}
	}
	if resized.Epoch == 0 {
		t.Fatal("timed out waiting for resize snapshot")
	}
	if got := len(resized.History.Lines); got == 0 {
		t.Fatal("resize snapshot discarded scrollback")
	}
	if got := resized.Epoch; got <= before.LayoutEpoch {
		t.Fatalf("resize did not advance layout epoch: before=%d after=%d", before.LayoutEpoch, got)
	}
	if resized.BaseRevision != 0 {
		t.Fatalf("resize should send a full snapshot, got patch base revision %d", resized.BaseRevision)
	}
}

func waitForRuntimeFrame(t *testing.T, frames <-chan terminalengine.ScreenFrame) terminalengine.ScreenFrame {
	t.Helper()
	select {
	case frame := <-frames:
		return frame
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for screen runtime frame")
		return terminalengine.ScreenFrame{}
	}
}

func waitForProjectedHistory(t *testing.T, terminal *TerminalSession) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		frame, ok := terminal.ProjectedScreenSnapshot().(terminalengine.ScreenFrame)
		if ok && len(frame.History.Lines) > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("projected scrollback was empty")
}

func consumeInitialScreenSnapshot(t *testing.T, client *terminalChannelRuntime) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if isSnapshot(readClientBinary(t, client)) {
			return
		}
	}
	t.Fatal("expected initial screen snapshot")
}

func frameRevision(data []byte) (seq uint64, base uint64, ok bool) {
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		return 0, 0, false
	}
	switch p := env.Payload.(type) {
	case *pb.ScreenEnvelope_Snapshot:
		return p.Snapshot.ScreenRevision, 0, true
	case *pb.ScreenEnvelope_Patch:
		return p.Patch.ScreenRevision, p.Patch.BaseRevision, true
	}
	return 0, 0, false
}

func drainFrames(t *testing.T, client *terminalChannelRuntime, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		select {
		case <-client.send:
		case <-time.After(50 * time.Millisecond):
			return
		}
	}
}
