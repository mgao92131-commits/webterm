package terminalsession

import (
	"bytes"
	"io"
	"strconv"
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

func TestPendingClipboardRequestsAreBoundedAndExpire(t *testing.T) {
	r := &Runtime{pendingClipboard: make(map[string]pendingClipboardRequest)}
	now := time.Unix(1_000, 0)
	for i := 0; i < maxPendingClipboardRequests+10; i++ {
		r.trackPendingClipboard(
			strconv.Itoa(i), 'c', now.Add(time.Duration(i)*time.Millisecond))
	}
	if got := len(r.pendingClipboard); got != maxPendingClipboardRequests {
		t.Fatalf("pending clipboard requests=%d, want %d", got, maxPendingClipboardRequests)
	}
	if _, ok := r.pendingClipboard["0"]; ok {
		t.Fatal("oldest clipboard request was not evicted at the hard bound")
	}

	r.prunePendingClipboard(now.Add(pendingClipboardTTL + time.Minute))
	if got := len(r.pendingClipboard); got != 0 {
		t.Fatalf("expired clipboard requests=%d, want 0", got)
	}
}

func TestClipboardReadResponseCompletesAndLastClientDisconnectClearsPending(t *testing.T) {
	var terminal bytes.Buffer
	effects := make(chan terminalengine.Effect, 2)
	r := &Runtime{
		terminalIO:       &terminal,
		clients:          make(map[string]*ScreenClient),
		leaseManager:     NewLeaseManager(),
		pendingClipboard: make(map[string]pendingClipboardRequest),
	}
	client := &ScreenClient{
		ID:   "clipboard",
		Send: func(terminalengine.ScreenFrame) {},
		SendEffect: func(_ string, _ uint64, effect terminalengine.Effect) {
			effects <- effect
		},
	}
	lease := r.leaseManager.Acquire("clipboard", true)
	if !lease.Granted {
		t.Fatal("clipboard client did not acquire layout lease")
	}
	client.LayoutLeaseID = lease.LeaseID
	r.clients[client.ID] = client

	firstRequestID := "clipboard-read-1"
	r.trackPendingClipboard(firstRequestID, 'c', time.Now())
	r.handleEffect(terminalengine.Effect{
		Kind: terminalengine.EffectClipboardRead, RequestID: firstRequestID, Clipboard: "c",
	})
	var effect terminalengine.Effect
	select {
	case effect = <-effects:
	case <-time.After(2 * time.Second):
		t.Fatal("clipboard read effect was not delivered")
	}
	if effect.Kind != terminalengine.EffectClipboardRead || effect.RequestID == "" {
		t.Fatalf("clipboard effect=%+v", effect)
	}

	r.handleClipboardResponse(clipboardResponseEvent{
		clientID: "clipboard", requestID: effect.RequestID, allowed: true, data: []byte("answer"),
	})
	if _, ok := r.pendingClipboard[effect.RequestID]; ok {
		t.Fatal("completed clipboard request remained pending")
	}
	if got := terminal.String(); !strings.Contains(got, "YW5zd2Vy") {
		t.Fatalf("clipboard response did not reach PTY: %q", got)
	}

	secondRequestID := "clipboard-read-2"
	r.trackPendingClipboard(secondRequestID, 'c', time.Now())
	r.handleEffect(terminalengine.Effect{
		Kind: terminalengine.EffectClipboardRead, RequestID: secondRequestID, Clipboard: "c",
	})
	select {
	case effect = <-effects:
	case <-time.After(2 * time.Second):
		t.Fatal("second clipboard read effect was not delivered")
	}
	r.handleClientDetach("clipboard")
	if got := len(r.pendingClipboard); got != 0 {
		t.Fatalf("pending clipboard requests after disconnect=%d, want 0", got)
	}
}

type closeTrackingTerminalIO struct {
	closed atomic.Int64
}

func (p *closeTrackingTerminalIO) Read([]byte) (int, error)       { return 0, io.EOF }
func (p *closeTrackingTerminalIO) Write(data []byte) (int, error) { return len(data), nil }
func (p *closeTrackingTerminalIO) Close() error {
	p.closed.Add(1)
	return nil
}

func TestRuntimeCloseDoesNotCloseTerminalIO(t *testing.T) {
	terminalIO := &closeTrackingTerminalIO{}
	r := NewRuntime("runtime-close-owner", terminalIO, 2, 80)
	if err := r.Close(); err != nil {
		t.Fatalf("Runtime.Close: %v", err)
	}
	if got := terminalIO.closed.Load(); got != 0 {
		t.Fatalf("Runtime.Close closed TerminalIO %d times, want 0", got)
	}
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

	r.AttachClient(&ScreenClient{
		ID:   "screen-1",
		Send: func(terminalengine.ScreenFrame) {},
	})
	if _, granted := r.AcquireLayout("screen-1", true); !granted {
		t.Fatal("screen client was not attached")
	}

	// 大量滚屏必须在一次 engine.Write 中完成裁剪，且不能阻塞 actor。
	r.postEvent(ptyOutputEvent{data: []byte(strings.Repeat("x\r\n", 2000))})
	waitRuntimeSnapshot(t, r)

	if firstSeq := r.ProjectedSnapshot().History.FirstAvailableHistorySeq; firstSeq <= 1 {
		t.Fatalf("first available history seq=%d, want > 1", firstSeq)
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
