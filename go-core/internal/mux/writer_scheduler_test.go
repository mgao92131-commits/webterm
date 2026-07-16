package mux

import (
	"context"
	"io"
	"sync"
	"testing"
	"time"

	termsession "webterm/go-core/internal/session"
)

type blockingMuxSocket struct {
	mu           sync.Mutex
	writes       [][]byte
	firstStarted chan struct{}
	releaseFirst chan struct{}
}

func (s *blockingMuxSocket) Read(ctx context.Context) (termsession.MessageType, []byte, error) {
	<-ctx.Done()
	return 0, nil, ctx.Err()
}

func (s *blockingMuxSocket) Write(ctx context.Context, _ termsession.MessageType, payload []byte) error {
	s.mu.Lock()
	index := len(s.writes)
	s.writes = append(s.writes, append([]byte(nil), payload...))
	s.mu.Unlock()
	if index == 0 {
		close(s.firstStarted)
		select {
		case <-s.releaseFirst:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	return nil
}

func (s *blockingMuxSocket) Close() error     { return nil }
func (s *blockingMuxSocket) Protocol() string { return "webterm.mux.v1" }

func TestPhysicalWriterPrioritizesControlBetweenChannelFrames(t *testing.T) {
	socket := &blockingMuxSocket{
		firstStarted: make(chan struct{}),
		releaseFirst: make(chan struct{}),
	}
	session := Serve(socket, &ServeOpts{})
	ctx, cancel := context.WithCancel(context.Background())
	go session.writeLoop(ctx)
	defer func() {
		cancel()
		<-session.writerDone
	}()

	results := make(chan error, 3)
	go func() { results <- session.submitWrite(ctx, termsession.MessageBinary, []byte("screen-a"), false) }()
	select {
	case <-socket.firstStarted:
	case <-time.After(time.Second):
		t.Fatal("first screen write did not start")
	}
	go func() { results <- session.submitWrite(ctx, termsession.MessageBinary, []byte("screen-b"), false) }()
	go func() { results <- session.submitWrite(ctx, termsession.MessageText, []byte("control"), true) }()

	deadline := time.Now().Add(time.Second)
	for (len(session.dataWrites) == 0 || len(session.highWrites) == 0) && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	close(socket.releaseFirst)
	for range 3 {
		if err := <-results; err != nil && err != io.EOF {
			t.Fatal(err)
		}
	}

	socket.mu.Lock()
	defer socket.mu.Unlock()
	if len(socket.writes) != 3 {
		t.Fatalf("writes=%q, want 3", socket.writes)
	}
	if string(socket.writes[0]) != "screen-a" ||
		string(socket.writes[1]) != "control" ||
		string(socket.writes[2]) != "screen-b" {
		t.Fatalf("write order=%q, want screen-a, control, screen-b", socket.writes)
	}
}

func TestPhysicalWriterLetsAnotherTerminalRunBeforeNextFrame(t *testing.T) {
	socket := &blockingMuxSocket{
		firstStarted: make(chan struct{}),
		releaseFirst: make(chan struct{}),
	}
	session := Serve(socket, &ServeOpts{})
	ctx, cancel := context.WithCancel(context.Background())
	go session.writeLoop(ctx)
	defer func() {
		cancel()
		<-session.writerDone
	}()

	results := make(chan error, 3)
	go func() {
		results <- session.submitWrite(ctx, termsession.MessageBinary, []byte("terminal-a-1"), false)
		// A logical channel cannot submit its next screen state until the
		// physical result for the previous state is known.
		results <- session.submitWrite(ctx, termsession.MessageBinary, []byte("terminal-a-2"), false)
	}()
	select {
	case <-socket.firstStarted:
	case <-time.After(time.Second):
		t.Fatal("first terminal write did not start")
	}

	go func() {
		results <- session.submitWrite(ctx, termsession.MessageBinary, []byte("terminal-b-1"), false)
	}()
	deadline := time.Now().Add(time.Second)
	for len(session.dataWrites) == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if len(session.dataWrites) == 0 {
		t.Fatal("second terminal did not enter the physical writer queue")
	}
	close(socket.releaseFirst)
	for range 3 {
		if err := <-results; err != nil && err != io.EOF {
			t.Fatal(err)
		}
	}

	socket.mu.Lock()
	defer socket.mu.Unlock()
	if len(socket.writes) != 3 {
		t.Fatalf("writes=%q, want 3", socket.writes)
	}
	if string(socket.writes[0]) != "terminal-a-1" ||
		string(socket.writes[1]) != "terminal-b-1" ||
		string(socket.writes[2]) != "terminal-a-2" {
		t.Fatalf("write order=%q, want a-1, b-1, a-2", socket.writes)
	}
}
