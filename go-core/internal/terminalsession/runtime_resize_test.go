package terminalsession

import (
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"webterm/go-core/internal/terminalengine"
)

// blockingPTY 的 Read 阻塞到关闭，使 runtime 保持存活但不产生输出，
// 供 resize actor 测试使用。
type blockingPTY struct {
	closed chan struct{}
}

func newBlockingPTY() *blockingPTY { return &blockingPTY{closed: make(chan struct{})} }

func (p *blockingPTY) Read([]byte) (int, error) {
	<-p.closed
	return 0, errors.New("pty closed")
}
func (p *blockingPTY) Write(data []byte) (int, error) { return len(data), nil }
func (p *blockingPTY) Close() error {
	select {
	case <-p.closed:
	default:
		close(p.closed)
	}
	return nil
}

// resizeRecorder 记录 onResize 回调的每次触发。
type resizeRecorder struct {
	mu     sync.Mutex
	calls  [][2]int
	notify chan struct{}
}

func newResizeRecorder() *resizeRecorder {
	return &resizeRecorder{notify: make(chan struct{}, 64)}
}

func (rec *resizeRecorder) record(cols, rows int) {
	rec.mu.Lock()
	rec.calls = append(rec.calls, [2]int{cols, rows})
	rec.mu.Unlock()
	select {
	case rec.notify <- struct{}{}:
	default:
	}
}

func (rec *resizeRecorder) snapshot() [][2]int {
	rec.mu.Lock()
	defer rec.mu.Unlock()
	return append([][2]int(nil), rec.calls...)
}

func (rec *resizeRecorder) waitForCount(t *testing.T, n int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if len(rec.snapshot()) >= n {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %d onResize calls, got %d", n, len(rec.snapshot()))
}

// controllableResizer 的失败开关可由测试切换，用于模拟 PTY resize 成功/失败。
type controllableResizer struct {
	fail   atomic.Bool
	calls  atomic.Int64
	lastMu sync.Mutex
	cols   int
	rows   int
}

func (res *controllableResizer) resize(cols, rows int) error {
	res.calls.Add(1)
	res.lastMu.Lock()
	res.cols, res.rows = cols, rows
	res.lastMu.Unlock()
	if res.fail.Load() {
		return errors.New("pty resize failed")
	}
	return nil
}

// newResizeRuntime 构造一个带可控 PTY resizer 与 onResize 记录器的 Runtime（5 行 × 10 列），
// 并返回已授权的 layout lease ID。
func newResizeRuntime(t *testing.T, resizer *controllableResizer, rec *resizeRecorder) (*Runtime, string) {
	t.Helper()
	pty := newBlockingPTY()
	r := NewRuntime("resize-test", pty, 5, 10,
		WithPTYResizer(resizer.resize),
		WithOnResize(rec.record),
	)
	t.Cleanup(func() { _ = r.Close() })
	r.AttachClient(&ScreenClient{ID: "screen-1", Send: func(terminalengine.ScreenFrame) {}})
	leaseID, granted := r.AcquireLayout("screen-1", true)
	if !granted || leaseID == "" {
		t.Fatal("layout lease not granted")
	}
	t.Cleanup(func() { r.DetachClient("screen-1") })
	return r, leaseID
}

// waitForResizerCalls 轮询 PTY resizer 的原子调用计数，作为“resize 事件已被 actor
// 处理到 ptyResizer 一步”的无竞态屏障（不读取受 actor 无锁写的 layoutEpoch）。
func waitForResizerCalls(t *testing.T, resizer *controllableResizer, want int64) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if resizer.calls.Load() >= want {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for pty resizer calls >= %d, got %d", want, resizer.calls.Load())
}

// 有效 resize：租约有效且 PTY 成功，onResize 携带新尺寸触发。
func TestRuntimeResizeCallsOnResizeOnSuccess(t *testing.T) {
	resizer := &controllableResizer{}
	rec := newResizeRecorder()
	r, leaseID := newResizeRuntime(t, resizer, rec)

	r.Resize("screen-1", leaseID, 80, 20)
	rec.waitForCount(t, 1)

	calls := rec.snapshot()
	if len(calls) != 1 || calls[0] != [2]int{80, 20} {
		t.Fatalf("onResize calls = %v, want [[80 20]]", calls)
	}
	if resizer.calls.Load() != 1 {
		t.Fatalf("pty resizer calls = %d, want 1", resizer.calls.Load())
	}
}

// 无效 lease：resize 被拒绝，onResize 不触发；随后的有效 resize 仍正常触发。
func TestRuntimeResizeInvalidLeaseDoesNotCallOnResize(t *testing.T) {
	resizer := &controllableResizer{}
	rec := newResizeRecorder()
	r, leaseID := newResizeRuntime(t, resizer, rec)

	r.Resize("screen-1", "bogus-lease", 80, 20) // 无效租约
	r.Resize("screen-1", leaseID, 60, 15)       // 有效 barrier
	rec.waitForCount(t, 1)

	calls := rec.snapshot()
	if len(calls) != 1 || calls[0] != [2]int{60, 15} {
		t.Fatalf("onResize calls = %v, want only [[60 15]] (invalid lease must not fire)", calls)
	}
}

// PTY resize 失败：引擎几何仍会更新（best-effort），但 onResize 不触发，
// 因此外层 Info 不应报告一个真实终端未采用的尺寸。
func TestRuntimeResizePTYFailureDoesNotCallOnResize(t *testing.T) {
	resizer := &controllableResizer{}
	rec := newResizeRecorder()
	r, leaseID := newResizeRuntime(t, resizer, rec)

	resizer.fail.Store(true)
	r.Resize("screen-1", leaseID, 80, 20)
	// ptyResizer 被调用（且失败）证明 resize 事件已被 actor 处理。onResize 由
	// ptyResized 门控，PTY 失败时永不触发，因此此刻断言 rec 为空是稳定的。
	waitForResizerCalls(t, resizer, 1)
	if calls := rec.snapshot(); len(calls) != 0 {
		t.Fatalf("onResize fired on PTY failure: %v, want none", calls)
	}

	// PTY 恢复成功后，新的有效 resize 正常触发 onResize。
	resizer.fail.Store(false)
	r.Resize("screen-1", leaseID, 60, 15)
	rec.waitForCount(t, 1)
	calls := rec.snapshot()
	if len(calls) != 1 || calls[0] != [2]int{60, 15} {
		t.Fatalf("onResize calls after recovery = %v, want [[60 15]]", calls)
	}
}

// 重复相同尺寸：不推进 layoutEpoch，也不触发 onResize。
func TestRuntimeResizeSameSizeDoesNotCallOnResize(t *testing.T) {
	resizer := &controllableResizer{}
	rec := newResizeRecorder()
	r, leaseID := newResizeRuntime(t, resizer, rec)

	r.Resize("screen-1", leaseID, 10, 5)   // 与初始 5×10 相同
	r.Resize("screen-1", leaseID, 80, 20)  // 有效 barrier
	rec.waitForCount(t, 1)

	calls := rec.snapshot()
	if len(calls) != 1 || calls[0] != [2]int{80, 20} {
		t.Fatalf("onResize calls = %v, want only [[80 20]] (same-size must not fire)", calls)
	}
	if epoch := r.Info().LayoutEpoch; epoch != 2 {
		t.Fatalf("layoutEpoch = %d, want 2 (same-size resize must not advance epoch)", epoch)
	}
}
