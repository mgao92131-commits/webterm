package pty

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestIsPowerShell(t *testing.T) {
	cases := map[string]bool{
		"pwsh.exe":                               true,
		"powershell.exe":                         true,
		"pwsh":                                   true,
		"powershell":                             true,
		"PwSh.EXE":                               true,
		"C:/Program Files/PowerShell/7/pwsh.exe": true,
		"C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe": true,
		"cmd.exe":   false,
		"/bin/sh":   false,
		"/bin/bash": false,
		"":          false,
	}
	for command, want := range cases {
		if got := isPowerShell(command); got != want {
			t.Errorf("isPowerShell(%q) = %v, want %v", command, got, want)
		}
	}
}

func TestApplyPowerShellHookInjectsHook(t *testing.T) {
	args, warn := applyPowerShellHook("pwsh.exe", nil, "/tmp/hook.ps1")
	want := []string{"-NoLogo", "-NoExit", "-Command", ". '/tmp/hook.ps1'"}
	if warn != "" {
		t.Fatalf("unexpected warn: %s", warn)
	}
	if !reflect.DeepEqual(args, want) {
		t.Fatalf("expected args %v, got %v", want, args)
	}
}

func TestApplyPowerShellHookKeepsUserFlags(t *testing.T) {
	args, warn := applyPowerShellHook("pwsh.exe", []string{"-nologo", "-NoExit", "-WorkingDirectory", "D:/work"}, "/tmp/hook.ps1")
	want := []string{"-nologo", "-NoExit", "-WorkingDirectory", "D:/work", "-Command", ". '/tmp/hook.ps1'"}
	if warn != "" {
		t.Fatalf("unexpected warn: %s", warn)
	}
	if !reflect.DeepEqual(args, want) {
		t.Fatalf("expected args %v, got %v", want, args)
	}
}

func TestApplyPowerShellHookAddsMissingFlags(t *testing.T) {
	// 用户只带了 -NoLogo：不重复添加，但 -NoExit 仍需补上。
	args, warn := applyPowerShellHook("powershell.exe", []string{"-NoLogo"}, "/tmp/hook.ps1")
	want := []string{"-NoExit", "-NoLogo", "-Command", ". '/tmp/hook.ps1'"}
	if warn != "" {
		t.Fatalf("unexpected warn: %s", warn)
	}
	if !reflect.DeepEqual(args, want) {
		t.Fatalf("expected args %v, got %v", want, args)
	}
}

func TestApplyPowerShellHookSkipsExplicitCommandOrFile(t *testing.T) {
	userArgs := []string{"-NoLogo", "-File", "D:/scripts/init.ps1"}
	args, warn := applyPowerShellHook("pwsh.exe", userArgs, "/tmp/hook.ps1")
	if !reflect.DeepEqual(args, userArgs) {
		t.Fatalf("expected args unchanged %v, got %v", userArgs, args)
	}
	if warn == "" {
		t.Fatal("expected warn for -File, got empty")
	}

	userArgs = []string{"-Command", "Get-Date"}
	args, warn = applyPowerShellHook("powershell.exe", userArgs, "/tmp/hook.ps1")
	if !reflect.DeepEqual(args, userArgs) {
		t.Fatalf("expected args unchanged %v, got %v", userArgs, args)
	}
	if warn == "" {
		t.Fatal("expected warn for -Command, got empty")
	}
}

func TestApplyPowerShellHookEscapesSingleQuotes(t *testing.T) {
	args, warn := applyPowerShellHook("pwsh.exe", nil, "/tmp/it's a hook.ps1")
	if warn != "" {
		t.Fatalf("unexpected warn: %s", warn)
	}
	want := ". '/tmp/it''s a hook.ps1'"
	if len(args) != 4 || args[3] != want {
		t.Fatalf("expected command %q, got %v", want, args)
	}
}

