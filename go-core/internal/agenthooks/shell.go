package agenthooks

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const shellHookTemplate = `#!/usr/bin/env bash
# WebTerm shell hook: 上报最近输入的 shell 命令和状态。
# 被 bash / zsh 启动时 source。上报为后台 fire-and-forget，绝不阻塞 prompt。

[ "${WEBTERM_INTEGRATION:-}" = "1" ] || return 0
[ -n "${WEBTERM_SESSION_ID:-}" ] || return 0
[ -n "${WEBTERM_IPC_ENDPOINT:-}" ] || return 0

WEBTERM="{{.WebtermBin}}"
[ -x "$WEBTERM" ] || WEBTERM="webterm"

# 每个提示符上报当前目录，避免 cd 后上传仍落到初始目录。
webterm_session_update() {
  local text="$1"
  local kind="${2:-shell}"
  # 退避：Agent 故障期间按指数退避跳过上报，避免每个 prompt 都启动失败进程。
  # 状态文件由 CLI 的 --hook-mode 维护（成功清除、失败延长），此处只读不写。
  local wt_state_dir="${WEBTERM_HOOK_STATE_DIR:-}"
  if [ -n "$wt_state_dir" ] && [ -n "${WEBTERM_SESSION_ID:-}" ]; then
    local wt_state_file="$wt_state_dir/$WEBTERM_SESSION_ID"
    if [ -f "$wt_state_file" ]; then
      local wt_next
      read -r wt_next _ < "$wt_state_file" 2>/dev/null || wt_next=""
      case "$wt_next" in
        ''|*[!0-9]*) : ;;
        *)
          if [ "$(date +%s)" -lt "$wt_next" ]; then
            return 0
          fi
          ;;
      esac
    fi
  fi
  # 后台 fire-and-forget：在子 shell 中后台运行，交互式 shell 不跟踪该作业，
  # 因此不会打印 "[1] Done"；输出全部丢弃，失败绝不影响 prompt。元数据经
  # 环境变量传入，避免对动态值（cwd/最近命令）做 shell 转义。
  (
    WEBTERM_HOOK_CWD="$PWD" \
    WEBTERM_HOOK_LAST_COMMAND="$text" \
    WEBTERM_HOOK_INPUT_KIND="$kind" \
    WEBTERM_HOOK_SHELL_STATE="prompt" \
    "$WEBTERM" internal session-update --hook-mode >/dev/null 2>&1 &
  )
}

# Bash 使用 PROMPT_COMMAND
if [ -n "${BASH_VERSION:-}" ]; then
  __webterm_prompt_command() {
    local last
    last="$(history 1 2>/dev/null | sed 's/^[ ]*[0-9]*[ ]*//')"
    webterm_session_update "$last" "shell"
  }
  if [ -z "${PROMPT_COMMAND:-}" ]; then
    PROMPT_COMMAND='__webterm_prompt_command'
  elif [[ "$PROMPT_COMMAND" != *'__webterm_prompt_command'* ]]; then
    PROMPT_COMMAND="${PROMPT_COMMAND%;}; __webterm_prompt_command"
  fi
fi

# Zsh 使用 precmd
if [ -n "${ZSH_VERSION:-}" ]; then
  __webterm_precmd() {
    local last
    last="$(fc -ln -1 2>/dev/null | sed 's/^[ ]*//')"
    webterm_session_update "$last" "shell"
  }
  if ! printf '%s\n' "${precmd_functions[@]}" | grep -qx '__webterm_precmd'; then
    precmd_functions+=(__webterm_precmd)
  fi
fi
`

const bashRcTemplate = `# WebTerm bash 初始化文件
[ -f ~/.bashrc ] && source ~/.bashrc
[ -f "{{.HookScript}}" ] && source "{{.HookScript}}"
`

const zshRcTemplate = `# WebTerm zsh 初始化文件
# 先加载用户原来的 zsh 配置
[ -f "$HOME/.zshenv" ] && source "$HOME/.zshenv"
[ -f "$HOME/.zprofile" ] && source "$HOME/.zprofile"
[ -f "$HOME/.zshrc" ] && source "$HOME/.zshrc"
# 再加载 WebTerm shell hook
[ -f "{{.HookScript}}" ] && source "{{.HookScript}}"
`

