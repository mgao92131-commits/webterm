package session

import (
	"testing"

	"webterm/go-core/internal/infrastructure/pty"
)

func TestProcessSessionIndexResolvesParentAndTTY(t *testing.T) {
	parents := map[int]int{300: 200, 200: 100, 100: 1}
	ttys := map[int]string{400: "/dev/pts/7"}
	index := newProcessSessionIndex(
		func(pid int) int { return parents[pid] },
		func(pid int) string { return ttys[pid] },
	)
	index.Register("session-parent", pty.Identity{PID: 100, Backend: "unix-pty", TerminalKey: "/dev/pts/1"})
	index.Register("session-tty", pty.Identity{PID: 500, Backend: "unix-pty", TerminalKey: "/dev/pts/7"})

	if got, err := index.Resolve(300); err != nil || got != "session-parent" {
		t.Fatalf("Resolve(parent chain) = %q, %v", got, err)
	}
	if got, err := index.Resolve(400); err != nil || got != "session-tty" {
		t.Fatalf("Resolve(tty) = %q, %v", got, err)
	}

	index.Unregister("session-parent", pty.Identity{PID: 100, Backend: "unix-pty", TerminalKey: "/dev/pts/1"})
	if _, err := index.Resolve(300); err == nil {
		t.Fatal("cached child PID survived session unregister")
	}
}
