package logs

import (
	"fmt"
	"strings"
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

func TestLoggerRingByteBudgetDropsOldest(t *testing.T) {
	logger := NewWithRunID(10000, "run-1")
	logger.SetRateLimiter(nil)
	logger.maxBytes = 512
	for i := 0; i < 50; i++ {
		logger.Event("info", "test", "evt", map[string]any{"pad": strings.Repeat("x", 40)})
	}
	recent := logger.Recent(0)
	if len(recent) >= 50 {
		t.Fatalf("byte budget should drop oldest entries, got %d", len(recent))
	}
	if recent[len(recent)-1].Fields["pad"] == nil {
		t.Fatal("newest entry should be kept")
	}
}

func TestLoggerRingCountBudgetDropsOldest(t *testing.T) {
	logger := NewWithRunID(5, "run-1")
	logger.SetRateLimiter(nil)
	for i := 0; i < 10; i++ {
		logger.Event("info", "test", "evt", map[string]any{"i": i})
	}
	recent := logger.Recent(0)
	if len(recent) != 5 {
		t.Fatalf("count=%d, want 5", len(recent))
	}
	if recent[0].Fields["i"] != 5 && recent[0].Fields["i"] != float64(5) {
		t.Fatalf("oldest kept i=%v, want 5", recent[0].Fields["i"])
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
