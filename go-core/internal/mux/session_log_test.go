package mux

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/logs"
	termsession "webterm/go-core/internal/session"
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
		{"deadline", context.DeadlineExceeded, "timeout"},
		{"net timeout", timeoutNetError{}, "timeout"},
		{"closed", net.ErrClosed, "closed"},
		{"eof", io.EOF, "closed"},
		{"unexpected eof", io.ErrUnexpectedEOF, "closed"},
		{"other", errors.New("boom"), "unknown"},
	}
	for _, tc := range cases {
		if got := errorKind(tc.err); got != tc.want {
			t.Errorf("%s: errorKind = %q, want %q", tc.name, got, tc.want)
		}
	}
}

// readLogLines 读取 sink 目录下 agent.jsonl 的所有行。
func readLogLines(t *testing.T, dir string) []string {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, "agent.jsonl"))
	if err != nil {
		t.Fatalf("read log: %v", err)
	}
	text := strings.TrimSpace(string(data))
	if text == "" {
		return nil
	}
	return strings.Split(text, "\n")
}

// TestMuxWriterFailuresAreRateLimited 同一 writer 错误触发 1000 次：
// 磁盘日志应远少于 1000 条，且窗口结束后产出一条 event_suppressed 汇总。
func TestMuxWriterFailuresAreRateLimited(t *testing.T) {
	dir := t.TempDir()
	sink, err := logs.NewFileSink(dir, 0, -1)
	if err != nil {
		t.Fatalf("new sink: %v", err)
	}
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
	logger.SetSink(sink)

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

	if err := sink.Close(); err != nil {
		t.Fatalf("close sink: %v", err)
	}

	lines := readLogLines(t, dir)
	if len(lines) >= 100 {
		t.Fatalf("disk log lines = %d, want far fewer than 1000", len(lines))
	}
	var writerEvents, suppressedSummaries int
	var suppressedCount float64
	for _, line := range lines {
		var entry logs.Entry
		if err := json.Unmarshal([]byte(line), &entry); err != nil {
			t.Fatalf("invalid log line: %v", err)
		}
		switch entry.Event {
		case "mux_writer_failed":
			writerEvents++
			if entry.Fields["reason"] != "unknown" {
				t.Errorf("mux_writer_failed reason = %v, want unknown", entry.Fields["reason"])
			}
		case "event_suppressed":
			suppressedSummaries++
			if entry.Fields["originalEvent"] != "mux_writer_failed" {
				t.Errorf("suppressed originalEvent = %v", entry.Fields["originalEvent"])
			}
			suppressedCount, _ = entry.Fields["suppressedCount"].(float64)
		}
	}
	if writerEvents != 2 {
		t.Errorf("mux_writer_failed entries = %d, want 2（窗口首条 + 跨窗口一条）", writerEvents)
	}
	if suppressedSummaries != 1 || suppressedCount != 999 {
		t.Errorf("suppressed summaries = %d count=%v, want 1/999", suppressedSummaries, suppressedCount)
	}
	// 原始错误文本不得进入磁盘日志。
	for _, line := range lines {
		if strings.Contains(line, "boom-secret-detail") {
			t.Fatalf("raw error text leaked into disk log: %s", line)
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
