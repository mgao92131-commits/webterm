package application

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/agentnotify"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

// recordingSocket 是一个 fake Socket，用于捕获 Client 发送的下行消息，也可注入上行消息。
type recordingSocket struct {
	mu       sync.Mutex
	messages []recordedMessage
	incoming chan recordedMessage
	closed   bool
}

type recordedMessage struct {
	msgType session.MessageType
	data    []byte
}

func newRecordingSocket() *recordingSocket {
	return &recordingSocket{incoming: make(chan recordedMessage, 8)}
}

func (s *recordingSocket) Read(ctx context.Context) (session.MessageType, []byte, error) {
	select {
	case <-ctx.Done():
		return 0, nil, ctx.Err()
	case m := <-s.incoming:
		return m.msgType, m.data, nil
	}
}

func (s *recordingSocket) Write(ctx context.Context, msgType session.MessageType, data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	s.messages = append(s.messages, recordedMessage{msgType: msgType, data: append([]byte(nil), data...)})
	return nil
}

func (s *recordingSocket) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	return nil
}

func (s *recordingSocket) inject(msgType session.MessageType, data []byte) {
	s.incoming <- recordedMessage{msgType: msgType, data: append([]byte(nil), data...)}
}

func (s *recordingSocket) binaryMessages() [][]byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out [][]byte
	for _, m := range s.messages {
		if m.msgType == session.MessageBinary {
			out = append(out, m.data)
		}
	}
	return out
}

func TestRouteHTTPv2FileSendStub(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{
		Command: "/bin/sh",
		CWD:     ".",
	})
	router := NewSessionRouter(manager)
	// 未注入 FileSendService 时，/api/file-send/ 返回 503。
	result, err := router.RouteHTTPv2("GET", "/api/file-send/t_abc123", nil, nil)
	if err != nil {
		t.Fatalf("RouteHTTPv2 error: %v", err)
	}
	if result.StatusCode != 503 {
		t.Fatalf("status = %d, want 503 when file-send service is not wired", result.StatusCode)
	}
}

func TestRouteHTTPv2FileSendTokenAuthAndStream(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{Command: "/bin/sh", CWD: "."})
	router := NewSessionRouter(manager)
	svc := filesend.New(0)
	router.SetFileSendService(svc)

	dir := t.TempDir()
	path := filepath.Join(dir, "payload.bin")
	want := []byte("file-send payload 0123456789 abcdef")
	if err := os.WriteFile(path, want, 0o644); err != nil {
		t.Fatal(err)
	}

	task, err := svc.CreateTask(filesend.CreateTaskOptions{
		DeviceID: "dev-1",
		Path:     path,
		FileName: "payload.bin",
		Size:     int64(len(want)),
	})
	if err != nil {
		t.Fatalf("CreateTask: %v", err)
	}
	// 模拟 Android 接受请求
	svc.HandleControl(map[string]any{"type": filesend.TypeAccepted, "transfer_id": task.ID})

	// 错误 token -> 401
	badHeader := http.Header{}
	badHeader.Set("X-WebTerm-Transfer-Token", "wrong")
	bad, err := router.RouteHTTPv2("GET", "/api/file-send/"+task.ID, badHeader, nil)
	if err != nil {
		t.Fatalf("RouteHTTPv2 bad token: %v", err)
	}
	if bad.StatusCode != http.StatusUnauthorized {
		t.Fatalf("bad token status = %d, want 401", bad.StatusCode)
	}

	// 正确 token -> 200 + 流式 body
	goodHeader := http.Header{}
	goodHeader.Set("Authorization", "Bearer "+task.Token)
	result, err := router.RouteHTTPv2("GET", "/api/file-send/"+task.ID, goodHeader, nil)
	if err != nil {
		t.Fatalf("RouteHTTPv2 good token: %v", err)
	}
	if result.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", result.StatusCode)
	}
	if result.Header.Get("Cache-Control") != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", result.Header.Get("Cache-Control"))
	}
	got, err := io.ReadAll(result.Body)
	_ = result.Body.Close()
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("body mismatch")
	}
	// EOF 不等于成功：HTTP 流结束后任务仍未进入 saved
	if task.Status == filesend.StatusSaved {
		t.Fatal("HTTP stream EOF must not mark task saved")
	}

	// Android 回传 saved 后，任务进入终态并被移除，再次请求 -> 410
	svc.HandleControl(map[string]any{"type": filesend.TypeSaved, "transfer_id": task.ID})
	gone, err := router.RouteHTTPv2("GET", "/api/file-send/"+task.ID, goodHeader, nil)
	if err != nil {
		t.Fatalf("RouteHTTPv2 after saved: %v", err)
	}
	if gone.StatusCode != http.StatusGone && gone.StatusCode != http.StatusUnauthorized {
		t.Fatalf("after-saved status = %d, want 410 or 401", gone.StatusCode)
	}
}

