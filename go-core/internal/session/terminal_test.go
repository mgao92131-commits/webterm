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
