package terminalsession

import (
	"bytes"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"webterm/go-core/internal/terminalengine"
)

type reliableInputPTY struct {
	closed chan struct{}
	writes atomic.Int64
	data   bytes.Buffer
}

func newReliableInputPTY() *reliableInputPTY {
	return &reliableInputPTY{closed: make(chan struct{})}
}

func (p *reliableInputPTY) Read([]byte) (int, error) {
	<-p.closed
	return 0, io.EOF
}

func (p *reliableInputPTY) Write(data []byte) (int, error) {
	p.writes.Add(1)
	return p.data.Write(data)
}

func (p *reliableInputPTY) Close() error {
	select {
	case <-p.closed:
	default:
		close(p.closed)
	}
	return nil
}

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

func TestPTYOutputAccumulatesEventsAndBytes(t *testing.T) {
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

	if events, bytes := r.PTYOutputSnapshot(); events != 0 || bytes != 0 {
		t.Fatalf("initial pty output events=%d bytes=%d, want 0 0", events, bytes)
	}

	data := []byte("hello pty output")
	if _, err := outW.Write(data); err != nil {
		t.Fatalf("write pty: %v", err)
	}

	// 等待 actor 处理事件。
	for {
		events, bytes := r.PTYOutputSnapshot()
		if events == 1 && bytes == uint64(len(data)) {
			break
		}
		select {
		case <-done:
			t.Fatal("pty closed before output processed")
		default:
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestPTYOutputAccumulatesConcurrently(t *testing.T) {
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

	const goroutines = 8
	const iterations = 100
	var wg sync.WaitGroup
	wg.Add(goroutines)
	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			for j := 0; j < iterations; j++ {
				_, _ = outW.Write([]byte("x"))
			}
		}()
	}
	wg.Wait()

	// 等待所有输出被处理。
	wantEvents := uint64(goroutines * iterations)
	for {
		events, bytes := r.PTYOutputSnapshot()
		if events == wantEvents {
			if bytes != wantEvents {
				t.Fatalf("pty output bytes=%d, want %d", bytes, wantEvents)
			}
			break
		}
		select {
		case <-done:
			t.Fatalf("pty closed early: events=%d want %d", events, wantEvents)
		default:
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestPTYReadBudgetCapsPendingBuffersBeforeRead(t *testing.T) {
	if got, want := ptyPendingByteLimit/ptyReadBufferSize, 256; got != want {
		t.Fatalf("PTY read credits=%d, want %d", got, want)
	}
	r := &Runtime{
		stopCh:         make(chan struct{}),
		ptyReadCredits: make(chan struct{}, 2),
	}
	r.ptyReadCredits <- struct{}{}
	r.ptyReadCredits <- struct{}{}
	if !r.acquirePTYReadCredit() || !r.acquirePTYReadCredit() {
		t.Fatal("initial PTY read credits were unavailable")
	}

	acquired := make(chan bool, 1)
	go func() { acquired <- r.acquirePTYReadCredit() }()
	select {
	case <-acquired:
		t.Fatal("a read acquired memory beyond its pending-output budget")
	case <-time.After(20 * time.Millisecond):
		// Expected: actor must consume an existing output before another Read starts.
	}

	r.releasePTYReadCredit()
	select {
	case ok := <-acquired:
		if !ok {
			t.Fatal("read credit acquisition stopped unexpectedly")
		}
	case <-time.After(time.Second):
		t.Fatal("released PTY output budget did not unblock the next read")
	}
}

func TestProjectionBusyWindowFromEnv(t *testing.T) {
	t.Setenv("WEBTERM_PROJECTION_ADAPTIVE_FLUSH", "")
	if got := projectionBusyWindowFromEnv(); got != projectionFlushWindow {
		t.Fatalf("adaptive disabled window=%s, want %s", got, projectionFlushWindow)
	}

	t.Setenv("WEBTERM_PROJECTION_ADAPTIVE_FLUSH", "1")
	t.Setenv("WEBTERM_PROJECTION_BUSY_FLUSH_MS", "")
	if got := projectionBusyWindowFromEnv(); got != defaultBusyProjectionWindow {
		t.Fatalf("default busy window=%s, want %s", got, defaultBusyProjectionWindow)
	}
	t.Setenv("WEBTERM_PROJECTION_BUSY_FLUSH_MS", "33")
	if got := projectionBusyWindowFromEnv(); got != 33*time.Millisecond {
		t.Fatalf("configured busy window=%s, want 33ms", got)
	}
	t.Setenv("WEBTERM_PROJECTION_BUSY_FLUSH_MS", "99")
	if got := projectionBusyWindowFromEnv(); got != defaultBusyProjectionWindow {
		t.Fatalf("out-of-range busy window=%s, want %s", got, defaultBusyProjectionWindow)
	}
}

func TestRuntimeLargeScrollbackTrimDoesNotSelfDeadlock(t *testing.T) {
	r := newRuntimeTestHarness(t, WithScrollbackLimits(1, 1<<30))

	trimWatermarks := make(chan uint64, 8)
	r.AttachClient(&ScreenClient{
		ID:   "screen-1",
		Send: func(terminalengine.ScreenFrame) {},
		SendHistoryTrim: func(_ uint64, firstAvailableSeq uint64) {
			trimWatermarks <- firstAvailableSeq
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
	case firstSeq := <-trimWatermarks:
		if firstSeq <= 1 {
			t.Fatalf("trim watermark=%d, want > 1", firstSeq)
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

func TestRuntimeWorkingDirectoryMetadataUpdatesProjection(t *testing.T) {
	r := newRuntimeTestHarness(t)
	r.SetWorkingDirectory("/Users/test/project with spaces")

	snapshot := r.ProjectedSnapshot()
	if got, want := snapshot.WorkingDir, "/Users/test/project with spaces"; got != want {
		t.Fatalf("projected cwd=%q, want %q", got, want)
	}
}

func TestRuntimeExpiredInputRevokesLeaseOnce(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	r := newRuntimeTestHarness(t)
	r.leaseManager = newLeaseManager(func() time.Time { return now }, time.Minute)

	revoked := make(chan LayoutLeaseEvent, 2)
	r.AttachClient(&ScreenClient{
		ID:              "screen-1",
		Send:            func(terminalengine.ScreenFrame) {},
		SendLayoutLease: func(event LayoutLeaseEvent) { revoked <- event },
	})
	leaseID, granted := r.AcquireLayout("screen-1", true)
	if !granted {
		t.Fatal("expected layout lease")
	}

	now = now.Add(time.Minute + time.Millisecond)
	r.WriteSemanticInput("screen-1", leaseID, terminalengine.SemanticInput{
		Kind: terminalengine.InputText, Data: "must-not-reach-pty",
	})
	select {
	case event := <-revoked:
		if event.Granted || event.RequestID != "" {
			t.Fatalf("unexpected revocation event: %+v", event)
		}
	case <-time.After(time.Second):
		t.Fatal("expired input did not notify the screen client")
	}

	// client 的失效代次已经被清空；后续旧输入不能放大成通知风暴。
	r.WriteSemanticInput("screen-1", leaseID, terminalengine.SemanticInput{
		Kind: terminalengine.InputText, Data: "still-invalid",
	})
	time.Sleep(20 * time.Millisecond)
	select {
	case event := <-revoked:
		t.Fatalf("duplicate revocation event: %+v", event)
	default:
	}
}

func TestReliableSemanticInputDeduplicatesAfterPTYWrite(t *testing.T) {
	pty := newReliableInputPTY()
	r := NewRuntime("reliable-input", pty, 2, 80)
	t.Cleanup(func() { _ = r.Close() })
	r.AttachClient(&ScreenClient{ID: "screen-1", Send: func(terminalengine.ScreenFrame) {}})
	leaseID, granted := r.AcquireLayout("screen-1", true)
	if !granted {
		t.Fatal("expected layout lease")
	}

	results := make(chan InputDeliveryResult, 2)
	write := func() {
		r.WriteReliableSemanticInput("screen-1", leaseID, "android-1", 7,
			terminalengine.SemanticInput{Kind: terminalengine.InputText, Data: "echo once\n"},
			func(result InputDeliveryResult) { results <- result })
	}
	write()
	first := <-results
	if first.Status != InputDeliveryWritten {
		t.Fatalf("first status=%v, want written", first.Status)
	}
	write()
	second := <-results
	if second.Status != InputDeliveryWritten {
		t.Fatalf("duplicate status=%v, want replayed written", second.Status)
	}
	if got := pty.writes.Load(); got != 1 {
		t.Fatalf("PTY writes=%d, want exactly 1", got)
	}
	if got := pty.data.String(); got != "echo once\n" {
		t.Fatalf("PTY data=%q", got)
	}
}

func TestChunkedReliablePasteKeepsSingleBracketedPasteTransaction(t *testing.T) {
	pty := newReliableInputPTY()
	r := NewRuntime("bracketed-paste", pty, 2, 80)
	t.Cleanup(func() { _ = r.Close() })
	r.AttachClient(&ScreenClient{ID: "screen-1", Send: func(terminalengine.ScreenFrame) {}})
	leaseID, granted := r.AcquireLayout("screen-1", true)
	if !granted {
		t.Fatal("expected layout lease")
	}

	// 由终端输出打开 bracketed-paste 模式，确保分段发生在编码之后，而不是
	// 把一次粘贴重编码为多个独立 paste transaction。
	r.postEvent(ptyOutputEvent{data: []byte("\x1b[?2004h")})
	waitRuntimeSnapshot(t, r)
	paste := strings.Repeat("粘贴内容", 40)
	resultCh := make(chan InputDeliveryResult, 1)
	r.WriteReliableSemanticInput("screen-1", leaseID, "android-1", 10,
		terminalengine.SemanticInput{Kind: terminalengine.InputPaste, Data: paste},
		func(result InputDeliveryResult) { resultCh <- result })
	select {
	case result := <-resultCh:
		if result.Status != InputDeliveryWritten {
			t.Fatalf("status=%v, want written", result.Status)
		}
	case <-time.After(time.Second):
		t.Fatal("chunked bracketed paste did not complete")
	}

	got := pty.data.String()
	want := "\x1b[200~" + paste + "\x1b[201~"
	if got != want {
		t.Fatalf("PTY paste transaction changed: got %q want %q", got, want)
	}
	if strings.Count(got, "\x1b[200~") != 1 || strings.Count(got, "\x1b[201~") != 1 {
		t.Fatal("chunking created more than one bracketed-paste transaction")
	}
}

func TestBlockedReliableInputDoesNotBlockRuntimeActor(t *testing.T) {
	pty := newBlockingInputPTY()
	r := NewRuntime("blocked-input", pty, 2, 80)
	t.Cleanup(func() {
		pty.unblock()
		_ = r.Close()
	})
	r.AttachClient(&ScreenClient{ID: "screen-1", Send: func(terminalengine.ScreenFrame) {}})
	leaseID, granted := r.AcquireLayout("screen-1", true)
	if !granted {
		t.Fatal("expected layout lease")
	}

	resultCh := make(chan InputDeliveryResult, 1)
	r.WriteReliableSemanticInput("screen-1", leaseID, "android-1", 8,
		terminalengine.SemanticInput{Kind: terminalengine.InputPaste, Data: "blocked paste"},
		func(result InputDeliveryResult) { resultCh <- result })
	select {
	case <-pty.started:
	case <-time.After(time.Second):
		t.Fatal("PTY write did not start")
	}

	// 旧实现会卡在 actor 内的 pty.Write，连投影快照都无法返回。
	waitRuntimeSnapshot(t, r)
	snapshotDone := make(chan struct{})
	go func() {
		_ = r.ProjectedSnapshot()
		close(snapshotDone)
	}()
	select {
	case <-snapshotDone:
	case <-time.After(time.Second):
		t.Fatal("runtime actor was blocked by PTY input")
	}

	pty.unblock()
	select {
	case result := <-resultCh:
		if result.Status != InputDeliveryWritten {
			t.Fatalf("status=%v, want written", result.Status)
		}
	case <-time.After(time.Second):
		t.Fatal("reliable input did not ACK after PTY resumed")
	}
}

func TestReliableSemanticInputDeduplicatesWhileWriteIsInflight(t *testing.T) {
	pty := newBlockingInputPTY()
	r := NewRuntime("inflight-dedupe", pty, 2, 80)
	t.Cleanup(func() {
		pty.unblock()
		_ = r.Close()
	})
	r.AttachClient(&ScreenClient{ID: "screen-1", Send: func(terminalengine.ScreenFrame) {}})
	leaseID, granted := r.AcquireLayout("screen-1", true)
	if !granted {
		t.Fatal("expected layout lease")
	}

	results := make(chan InputDeliveryResult, 2)
	write := func() {
		r.WriteReliableSemanticInput("screen-1", leaseID, "android-1", 9,
			terminalengine.SemanticInput{Kind: terminalengine.InputText, Data: "once"},
			func(result InputDeliveryResult) { results <- result })
	}
	write()
	select {
	case <-pty.started:
	case <-time.After(time.Second):
		t.Fatal("PTY write did not start")
	}
	write()
	waitRuntimeSnapshot(t, r)
	pty.unblock()

	for i := 0; i < 2; i++ {
		select {
		case result := <-results:
			if result.Status != InputDeliveryWritten {
				t.Fatalf("status=%v, want written", result.Status)
			}
		case <-time.After(time.Second):
			t.Fatal("in-flight duplicate did not receive the original result")
		}
	}
	if got := pty.writes.Load(); got != 1 {
		t.Fatalf("PTY writes=%d, want one in-flight write", got)
	}
}

func TestRuntimeDeniedClientCanAcquireAfterOldOwnerDetaches(t *testing.T) {
	r := newRuntimeTestHarness(t)
	r.AttachClient(&ScreenClient{ID: "screen-a", Send: func(terminalengine.ScreenFrame) {}})
	r.AttachClient(&ScreenClient{ID: "screen-b", Send: func(terminalengine.ScreenFrame) {}})
	if _, granted := r.AcquireLayout("screen-a", true); !granted {
		t.Fatal("screen-a must acquire the first lease")
	}
	if _, granted := r.AcquireLayout("screen-b", true); granted {
		t.Fatal("screen-b must not steal the live lease")
	}
	r.DetachClient("screen-a")
	if leaseID, granted := r.AcquireLayout("screen-b", true); !granted || leaseID == "" {
		t.Fatal("screen-b must acquire after screen-a detach")
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
