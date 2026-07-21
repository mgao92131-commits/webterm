package logs

import "testing"

// TestLoggerStampsRunID NewWithRunID 写入的每条 Entry 携带 runID；New 不带（空串）。
func TestLoggerStampsRunID(t *testing.T) {
	withRun := NewWithRunID(10, "run-x")
	if entry := withRun.Add("info", "test", "m"); entry.RunID != "run-x" {
		t.Errorf("entry.RunID = %q, want run-x", entry.RunID)
	}
	plain := New(10)
	if entry := plain.Add("info", "test", "m"); entry.RunID != "" {
		t.Errorf("New() entry.RunID = %q, want empty", entry.RunID)
	}
}

func TestLoggerKeepsRecentEntriesAndPublishes(t *testing.T) {
	logger := New(2)
	events, cancel := logger.Subscribe(1)
	defer cancel()

	first := logger.Add("info", "test", "one")
	second := logger.Add("warn", "test", "two")
	third := logger.Add("error", "test", "three")

	if first.Seq != 1 || second.Seq != 2 || third.Seq != 3 {
		t.Fatalf("unexpected seqs: %d %d %d", first.Seq, second.Seq, third.Seq)
	}
	recent := logger.Recent(10)
	if len(recent) != 2 || recent[0].Message != "two" || recent[1].Message != "three" {
		t.Fatalf("recent entries = %#v", recent)
	}
	select {
	case event := <-events:
		if event.Message != "one" {
			t.Fatalf("published message = %q, want one", event.Message)
		}
	default:
		t.Fatalf("expected published event")
	}
}
