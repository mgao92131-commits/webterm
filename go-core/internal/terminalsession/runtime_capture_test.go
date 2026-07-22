//go:build webterm_capture

package terminalsession

import (
	"io"
	"strings"
	"testing"
	"time"

	"webterm/go-core/internal/terminalcapture"
	"webterm/go-core/internal/terminalengine"
)

// captureTestHarness 装配一个带捕获 sink 与帧捕获客户端的 Runtime。
type captureTestHarness struct {
	r       *Runtime
	coord   *terminalcapture.Coordinator
	outW    *io.PipeWriter
	frames  chan terminalengine.ScreenFrame
	cleanup func()
}

func newCaptureHarness(t *testing.T) *captureTestHarness {
	t.Helper()
	outR, outW := io.Pipe()
	inR, inW := io.Pipe()
	pty := &benchFakePTY{reader: outR, writer: inW}
	drainDone := make(chan struct{})
	go func() {
		_, _ = io.Copy(io.Discard, inR)
		close(drainDone)
	}()

	coord := terminalcapture.NewCoordinator()
	r := NewRuntime("cap-test", pty, 6, 20, WithCaptureSink(coord))

	frames := make(chan terminalengine.ScreenFrame, 16)
	r.AttachClient(&ScreenClient{
		ID: "cap-client",
		Send: func(frame terminalengine.ScreenFrame) {
			select {
			case frames <- frame:
			default:
			}
		},
	})

	h := &captureTestHarness{r: r, coord: coord, outW: outW, frames: frames}
	h.cleanup = func() {
		_ = r.Close()
		_ = outW.Close()
		_ = inW.Close()
		<-drainDone
	}
	// 开启针对该实例的捕获，使 broadcastFrame 缓存 lastCanonicalFrame。
	id := r.Info().InstanceID
	if err := coord.StartCapture(terminalcapture.Identity{
		CaptureID: "cap-test", SessionID: "cap-test",
		ClientInstanceID: "cap-client", TerminalInstanceID: id, LayoutEpoch: 1,
	}, terminalcapture.DefaultLimits()); err != nil {
		t.Fatalf("StartCapture: %v", err)
	}
	return h
}

func (h *captureTestHarness) writePTY(t *testing.T, data string) {
	t.Helper()
	if _, err := h.outW.Write([]byte(data)); err != nil {
		t.Fatalf("write pty: %v", err)
	}
}

func (h *captureTestHarness) waitFrame(t *testing.T) terminalengine.ScreenFrame {
	t.Helper()
	select {
	case frame := <-h.frames:
		return frame
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for screen frame")
		return terminalengine.ScreenFrame{}
	}
}

func frameText(frame terminalengine.ScreenFrame) string {
	var sb strings.Builder
	for _, line := range frame.Screen {
		for _, run := range line.Runs {
			for _, cell := range run.Cells {
				sb.WriteString(cell.Text)
			}
		}
		sb.WriteByte('\n')
	}
	return sb.String()
}

// 要求 4：capture barrier 不推进 screenRevision。
func TestCaptureBarrierDoesNotAdvanceRevision(t *testing.T) {
	h := newCaptureHarness(t)
	defer h.cleanup()

	h.waitFrame(t) // attach 初始快照
	h.writePTY(t, "hello\r\n")
	h.waitFrame(t)

	revBefore := h.r.Info().ScreenRevision
	barrier := h.r.CaptureBarrier()
	revAfter := h.r.Info().ScreenRevision

	if revAfter != revBefore {
		t.Fatalf("barrier advanced screenRevision: before=%d after=%d", revBefore, revAfter)
	}
	if barrier.AgentRevision != revBefore {
		t.Fatalf("barrier.AgentRevision=%d, want current revision %d", barrier.AgentRevision, revBefore)
	}
	if barrier.TerminalInstanceID != h.r.Info().InstanceID {
		t.Fatalf("barrier instance id mismatch")
	}
}

// 要求 5：capture barrier 不消费 projection dirty——barrier 之后正常投影仍能导出新内容。
func TestCaptureBarrierDoesNotConsumeDirty(t *testing.T) {
	h := newCaptureHarness(t)
	defer h.cleanup()

	h.waitFrame(t)            // attach 初始快照
	h.writePTY(t, "AAAA\r\n") // 第一行内容
	frame1 := h.waitFrame(t)
	if !strings.Contains(frameText(frame1), "AAAA") {
		t.Fatalf("frame1 missing AAAA:\n%s", frameText(frame1))
	}

	// 写入新内容但不等待投影刷新；此时权威缓存仍是 AAAA 帧。
	h.writePTY(t, "BBBB\r\n")
	// 立即执行 barrier：读取已缓存的权威帧，绝不消费 BBBB 的 dirty。
	barrier := h.r.CaptureBarrier()
	if strings.Contains(frameText(barrier.Canonical), "BBBB") {
		t.Fatalf("barrier canonical unexpectedly contains BBBB (read ahead of cached state):\n%s", frameText(barrier.Canonical))
	}
	if !strings.Contains(frameText(barrier.Canonical), "AAAA") {
		t.Fatalf("barrier canonical missing cached AAAA:\n%s", frameText(barrier.Canonical))
	}

	// 关键断言：barrier 未消费 dirty，随后的正常投影必须包含 BBBB。
	frame2 := h.waitFrame(t)
	if !strings.Contains(frameText(frame2), "BBBB") {
		t.Fatalf("barrier consumed projection dirty: BBBB lost in next frame:\n%s", frameText(frame2))
	}
}

// 要求 5（补充）：连续多次 barrier 幂等，不改变权威内容，也不推进 revision。
func TestCaptureBarrierIdempotent(t *testing.T) {
	h := newCaptureHarness(t)
	defer h.cleanup()

	h.waitFrame(t)
	h.writePTY(t, "stable\r\n")
	h.waitFrame(t)

	b1 := h.r.CaptureBarrier()
	b2 := h.r.CaptureBarrier()
	if b1.AgentRevision != b2.AgentRevision {
		t.Fatalf("barrier not idempotent: rev %d vs %d", b1.AgentRevision, b2.AgentRevision)
	}
	if frameText(b1.Canonical) != frameText(b2.Canonical) {
		t.Fatal("barrier canonical content changed between calls")
	}
}

// 要求 7（runtime 层）：barrier.AgentRevision 等于当前 screenRevision，
// barrier.Canonical.Seq <= AgentRevision（缓存帧可能略旧），二者忠实记录、不强制相等。
func TestCaptureBarrierRevisionConsistency(t *testing.T) {
	h := newCaptureHarness(t)
	defer h.cleanup()

	h.waitFrame(t)
	h.writePTY(t, "data\r\n")
	h.waitFrame(t)

	barrier := h.r.CaptureBarrier()
	current := h.r.Info().ScreenRevision
	if barrier.AgentRevision != current {
		t.Fatalf("barrier.AgentRevision=%d, want current %d", barrier.AgentRevision, current)
	}
	if barrier.Canonical.Seq > barrier.AgentRevision {
		t.Fatalf("canonical seq %d exceeds agent revision %d", barrier.Canonical.Seq, barrier.AgentRevision)
	}
	if barrier.Canonical.Seq == 0 {
		t.Fatal("barrier canonical frame must be available after a broadcast")
	}
}
