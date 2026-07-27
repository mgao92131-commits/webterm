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
// mux_read_failed 事件，reason 为 closed 分类而非错误原文。
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
	if entry.Event != "mux_read_failed" {
		t.Fatalf("event = %q, want mux_read_failed", entry.Event)
	}
	if entry.Fields["reason"] != "closed" {
		t.Errorf("reason = %v, want closed", entry.Fields["reason"])
	}
	if entry.Message != "" {
		t.Errorf("free-text message must be empty, got %q", entry.Message)
	}
}