const powerShellHookTemplate = `# WebTerm PowerShell hook: reports session metadata at every prompt (non-blocking).
if ($env:WEBTERM_INTEGRATION -ne "1" -or [string]::IsNullOrWhiteSpace($env:WEBTERM_SESSION_ID) -or [string]::IsNullOrWhiteSpace($env:WEBTERM_IPC_ENDPOINT)) { return }
$script:WebTermBin = "{{.WebtermBin}}"
if (-not (Test-Path $script:WebTermBin)) { $script:WebTermBin = "webterm" }
# 退避状态由 CLI 的 --hook-mode 维护（成功清除、失败延长）；此处只读，故障期间跳过启动子进程。
function Test-WebTermHookBackoff {
  $stateDir = $env:WEBTERM_HOOK_STATE_DIR
  if ([string]::IsNullOrWhiteSpace($stateDir) -or [string]::IsNullOrWhiteSpace($env:WEBTERM_SESSION_ID)) { return $false }
  $stateFile = Join-Path $stateDir $env:WEBTERM_SESSION_ID
  if (-not (Test-Path $stateFile)) { return $false }
  $line = Get-Content $stateFile -TotalCount 1 -ErrorAction SilentlyContinue
  if ([string]::IsNullOrWhiteSpace($line)) { return $false }
  $next = 0
  try { $next = [long](($line -split '\s+')[0]) } catch { return $false }
  return ([DateTimeOffset]::Now.ToUnixTimeSeconds() -lt $next)
}
function Invoke-WebTermSessionUpdate {
  if (Test-WebTermHookBackoff) { return }
  $last = ""
  $history = Get-History -Count 1 -ErrorAction SilentlyContinue
  if ($null -ne $history) { $last = $history.CommandLine }
  # 后台启动、不弹窗、输出不连接终端；启动后短等退出（见下方注释），失败绝不影响 prompt。
  # 元数据经环境变量传入，避免对动态值做命令行转义。
  try {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:WebTermBin
    $psi.Arguments = "internal session-update --hook-mode"
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.EnvironmentVariables["WEBTERM_HOOK_CWD"] = $PWD.Path
    $psi.EnvironmentVariables["WEBTERM_HOOK_LAST_COMMAND"] = $last
    $psi.EnvironmentVariables["WEBTERM_HOOK_INPUT_KIND"] = "shell"
    $psi.EnvironmentVariables["WEBTERM_HOOK_SHELL_STATE"] = "prompt"
    $proc = [System.Diagnostics.Process]::Start($psi)
    if ($null -ne $proc) {
      # 必须短等子进程退出：spawn 后立即返回 prompt（含仅去掉 Dispose、交由 GC
      # 回收的写法）会让子进程在 ConPTY 会话内初始化阶段静默死亡——进程从未执行，
      # 上报整条丢失且无任何失败记录（CI runner 上 5/5 稳定复现，本地 Win10 不
      # 复现）。等待期间 PowerShell 管线保持安静，子进程可正常完成初始化。
      # 正常退出约几十毫秒；hook-mode CLI 自身有亚秒级超时，2s 封顶兜底，
      # prompt 不会被无限阻塞。
      try { [void]$proc.WaitForExit(2000) } catch { }
      try { $proc.Dispose() } catch { }
    }
  } catch { }
}
if (-not (Get-Variable -Name WebTermOriginalPrompt -Scope Global -ErrorAction SilentlyContinue)) {
  $global:WebTermOriginalPrompt = $function:prompt
  function global:prompt {
    Invoke-WebTermSessionUpdate
    if ($null -ne $global:WebTermOriginalPrompt) { return & $global:WebTermOriginalPrompt }
    return "PS $($PWD.Path)> "
  }
}
`

// InstallShellHook 安装 shell hook 脚本和 bash 初始化文件。
// webtermBin 是 webterm 二进制路径；为空时使用 PATH 中的 webterm。
func InstallShellHook(webtermBin string) (string, string, error) {
	return InstallShellHookAt(baseDir(), webtermBin)
}

// InstallShellHookAt 在 runtimeBaseDir 下安装生成的 shell integration。
// 按 runtime 隔离后，并行运行的 Agent checkout 不会互相覆盖 CLI 路径和初始化文件。
func InstallShellHookAt(runtimeBaseDir, webtermBin string) (string, string, error) {
	binDir := hookBinDirAt(runtimeBaseDir)
	if err := os.MkdirAll(binDir, 0o700); err != nil {
		return "", "", fmt.Errorf("create hook bin dir: %w", err)
	}

	hookPath := filepath.Join(binDir, "webterm-shell-hook.sh")
	if err := os.WriteFile(hookPath, []byte(replaceShellHookTemplate(webtermBin)), 0o755); err != nil {
		return "", "", fmt.Errorf("write shell hook: %w", err)
	}
	powerShellHookPath := filepath.Join(binDir, "webterm-shell-hook.ps1")
	if err := os.WriteFile(powerShellHookPath, []byte(replacePowerShellHookTemplate(webtermBin)), 0o600); err != nil {
		return "", "", fmt.Errorf("write PowerShell hook: %w", err)
	}

	initDir := ShellInitDirAt(runtimeBaseDir)
	if err := os.MkdirAll(initDir, 0o700); err != nil {
		return "", "", fmt.Errorf("create shell init dir: %w", err)
	}
	bashRcPath := filepath.Join(initDir, "bashrc")
	if err := os.WriteFile(bashRcPath, []byte(replaceBashRcTemplate(hookPath)), 0o600); err != nil {
		return "", "", fmt.Errorf("write bash rc: %w", err)
	}
	zshDir := filepath.Join(initDir, "zsh")
	if err := os.MkdirAll(zshDir, 0o700); err != nil {
		return "", "", fmt.Errorf("create zsh init dir: %w", err)
	}
	zshRcPath := filepath.Join(zshDir, ".zshrc")
	if err := os.WriteFile(zshRcPath, []byte(replaceZshRcTemplate(hookPath)), 0o600); err != nil {
		return "", "", fmt.Errorf("write zsh rc: %w", err)
	}
	return hookPath, bashRcPath, nil
}

func replacePowerShellHookTemplate(webtermBin string) string {
	return strings.ReplaceAll(powerShellHookTemplate, "{{.WebtermBin}}", webtermBin)
}

func replaceShellHookTemplate(webtermBin string) string {
	return strings.ReplaceAll(shellHookTemplate, "{{.WebtermBin}}", webtermBin)
}

func replaceBashRcTemplate(hookPath string) string {
	return strings.ReplaceAll(bashRcTemplate, "{{.HookScript}}", hookPath)
}

func replaceZshRcTemplate(hookPath string) string {
	return strings.ReplaceAll(zshRcTemplate, "{{.HookScript}}", hookPath)
}
