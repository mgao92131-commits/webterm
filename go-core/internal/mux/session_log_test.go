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
		name string
		err  error
		want string
	}{
		{"nil", nil, "none"},
		{"context cancelled", context.Canceled, "context_cancelled"},
		{"deadline", context.DeadlineExceeded, "timeout"},
		{"net timeout", timeoutNetError{}, "timeout"},
		{"relay stream closed", transporterr.ErrRelayStreamClosed, "stream_closed"},
		{"wrapped relay stream closed", fmt.Errorf("read: %w", transporterr.ErrRelayStreamClosed), "stream_closed"},
		{"closed", net.ErrClosed, "closed"},
		{"eof", io.EOF, "closed"},
		{"unexpected eof", io.ErrUnexpectedEOF, "closed"},
		{"websocket closed", websocket.CloseError{Code: websocket.StatusNormalClosure}, "websocket_closed"},
		{"generic io", errors.New("boom"), "io_error"},
	}
	for _, tc := range cases {
		if got := errorKind(tc.err); got != tc.want {
			t.Errorf("%s: errorKind = %q, want %q", tc.name, got, tc.want)
		}
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
// 写入 HashID 上下文后，后续 mux 事件应带上 connectionHash 等字段。
func TestDiagnosticsConnectionSetsContextAndMergesIntoEvents(t *testing.T) {
	logger := logs.New(logs.DefaultCapacity)
	logger.SetRateLimiter(nil)

	connectionID := "11111111-2222-3333-4444-555555555555"
	recoveryID := "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
	ctrl := []byte(`{"type":"diagnostics.connection","connection_id":"` + connectionID +
		`","recovery_id":"` + recoveryID + `","transport_generation":12}`)

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
	if sess.diag.ConnectionHash != logs.HashID(connectionID) {
		t.Errorf("ConnectionHash = %q, want %q", sess.diag.ConnectionHash, logs.HashID(connectionID))
	}
	if sess.diag.RecoveryHash != logs.HashID(recoveryID) {
		t.Errorf("RecoveryHash = %q, want %q", sess.diag.RecoveryHash, logs.HashID(recoveryID))
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
	if entry.Fields["connectionHash"] != logs.HashID(connectionID) {
		t.Errorf("connectionHash = %v, want %q", entry.Fields["connectionHash"], logs.HashID(connectionID))
	}
	if entry.Fields["recoveryHash"] != logs.HashID(recoveryID) {
		t.Errorf("recoveryHash = %v, want %q", entry.Fields["recoveryHash"], logs.HashID(recoveryID))
	}
	if entry.Fields["transportGeneration"] != uint64(12) {
		// json/log fields may be uint64; accept common numeric forms
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
