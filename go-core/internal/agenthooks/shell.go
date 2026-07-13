package agenthooks

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const shellHookTemplate = `#!/usr/bin/env bash
# WebTerm shell hook: 上报最近输入的 shell 命令和状态。
# 被 bash / zsh 启动时 source。

[ "${WEBTERM_INTEGRATION:-}" = "1" ] || return 0
[ -n "${WEBTERM_SESSION_ID:-}" ] || return 0
[ -S "${WEBTERM_SOCKET_PATH:-}" ] || return 0

WEBTERM="{{.WebtermBin}}"
[ -x "$WEBTERM" ] || WEBTERM="webterm"

# 安全调用 webterm meta；每个提示符同时上报当前目录，避免 cd 后上传仍落到初始目录。
webterm_meta() {
  local text="$1"
  local kind="${2:-shell}"
  "$WEBTERM" meta --quiet --cwd "$PWD" --last-command "$text" --input-kind "$kind"
}

# Bash 使用 PROMPT_COMMAND
if [ -n "${BASH_VERSION:-}" ]; then
  __webterm_prompt_command() {
    local last
    last="$(history 1 2>/dev/null | sed 's/^[ ]*[0-9]*[ ]*//')"
    webterm_meta "$last" "shell"
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
    webterm_meta "$last" "shell"
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

// InstallShellHook 安装 shell hook 脚本和 bash 初始化文件。
// webtermBin 是 webterm 二进制路径；为空时使用 PATH 中的 webterm。
func InstallShellHook(webtermBin string) (string, string, error) {
	binDir := hookBinDir()
	if err := os.MkdirAll(binDir, 0o700); err != nil {
		return "", "", fmt.Errorf("create hook bin dir: %w", err)
	}

	hookPath := filepath.Join(binDir, "webterm-shell-hook.sh")
	if err := os.WriteFile(hookPath, []byte(replaceShellHookTemplate(webtermBin)), 0o755); err != nil {
		return "", "", fmt.Errorf("write shell hook: %w", err)
	}

	initDir := ShellInitDir()
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

func replaceShellHookTemplate(webtermBin string) string {
	return strings.ReplaceAll(shellHookTemplate, "{{.WebtermBin}}", webtermBin)
}

func replaceBashRcTemplate(hookPath string) string {
	return strings.ReplaceAll(bashRcTemplate, "{{.HookScript}}", hookPath)
}

func replaceZshRcTemplate(hookPath string) string {
	return strings.ReplaceAll(zshRcTemplate, "{{.HookScript}}", hookPath)
}
