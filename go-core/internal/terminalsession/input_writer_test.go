package terminalsession

import (
	"bytes"
	"context"
	"errors"
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

// 关闭时：正在写入的任务以部分写入结束（Uncertain），仍留在队列中的任务必须
// 拿到 ErrInputWriterClosedBeforeWrite（Rejected），每个任务回调恰好一次，
// 计数归零，关闭后 Submit 返回 false。
func TestInputWriterShutdownSettlesQueuedJobsOnClose(t *testing.T) {
	pty := newBlockingInputPTY()
	w := newInputWriter(pty, inputWriterConfig{
		chunkSize: 64, chunkDelay: 0, maxJobs: 8, maxBytes: 1024,
		wait: func(time.Duration) {},
	})

	type outcome struct {
		name   string
		result inputWriteResult
	}
	outcomes := make(chan outcome, 4)
	var mu sync.Mutex
	counts := map[string]int{}
	record := func(name string) func(inputWriteResult) {
		return func(res inputWriteResult) {
			mu.Lock()
			counts[name]++
			mu.Unlock()
			outcomes <- outcome{name, res}
		}
	}

	// A 进入并阻塞在 Write 中（in-flight）。
	if !w.Submit([]byte("AAAA"), record("A")) {
		t.Fatal("A must be accepted")
	}
	<-pty.started
	// B/C/D 排在 A 之后，尚未开始写入。
	for _, name := range []string{"B", "C", "D"} {
		if !w.Submit([]byte(name+name+name), record(name)) {
			t.Fatalf("%s must be accepted", name)
		}
	}

	// 先关 stopCh（A 仍阻塞在 Write），再释放 A：A 以 ErrClosedPipe 结束，
	// worker 随后看到 stopCh 并结算 B/C/D。顺序保证 B/C/D 不会被取出写入。
	w.Close()
	pty.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := w.Shutdown(ctx); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}

	got := map[string]inputWriteResult{}
	for i := 0; i < 4; i++ {
		select {
		case o := <-outcomes:
			got[o.name] = o.result
		case <-time.After(time.Second):
			t.Fatalf("expected 4 callbacks, got %d (%v)", len(got), got)
		}
	}

	if !errors.Is(got["A"].err, io.ErrClosedPipe) {
		t.Fatalf("A (in-flight) err=%v, want ErrClosedPipe -> Uncertain", got["A"].err)
	}
	for _, name := range []string{"B", "C", "D"} {
		if !errors.Is(got[name].err, ErrInputWriterClosedBeforeWrite) {
			t.Fatalf("%s (queued) err=%v, want ErrInputWriterClosedBeforeWrite -> Rejected", name, got[name].err)
		}
	}

	mu.Lock()
	for name, c := range counts {
		if c != 1 {
			t.Fatalf("callback %s fired %d times, want exactly 1", name, c)
		}
	}
	mu.Unlock()

	w.mu.Lock()
	jobs, byts := w.pendingJobs, w.pendingBytes
	w.mu.Unlock()
	if jobs != 0 || byts != 0 {
		t.Fatalf("pending jobs=%d bytes=%d after shutdown, want 0/0", jobs, byts)
	}

	if w.Submit([]byte("late"), nil) {
		t.Fatal("Submit after close must return false")
	}
}

// 关闭前已完整写入的任务结果保持 Written（err==nil），未开始的任务为 Rejected。
func TestInputWriterShutdownKeepsFullyWrittenResult(t *testing.T) {
	pty := newBlockingInputPTY()
	w := newInputWriter(pty, inputWriterConfig{
		chunkSize: 64, chunkDelay: 0, maxJobs: 8, maxBytes: 1024,
		wait: func(time.Duration) {},
	})

	results := make(chan inputWriteResult, 2)
	cb := func(res inputWriteResult) { results <- res }

	if !w.Submit([]byte("full"), cb) {
		t.Fatal("A must be accepted")
	}
	<-pty.started
	if !w.Submit([]byte("queued"), cb) {
		t.Fatal("B must be accepted")
	}

	// 关 stopCh 后放行 A：A 完整写入（Written），worker 随后结算 B（Rejected）。
	w.Close()
	pty.unblock()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := w.Shutdown(ctx); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}

	var a, b inputWriteResult
	for i := 0; i < 2; i++ {
		select {
		case res := <-results:
			if res.err == nil {
				a = res
			} else {
				b = res
			}
		case <-time.After(time.Second):
			t.Fatal("expected 2 callbacks")
		}
	}
	if a.err != nil || a.written != len("full") {
		t.Fatalf("fully-written result=%+v, want complete write, nil err", a)
	}
	if !errors.Is(b.err, ErrInputWriterClosedBeforeWrite) {
		t.Fatalf("queued result err=%v, want ErrInputWriterClosedBeforeWrite", b.err)
	}
}

// 并发 Submit 与 Shutdown：每个被接受的任务恰好结算一次（-race 下验证无竞争、
// 无回调丢失或重复）。
func TestInputWriterConcurrentSubmitShutdownRace(t *testing.T) {
	pty := newBlockingInputPTY()
	w := newInputWriter(pty, inputWriterConfig{
		chunkSize: 16, chunkDelay: 0, maxJobs: 256, maxBytes: 1 << 20,
		wait: func(time.Duration) {},
	})
	var accepted, callbacks atomic.Int64
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				if w.Submit([]byte("abcd"), func(inputWriteResult) { callbacks.Add(1) }) {
					accepted.Add(1)
				}
			}
		}()
	}
	// 释放可能阻塞的 in-flight 写入，让 worker 能进入结算。
	pty.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = w.Shutdown(ctx)
	wg.Wait()
	if a, c := accepted.Load(), callbacks.Load(); a != c {
		t.Fatalf("accepted=%d callbacks=%d, want equal (each accepted job settled exactly once)", a, c)
	}
}
