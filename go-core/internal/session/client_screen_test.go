package session

import (
	"context"
	"fmt"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generated"
	"webterm/go-core/internal/terminalengine"
	"webterm/go-core/internal/terminalsession"
)

func TestScreenClientSendsSnapshotOnHello(t *testing.T) {
	terminal, ptyOut := newScreenTestTerminal(t)
	if _, err := ptyOut.Write([]byte("hello")); err != nil {
		t.Fatalf("write pty: %v", err)
	}
	// 等待 Runtime 处理输出。
	time.Sleep(200 * time.Millisecond)

	socket := &testSocket{protocolName: "webterm.screen.v1"}
	client := NewClient(socket, terminal, ClientModeScreen)
	client.ready.Store(true)

	hello := &pb.Hello{Version: 1, Cols: 20, Rows: 10}
	helloBytes, err := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload:         &pb.ScreenEnvelope_Hello{Hello: hello},
	})
	if err != nil {
		t.Fatalf("marshal hello: %v", err)
	}
	client.handleBinary(helloBytes)

	// 等待 actor 处理 attach 并生成初始快照。
	var snapshot []byte
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		msg := readClientBinary(t, client)
		if isSnapshot(msg) {
			snapshot = msg
			break
		}
	}
	if snapshot == nil {
		t.Fatalf("expected initial snapshot")
	}
	if !screenContains(snapshot, "hello") {
		dumpScreen(t, snapshot)
		t.Fatalf("snapshot did not contain 'hello'")
	}
}

func TestScreenClientReusesProtocolHandler(t *testing.T) {
	terminal, _ := newScreenTestTerminal(t)
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)

	handler := client.screenHandler
	if handler == nil {
		t.Fatal("expected screen handler to be initialized once with the client")
	}
	client.handleScreenBinary([]byte("not-a-protobuf-envelope"))
	client.handleScreenBinary([]byte("still-not-a-protobuf-envelope"))
	if client.screenHandler != handler {
		t.Fatal("screen protocol handler was recreated for an inbound message")
	}
}

// screen 协议的 resize 必须同时落到 PTY winsize 上，
// 否则 shell/TUI 程序（stty、vim、htop）看到的尺寸会停留在会话创建时的默认值。
func TestScreenClientResizeUpdatesPTYWinsize(t *testing.T) {
	var mu sync.Mutex
	calls := 0
	gotCols, gotRows := 0, 0
	terminal, _ := newScreenTestTerminalWithResizer(t, func(cols, rows int) error {
		mu.Lock()
		defer mu.Unlock()
		calls++
		gotCols = cols
		gotRows = rows
		return nil
	})

	socket := &testSocket{protocolName: "webterm.screen.v1"}
	client := NewClient(socket, terminal, ClientModeScreen)
	client.ready.Store(true)

	helloBytes, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload:         &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{Version: 1, Cols: 20, Rows: 10}},
	})
	client.handleBinary(helloBytes)

	acquireBytes, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_AcquireLayout{AcquireLayout: &pb.AcquireLayout{
			RequestId: "layout-request-1", Interactive: true,
		}},
	})
	client.handleBinary(acquireBytes)

	// 读到租约授予消息（中间会夹带初始 snapshot）。
	leaseID := ""
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && leaseID == "" {
		msg := readClientBinary(t, client)
		var env pb.ScreenEnvelope
		if err := proto.Unmarshal(msg, &env); err != nil {
			continue
		}
		if lease := env.GetLayoutLease(); lease != nil && lease.GetGranted() {
			if lease.GetRequestId() != "layout-request-1" {
				t.Fatalf("layout request id=%q", lease.GetRequestId())
			}
			if lease.GetExpiresAtMs() <= uint64(time.Now().UnixMilli()) {
				t.Fatalf("layout lease missing future expiry: %d", lease.GetExpiresAtMs())
			}
			leaseID = lease.GetLeaseId()
		}
	}
	if leaseID == "" {
		t.Fatalf("expected granted layout lease")
	}

	resizeBytes, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload:         &pb.ScreenEnvelope_Resize{Resize: &pb.Resize{Cols: 132, Rows: 43, LeaseId: leaseID}},
	})
	client.handleBinary(resizeBytes)

	waitDeadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(waitDeadline) {
		mu.Lock()
		done := calls > 0
		mu.Unlock()
		if done {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	mu.Lock()
	defer mu.Unlock()
	if calls != 1 {
		t.Fatalf("expected pty resizer called once, got %d", calls)
	}
	if gotCols != 132 || gotRows != 43 {
		t.Fatalf("expected pty resize 132x43, got %dx%d", gotCols, gotRows)
	}
}

// 同一 screen channel 只接受一次 Hello（计划 §3.5）：第二个 Hello 是协议
// 错误，连接必须关闭且不得再次 SendInfo。
func TestScreenClientRejectsDuplicateHello(t *testing.T) {
	terminal, _ := newScreenTestTerminal(t)
	client := NewClient(&testSocket{protocolName: "webterm.screen.v1"}, terminal, ClientModeScreen)
	client.ready.Store(true)

	helloBytes, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload:         &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{Version: 1, Cols: 20, Rows: 10}},
	})
	client.handleBinary(helloBytes)

	// 第一个 Hello 正常：等到初始 snapshot 到达，排除后续竞态。
	consumeInitialScreenSnapshot(t, client)
	drainFrames(t, client, 200*time.Millisecond)
	select {
	case <-client.done:
		t.Fatal("first hello must not close the connection")
	default:
	}

	client.handleBinary(helloBytes)
	select {
	case <-client.done:
	default:
		t.Fatal("duplicate hello must close the connection")
	}

	// 关闭后不得再有 Info（或任何消息）流出。
	select {
	case msg := <-client.send:
		if isInfo(msg.binary) {
			t.Fatal("duplicate hello must not resend Info")
		}
		t.Fatalf("unexpected message after duplicate hello: %d bytes", len(msg.binary))
	case <-time.After(200 * time.Millisecond):
	}
}

