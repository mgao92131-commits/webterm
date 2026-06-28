package logs

import "testing"

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
