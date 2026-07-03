package session

import (
	"testing"
	"time"
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
				if state := string(terminal.StateBytes()); !stringContains(state, "WEBTERM_GO_OK") {
					t.Fatalf("screen state did not contain WEBTERM_GO_OK; state=%q", state)
				}
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

func TestTerminalSessionTitleUpdate(t *testing.T) {
	var titleUpdated bool
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: "/bin/sh",
		OnTitle: func() {
			titleUpdated = true
		},
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	// 模拟终端输出 ANSI 设置标题转义序列：\x1b]0;TestTitle\x07
	terminal.PushOutput([]byte("\x1b]0;TestTitle\x07"))

	if !titleUpdated {
		t.Errorf("expected OnTitle callback to be triggered")
	}

	info := terminal.Info()
	if info.TermTitle != "TestTitle" {
		t.Errorf("expected TermTitle to be %q, got %q", "TestTitle", info.TermTitle)
	}
}

func TestTerminalSessionCwdUpdatesFromOSC7(t *testing.T) {
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: "/bin/sh",
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	initialCwd := terminal.Info().CWD
	if initialCwd == "" {
		t.Fatalf("initial CWD should not be empty")
	}

	terminal.PushOutput([]byte("\x1b]7;file://localhost/tmp\x07"))

	info := terminal.Info()
	if info.CWD != "/tmp" {
		t.Errorf("expected CWD to update to /tmp after OSC 7, got %q", info.CWD)
	}
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

func TestTerminalSessionBroadcastsOnCwdChange(t *testing.T) {
	var broadcastCount int
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: "/bin/sh",
		OnTitle: func() {
			broadcastCount++
		},
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	terminal.PushOutput([]byte("\x1b]7;file://localhost/tmp\x07"))
	if broadcastCount < 1 {
		t.Errorf("expected OnTitle broadcast to fire on cwd change, got %d", broadcastCount)
	}

	before := broadcastCount
	terminal.PushOutput([]byte("\x1b]7;file://localhost/tmp\x07"))
	if broadcastCount != before {
		t.Errorf("expected no extra broadcast when cwd unchanged, got delta %d", broadcastCount-before)
	}
}
