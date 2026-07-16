package session

import (
	"testing"
	"time"

	"webterm/go-core/internal/protocol"
)

func TestDownloadTaskRegistryOwnsConsumeAndExpiry(t *testing.T) {
	now := time.Unix(1000, 0)
	registry := NewDownloadTaskRegistry()
	registry.now = func() time.Time { return now }
	task := &DownloadTask{
		ID:        "download-1",
		StateChan: make(chan protocol.CLIResponse, 1),
		ExpiresAt: now.Add(time.Minute),
	}
	registry.Add("session-1", task)
	if got, ok := registry.Consume(task.ID); !ok || got.SessionID != "session-1" {
		t.Fatal("first consume failed")
	}
	if _, ok := registry.Consume(task.ID); ok {
		t.Fatal("second consume succeeded")
	}
	if _, ok := registry.Peek(task.ID); !ok {
		t.Fatal("peek should remain available until completion")
	}
	now = now.Add(2 * time.Minute)
	if _, ok := registry.Peek(task.ID); ok {
		t.Fatal("expired task remained visible")
	}
	select {
	case _, open := <-task.StateChan:
		if open {
			t.Fatal("expired task channel remained open")
		}
	default:
		t.Fatal("expired task channel was not closed")
	}
}
