package terminalsession

import (
	"io"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"webterm/go-core/internal/terminalengine"
)

// 版本契约（计划 §3.4）：新建 Runtime 的 layoutEpoch/screenRevision 固定为 1，
// 0 保留给“客户端无投影”的默认值。
func TestNewRuntimeInitialVersions(t *testing.T) {
	outR, outW := io.Pipe()
	inR, inW := io.Pipe()
	pty := &benchFakePTY{reader: outR, writer: inW}
	done := make(chan struct{})
	go func() {
		_, _ = io.Copy(io.Discard, inR)
		close(done)
	}()

	r := NewRuntime("s1", pty, 5, 10)
	t.Cleanup(func() {
		_ = r.Close()
		_ = outW.Close()
		_ = inW.Close()
		<-done
	})

	info := r.Info()
	if info.LayoutEpoch != 1 {
		t.Fatalf("initial layoutEpoch=%d, want 1", info.LayoutEpoch)
	}
	if info.ScreenRevision != 1 {
		t.Fatalf("initial screenRevision=%d, want 1", info.ScreenRevision)
	}
	if info.InstanceID == "" {
		t.Fatal("instance id must be assigned")
	}
}

func TestRuntimeLargeScrollbackTrimDoesNotSelfDeadlock(t *testing.T) {
	r := newRuntimeTestHarness(t, WithScrollbackLimits(1, 1<<30))

	trimWatermarks := make(chan uint64, 8)
	r.AttachClient(&ScreenClient{
		ID:   "screen-1",
		Send: func(terminalengine.ScreenFrame) {},
		SendHistoryTrim: func(_ uint64, firstAvailableID uint64) {
			trimWatermarks <- firstAvailableID
		},
	})
	if _, granted := r.AcquireLayout("screen-1", true); !granted {
		t.Fatal("screen client was not attached")
	}

	// 旧实现会在同一个 engine.Write 中产生超过 events 容量的 trim 回调，
	// actor 随后阻塞在给自己的 inbox 投递 historyTrimEvent。
	r.postEvent(ptyOutputEvent{data: []byte(strings.Repeat("x\r\n", 2000))})
	waitRuntimeSnapshot(t, r)

	select {
	case firstID := <-trimWatermarks:
		if firstID <= 1 {
			t.Fatalf("trim watermark=%d, want > 1", firstID)
		}
	default:
		t.Fatal("large scrollback output did not publish a trim watermark")
	}
	select {
	case extra := <-trimWatermarks:
		t.Fatalf("trim callbacks were not coalesced, extra watermark=%d", extra)
	default:
	}
}

func TestRuntimeEngineEffectFloodDoesNotSelfDeadlock(t *testing.T) {
	var bells atomic.Int64
	r := newRuntimeTestHarness(t, WithOnBell(func() { bells.Add(1) }))

	r.postEvent(ptyOutputEvent{data: []byte(strings.Repeat("\a", 2000))})
	waitRuntimeSnapshot(t, r)
	if got := bells.Load(); got != 2000 {
		t.Fatalf("bell count=%d, want 2000", got)
	}
}

func newRuntimeTestHarness(t *testing.T, options ...Option) *Runtime {
	t.Helper()
	outR, outW := io.Pipe()
	inR, inW := io.Pipe()
	pty := &benchFakePTY{reader: outR, writer: inW}
	done := make(chan struct{})
	go func() {
		_, _ = io.Copy(io.Discard, inR)
		close(done)
	}()

	r := NewRuntime("runtime-test", pty, 2, 80, options...)
	t.Cleanup(func() {
		_ = r.Close()
		_ = outW.Close()
		_ = inW.Close()
		<-done
	})
	return r
}

func waitRuntimeSnapshot(t *testing.T, r *Runtime) {
	t.Helper()
	done := make(chan struct{})
	go func() {
		r.ProjectedSnapshot()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("runtime actor stopped responding after engine output")
	}
}
