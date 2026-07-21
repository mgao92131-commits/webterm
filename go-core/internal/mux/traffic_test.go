package mux

import (
	"context"
	"errors"
	"io"
	"sync"
	"testing"

	termsession "webterm/go-core/internal/session"
)

// fakeSocket 是一个可控的 termsession.Socket：Read 依次返回预设帧，Write 可注入错误。
type fakeSocket struct {
	mu       sync.Mutex
	reads    []fakeRead
	readIdx  int
	writeErr error
	writes   [][]byte
}

type fakeRead struct {
	msgType termsession.MessageType
	data    []byte
	err     error
}

func (f *fakeSocket) Read(ctx context.Context) (termsession.MessageType, []byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.readIdx >= len(f.reads) {
		return 0, nil, io.EOF
	}
	r := f.reads[f.readIdx]
	f.readIdx++
	return r.msgType, r.data, r.err
}

func (f *fakeSocket) Write(_ context.Context, _ termsession.MessageType, data []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.writeErr != nil {
		return f.writeErr
	}
	cp := make([]byte, len(data))
	copy(cp, data)
	f.writes = append(f.writes, cp)
	return nil
}

func (f *fakeSocket) Close() error { return nil }

func TestPhysicalWriterCountsTxOnlyAfterSuccess(t *testing.T) {
	sock := &fakeSocket{}
	writer := NewPhysicalWriter(sock, 16)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go writer.Run(ctx)

	if err := writer.Submit(ctx, termsession.MessageBinary, []byte("abcd"), false); err != nil {
		t.Fatalf("submit: %v", err)
	}
	if err := writer.Submit(ctx, termsession.MessageBinary, []byte("ef"), true); err != nil {
		t.Fatalf("submit: %v", err)
	}

	frames, bytes := writer.TxSnapshot()
	if frames != 2 || bytes != 6 {
		t.Errorf("tx = (%d frames, %d bytes), want (2, 6)", frames, bytes)
	}
}

func TestPhysicalWriterDoesNotCountFailedWrites(t *testing.T) {
	sock := &fakeSocket{writeErr: errors.New("boom")}
	writer := NewPhysicalWriter(sock, 16)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go writer.Run(ctx)

	if err := writer.Submit(ctx, termsession.MessageBinary, []byte("abcd"), false); err == nil {
		t.Fatal("expected write error")
	}
	frames, bytes := writer.TxSnapshot()
	if frames != 0 || bytes != 0 {
		t.Errorf("failed write must not count, got (%d, %d)", frames, bytes)
	}
}

func TestSessionCountsRxFramesAndBytes(t *testing.T) {
	sock := &fakeSocket{
		reads: []fakeRead{
			{msgType: termsession.MessageBinary, data: []byte("aaa")},
			{msgType: termsession.MessageText, data: []byte("bb")},
			{err: io.EOF},
		},
	}
	sess := Serve(sock, &ServeOpts{})
	if err := sess.readLoop(context.Background()); !errors.Is(err, io.EOF) {
		t.Fatalf("readLoop = %v, want EOF", err)
	}
	snap := sess.TrafficSnapshot()
	if snap.RxFrames != 2 || snap.RxBytes != 5 {
		t.Errorf("rx = (%d frames, %d bytes), want (2, 5)", snap.RxFrames, snap.RxBytes)
	}
}

func TestPhysicalWriterConcurrentTxCounting(t *testing.T) {
	sock := &fakeSocket{}
	writer := NewPhysicalWriter(sock, 256)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go writer.Run(ctx)

	const goroutines = 8
	const perGoroutine = 50
	var wg sync.WaitGroup
	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < perGoroutine; i++ {
				_ = writer.Submit(ctx, termsession.MessageBinary, []byte("abc"), false)
			}
		}()
	}
	wg.Wait()

	frames, bytes := writer.TxSnapshot()
	if frames != goroutines*perGoroutine {
		t.Errorf("frames = %d, want %d", frames, goroutines*perGoroutine)
	}
	if bytes != goroutines*perGoroutine*3 {
		t.Errorf("bytes = %d, want %d", bytes, goroutines*perGoroutine*3)
	}
}
