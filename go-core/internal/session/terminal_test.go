package session

import (
	"testing"
	"time"
)

func TestTerminalSessionStartsShellAndCapturesOutput(t *testing.T) {
	command, args := testShellCommand()
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: command,
		Args:    args,
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	if err := terminal.WriteInput([]byte(testEchoInput("WEBTERM_GO_OK"))); err != nil {
		t.Fatalf("WriteInput returned error: %v", err)
	}

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		frame := terminal.runtime.ProjectedSnapshot()
		for _, line := range frame.Screen {
			lineText := ""
			for _, run := range line.Runs {
				for _, cell := range run.Cells {
					lineText += cell.Text
				}
			}
			if stringContains(lineText, "WEBTERM_GO_OK") {
				return
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("authoritative screen snapshot did not contain WEBTERM_GO_OK")
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
	command, args := testShellCommand()
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: command,
		Args:    args,
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

func TestTerminalSessionHookCwdUpdatesInfoAndScreenProjection(t *testing.T) {
	command, args := testShellCommand()
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: command,
		Args:    args,
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	terminal.ApplySessionUpdate("", "/tmp/project with spaces", "", "", 0)
	if got := terminal.Info().CWD; got != "/tmp/project with spaces" {
		t.Fatalf("session cwd=%q", got)
	}
	if got := terminal.runtime.ProjectedSnapshot().WorkingDir; got != "/tmp/project with spaces" {
		t.Fatalf("projected cwd=%q", got)
	}
}

func TestTerminalSessionNotificationOverride(t *testing.T) {
	command, args := testShellCommand()
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: command,
		Args:    args,
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	terminal.ApplyNotification("normal", "Done", "claude", time.Now().Unix())

	info := terminal.Info()
	if info.Notification == nil || info.Notification.Importance != "normal" || info.Notification.Message != "Done" || info.Notification.Source != "claude" {
		t.Fatalf("expected notification to be set, got %+v", info.Notification)
	}

	terminal.ApplyNotification("quiet", "Running", "claude", time.Now().Unix())

	info = terminal.Info()
	if info.Notification == nil || info.Notification.Importance != "quiet" || info.Notification.Message != "Running" {
		t.Errorf("expected notification to be overridden, got %+v", info.Notification)
	}
}

func TestTerminalNaturalExitClosesProcessResources(t *testing.T) {
	command, args := testExitCommand(0)
	terminal, err := NewTerminalSession(TerminalOptions{ID: "natural-exit", CWD: ".", Command: command, Args: args})
	if err != nil {
		t.Fatalf("NewTerminalSession: %v", err)
	}
	defer terminal.Close()
	deadline := time.Now().Add(3 * time.Second)
	for terminal.Info().Status != StatusClosed && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if terminal.Info().Status != StatusClosed {
		t.Fatal("natural exit did not close terminal")
	}
	if err := terminal.process.Resize(80, 24); err == nil {
		t.Fatal("process resources remain open after natural exit")
	}
}
