package relay

import (
	"context"
	"encoding/json"
	"sync"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/application"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/session"
)

type capWriter struct {
	mu     sync.Mutex
	frames []relaycore.Frame
}

func (w *capWriter) writeFrame(_ context.Context, _ *websocket.Conn, f relaycore.Frame) {
	w.mu.Lock()
	w.frames = append(w.frames, f)
	w.mu.Unlock()
}

func (w *capWriter) writeRaw(context.Context, *websocket.Conn, []byte) error { return nil }

func (w *capWriter) forStream(id string) []relaycore.Frame {
	w.mu.Lock()
	defer w.mu.Unlock()
	var out []relaycore.Frame
	for _, f := range w.frames {
		if f.StreamID == id {
			out = append(out, f)
		}
	}
	return out
}

// 对端在请求体未收完（未 Fin）就断开时，processStream 必须经由 done 退出，
// 不能继续 respond（不应产生任何响应帧）。
func TestCloseStreamAbortsRequestPhaseBeforeRespond(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{})
	router := application.NewSessionRouter(manager)
	w := &capWriter{}
	p := NewHTTPProxy(router, w)

	meta, _ := json.Marshal(relaycore.HTTPRequestMeta{Method: "GET", Path: "/api/sessions"})
	p.HandleHTTPHeaders(context.Background(), nil, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, "s1", 0, meta))
	p.DeliverChunk(relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, "s1", 0, []byte("partial")))

	p.CloseStream("s1")

	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		p.mu.Lock()
		_, still := p.streams["s1"]
		p.mu.Unlock()
		if !still {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if got := len(w.forStream("s1")); got != 0 {
		t.Fatalf("expected no response frames after peer close, got %d", got)
	}
}

func TestCloseStreamUnknownIsNoOp(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{})
	p := NewHTTPProxy(application.NewSessionRouter(manager), &capWriter{})
	// 不存在的 stream 不应 panic；重复 CloseStream 也不应 panic（closeOnce）。
	p.CloseStream("does-not-exist")
	p.CloseStream("does-not-exist")
}