func dumpScreen(t *testing.T, data []byte) {
	t.Helper()
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		t.Logf("unmarshal: %v", err)
		return
	}
	var lines []*pb.TerminalLine
	switch p := env.Payload.(type) {
	case *pb.ScreenEnvelope_Snapshot:
		lines = p.Snapshot.Screen
		t.Logf("snapshot rows=%d cols=%d", p.Snapshot.Geometry.Rows, p.Snapshot.Geometry.Cols)
	case *pb.ScreenEnvelope_Patch:
		lines = p.Patch.ScreenRows
		t.Logf("patch base=%d rev=%d", p.Patch.BaseRevision, p.Patch.ScreenRevision)
	}
	for r, line := range lines {
		for _, run := range line.Runs {
			var texts []string
			for _, cell := range run.Cells {
				texts = append(texts, fmt.Sprintf("%q", cell.Text))
			}
			t.Logf("row %d col %d: %s", r, run.Col, strings.Join(texts, ", "))
		}
	}
}

func TestScreenClientSendsPatchOnOutput(t *testing.T) {
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

	// 消费初始快照。
	readClientBinary(t, client)

	// 向 PTY 写入输出，Runtime 应生成 patch。
	if _, err := ptyOut.Write([]byte("ab")); err != nil {
		t.Fatalf("write pty: %v", err)
	}
	// 等待 Runtime 处理输出并生成 patch。
	time.Sleep(200 * time.Millisecond)

	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		msg := readClientBinary(t, client)
		if isPatch(msg) && screenContains(msg, "ab") {
			return
		}
	}
	t.Fatalf("expected patch containing 'ab'")
}

