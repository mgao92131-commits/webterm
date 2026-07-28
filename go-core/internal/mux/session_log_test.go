package mux

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/diagnostics"
	"webterm/go-core/internal/logs"
	termsession "webterm/go-core/internal/session"
	"webterm/go-core/internal/transporterr"
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

type timeoutNetError struct{}

func (timeoutNetError) Error() string   { return "i/o timeout" }
func (timeoutNetError) Timeout() bool   { return true }
func (timeoutNetError) Temporary() bool { return true }

func TestErrorKindClassifiesErrors(t *testing.T) {
	cases := []struct {
		name      string
		err       error
		wantKind  string
		wantLevel string
		wantCode  int
	}{
		{"nil", nil, "none", "info", 0},
		{"context cancelled", context.Canceled, "context_cancelled", "info", 0},
		{"deadline", context.DeadlineExceeded, "timeout", "warn", 0},
		{"net timeout", timeoutNetError{}, "timeout", "warn", 0},
		{"relay stream closed", transporterr.ErrRelayStreamClosed, "stream_closed", "info", 0},
		{"wrapped relay stream closed", fmt.Errorf("read: %w", transporterr.ErrRelayStreamClosed), "stream_closed", "info", 0},
		{"closed", net.ErrClosed, "closed", "info", 0},
		{"eof", io.EOF, "closed", "info", 0},
		{"unexpected eof", io.ErrUnexpectedEOF, "closed", "info", 0},
		{"ws normal", websocket.CloseError{Code: websocket.StatusNormalClosure}, "websocket_closed", "info", 1000},
		{"ws going away", websocket.CloseError{Code: websocket.StatusGoingAway}, "websocket_closed", "info", 1001},
		{"ws no status", websocket.CloseError{Code: websocket.StatusNoStatusRcvd}, "websocket_closed", "warn", 1005},
		{"ws protocol", websocket.CloseError{Code: websocket.StatusProtocolError}, "websocket_closed", "warn", 1002},
		{"ws unsupported", websocket.CloseError{Code: websocket.StatusUnsupportedData}, "websocket_closed", "warn", 1003},
		{"ws policy", websocket.CloseError{Code: websocket.StatusPolicyViolation}, "websocket_closed", "warn", 1008},
		{"ws internal", websocket.CloseError{Code: websocket.StatusInternalError}, "websocket_closed", "warn", 1011},
		{"ws unknown", websocket.CloseError{Code: 4999}, "websocket_closed", "error", 4999},
		{"generic io", errors.New("boom"), "io_error", "warn", 0},
	}
	for _, tc := range cases {
		got := classifyError(tc.err)
		if got.Kind != tc.wantKind {
			t.Errorf("%s: Kind = %q, want %q", tc.name, got.Kind, tc.wantKind)
		}
		if got.Level != tc.wantLevel {
			t.Errorf("%s: Level = %q, want %q", tc.name, got.Level, tc.wantLevel)
		}
		if got.CloseCode != tc.wantCode {
			t.Errorf("%s: CloseCode = %d, want %d", tc.name, got.CloseCode, tc.wantCode)
		}
		if errorKind(tc.err) != tc.wantKind {
			t.Errorf("%s: errorKind = %q, want %q", tc.name, errorKind(tc.err), tc.wantKind)
		}
	}
}

func TestMuxReadFailureEmitsCloseCodeForWebSocket(t *testing.T) {
	logger := logs.New(logs.DefaultCapacity)
	logger.SetRateLimiter(nil)
	sock := &fakeSocket{reads: []fakeRead{
		{err: websocket.CloseError{Code: websocket.StatusProtocolError, Reason: "bad"}},
	}}
	sess := Serve(sock, &ServeOpts{Logger: logger})

	if err := sess.readLoop(context.Background()); err == nil {
		t.Fatal("readLoop = nil, want websocket close error")
	}
	entries := logger.Recent(0)
	if len(entries) != 1 {
		t.Fatalf("entries = %d, want 1", len(entries))
	}
	entry := entries[0]
	if entry.Event != "mux_read_failed" {
		t.Fatalf("event = %q, want mux_read_failed", entry.Event)
	}
	if entry.Level != "warn" {
		t.Errorf("level = %q, want warn", entry.Level)
	}
	if entry.Fields["reason"] != "websocket_closed" {
		t.Errorf("reason = %v, want websocket_closed", entry.Fields["reason"])
	}
	if entry.Fields["closeCode"] != 1002 {
		t.Errorf("closeCode = %v, want 1002", entry.Fields["closeCode"])
	}
}

