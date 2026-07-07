package agenthooks

import (
	"fmt"
	"os"
	"path/filepath"
	"text/template"
)

const hookScriptTemplate = `#!/usr/bin/env bash
set -euo pipefail

agent="${1:-}"
event="${2:-}"

[ "${WEBTERM_INTEGRATION:-}" = "1" ] || exit 0
[ -n "${WEBTERM_SESSION_ID:-}" ] || exit 0
[ -S "${WEBTERM_SOCKET_PATH:-}" ] || exit 0

WEBTERM="{{.WebtermBin}}"
[ -x "$WEBTERM" ] || WEBTERM="webterm"

# 检测 python3 是否可用；缺失时跳过 meta 提取，但不影响核心 state 上报。
if command -v python3 >/dev/null 2>&1; then
  HAS_PYTHON3=1
else
  HAS_PYTHON3=0
fi

# 读取 Agent 通过 stdin 传来的事件 JSON
payload=$(cat 2>/dev/null || true)

# 安全调用 webterm meta --last-command，避免 prompt/command 里的引号破坏 shell
webterm_meta_last_command() {
  local text="$1"
  local kind="$2"
  "$WEBTERM" meta --quiet --last-command "$text" --input-kind "$kind"
}

# 从 payload 提取用户提示词（兼容 Claude/Kimi/Codex 的字段差异）
extract_prompt() {
  if [ "$HAS_PYTHON3" -ne 1 ]; then
    return 0
  fi
  python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for key in ("prompt", "content", "text"):
    v = d.get(key)
    if isinstance(v, str) and v:
        print(v)
        sys.exit(0)
msgs = d.get("messages")
if isinstance(msgs, list) and msgs:
    last = msgs[-1]
    if isinstance(last, dict):
        for key in ("content", "text", "prompt"):
            v = last.get(key)
            if isinstance(v, str) and v:
                print(v)
                sys.exit(0)
' <<<"$payload"
}

# 从 payload 提取工具命令（如 Bash 的 command）
extract_tool_command() {
  if [ "$HAS_PYTHON3" -ne 1 ]; then
    return 0
  fi
  python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
ti = d.get("tool_input") if isinstance(d, dict) else None
if isinstance(ti, dict):
    for key in ("command", "cmd", "code", "script"):
        v = ti.get(key)
        if isinstance(v, str) and v:
            print(v)
            sys.exit(0)
' <<<"$payload"
}

case "$agent:$event" in
  claude:user_prompt_submit|kimi:user_prompt_submit|codex:user_prompt_submit)
    prompt=$(extract_prompt)
    if [ -n "$prompt" ]; then
      webterm_meta_last_command "$prompt" "agent_prompt"
    fi
    "$WEBTERM" state --quiet --agent running
    ;;

  claude:pre_tool_use|kimi:pre_tool_use|codex:pre_tool_use)
    cmd=$(extract_tool_command)
    if [ -n "$cmd" ]; then
      webterm_meta_last_command "$cmd" "agent_tool"
    fi
    "$WEBTERM" state --quiet --agent running
    ;;

  claude:permission_request)
    "$WEBTERM" notify --quiet --title "Claude Code" --body "Waiting for approval" --level warning
    "$WEBTERM" state --quiet --agent approval_required
    ;;
  claude:notification)
    "$WEBTERM" notify --quiet --title "Claude Code" --body "Needs attention" --level info
    "$WEBTERM" state --quiet --agent waiting_input
    ;;
  claude:stop)
    "$WEBTERM" notify --quiet --title "Claude Code" --body "Turn completed" --level success
    "$WEBTERM" state --quiet --agent done
    ;;
  claude:session_end)
    "$WEBTERM" state --quiet --agent idle
    ;;

  kimi:permission_request)
    "$WEBTERM" notify --quiet --title "Kimi Code" --body "Waiting for approval" --level warning
    "$WEBTERM" state --quiet --agent approval_required
    ;;
  kimi:notification)
    "$WEBTERM" notify --quiet --title "Kimi Code" --body "Needs attention" --level info
    "$WEBTERM" state --quiet --agent waiting_input
    ;;
  kimi:stop_failure)
    "$WEBTERM" notify --quiet --title "Kimi Code" --body "Task failed" --level error
    "$WEBTERM" state --quiet --agent failed
    ;;
  kimi:session_end)
    "$WEBTERM" state --quiet --agent idle
    ;;

  codex:post_tool_use)
    "$WEBTERM" state --quiet --agent running
    ;;
  codex:subagent_start)
    "$WEBTERM" state --quiet --agent running
    ;;
  codex:stop)
    "$WEBTERM" notify --quiet --title "Codex" --body "Turn completed" --level success
    "$WEBTERM" state --quiet --agent done
    ;;
  codex:session_end)
    "$WEBTERM" state --quiet --agent idle
    ;;

  *)
    # 未识别事件，静默忽略
    ;;
esac

exit 0
`

// InstallHookScript 生成 ~/.webterm/bin/webterm-agent-hook 脚本。
// webtermBin 是 webterm 二进制路径，会优先写入脚本；如果为空则回退到 PATH 中的 webterm。
func InstallHookScript(webtermBin string) (string, error) {
	binDir := hookBinDir()
	if err := os.MkdirAll(binDir, 0o700); err != nil {
		return "", fmt.Errorf("create hook bin dir: %w", err)
	}

	path := hookBinPath()
	tmpl, err := template.New("hook").Parse(hookScriptTemplate)
	if err != nil {
		return "", fmt.Errorf("parse hook script template: %w", err)
	}

	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		return "", fmt.Errorf("open hook script: %w", err)
	}
	defer f.Close()

	if err := tmpl.Execute(f, map[string]string{"WebtermBin": webtermBin}); err != nil {
		return "", fmt.Errorf("write hook script: %w", err)
	}
	return path, nil
}

// ResolveWebtermBinary 探测 webterm 二进制路径。
// 优先级：WEBTERM_BIN 环境变量 > webterm-agent 同级目录 > PATH。
func ResolveWebtermBinary() (string, error) {
	if env := os.Getenv("WEBTERM_BIN"); env != "" {
		return filepath.Abs(env)
	}

	agentPath, err := os.Executable()
	if err == nil {
		dir := filepath.Dir(agentPath)
		candidate := filepath.Join(dir, "webterm")
		if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
			return candidate, nil
		}
	}

	if path, err := execLookPath("webterm"); err == nil {
		return path, nil
	}

	return "", fmt.Errorf("webterm binary not found")
}

func execLookPath(name string) (string, error) {
	// 使用 exec.LookPath 会引入 os/exec；这里只用于解析 PATH，可以直接调用。
	// 为避免在生成脚本阶段依赖 exec，手动扫描 PATH。
	paths := filepath.SplitList(os.Getenv("PATH"))
	for _, dir := range paths {
		if dir == "" {
			continue
		}
		candidate := filepath.Join(dir, name)
		if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
			return candidate, nil
		}
	}
	return "", fmt.Errorf("not found")
}
