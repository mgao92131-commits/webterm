package session

import (
	"math"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generated"
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
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	client.ready.Store(true)
	helloBytes, _ := proto.Marshal(&pb.ScreenEnvelope{ProtocolVersion: 1, Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{Version: 1, Cols: 20, Rows: 10}}})
	client.handleBinary(helloBytes)
	consumeInitialScreenSnapshot(t, client)
	reqBytes, _ := proto.Marshal(&pb.ScreenEnvelope{ProtocolVersion: 1, Payload: &pb.ScreenEnvelope_HistoryRequest{HistoryRequest: &pb.HistoryRequest{RequestId: "page-1", BeforeLineId: math.MaxUint64, Limit: 5}}})
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
	client := NewClient(socket, terminal, ClientModeScreen)
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
	client := NewClient(socket, terminal, ClientModeScreen)
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
	client := NewClient(socket, terminal, ClientModeScreen)
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
		ID:   client.screenClientID,
		Send: client.sendScreenFrame,
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

func consumeInitialScreenSnapshot(t *testing.T, client *Client) {
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

func drainFrames(t *testing.T, client *Client, timeout time.Duration) {
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
