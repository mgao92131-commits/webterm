package app

import (
	"path/filepath"
	"testing"
)

func TestSessionEnvOmitsHookPathsWhenInstallationFails(t *testing.T) {
	runtimeDir := filepath.Join(t.TempDir(), "runtime")
	// 失败路径只保留基础运行环境和退避状态目录，不携带可能指向旧文件的 Hook 路径。
	got := buildSessionEnv("unix:/tmp/webterm.sock", runtimeDir, false)
	if _, ok := got["WEBTERM_SHELL_INIT_DIR"]; ok {
		t.Fatal("shell init path must be omitted when hook installation fails")
	}
	if _, ok := got["WEBTERM_POWERSHELL_HOOK"]; ok {
		t.Fatal("PowerShell hook path must be omitted when hook installation fails")
	}
}
