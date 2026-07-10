package relay

import (
	"context"
	"encoding/json"
	"sync"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/application"
	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/session"
)

type noopFrameWriter struct{}

func (noopFrameWriter) writeFrame(context.Context, *websocket.Conn, relaycore.Frame) {}
func (noopFrameWriter) writeRaw(context.Context, *websocket.Conn, []byte) error        { return nil }

// fakeRelaySession 模仿 *mux.Session：Run 持续读取 socket 直到出错（socket.close 后 Read 返回错误），
// SendControl 记录下行控制消息。这样 CloseStream → socket.close → Run 返回 → start 返回，
// 与真实 mux session 的生命周期一致，便于验证 UnregisterSender。
type fakeRelaySession struct {
	socket session.Socket
	mu     sync.Mutex
	sent   []map[string]any
}

func (f *fakeRelaySession) Run(ctx context.Context) error {
	for {
		if _, _, err := f.socket.Read(ctx); err != nil {
			return nil
		}
	}
}

func (f *fakeRelaySession) SendControl(_ context.Context, msg map[string]any) error {
	f.mu.Lock()
	f.sent = append(f.sent, msg)
	f.mu.Unlock()
	return nil
}

type fakeRegistry struct {
	mu           sync.Mutex
	registered   map[string]filesend.ControlSender
	unregistered map[string]filesend.ControlSender
}

func newFakeRegistry() *fakeRegistry {
	return &fakeRegistry{
		registered:   make(map[string]filesend.ControlSender),
		unregistered: make(map[string]filesend.ControlSender),
	}
}

func (r *fakeRegistry) RegisterSender(id string, s filesend.ControlSender) {
	r.mu.Lock()
	r.registered[id] = s
	r.mu.Unlock()
}

func (r *fakeRegistry) UnregisterSender(id string, s filesend.ControlSender) {
	r.mu.Lock()
	r.unregistered[id] = s
	r.mu.Unlock()
}

func newMuxForTest(sess *fakeRelaySession, reg *fakeRegistry) *StreamMultiplexer {
	manager := session.NewManager(session.TerminalDefaults{})
	muxServe := func(conn session.Socket, _ *application.MuxServeOpts) application.MuxSession {
		sess.socket = conn
		return sess
	}
	router := application.NewSessionRouterWithMux(manager, muxServe)
	mux := NewStreamMultiplexer(router, noopFrameWriter{}, nil)
	mux.SetControlSenderRegistry(reg)
	return mux
}

func streamOpenFrame(id string) relaycore.Frame {
	payload, _ := json.Marshal(relaycore.StreamRoute{
		Path:        "/ws/sessions",
		Subprotocol: protocol.MuxSubprotocol,
	})
	return relaycore.NewFrame(relaycore.FrameTypeStreamOpen, id, 0, payload)
}

func TestOpenStreamRegistersControlSender(t *testing.T) {
	sess := &fakeRelaySession{}
	reg := newFakeRegistry()
	mux := newMuxForTest(sess, reg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	mux.OpenStream(ctx, nil, streamOpenFrame("stream-1"))

	reg.mu.Lock()
	got := reg.registered["stream-1"]
	reg.mu.Unlock()
	if got != sess {
		t.Fatalf("RegisterSender not called with stream-1 session")
	}
}

func TestCloseStreamUnregistersControlSender(t *testing.T) {
	sess := &fakeRelaySession{}
	reg := newFakeRegistry()
	mux := newMuxForTest(sess, reg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	mux.OpenStream(ctx, nil, streamOpenFrame("stream-2"))

	mux.CloseStream("stream-2", false)

	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		reg.mu.Lock()
		got := reg.unregistered["stream-2"]
		reg.mu.Unlock()
		if got == sess {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("UnregisterSender(stream-2, same session) not called after CloseStream")
}