// TestMuxWriterFailuresAreRateLimited 同一 writer 错误触发 1000 次：
// 内存 Ring 应远少于 1000 条，且窗口结束后产出一条 event_suppressed 汇总。
func TestMuxWriterFailuresAreRateLimited(t *testing.T) {
	logger := logs.New(logs.DefaultCapacity)

	// 注入假时钟，避免测试等待真实 5 秒窗口。
	now := time.Now()
	current := now
	clockMu := sync.Mutex{}
	logger.SetRateLimiter(logs.NewRateLimiter(logs.DefaultRateLimitWindow, func() time.Time {
		clockMu.Lock()
		defer clockMu.Unlock()
		return current
	}))

	sess := Serve(&fakeSocket{}, &ServeOpts{Logger: logger})
	writeErr := errors.New("boom-secret-detail")
	for i := 0; i < 1000; i++ {
		sess.logWriteError(writeErr)
	}

	// 推进窗口后再次触发：应先吐出 suppressed 汇总，再放行本条。
	clockMu.Lock()
	current = current.Add(2 * logs.DefaultRateLimitWindow)
	clockMu.Unlock()
	sess.logWriteError(writeErr)

	entries := logger.Recent(0)
	if len(entries) >= 100 {
		t.Fatalf("ring entries = %d, want far fewer than 1000", len(entries))
	}
	var writerEvents, suppressedSummaries int
	var suppressedCount float64
	for _, entry := range entries {
		switch entry.Event {
		case "mux_writer_failed":
			writerEvents++
			if entry.Fields["reason"] != "io_error" {
				t.Errorf("mux_writer_failed reason = %v, want io_error", entry.Fields["reason"])
			}
		case "event_suppressed":
			suppressedSummaries++
			if entry.Fields["originalEvent"] != "mux_writer_failed" {
				t.Errorf("suppressed originalEvent = %v", entry.Fields["originalEvent"])
			}
			switch v := entry.Fields["suppressedCount"].(type) {
			case int:
				suppressedCount = float64(v)
			case float64:
				suppressedCount = v
			}
		}
	}
	if writerEvents != 2 {
		t.Errorf("mux_writer_failed entries = %d, want 2（窗口首条 + 跨窗口一条）", writerEvents)
	}
	if suppressedSummaries != 1 || suppressedCount != 999 {
		t.Errorf("suppressed summaries = %d count=%v, want 1/999", suppressedSummaries, suppressedCount)
	}
	for _, entry := range entries {
		if entry.Message != "" && strings.Contains(entry.Message, "boom-secret-detail") {
			t.Fatalf("raw error text leaked into ring: %+v", entry)
		}
	}
}

// TestMuxReadFailureEmitsClassifiedEvent readLoop 读到 EOF 时应产出
// mux_stream_closed 事件（info），reason 为 closed 分类而非错误原文。
func TestMuxReadFailureEmitsClassifiedEvent(t *testing.T) {
	logger := logs.New(logs.DefaultCapacity)
	logger.SetRateLimiter(nil)
	sock := &fakeSocket{reads: []fakeRead{{err: io.EOF}}}
	sess := Serve(sock, &ServeOpts{Logger: logger})

	if err := sess.readLoop(context.Background()); !errors.Is(err, io.EOF) {
		t.Fatalf("readLoop = %v, want EOF", err)
	}
	entries := logger.Recent(0)
	if len(entries) != 1 {
		t.Fatalf("entries = %d, want 1", len(entries))
	}
	entry := entries[0]
	if entry.Event != "mux_stream_closed" {
		t.Fatalf("event = %q, want mux_stream_closed", entry.Event)
	}
	if entry.Level != "info" {
		t.Errorf("level = %q, want info", entry.Level)
	}
	if entry.Fields["reason"] != "closed" {
		t.Errorf("reason = %v, want closed", entry.Fields["reason"])
	}
	if entry.Message != "" {
		t.Errorf("free-text message must be empty, got %q", entry.Message)
	}
}

