package agenthooks

import (
	"path/filepath"
	"testing"
)

func TestRuntimeBaseDirIsStableAndSocketSpecific(t *testing.T) {
	socket := filepath.Join(t.TempDir(), "agent.sock")
	if got, want := RuntimeBaseDir(socket), RuntimeBaseDir(socket); got != want {
		t.Fatalf("runtime directory is not stable: %q != %q", got, want)
	}
	other := RuntimeBaseDir(filepath.Join(t.TempDir(), "agent.sock"))
	if RuntimeBaseDir(socket) == other {
		t.Fatal("different socket paths must not share shell integration")
	}
}

func TestInstallShellHookAtUsesIsolatedDirectories(t *testing.T) {
	root := t.TempDir()
	hook, bashRC, err := InstallShellHookAt(root, "/tmp/webterm")
	if err != nil {
		t.Fatalf("InstallShellHookAt: %v", err)
	}
	if filepath.Dir(hook) != hookBinDirAt(root) {
		t.Fatalf("hook installed outside runtime bin dir: %q", hook)
	}
	if filepath.Dir(bashRC) != ShellInitDirAt(root) {
		t.Fatalf("bash rc installed outside runtime shell dir: %q", bashRC)
	}
}
