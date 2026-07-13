package session

import (
	"testing"
	"time"

	"webterm/go-core/internal/protocol"
)

func TestTerminalSessionStartsShellAndCapturesOutput(t *testing.T) {
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: "/bin/sh",
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	if err := terminal.WriteInput([]byte("printf WEBTERM_GO_OK\\n\r")); err != nil {
		t.Fatalf("WriteInput returned error: %v", err)
	}

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		for _, frame := range terminal.ReplayAfter(0) {
			if stringContains(string(frame.Bytes), "WEBTERM_GO_OK") {
				return
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("terminal output did not contain WEBTERM_GO_OK; frames=%#v", terminal.ReplayAfter(0))
}

func stringContains(value string, needle string) bool {
	for i := 0; i+len(needle) <= len(value); i++ {
		if value[i:i+len(needle)] == needle {
			return true
		}
	}
	return false
}

func TestTerminalSessionCwdFallsBackWhenNoOSC7(t *testing.T) {
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: "/bin/sh",
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	info := terminal.Info()
	if info.CWD == "" {
		t.Errorf("CWD must fall back to initial cwd when no OSC 7 received, got empty")
	}
}

func TestTerminalSessionNotificationOverride(t *testing.T) {
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: "/bin/sh",
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	terminal.ApplyHookEvent(protocol.HookEvent{
		Type:      "notify",
		SessionID: "s1",
		Level:     "idle",
		Message:   "Done",
		Source:    "claude",
		Timestamp: time.Now().Unix(),
	})

	info := terminal.Info()
	if info.Notification == nil || info.Notification.Level != "idle" || info.Notification.Message != "Done" || info.Notification.Source != "claude" {
		t.Fatalf("expected notification to be set, got %+v", info.Notification)
	}

	terminal.ApplyHookEvent(protocol.HookEvent{
		Type:      "notify",
		SessionID: "s1",
		Level:     "running",
		Message:   "Running",
		Source:    "claude",
		Timestamp: time.Now().Unix(),
	})

	info = terminal.Info()
	if info.Notification == nil || info.Notification.Level != "running" || info.Notification.Message != "Running" {
		t.Errorf("expected notification to be overridden, got %+v", info.Notification)
	}
}