// TestDiagnosticsConnectionSetsContextAndMergesIntoEvents diagnostics.connection
// 传入 Android 侧已计算的 12 位 hex hash 后，后续 mux 事件应原样带上 connectionHash。
func TestDiagnosticsConnectionSetsContextAndMergesIntoEvents(t *testing.T) {
	logger := logs.New(logs.DefaultCapacity)
	logger.SetRateLimiter(nil)

	connectionHash := "a1b2c3d4e5f6"
	recoveryHash := "fedcba987654"
	ctrl := []byte(`{"type":"diagnostics.connection","connection_hash":"` + connectionHash +
		`","recovery_hash":"` + recoveryHash + `","transport_generation":12}`)

	var onControlCalls int
	sock := &fakeSocket{reads: []fakeRead{
		{msgType: termsession.MessageText, data: ctrl},
		{err: io.EOF},
	}}
	sess := Serve(sock, &ServeOpts{
		Logger: logger,
		OnControl: func(ctx context.Context, source *Session, msg map[string]any) {
			onControlCalls++
		},
	})

	if err := sess.readLoop(context.Background()); !errors.Is(err, io.EOF) {
		t.Fatalf("readLoop = %v, want EOF", err)
	}
	if onControlCalls != 0 {
		t.Fatalf("onControl calls = %d, want 0 (diagnostics.connection must not forward)", onControlCalls)
	}
	if sess.diag.ConnectionHash != connectionHash {
		t.Errorf("ConnectionHash = %q, want %q", sess.diag.ConnectionHash, connectionHash)
	}
	if sess.diag.RecoveryHash != recoveryHash {
		t.Errorf("RecoveryHash = %q, want %q", sess.diag.RecoveryHash, recoveryHash)
	}
	if sess.diag.TransportGeneration != 12 {
		t.Errorf("TransportGeneration = %d, want 12", sess.diag.TransportGeneration)
	}

	entries := logger.Recent(0)
	if len(entries) != 1 {
		t.Fatalf("entries = %d, want 1", len(entries))
	}
	entry := entries[0]
	if entry.Event != "mux_stream_closed" {
		t.Fatalf("event = %q, want mux_stream_closed", entry.Event)
	}
	if entry.Fields["connectionHash"] != connectionHash {
		t.Errorf("connectionHash = %v, want %q", entry.Fields["connectionHash"], connectionHash)
	}
	if entry.Fields["recoveryHash"] != recoveryHash {
		t.Errorf("recoveryHash = %v, want %q", entry.Fields["recoveryHash"], recoveryHash)
	}
	if entry.Fields["transportGeneration"] != uint64(12) {
		switch v := entry.Fields["transportGeneration"].(type) {
		case uint64:
			if v != 12 {
				t.Errorf("transportGeneration = %v, want 12", v)
			}
		case int:
			if v != 12 {
				t.Errorf("transportGeneration = %v, want 12", v)
			}
		case float64:
			if v != 12 {
				t.Errorf("transportGeneration = %v, want 12", v)
			}
		default:
			t.Errorf("transportGeneration = %v (%T), want 12", v, v)
		}
	}
	if entry.Fields["connection_id"] != nil || entry.Fields["recovery_id"] != nil {
		t.Fatalf("raw UUIDs must not appear in fields: %+v", entry.Fields)
	}
}

func TestDiagnosticsConnectionRejectsInvalidHash(t *testing.T) {
	before := diagnostics.Default.MuxDiagnosticsContextRejectedCount.Load()
	sess := Serve(&fakeSocket{}, &ServeOpts{})
	sess.applyDiagnosticsConnection(map[string]any{
		"connection_hash": "NOT_VALID!!!!",
		"recovery_hash":   "abc",
	})
	if sess.diag.ConnectionHash != "" || sess.diag.RecoveryHash != "" {
		t.Fatalf("invalid hashes must be ignored, got %+v", sess.diag)
	}
	if got := diagnostics.Default.MuxDiagnosticsContextRejectedCount.Load(); got <= before {
		t.Fatalf("rejected count = %d, want > %d", got, before)
	}
}

