package agenthooks

import (
	"strings"
	"testing"
)

func TestShellHookReportsCurrentDirectoryAtPrompt(t *testing.T) {
	if !strings.Contains(shellHookTemplate, `internal session-update --cwd "$PWD" --last-command`) {
		t.Fatal("shell hook must report $PWD with prompt metadata")
	}
	if strings.Contains(shellHookTemplate, `if [ -n "$last" ]; then`) {
		t.Fatal("shell hook must report $PWD even when shell history is empty")
	}
}
