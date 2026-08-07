package mux

import (
	"context"
	"errors"
	"io"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"webterm/go-core/internal/diagnostics"
	termsession "webterm/go-core/internal/session"
)

type blockingMuxSocket struct {
	mu           sync.Mutex
	writes       [][]byte
	firstStarted chan struct{}
	releaseFirst chan struct{}
}

type partsMuxSocket struct {
	writeCalled      atomic.Bool
	writePartsCalled atomic.Bool
	received         []byte
}

func (s *partsMuxSocket) Read(ctx context.Context) (termsession.MessageType, []byte, error) {
	<-ctx.Done()
	return 0, nil, ctx.Err()
}
func (s *partsMuxSocket) Write(context.Context, termsession.MessageType, []byte) error {
	s.writeCalled.Store(true)
	return errors.New("contiguous fallback must not be used")
}
func (s *partsMuxSocket) WriteParts(_ context.Context, _ termsession.MessageType, parts ...[]byte) error {
	s.writePartsCalled.Store(true)
	for _, part := range parts {
		s.received = append(s.received, part...)
	}
	return nil
}
func (s *partsMuxSocket) Close() error     { return nil }
func (s *partsMuxSocket) Protocol() string { return "webterm.mux.v1" }

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
	writer := NewPhysicalWriter(socket, 128)
	ctx, cancel := context.WithCancel(context.Background())
	go writer.Run(ctx)
	defer func() {
		cancel()
		<-writer.Done()
	}()

	results := make(chan error, 3)
	go func() { results <- writer.Submit(ctx, termsession.MessageBinary, []byte("screen-a"), false) }()
	select {
	case <-socket.firstStarted:
	case <-time.After(time.Second):
		t.Fatal("first screen write did not start")
	}
	go func() { results <- writer.Submit(ctx, termsession.MessageBinary, []byte("screen-b"), false) }()
	go func() { results <- writer.Submit(ctx, termsession.MessageText, []byte("control"), true) }()

	deadline := time.Now().Add(time.Second)
	for (len(writer.dataWrites) == 0 || len(writer.highWrites) == 0) && time.Now().Before(deadline) {
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

func TestPhysicalWriterUsesSocketPartsWriterWithoutCombiningPayload(t *testing.T) {
	socket := &partsMuxSocket{}
	writer := NewPhysicalWriter(socket, 8)
	ctx, cancel := context.WithCancel(context.Background())
	go writer.Run(ctx)

	err := writer.SubmitParts(ctx, termsession.MessageBinary,
		[][]byte{[]byte("header"), []byte("payload")}, false)
	cancel()
	<-writer.Done()
	if err != nil {
		t.Fatal(err)
	}
	if socket.writeCalled.Load() || !socket.writePartsCalled.Load() {
		t.Fatalf("Write called=%v WriteParts called=%v",
			socket.writeCalled.Load(), socket.writePartsCalled.Load())
	}
	if got := string(socket.received); got != "headerpayload" {
		t.Fatalf("received=%q, want headerpayload", got)
	}
}

func TestPhysicalWriterLetsAnotherTerminalRunBeforeNextFrame(t *testing.T) {
	socket := &blockingMuxSocket{
		firstStarted: make(chan struct{}),
		releaseFirst: make(chan struct{}),
	}
	writer := NewPhysicalWriter(socket, 128)
	ctx, cancel := context.WithCancel(context.Background())
	go writer.Run(ctx)
	defer func() {
		cancel()
		<-writer.Done()
	}()

	results := make(chan error, 3)
	go func() {
		results <- writer.Submit(ctx, termsession.MessageBinary, []byte("terminal-a-1"), false)
		// A logical channel cannot submit its next screen state until the
		// physical result for the previous state is known.
		results <- writer.Submit(ctx, termsession.MessageBinary, []byte("terminal-a-2"), false)
	}()
	select {
	case <-socket.firstStarted:
	case <-time.After(time.Second):
		t.Fatal("first terminal write did not start")
	}

	go func() {
		results <- writer.Submit(ctx, termsession.MessageBinary, []byte("terminal-b-1"), false)
	}()
	deadline := time.Now().Add(time.Second)
	for len(writer.dataWrites) == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if len(writer.dataWrites) == 0 {
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

func TestPhysicalWriterBoundsHighPriorityBurst(t *testing.T) {
	socket := &blockingMuxSocket{
		firstStarted: make(chan struct{}),
		releaseFirst: make(chan struct{}),
	}
	writer := NewPhysicalWriter(socket, 128)
	ctx, cancel := context.WithCancel(context.Background())
	go writer.Run(ctx)
	defer func() {
		cancel()
		<-writer.Done()
	}()

	results := make(chan error, 14)
	go func() { results <- writer.Submit(ctx, termsession.MessageText, []byte("high-0"), true) }()
	select {
	case <-socket.firstStarted:
	case <-time.After(time.Second):
		t.Fatal("first high-priority write did not start")
	}
	for i := 1; i <= 12; i++ {
		payload := []byte{byte(i)}
		go func() { results <- writer.Submit(ctx, termsession.MessageText, payload, true) }()
	}
	go func() { results <- writer.Submit(ctx, termsession.MessageBinary, []byte("normal"), false) }()

	deadline := time.Now().Add(time.Second)
	for (len(writer.highWrites) < 12 || len(writer.dataWrites) < 1) && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	close(socket.releaseFirst)
	for range 14 {
		if err := <-results; err != nil && err != io.EOF {
			t.Fatal(err)
		}
	}

	socket.mu.Lock()
	defer socket.mu.Unlock()
	normalIndex := -1
	for i, payload := range socket.writes {
		if string(payload) == "normal" {
			normalIndex = i
			break
		}
	}
	if normalIndex < 0 || normalIndex > maxHighPriorityBurst {
		t.Fatalf("normal write index=%d, want <= %d; writes=%q", normalIndex, maxHighPriorityBurst, socket.writes)
	}
}

// 指标埋点不得改变公平调度：连续 maxHighPriorityBurst 条高优先级后必须插入 data。
func TestPhysicalWriterPrefersDataAfterEightHighBurst(t *testing.T) {
	TestPhysicalWriterBoundsHighPriorityBurst(t)
}

func waitUntil(t *testing.T, deadline time.Time, cond func() bool, msg string) {
	t.Helper()
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal(msg)
}

func TestPhysicalWriterGlobalDepthAcrossWriters(t *testing.T) {
	diagnostics.Default.ResetWriterDepthsForTest()
	t.Cleanup(diagnostics.Default.ResetWriterDepthsForTest)

	socketA := &blockingMuxSocket{}
	socketB := &blockingMuxSocket{}
	writerA := NewPhysicalWriter(socketA, 128)
	writerB := NewPhysicalWriter(socketB, 128)

	ctx := context.Background()
	results := make(chan error, 100)
	for range 80 {
		go func() {
			results <- writerA.Submit(ctx, termsession.MessageText, []byte("a"), true)
		}()
	}
	waitUntil(t, time.Now().Add(time.Second), func() bool {
		return len(writerA.highWrites) == 80
	}, "writer A queue did not reach depth 80")
	writerA.observeQueueDepths()

	for range 20 {
		go func() {
			results <- writerB.Submit(ctx, termsession.MessageText, []byte("b"), true)
		}()
	}
	waitUntil(t, time.Now().Add(time.Second), func() bool {
		return len(writerB.highWrites) == 20
	}, "writer B queue did not reach depth 20")
	writerB.observeQueueDepths()

	waitUntil(t, time.Now().Add(time.Second), func() bool {
		return diagnostics.Default.WriterTotalQueueCurrentDepth.Load() == 100
	}, "global total depth did not reach 100")

	ctxB, cancelB := context.WithCancel(context.Background())
	cancelB()
	go writerB.Run(ctxB)
	<-writerB.Done()
	waitUntil(t, time.Now().Add(time.Second), func() bool {
		return diagnostics.Default.WriterTotalQueueCurrentDepth.Load() == 80
	}, "after B close total depth did not settle at 80")

	ctxA, cancelA := context.WithCancel(context.Background())
	cancelA()
	go writerA.Run(ctxA)
	<-writerA.Done()
	waitUntil(t, time.Now().Add(time.Second), func() bool {
		return diagnostics.Default.WriterTotalQueueCurrentDepth.Load() == 0
	}, "after A close total depth did not settle at 0")

	for range 100 {
		if err := <-results; err != ErrWriterClosed {
			t.Fatalf("submit result = %v, want ErrWriterClosed", err)
		}
	}
}

func TestPhysicalWriterCloseUnblocksSubmitWaiters(t *testing.T) {
	socket := &blockingMuxSocket{}
	writer := NewPhysicalWriter(socket, 128)
	ctx := context.Background()

	results := make(chan error, 8)
	for range 8 {
		go func() {
			results <- writer.Submit(ctx, termsession.MessageBinary, []byte("pending"), false)
		}()
	}
	waitUntil(t, time.Now().Add(time.Second), func() bool {
		return len(writer.dataWrites) == 8
	}, "writer queue did not fill")
	if got := writer.dataBytes.Load(); got != int64(8*len("pending")) {
		t.Fatalf("queued data bytes = %d, want %d", got, 8*len("pending"))
	}

	runCtx, cancel := context.WithCancel(context.Background())
	cancel()
	go writer.Run(runCtx)
	<-writer.Done()
	if got := writer.dataBytes.Load(); got != 0 {
		t.Fatalf("queued data bytes after close = %d, want 0", got)
	}

	for range 8 {
		err := <-results
		if err != nil && err != ErrWriterClosed {
			t.Fatalf("submit result = %v, want nil or ErrWriterClosed", err)
		}
	}
}

func TestPhysicalWriterDataByteBudgetBlocksBeforeFrameQueueLimit(t *testing.T) {
	socket := &blockingMuxSocket{}
	writer := newPhysicalWriterWithLimits(socket, 8, 8, 8)

	firstResult := make(chan error, 1)
	go func() {
		firstResult <- writer.Submit(
			context.Background(), termsession.MessageBinary, []byte("123456"), false)
	}()
	waitUntil(t, time.Now().Add(time.Second), func() bool {
		return len(writer.dataWrites) == 1
	}, "first frame did not enter data queue")

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	err := writer.Submit(ctx, termsession.MessageBinary, []byte("7890"), false)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("second submit error=%v, want byte-budget deadline", err)
	}
	if got := len(writer.dataWrites); got != 1 {
		t.Fatalf("queued frames=%d, want 1 while byte budget is full", got)
	}

	runCtx, stop := context.WithCancel(context.Background())
	stop()
	go writer.Run(runCtx)
	<-writer.Done()
	if err := <-firstResult; !errors.Is(err, ErrWriterClosed) {
		t.Fatalf("first submit result=%v, want ErrWriterClosed", err)
	}
}

func TestPhysicalWriterAllowsOnlyOneOversizedDataFrameAtATime(t *testing.T) {
	socket := &blockingMuxSocket{
		firstStarted: make(chan struct{}),
		releaseFirst: make(chan struct{}),
	}
	writer := newPhysicalWriterWithLimits(socket, 8, 8, 8)
	runCtx, stop := context.WithCancel(context.Background())
	go writer.Run(runCtx)
	defer func() {
		stop()
		<-writer.Done()
	}()

	results := make(chan error, 2)
	go func() {
		results <- writer.Submit(
			runCtx, termsession.MessageBinary, []byte("oversized-1"), false)
	}()
	select {
	case <-socket.firstStarted:
	case <-time.After(time.Second):
		t.Fatal("oversized frame did not start")
	}
	go func() {
		results <- writer.Submit(
			runCtx, termsession.MessageBinary, []byte("oversized-2"), false)
	}()
	time.Sleep(20 * time.Millisecond)
	if got := len(writer.dataWrites); got != 0 {
		t.Fatalf("second oversized frame entered queue while first was in flight: %d", got)
	}
	close(socket.releaseFirst)
	for range 2 {
		if err := <-results; err != nil {
			t.Fatal(err)
		}
	}
}

func TestPhysicalWriterSubmitAfterAcceptingFalseFailsImmediately(t *testing.T) {
	socket := &blockingMuxSocket{}
	writer := NewPhysicalWriter(socket, 128)
	ctx := context.Background()

	runCtx, cancel := context.WithCancel(context.Background())
	cancel()
	go writer.Run(runCtx)
	<-writer.Done()

	if writer.accepting.Load() {
		t.Fatal("accepting must be false after Run exits")
	}

	started := time.Now()
	err := writer.Submit(ctx, termsession.MessageText, []byte("after-close"), true)
	if err != ErrWriterClosed {
		t.Fatalf("Submit after close = %v, want ErrWriterClosed", err)
	}
	if time.Since(started) > 200*time.Millisecond {
		t.Fatalf("Submit after accepting=false must fail immediately, took %v", time.Since(started))
	}

	// 关闭后不应再有排队请求卡住。
	if len(writer.highWrites) != 0 || len(writer.dataWrites) != 0 {
		t.Fatalf("queues must be empty after close, high=%d data=%d",
			len(writer.highWrites), len(writer.dataWrites))
	}
}

func TestPhysicalWriterInvalidatedChannelPurgesQueuedFramesBeforeBarrier(t *testing.T) {
	socket := &blockingMuxSocket{
		firstStarted: make(chan struct{}),
		releaseFirst: make(chan struct{}),
	}
	writer := NewPhysicalWriter(socket, 128)
	ctx, cancel := context.WithCancel(context.Background())
	go writer.Run(ctx)
	defer func() {
		cancel()
		<-writer.Done()
	}()

	lifecycle := newChannelLifecycle()
	results := make(chan error, 2)
	go func() {
		results <- writer.SubmitChannel(
			ctx, termsession.MessageBinary, []byte("already-writing"), false, lifecycle)
	}()
	select {
	case <-socket.firstStarted:
	case <-time.After(time.Second):
		t.Fatal("first channel write did not start")
	}
	go func() {
		results <- writer.SubmitChannel(
			ctx, termsession.MessageBinary, []byte("queued-old-frame"), false, lifecycle)
	}()
	waitUntil(t, time.Now().Add(time.Second), func() bool {
		return len(writer.dataWrites) == 1
	}, "old channel frame did not enter physical queue")

	lifecycle.invalidate()
	barrierDone := make(chan error, 1)
	go func() { barrierDone <- lifecycle.wait(ctx) }()
	select {
	case <-barrierDone:
		t.Fatal("channel barrier completed while an old write was still in flight")
	case <-time.After(20 * time.Millisecond):
	}

	close(socket.releaseFirst)
	var sawSuccess, sawClosed bool
	for range 2 {
		switch err := <-results; {
		case err == nil:
			sawSuccess = true
		case errors.Is(err, ErrChannelClosed):
			sawClosed = true
		default:
			t.Fatalf("unexpected channel submit result: %v", err)
		}
	}
	if !sawSuccess || !sawClosed {
		t.Fatalf("submit results success=%v closed=%v, want both", sawSuccess, sawClosed)
	}
	select {
	case err := <-barrierDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("channel barrier did not complete after queued frame was purged")
	}

	socket.mu.Lock()
	defer socket.mu.Unlock()
	if len(socket.writes) != 1 || string(socket.writes[0]) != "already-writing" {
		t.Fatalf("socket writes=%q, queued old frame must be purged", socket.writes)
	}
	if err := writer.SubmitChannel(
		ctx, termsession.MessageBinary, []byte("after-abort"), false, lifecycle); !errors.Is(err, ErrChannelClosed) {
		t.Fatalf("SubmitChannel after abort = %v, want ErrChannelClosed", err)
	}
}
