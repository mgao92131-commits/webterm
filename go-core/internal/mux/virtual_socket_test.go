package mux

import (
	"context"
	"encoding/json"
	"sync"
	"testing"

	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

type recordingSocket struct {
	mu     sync.Mutex
	writes []writeRecord
	closed bool
}

type writeRecord struct {
	msgType session.MessageType
	data    []byte
}

func (s *recordingSocket) Read(ctx context.Context) (session.MessageType, []byte, error) {
	<-ctx.Done()
	return 0, nil, ctx.Err()
}

func (s *recordingSocket) Write(ctx context.Context, mt session.MessageType, data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.writes = append(s.writes, writeRecord{msgType: mt, data: append([]byte(nil), data...)})
	return nil
}

func (s *recordingSocket) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	return nil
}

func (s *recordingSocket) textWrites() []map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []map[string]any
	for _, w := range s.writes {
		if w.msgType == session.MessageText {
			var msg map[string]any
			_ = json.Unmarshal(w.data, &msg)
			out = append(out, msg)
		}
	}
	return out
}

func TestVirtualSocketBackpressureCloseCode(t *testing.T) {
	fake := &recordingSocket{}
	sess := Serve(fake, &ServeOpts{})
	vs := sess.newSocket("s1", "")

	// Fill the 256-slot incoming buffer.
	for i := 0; i < 256; i++ {
		if !vs.Emit([]byte("a"), false) {
			t.Fatalf("emit %d should succeed while buffer not full", i+1)
		}
	}
	// The 257th emit triggers backpressure close.
	if vs.Emit([]byte("b"), false) {
		t.Fatal("emit after full buffer should fail")
	}

	var closeMsg map[string]any
	for _, m := range fake.textWrites() {
		if m["type"] == protocol.WSClose {
			closeMsg = m
			break
		}
	}
	if closeMsg == nil {
		t.Fatalf("expected ws-close control message, got %#v", fake.textWrites())
	}
	if closeMsg["code"] == nil || int(closeMsg["code"].(float64)) != 1011 {
		t.Fatalf("expected close code 1011, got %#v", closeMsg["code"])
	}
	if closeMsg["reason"] != "incoming buffer full" {
		t.Fatalf("expected close reason 'incoming buffer full', got %#v", closeMsg["reason"])
	}
}