func TestValidAndroidDiagnosticHash(t *testing.T) {
	if !validAndroidDiagnosticHash("a1b2c3d4e5f6") {
		t.Fatal("expected valid hash")
	}
	if validAndroidDiagnosticHash("A1B2C3D4E5F6") {
		t.Fatal("uppercase must be rejected")
	}
	if validAndroidDiagnosticHash("short") {
		t.Fatal("short must be rejected")
	}
}

// TestAndroidCrossVectorHashFormat 与 Android DiagnosticIdHasher.hash("testsalt", ...)
// 对齐：12 位小写 hex。进程 salt 不可固定，故用固定 salt 向量校验格式；
// Agent 对合法 connection_hash 原样回显（见 TestDiagnosticsConnectionSetsContextAndMergesIntoEvents）。
func TestAndroidCrossVectorHashFormat(t *testing.T) {
	// SHA-256("testsalt:connection-id-vector-1")[:12] == 687dccd95cb4
	const androidFixedVector = "687dccd95cb4"
	if !validAndroidDiagnosticHash(androidFixedVector) {
		t.Fatalf("android fixed vector %q must pass validAndroidDiagnosticHash", androidFixedVector)
	}
	if len(androidFixedVector) != 12 {
		t.Fatalf("len = %d, want 12", len(androidFixedVector))
	}
}

func TestDiagnosticsConnectionClearsRecoveryHash(t *testing.T) {
	logger := logs.New(logs.DefaultCapacity)
	logger.SetRateLimiter(nil)
	sess := Serve(&fakeSocket{}, &ServeOpts{Logger: logger})

	connectionHash := "a1b2c3d4e5f6"
	recoveryHash := "fedcba987654"
	sess.applyDiagnosticsConnection(map[string]any{
		"connection_hash":      connectionHash,
		"recovery_hash":        recoveryHash,
		"transport_generation": float64(3),
	})
	if sess.diag.RecoveryHash != recoveryHash {
		t.Fatalf("RecoveryHash = %q, want %q", sess.diag.RecoveryHash, recoveryHash)
	}

	sess.event("info", "mux_test_with_recovery", map[string]any{"probe": true})
	entries := logger.Recent(0)
	if len(entries) == 0 {
		t.Fatal("expected at least one event")
	}
	last := entries[len(entries)-1]
	if last.Fields["recoveryHash"] != recoveryHash {
		t.Fatalf("recoveryHash field = %v, want %q", last.Fields["recoveryHash"], recoveryHash)
	}
	if last.Fields["connectionHash"] != connectionHash {
		t.Fatalf("connectionHash field = %v, want %q", last.Fields["connectionHash"], connectionHash)
	}

	// 字段存在且为空 → 清除 RecoveryHash；connectionHash 保持。
	sess.applyDiagnosticsConnection(map[string]any{
		"connection_hash": connectionHash,
		"recovery_hash":   "",
	})
	if sess.diag.RecoveryHash != "" {
		t.Fatalf("RecoveryHash after clear = %q, want empty", sess.diag.RecoveryHash)
	}
	if sess.diag.ConnectionHash != connectionHash {
		t.Fatalf("ConnectionHash after clear = %q, want %q", sess.diag.ConnectionHash, connectionHash)
	}

	sess.event("info", "mux_test_after_clear", map[string]any{"probe": true})
	entries = logger.Recent(0)
	last = entries[len(entries)-1]
	if _, ok := last.Fields["recoveryHash"]; ok {
		t.Fatalf("recoveryHash must be absent after clear, fields=%v", last.Fields)
	}
	if last.Fields["connectionHash"] != connectionHash {
		t.Fatalf("connectionHash after clear = %v, want %q", last.Fields["connectionHash"], connectionHash)
	}

	// 字段不存在 → 保持（仍为空）。
	sess.applyDiagnosticsConnection(map[string]any{
		"connection_hash": connectionHash,
	})
	if sess.diag.RecoveryHash != "" {
		t.Fatalf("absent recovery_hash must keep empty, got %q", sess.diag.RecoveryHash)
	}
}

