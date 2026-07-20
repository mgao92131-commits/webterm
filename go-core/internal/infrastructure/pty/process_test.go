//go:build !windows

package pty

import (
	"bytes"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type recordingBackend struct {
	resizeErr  error
	resizeCall int
	closeCall  int
}

func (b *recordingBackend) Read([]byte) (int, error)       { return 0, io.EOF }
func (b *recordingBackend) Write(data []byte) (int, error) { return len(data), nil }
func (b *recordingBackend) Resize(int, int) error {
	b.resizeCall++
	return b.resizeErr
}
func (b *recordingBackend) Wait() (int, error) { return 0, nil }
func (b *recordingBackend) Identity() Identity { return Identity{PID: 42, Backend: "test"} }
func (b *recordingBackend) Close() error {
	b.closeCall++
	return nil
}

func TestApplyShellInitBash(t *testing.T) {
	tmp := t.TempDir()
	if err := os.WriteFile(filepath.Join(tmp, bashRcName), []byte("source hook"), 0o600); err != nil {
		t.Fatalf("write bashrc: %v", err)
	}

	cmd, args, env, err := applyShellInit("/bin/bash", tmp)
	if err != nil {
		t.Fatalf("applyShellInit: %v", err)
	}
	if cmd != "/bin/bash" {
		t.Fatalf("expected /bin/bash, got %s", cmd)
	}
	want := []string{"--rcfile", filepath.Join(tmp, bashRcName), "-i"}
	if len(args) != len(want) || args[0] != want[0] || args[2] != want[2] {
		t.Fatalf("expected args %v, got %v", want, args)
	}
	if len(env) != 0 {
		t.Fatalf("expected no extra env, got %v", env)
	}
}

func TestApplyShellInitZsh(t *testing.T) {
	tmp := t.TempDir()
	initDir := filepath.Join(tmp, "shell-init")
	zshDir := filepath.Join(initDir, "zsh")
	if err := os.MkdirAll(zshDir, 0o700); err != nil {
		t.Fatalf("mkdir zsh dir: %v", err)
	}

	cmd, args, env, err := applyShellInit("/bin/zsh", initDir)
	if err != nil {
		t.Fatalf("applyShellInit: %v", err)
	}
	if cmd != "/bin/zsh" {
		t.Fatalf("expected /bin/zsh, got %s", cmd)
	}
	if len(args) != 1 || args[0] != "-i" {
		t.Fatalf("expected args [-i], got %v", args)
	}
	if env == nil || env["ZDOTDIR"] != zshDir {
		t.Fatalf("expected ZDOTDIR=%s, got %v", zshDir, env)
	}
}

func TestApplyShellInitNoInitDir(t *testing.T) {
	cmd, args, env, err := applyShellInit("/bin/bash", "")
	if err != nil {
		t.Fatalf("applyShellInit: %v", err)
	}
	if cmd != "/bin/bash" {
		t.Fatalf("expected /bin/bash, got %s", cmd)
	}
	if len(args) != 0 {
		t.Fatalf("expected no args, got %v", args)
	}
	if len(env) != 0 {
		t.Fatalf("expected no extra env, got %v", env)
	}
}

func TestResolveCommandReadsShellInitDirFromOpts(t *testing.T) {
	tmp := t.TempDir()
	if err := os.WriteFile(filepath.Join(tmp, bashRcName), []byte("source hook"), 0o600); err != nil {
		t.Fatalf("write bashrc: %v", err)
	}
	t.Setenv("SHELL", "/bin/bash")

	cmd, args, _, _, err := resolveCommand(Options{
		Env: map[string]string{shellInitDirEnv: tmp},
	})
	if err != nil {
		t.Fatalf("resolveCommand: %v", err)
	}
	if cmd != "/bin/bash" {
		t.Fatalf("expected /bin/bash, got %s", cmd)
	}
	if len(args) == 0 || args[0] != "--rcfile" {
		t.Fatalf("expected bash --rcfile, got %v", args)
	}
}

func TestResolveCommandNoShellInitDirWithoutEnv(t *testing.T) {
	t.Setenv("SHELL", "/bin/bash")

	cmd, args, _, _, err := resolveCommand(Options{})
	if err != nil {
		t.Fatalf("resolveCommand: %v", err)
	}
	if cmd != "/bin/bash" {
		t.Fatalf("expected /bin/bash, got %s", cmd)
	}
	if len(args) != 0 {
		t.Fatalf("expected no args, got %v", args)
	}
}

func TestBuildEnvDoesNotInheritHostColorDisables(t *testing.T) {
	env := buildEnv([]string{
		"NO_COLOR=1",
		"CLICOLOR=0",
		"CLICOLOR_FORCE=0",
		"FORCE_COLOR=0",
		"TERM=dumb",
	}, map[string]string{"EXTRA": "value"})
	for _, key := range []string{"NO_COLOR", "CLICOLOR", "CLICOLOR_FORCE", "FORCE_COLOR"} {
		for _, item := range env {
			if strings.HasPrefix(item, key+"=") {
				t.Fatalf("%s must not reach the terminal child: %q", key, item)
			}
		}
	}
	want := map[string]string{
		"TERM":      "xterm-256color",
		"COLORTERM": "truecolor",
		"EXTRA":     "value",
	}
	for key, value := range want {
		found := false
		for _, item := range env {
			if item == key+"="+value {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("missing %s=%s in %q", key, value, env)
		}
	}
}

func TestStartAppliesShellInit(t *testing.T) {
	if _, err := os.Stat("/bin/bash"); err != nil {
		t.Skip("bash not available")
	}
	t.Setenv("SHELL", "/bin/bash")
	tmp := t.TempDir()
	rc := filepath.Join(tmp, bashRcName)
	if err := os.WriteFile(rc, []byte("echo __WEBTERM_BASH_RC_LOADED__\n"), 0o600); err != nil {
		t.Fatalf("write bashrc: %v", err)
	}

	proc, err := Start(Options{
		Env: map[string]string{shellInitDirEnv: tmp},
	})
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer proc.Close()

	buf := make([]byte, 4096)
	var out []byte
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		n, err := proc.Read(buf)
		if n > 0 {
			out = append(out, buf[:n]...)
			if bytes.Contains(out, []byte("__WEBTERM_BASH_RC_LOADED__")) {
				return
			}
		}
		if err != nil {
			break
		}
	}
	t.Fatalf("bash did not load webterm rc; output: %q", string(out))
}

func TestProcessCloseIsOwnedAndIdempotent(t *testing.T) {
	backend := &recordingBackend{}
	process := &Process{backend: backend, cols: 80, rows: 24}
	if err := process.Close(); err != nil {
		t.Fatalf("first Close: %v", err)
	}
	if err := process.Close(); err != nil {
		t.Fatalf("second Close: %v", err)
	}
	if backend.closeCall != 1 {
		t.Fatalf("backend Close calls = %d, want 1", backend.closeCall)
	}
	if err := process.Resize(100, 30); err == nil {
		t.Fatal("Resize after Close succeeded")
	}
}

func TestProcessResizeCommitsGeometryOnlyAfterBackendSuccess(t *testing.T) {
	backend := &recordingBackend{resizeErr: errors.New("resize failed")}
	process := &Process{backend: backend, cols: 80, rows: 24}
	if err := process.Resize(100, 30); err == nil {
		t.Fatal("Resize unexpectedly succeeded")
	}
	if got := process.Cols(); got != 80 {
		t.Fatalf("Cols after failed Resize = %d, want 80", got)
	}
	if got := process.Rows(); got != 24 {
		t.Fatalf("Rows after failed Resize = %d, want 24", got)
	}
}

func TestProcessWaitReturnsRealExitCode(t *testing.T) {
	proc, err := Start(Options{Command: "/bin/sh", Args: []string{"-c", "exit 23"}})
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer proc.Close()
	code, err := proc.Wait()
	if err == nil {
		t.Fatal("Wait error = nil, want exit error")
	}
	if code != 23 {
		t.Fatalf("Wait exit code = %d, want 23", code)
	}
}