func TestApplyPowerShellHookWithoutHook(t *testing.T) {
	args, warn := applyPowerShellHook("pwsh.exe", nil, "")
	if warn != "" {
		t.Fatalf("unexpected warn: %s", warn)
	}
	if !reflect.DeepEqual(args, []string{"-NoLogo"}) {
		t.Fatalf("expected [-NoLogo], got %v", args)
	}
}

func writeHookFile(t *testing.T) string {
	t.Helper()
	hook := filepath.Join(t.TempDir(), "webterm-hook.ps1")
	if err := os.WriteFile(hook, []byte("# session hook"), 0o600); err != nil {
		t.Fatalf("write hook: %v", err)
	}
	return hook
}

func TestResolveCommandExplicitPowerShellInjectsHook(t *testing.T) {
	hook := writeHookFile(t)

	cmd, args, _, warn, err := resolveCommand(Options{
		Command: "pwsh.exe",
		Env:     map[string]string{"WEBTERM_POWERSHELL_HOOK": hook},
	})
	if err != nil {
		t.Fatalf("resolveCommand: %v", err)
	}
	if cmd != "pwsh.exe" {
		t.Fatalf("expected pwsh.exe, got %s", cmd)
	}
	if warn != "" {
		t.Fatalf("unexpected warn: %s", warn)
	}
	want := []string{"-NoLogo", "-NoExit", "-Command", ". '" + hook + "'"}
	if !reflect.DeepEqual(args, want) {
		t.Fatalf("expected args %v, got %v", want, args)
	}
}

func TestResolveCommandExplicitPowerShellWarnsOnFile(t *testing.T) {
	hook := writeHookFile(t)
	userArgs := []string{"-File", "D:/scripts/init.ps1"}

	_, args, _, warn, err := resolveCommand(Options{
		Command: "powershell.exe",
		Args:    userArgs,
		Env:     map[string]string{"WEBTERM_POWERSHELL_HOOK": hook},
	})
	if err != nil {
		t.Fatalf("resolveCommand: %v", err)
	}
	if !reflect.DeepEqual(args, userArgs) {
		t.Fatalf("expected args unchanged %v, got %v", userArgs, args)
	}
	if warn == "" || !strings.Contains(warn, "Session Hook") {
		t.Fatalf("expected hook skip warning, got %q", warn)
	}
}

func TestResolveCommandExplicitPowerShellMissingHookKeepsArgs(t *testing.T) {
	userArgs := []string{"-WorkingDirectory", "D:/work"}

	_, args, _, warn, err := resolveCommand(Options{
		Command: "pwsh.exe",
		Args:    userArgs,
		Env:     map[string]string{"WEBTERM_POWERSHELL_HOOK": filepath.Join(t.TempDir(), "missing.ps1")},
	})
	if err != nil {
		t.Fatalf("resolveCommand: %v", err)
	}
	if warn != "" {
		t.Fatalf("unexpected warn: %s", warn)
	}
	if !reflect.DeepEqual(args, userArgs) {
		t.Fatalf("expected args unchanged %v, got %v", userArgs, args)
	}
}

func TestResolveCommandExplicitNonPowerShellUnchanged(t *testing.T) {
	hook := writeHookFile(t)
	userArgs := []string{"-c", "echo hi"}

	cmd, args, _, warn, err := resolveCommand(Options{
		Command: "/bin/sh",
		Args:    userArgs,
		Env:     map[string]string{"WEBTERM_POWERSHELL_HOOK": hook},
	})
	if err != nil {
		t.Fatalf("resolveCommand: %v", err)
	}
	if cmd != "/bin/sh" {
		t.Fatalf("expected /bin/sh, got %s", cmd)
	}
	if warn != "" {
		t.Fatalf("unexpected warn: %s", warn)
	}
	if !reflect.DeepEqual(args, userArgs) {
		t.Fatalf("expected args unchanged %v, got %v", userArgs, args)
	}
}