type fakeMuxSession struct {
	mu   sync.Mutex
	sent []map[string]any
}

func (f *fakeMuxSession) Run(ctx context.Context) error {
	<-ctx.Done()
	return ctx.Err()
}

func (f *fakeMuxSession) SendControl(_ context.Context, msg map[string]any) error {
	f.mu.Lock()
	f.sent = append(f.sent, msg)
	f.mu.Unlock()
	return nil
}

type fakeAgentSender struct{}

func (fakeAgentSender) SendControlToDevice(_ context.Context, _ string, _ map[string]any) error {
	return nil
}

func TestRouteOpenWithControlReturnsControlSender(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{})
	sess := &fakeMuxSession{}
	router := NewSessionRouterWithMux(manager, func(_ session.Socket, _ *MuxServeOpts) MuxSession {
		return sess
	})

	start, ctrl, err := router.RouteOpenWithControl(context.Background(), newRecordingSocket(), "/ws/sessions", []string{protocol.MuxSubprotocol})
	if err != nil {
		t.Fatalf("RouteOpenWithControl: %v", err)
	}
	if start == nil {
		t.Fatal("start is nil")
	}
	if ctrl == nil {
		t.Fatal("ctrl is nil for mux /ws/sessions")
	}
	if err := ctrl.SendControl(context.Background(), map[string]any{"type": "x"}); err != nil {
		t.Fatalf("SendControl: %v", err)
	}
	sess.mu.Lock()
	n := len(sess.sent)
	sess.mu.Unlock()
	if n != 1 {
		t.Fatalf("expected 1 sent control, got %d", n)
	}
}

func TestRouteOpenWithControlNonMuxReturnsNilControl(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{})
	sess := &fakeMuxSession{}
	router := NewSessionRouterWithMux(manager, func(_ session.Socket, _ *MuxServeOpts) MuxSession {
		return sess
	})
	_, ctrl, err := router.RouteOpenWithControl(context.Background(), newRecordingSocket(), "/ws/sessions", nil)
	if err != nil {
		t.Fatalf("RouteOpenWithControl: %v", err)
	}
	if ctrl != nil {
		t.Fatal("expected nil ctrl for non-mux /ws/sessions")
	}
}

func TestSetAgentNotificationDispatcherRoutesAck(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{})
	router := NewSessionRouter(manager)

	var prev []map[string]any
	router.SetControlHandler(func(_ context.Context, m map[string]any) {
		prev = append(prev, m)
	})
	d := agentnotify.New(fakeAgentSender{})
	router.SetAgentNotificationDispatcher(d)

	// 非 ack 消息穿透到 prev。
	router.onControl(context.Background(), map[string]any{"type": "other"})
	if len(prev) != 1 {
		t.Fatalf("prev should receive non-ack, got %d", len(prev))
	}

	// ack 被消费并清理 pending，不再传给 prev。
	eventID, err := d.Notify(context.Background(), "", "s", agentnotify.ImportanceQuiet, "t", "m", "src")
	if err != nil {
		t.Fatalf("Notify: %v", err)
	}
	if d.PendingCount() != 1 {
		t.Fatalf("pending before ack = %d", d.PendingCount())
	}
	router.onControl(context.Background(), map[string]any{"type": agentnotify.TypeAgentAck, "event_id": eventID})
	if d.PendingCount() != 0 {
		t.Fatalf("pending after ack = %d", d.PendingCount())
	}
	if len(prev) != 1 {
		t.Fatalf("ack must not reach prev, prev len=%d", len(prev))
	}
}
