package agenthooks

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestShellHookReportsCurrentDirectoryAtPrompt(t *testing.T) {
	if !strings.Contains(shellHookTemplate, `WEBTERM_HOOK_CWD="$PWD"`) {
		t.Fatal("shell hook must report $PWD with prompt metadata")
	}
	if !strings.Contains(shellHookTemplate, "internal session-update --hook-mode") {
		t.Fatal("shell hook must invoke session-update in hook mode")
	}
	// 无论历史是否为空都要上报当前目录（不能以最近命令为空为由跳过）。
	if strings.Contains(shellHookTemplate, `if [ -n "$last" ]; then`) {
		t.Fatal("shell hook must report $PWD even when shell history is empty")
	}
}

func TestShellHookIsNonBlockingWithBackoff(t *testing.T) {
	// 后台 fire-and-forget 且丢弃输出：prompt 不会被上报阻塞或污染。
	if !strings.Contains(shellHookTemplate, ">/dev/null 2>&1 &") {
		t.Fatal("shell hook must run session-update in the background and discard output")
	}
	// 退避：启动子进程前先读状态文件，未到重试时间直接返回。
	if !strings.Contains(shellHookTemplate, "WEBTERM_HOOK_STATE_DIR") {
		t.Fatal("shell hook must consult backoff state before launching the CLI")
	}
}

func TestPowerShellHookReportsSessionUpdate(t *testing.T) {
	if !strings.Contains(powerShellHookTemplate, "internal session-update --hook-mode") || !strings.Contains(powerShellHookTemplate, "function global:prompt") {
		t.Fatal("PowerShell hook must update session metadata from prompt in hook mode")
	}
}

func TestPowerShellHookIsNonBlockingWithBackoff(t *testing.T) {
	if !strings.Contains(powerShellHookTemplate, "CreateNoWindow = $true") {
		t.Fatal("PowerShell hook must not show a window for the background CLI")
	}
	if !strings.Contains(powerShellHookTemplate, "[System.Diagnostics.Process]::Start") {
		t.Fatal("PowerShell hook must launch the CLI via Process.Start")
	}
	// 必须有界等待：ConPTY 会话内 spawn 后立即返回会让子进程初始化静默失败
	// （runner 上稳定复现），WaitForExit(2000) 保证正常 ~25ms 返回且 prompt 不被无限阻塞。
	if !strings.Contains(powerShellHookTemplate, "WaitForExit(2000)") {
		t.Fatal("PowerShell hook must wait for the CLI with a bounded 2s timeout")
	}
	if !strings.Contains(powerShellHookTemplate, "Test-WebTermHookBackoff") {
		t.Fatal("PowerShell hook must consult backoff state before launching the CLI")
	}
	// 不再同步执行 CLI（旧的 & $script:WebTermBin ... --shell-state 形式）。
	if strings.Contains(powerShellHookTemplate, "& $script:WebTermBin internal session-update --shell-state") {
		t.Fatal("PowerShell hook must not synchronously invoke session-update")
	}
}

func TestGeneratedPowerShellHookHasUTF8BOM(t *testing.T) {
	root := t.TempDir()
	if _, _, err := InstallShellHookAt(root, `C:\webterm.exe`); err != nil {
		t.Fatal(err)
	}

	path := filepath.Join(root, "bin", "webterm-shell-hook.ps1")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(data, utf8BOM) {
		t.Fatalf("PowerShell hook does not start with UTF-8 BOM: %x", data[:min(len(data), len(utf8BOM))])
	}
}

func TestPowerShellHookTemplateIsASCII(t *testing.T) {
	for index, value := range []byte(powerShellHookTemplate) {
		if value > 0x7F {
			t.Fatalf("PowerShell hook contains non-ASCII byte at %d: 0x%X", index, value)
		}
	}
}

func TestInstallReplacesMalformedPowerShellHook(t *testing.T) {
	root := t.TempDir()
	binDir := filepath.Join(root, "bin")
	if err := os.MkdirAll(binDir, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(binDir, "webterm-shell-hook.ps1")
	if err := os.WriteFile(path, []byte("function broken {\n}\n}\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	if _, _, err := InstallShellHookAt(root, `C:\webterm.exe`); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(data, utf8BOM) {
		t.Fatal("malformed hook was not replaced with a BOM-prefixed hook")
	}
	if bytes.Contains(data, []byte("function broken")) {
		t.Fatal("old malformed content remains")
	}
}

func TestInstallShellHookIsRepeatableWithoutTemporaryFiles(t *testing.T) {
	root := t.TempDir()
	for i := 0; i < 2; i++ {
		if _, _, err := InstallShellHookAt(root, `C:\webterm.exe`); err != nil {
			t.Fatalf("install %d: %v", i+1, err)
		}
	}

	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if strings.Contains(info.Name(), ".tmp-") {
			t.Errorf("temporary file remains after install: %s", path)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}
