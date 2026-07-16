package terminalsession

import (
	"bytes"
	"io"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type partialInputWriter struct {
	mu       sync.Mutex
	maxWrite int
	data     bytes.Buffer
}

func (w *partialInputWriter) Write(data []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if len(data) > w.maxWrite {
		data = data[:w.maxWrite]
	}
	return w.data.Write(data)
}

func (w *partialInputWriter) Bytes() []byte {
	w.mu.Lock()
	defer w.mu.Unlock()
	return append([]byte(nil), w.data.Bytes()...)
}

func TestInputWriterChunksPacesAndPreservesPartialWrites(t *testing.T) {
	target := &partialInputWriter{maxWrite: 7}
	var waits atomic.Int64
	w := newInputWriter(target, inputWriterConfig{
		chunkSize: 64, chunkDelay: time.Millisecond, maxJobs: 4, maxBytes: 1024,
		wait: func(delay time.Duration) {
			if delay != time.Millisecond {
				t.Errorf("delay=%s, want 1ms", delay)
			}
			waits.Add(1)
		},
	})
	t.Cleanup(w.Close)

	payload := bytes.Repeat([]byte("x"), 150)
	done := make(chan inputWriteResult, 1)
	if !w.Submit(payload, func(result inputWriteResult) { done <- result }) {
		t.Fatal("input must enter the bounded writer queue")
	}
	result := <-done
	if result.err != nil || result.written != len(payload) {
		t.Fatalf("result=%+v, want complete write", result)
	}
	if !bytes.Equal(target.Bytes(), payload) {
		t.Fatal("partial PTY writes lost or reordered input bytes")
	}
	if got := waits.Load(); got != 2 {
		t.Fatalf("paced waits=%d, want 2 between three chunks", got)
	}
}

func TestInputWriterCompletesOneMiBPasteWithoutChangingBytes(t *testing.T) {
	target := &partialInputWriter{maxWrite: 31}
	w := newInputWriter(target, inputWriterConfig{
		chunkSize: 64, chunkDelay: time.Millisecond, maxJobs: 2, maxBytes: 2 << 20,
		wait: func(time.Duration) {},
	})
	t.Cleanup(w.Close)

	payload := bytes.Repeat([]byte("0123456789abcdef"), 1<<16)
	done := make(chan inputWriteResult, 1)
	if !w.Submit(payload, func(result inputWriteResult) { done <- result }) {
		t.Fatal("1 MiB paste must enter the writer queue")
	}
	select {
	case result := <-done:
		if result.err != nil || result.written != len(payload) {
			t.Fatalf("result=%+v, want complete 1 MiB write", result)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("1 MiB chunked paste did not complete")
	}
	if !bytes.Equal(target.Bytes(), payload) {
		t.Fatal("1 MiB paste bytes changed during chunking")
	}
}

type blockingInputPTY struct {
	closed  chan struct{}
	started chan struct{}
	release chan struct{}
	writes  atomic.Int64
	dataMu  sync.Mutex
	data    bytes.Buffer
}

func newBlockingInputPTY() *blockingInputPTY {
	return &blockingInputPTY{
		closed: make(chan struct{}), started: make(chan struct{}, 1), release: make(chan struct{}),
	}
}

func (p *blockingInputPTY) Read([]byte) (int, error) {
	<-p.closed
	return 0, io.EOF
}

func (p *blockingInputPTY) Write(data []byte) (int, error) {
	p.writes.Add(1)
	select {
	case p.started <- struct{}{}:
	default:
	}
	select {
	case <-p.release:
		p.dataMu.Lock()
		defer p.dataMu.Unlock()
		return p.data.Write(data)
	case <-p.closed:
		return 0, io.ErrClosedPipe
	}
}

func (p *blockingInputPTY) Close() error {
	select {
	case <-p.closed:
	default:
		close(p.closed)
	}
	return nil
}

func (p *blockingInputPTY) unblock() {
	select {
	case <-p.release:
	default:
		close(p.release)
	}
}