func TestScreenWriter_CoalescesBlockedSocketToLatestRevision(t *testing.T) {
	socket := newBlockingWriteSocket()
	client := NewClient(socket, nil, ClientModeScreen)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client.writerStarted.Store(true)
	go client.writeLoop(ctx)
	defer client.Close()

	client.sendScreenState(testScreenState(1, "one"))
	select {
	case <-socket.firstWriteStarted:
	case <-time.After(time.Second):
		t.Fatal("initial screen write did not start")
	}

	// The first snapshot is still blocked in the socket. These intermediate
	// states must collapse to revision 3, not create a patch chain 1 -> 2 -> 3.
	client.sendScreenState(testScreenState(2, "two"))
	client.sendScreenState(testScreenState(3, "three"))
	close(socket.releaseFirstWrite)

	first := socket.waitWrite(t)
	second := socket.waitWrite(t)
	var firstEnv, secondEnv pb.ScreenEnvelope
	if err := proto.Unmarshal(first, &firstEnv); err != nil || firstEnv.GetSnapshot() == nil {
		t.Fatalf("first write must be snapshot: err=%v payload=%T", err, firstEnv.Payload)
	}
	if err := proto.Unmarshal(second, &secondEnv); err != nil {
		t.Fatal(err)
	}
	patch := secondEnv.GetPatch()
	if patch == nil {
		t.Fatalf("second write must be latest patch, got %T", secondEnv.Payload)
	}
	if patch.GetBaseRevision() != 1 || patch.GetScreenRevision() != 3 {
		t.Fatalf("coalesced patch base=%d revision=%d, want 1 -> 3",
			patch.GetBaseRevision(), patch.GetScreenRevision())
	}
}

func TestScreenWriter_InitialSyncCommitsOnlyAfterSocketWrite(t *testing.T) {
	socket := newBlockingWriteSocket()
	client := NewClient(socket, nil, ClientModeScreen)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client.writerStarted.Store(true)
	go client.writeLoop(ctx)
	defer client.Close()

	state := testScreenState(7, "seven")
	state.Kind = terminalengine.FrameSnapshot
	committed := make(chan bool, 1)
	client.sendInitialScreenSync(terminalsession.InitialSync{Frame: state, State: state}, func(written bool) {
		committed <- written
	})
	select {
	case <-socket.firstWriteStarted:
	case <-time.After(time.Second):
		t.Fatal("initial sync socket write did not start")
	}
	select {
	case result := <-committed:
		t.Fatalf("initial sync committed before socket write completed: %v", result)
	case <-time.After(100 * time.Millisecond):
	}

	close(socket.releaseFirstWrite)
	if result := <-committed; !result {
		t.Fatal("successful initial socket write must commit baseline")
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(socket.waitWrite(t), &env); err != nil || env.GetSnapshot() == nil {
		t.Fatalf("initial write payload=%T err=%v, want snapshot", env.Payload, err)
	}

	// 提交后的实时状态必须从实际写出的 revision 7 派生。
	client.sendScreenState(testScreenState(9, "nine"))
	if err := proto.Unmarshal(socket.waitWrite(t), &env); err != nil {
		t.Fatal(err)
	}
	if patch := env.GetPatch(); patch == nil || patch.BaseRevision != 7 || patch.ScreenRevision != 9 {
		t.Fatalf("post-initial patch=%+v, want 7 -> 9", patch)
	}
}

func testScreenState(revision uint64, text string) terminalengine.ScreenFrame {
	screen := make([]terminalengine.Line, 5)
	for row := range screen {
		screen[row] = terminalengine.Line{Row: row}
	}
	screen[0] = terminalengine.Line{Row: 0, Runs: []terminalengine.CellRun{{
		Col: 0, Cells: []terminalengine.Cell{{Text: text, Width: 1}},
	}}}
	return terminalengine.ScreenFrame{
		Version: 1, SessionID: "s1", InstanceID: "i1", Epoch: 1, Seq: revision,
		Rows: 5, Cols: 10, ActiveBuffer: terminalengine.BufferMain,
		Screen: screen,
	}
}

func newScreenTestTerminal(t *testing.T) (*TerminalSession, *io.PipeWriter) {
	return newScreenTestTerminalWithResizer(t, nil)
}

func newScreenTestTerminalWithResizer(t *testing.T, resizer func(cols, rows int) error) (*TerminalSession, *io.PipeWriter) {
	t.Helper()
	outR, outW := io.Pipe()
	inR, inW := io.Pipe()
	_ = inR
	pty := &fakePTY{reader: outR, writer: inW}

	terminal := &TerminalSession{
		id:        "s1",
		instance:  "i1",
		status:    StatusRunning,
		cols:      20,
		rows:      4,
		createdAt: time.Now().UTC(),
		activeAt:  time.Now().UTC(),
		ring:      NewEventRing(0, 0),
		clients:   make(map[*Client]struct{}),
	}
	opts := []terminalsession.Option{
		terminalsession.WithOnOutput(func(data []byte) {
			frame := terminal.PushOutput(data)
			terminal.broadcastOutput(frame)
		}),
	}
	if resizer != nil {
		opts = append(opts, terminalsession.WithPTYResizer(resizer))
	}
	terminal.runtime = terminalsession.NewRuntime(
		terminal.id,
		pty,
		terminal.rows,
		terminal.cols,
		opts...,
	)
	t.Cleanup(func() {
		_ = terminal.runtime.Close()
		_ = outW.Close()
		_ = inW.Close()
	})
	return terminal, outW
}

type fakePTY struct {
	reader *io.PipeReader
	writer *io.PipeWriter
}

func (p *fakePTY) Read(b []byte) (int, error)  { return p.reader.Read(b) }
func (p *fakePTY) Write(b []byte) (int, error) { return p.writer.Write(b) }
func (p *fakePTY) Close() error {
	_ = p.reader.Close()
	_ = p.writer.Close()
	return nil
}

func readClientBinary(t *testing.T, client *Client) []byte {
	t.Helper()
	select {
	case message := <-client.send:
		if message.binary == nil {
			t.Fatalf("expected binary message")
		}
		return message.binary
	case <-time.After(time.Second):
		t.Fatalf("timed out waiting for client binary message")
		return nil
	}
}

func isSnapshot(data []byte) bool {
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		return false
	}
	_, ok := env.Payload.(*pb.ScreenEnvelope_Snapshot)
	return ok
}

