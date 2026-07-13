package agentnotify

import (
	"context"
	"errors"
	"strings"
	"testing"
)

type fakeSender struct {
	deviceID string
	msg      map[string]any
	err      error
	calls    int
}

func (f *fakeSender) SendControlToDevice(_ context.Context, deviceID string, msg map[string]any) error {
	f.calls++
	f.deviceID = deviceID
	f.msg = msg
	return f.err
}

func TestNotifyBuildsMessageAndTracksPending(t *testing.T) {
	fs := &fakeSender{}
	d := New(fs)
	eventID, err := d.Notify(context.Background(), "dev1", "sess1", "", "T", "hello", "claude")
	if err != nil {
		t.Fatalf("Notify: %v", err)
	}
	if !strings.HasPrefix(eventID, "ev_") || len(eventID) != len("ev_")+32 {
		t.Fatalf("unexpected event_id %q", eventID)
	}
	if fs.calls != 1 {
		t.Fatalf("expected 1 send, got %d", fs.calls)
	}
	if fs.deviceID != "dev1" {
		t.Fatalf("deviceID=%q", fs.deviceID)
	}
	if fs.msg["type"] != TypeAgentNotification {
		t.Fatalf("type=%v", fs.msg["type"])
	}
	if fs.msg["session_id"] != "sess1" || fs.msg["importance"] != ImportanceQuiet || fs.msg["message"] != "hello" || fs.msg["source"] != "claude" {
		t.Fatalf("msg=%v", fs.msg)
	}
	if fs.msg["event_id"] != eventID {
		t.Fatalf("event_id mismatch")
	}
	if d.PendingCount() != 1 {
		t.Fatalf("pending=%d", d.PendingCount())
	}
}

func TestNotifySendErrorKeepsPendingForReconnect(t *testing.T) {
	fs := &fakeSender{err: errors.New("boom")}
	d := New(fs)
	if _, err := d.Notify(context.Background(), "dev1", "s", ImportanceAlert, "t", "m", "src"); err == nil {
		t.Fatal("expected error")
	}
	if d.PendingCount() != 1 {
		t.Fatalf("pending should survive send error, got %d", d.PendingCount())
	}
	fs.err = nil
	d.ReplayPending(context.Background())
	if fs.calls != 2 {
		t.Fatalf("expected replay send, got %d calls", fs.calls)
	}
}

func TestHandleAckRemovesPending(t *testing.T) {
	fs := &fakeSender{}
	d := New(fs)
	eventID, _ := d.Notify(context.Background(), "dev1", "s", ImportanceQuiet, "t", "m", "src")
	d.HandleAck("dev1", eventID)
	if d.PendingCount() != 0 {
		t.Fatalf("pending after ack=%d", d.PendingCount())
	}
}

func TestNotifyWithoutSenderFails(t *testing.T) {
	d := New(nil)
	if _, err := d.Notify(context.Background(), "dev1", "s", ImportanceQuiet, "t", "m", "src"); err == nil {
		t.Fatal("expected error when sender is nil")
	}
}
