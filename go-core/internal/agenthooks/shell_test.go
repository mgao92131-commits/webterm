package agenthooks

import (
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
		t.Fatal("PowerShell hook must launch the CLI without waiting for it")
	}
	if strings.Contains(powerShellHookTemplate, "WaitForExit") {
		t.Fatal("PowerShell hook must not wait for the CLI to exit")
	}
	if !strings.Contains(powerShellHookTemplate, "Test-WebTermHookBackoff") {
		t.Fatal("PowerShell hook must consult backoff state before launching the CLI")
	}
	// 不再同步执行 CLI（旧的 & $script:WebTermBin ... --shell-state 形式）。
	if strings.Contains(powerShellHookTemplate, "& $script:WebTermBin internal session-update --shell-state") {
		t.Fatal("PowerShell hook must not synchronously invoke session-update")
	}
}
