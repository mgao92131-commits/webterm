package logs

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestEventCarriesStructuredFields(t *testing.T) {
	logger := New(10)
	entry := logger.Event("warn", "relay", "relay_connect_failed", map[string]any{
		"attempt": 2,
		"reason":  "timeout",
	})
	if entry.Seq == 0 {
		t.Fatal("first event in window must be written")
	}
	if entry.Event != "relay_connect_failed" {
		t.Fatalf("event=%q", entry.Event)
	}
	if entry.Fields["attempt"] != 2 || entry.Fields["reason"] != "timeout" {
		t.Fatalf("fields=%v", entry.Fields)
	}
	if entry.Message != "" {
		t.Fatalf("message=%q, want empty for structured event", entry.Message)
	}
}

func TestEventRateLimitSuppressesDuplicatesAndEmitsSummary(t *testing.T) {
	now := time.Now()
	clock := func() time.Time { return now }
	logger := New(100)
	logger.SetRateLimiter(NewRateLimiter(5*time.Second, clock))

	fields := map[string]any{"sessionId": "s1", "reason": "overflow"}
	if entry := logger.Event("warn", "screen", "screen_mailbox_overflow", fields); entry.Seq == 0 {
		t.Fatal("first occurrence must be written")
	}
	for i := 0; i < 3; i++ {
		if entry := logger.Event("warn", "screen", "screen_mailbox_overflow", fields); entry.Seq != 0 {
			t.Fatalf("duplicate %d must be suppressed, got seq=%d", i, entry.Seq)
		}
	}

	now = now.Add(6 * time.Second)
	entry := logger.Event("warn", "screen", "screen_mailbox_overflow", fields)
	if entry.Seq == 0 {
		t.Fatal("event after window must be written")
	}
	recent := logger.Recent(10)
	var summary *Entry
	for i := range recent {
		if recent[i].Event == "event_suppressed" {
			summary = &recent[i]
		}
	}
	if summary == nil {
		t.Fatal("expected event_suppressed summary after window")
	}
	if summary.Fields["originalEvent"] != "screen_mailbox_overflow" || summary.Fields["suppressedCount"] != 3 {
		t.Fatalf("summary fields=%v", summary.Fields)
	}
}

func TestRateLimitKeySeparatesSessionsAndReasons(t *testing.T) {
	now := time.Now()
	logger := New(100)
	logger.SetRateLimiter(NewRateLimiter(5*time.Second, func() time.Time { return now }))

	if e := logger.Event("warn", "screen", "screen_mailbox_overflow", map[string]any{"sessionId": "s1"}); e.Seq == 0 {
		t.Fatal("s1 first must pass")
	}
	if e := logger.Event("warn", "screen", "screen_mailbox_overflow", map[string]any{"sessionId": "s2"}); e.Seq == 0 {
		t.Fatal("s2 has an independent window")
	}
	if e := logger.Event("warn", "screen", "screen_mailbox_overflow", map[string]any{"sessionId": "s1", "reason": "other"}); e.Seq == 0 {
		t.Fatal("different reason has an independent window")
	}
	if e := logger.Event("warn", "screen", "screen_mailbox_overflow", map[string]any{"sessionId": "s1"}); e.Seq != 0 {
		t.Fatal("s1 duplicate without reason must be suppressed")
	}
}

func TestNoRateLimitEventsAlwaysPass(t *testing.T) {
	now := time.Now()
	logger := New(100)
	logger.SetRateLimiter(NewRateLimiter(time.Hour, func() time.Time { return now }))
	for _, event := range []string{"agent_start", "agent_stop", "terminal_process_exited",
		"screen_encode_failed", "screen_writer_failed", "input_write_uncertain"} {
		for i := 0; i < 3; i++ {
			if e := logger.Event("info", "core", event, nil); e.Seq == 0 {
				t.Fatalf("%s occurrence %d must never be rate limited", event, i)
			}
		}
	}
}

func TestSubscriberDropIsCounted(t *testing.T) {
	logger := New(10)
	_, cancel := logger.Subscribe(1) // buffer=1，不读取
	defer cancel()
	for i := 0; i < 5; i++ {
		logger.Message("info", "test", fmt.Sprintf("m%d", i))
	}
	if dropped := logger.SubscriberDropped(); dropped != 4 {
		t.Fatalf("dropped=%d, want 4", dropped)
	}
}