func isPatch(data []byte) bool {
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		return false
	}
	_, ok := env.Payload.(*pb.ScreenEnvelope_Patch)
	return ok
}

func isInfo(data []byte) bool {
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		return false
	}
	_, ok := env.Payload.(*pb.ScreenEnvelope_Info)
	return ok
}

func screenContains(data []byte, text string) bool {
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		return false
	}
	var lines []*pb.TerminalLine
	switch p := env.Payload.(type) {
	case *pb.ScreenEnvelope_Snapshot:
		lines = p.Snapshot.Screen
	case *pb.ScreenEnvelope_Patch:
		lines = p.Patch.ScreenRows
	default:
		return false
	}
	for _, line := range lines {
		for _, run := range line.Runs {
			var sb strings.Builder
			for _, cell := range run.Cells {
				sb.WriteString(cell.Text)
			}
			if strings.Contains(sb.String(), text) {
				return true
			}
		}
	}
	return false
}

type testSocket struct {
	protocolName string
}

type blockingWriteSocket struct {
	firstWriteStarted  chan struct{}
	releaseFirstWrite  chan struct{}
	writes             chan []byte
	firstWriteObserved sync.Once
}

func newBlockingWriteSocket() *blockingWriteSocket {
	return &blockingWriteSocket{
		firstWriteStarted: make(chan struct{}),
		releaseFirstWrite: make(chan struct{}),
		writes:            make(chan []byte, 4),
	}
}

func (socket *blockingWriteSocket) Read(ctx context.Context) (MessageType, []byte, error) {
	<-ctx.Done()
	return 0, nil, ctx.Err()
}

func (socket *blockingWriteSocket) Write(_ context.Context, _ MessageType, data []byte) error {
	wait := false
	socket.firstWriteObserved.Do(func() {
		wait = true
		close(socket.firstWriteStarted)
	})
	if wait {
		<-socket.releaseFirstWrite
	}
	socket.writes <- append([]byte(nil), data...)
	return nil
}

func (socket *blockingWriteSocket) Close() error        { return nil }
func (socket *blockingWriteSocket) Subprotocol() string { return "webterm.screen.v1" }

func (socket *blockingWriteSocket) waitWrite(t *testing.T) []byte {
	t.Helper()
	select {
	case data := <-socket.writes:
		return data
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for socket write")
		return nil
	}
}

func (socket *testSocket) Read(ctx context.Context) (MessageType, []byte, error) {
	<-ctx.Done()
	return 0, nil, ctx.Err()
}

func (socket *testSocket) Write(context.Context, MessageType, []byte) error {
	return nil
}

func (socket *testSocket) Close() error {
	return nil
}

func (socket *testSocket) Subprotocol() string {
	return socket.protocolName
}