func TestFileSinkWritesValidJsonlAndRotates(t *testing.T) {
	dir := t.TempDir()
	sink, err := NewFileSink(dir, 256, 2)
	if err != nil {
		t.Fatalf("new sink: %v", err)
	}
	defer sink.Close()

	logger := New(1000)
	logger.SetRateLimiter(nil)
	logger.SetSink(sink)
	// 每条约 100 字节，写 30 条必然触发多次轮转。
	for i := 0; i < 30; i++ {
		logger.Event("info", "test", "some_event", map[string]any{
			"index": i, "padding": strings.Repeat("x", 40),
		})
	}
	sink.Close()

	// 最多保留当前 + 2 个备份。
	matches, err := filepath.Glob(filepath.Join(dir, "agent.jsonl*"))
	if err != nil || len(matches) == 0 || len(matches) > 3 {
		t.Fatalf("log files=%v err=%v, want 1..3 files", matches, err)
	}
	// 每个文件都不超过上限（最后一个除外——写入前检查，单条不超限时成立）。
	for _, path := range matches {
		info, err := os.Stat(path)
		if err != nil {
			t.Fatalf("stat %s: %v", path, err)
		}
		if info.Size() > 256+128 { // 上限 + 单条最大行宽
			t.Fatalf("%s size=%d exceeds rotation budget", path, info.Size())
		}
	}
	// 每行都是可独立解析的 JSON。
	for _, path := range matches {
		file, err := os.Open(path)
		if err != nil {
			t.Fatalf("open %s: %v", path, err)
		}
		scanner := bufio.NewScanner(file)
		line := 0
		for scanner.Scan() {
			line++
			var entry Entry
			if err := json.Unmarshal(scanner.Bytes(), &entry); err != nil {
				t.Fatalf("%s:%d invalid json: %v", path, line, err)
			}
			if entry.Seq == 0 || entry.Event == "" {
				t.Fatalf("%s:%d malformed entry: %+v", path, line, entry)
			}
		}
		file.Close()
	}
}

func TestFileSinkConcurrentWritesProduceIntactLines(t *testing.T) {
	dir := t.TempDir()
	sink, err := NewFileSink(dir, 1<<20, 1)
	if err != nil {
		t.Fatalf("new sink: %v", err)
	}
	logger := New(10000)
	logger.SetRateLimiter(nil)
	logger.SetSink(sink)

	var wg sync.WaitGroup
	for g := 0; g < 8; g++ {
		wg.Add(1)
		go func(g int) {
			defer wg.Done()
			for i := 0; i < 50; i++ {
				logger.Event("info", "test", "concurrent_event", map[string]any{"g": g, "i": i})
			}
		}(g)
	}
	wg.Wait()
	sink.Close()

	total := 0
	matches, _ := filepath.Glob(filepath.Join(dir, "agent.jsonl*"))
	for _, path := range matches {
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read %s: %v", path, err)
		}
		for _, line := range strings.Split(strings.TrimRight(string(data), "\n"), "\n") {
			if line == "" {
				continue
			}
			total++
			var entry Entry
			if err := json.Unmarshal([]byte(line), &entry); err != nil {
				t.Fatalf("corrupted line in %s: %v", path, err)
			}
		}
	}
	if total != 400 {
		t.Fatalf("lines=%d, want 400（并发写入丢行或坏行）", total)
	}
}

func TestSafeIDAndHashID(t *testing.T) {
	if SafeID("s6") != "s6" {
		t.Fatal("short structured id passes through")
	}
	if SafeID(strings.Repeat("a", 100)) == strings.Repeat("a", 100) {
		t.Fatal("long value must degrade to hash")
	}
	if SafeID("has space") == "has space" {
		t.Fatal("value with whitespace must degrade to hash")
	}
	if HashID("a") == HashID("b") || len(HashID("a")) != 8 {
		t.Fatal("hash id must be 8 hex chars and value-dependent")
	}
	if SafeDuration(1500*time.Millisecond) != 1500 {
		t.Fatal("duration recorded as milliseconds")
	}
}
