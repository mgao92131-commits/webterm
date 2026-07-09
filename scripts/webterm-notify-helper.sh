#!/usr/bin/env bash
set -euo pipefail

# WebTerm Agent Hook 辅助脚本。
# 从 stdin 读取 Agent 事件 JSON，提取最有意义的文本，然后调用 webterm notify。
# 目标 session 由 webterm notify 通过自身 PID 交给后端解析，本脚本不再判断。
#
# 用法：
#   webterm-notify-helper <level> <default-message> <source>
#
# 示例：
#   echo '{"prompt":"hello world"}' | webterm-notify-helper running "Running" claude

level="${1:-}"
default_msg="${2:-}"
source="${3:-}"

if [ -z "$level" ] || [ -z "$default_msg" ] || [ -z "$source" ]; then
  echo "Usage: $0 <level> <default-message> <source>" >&2
  echo "Example: echo '{\"prompt\":\"hi\"}' | $0 running \"Running\" claude" >&2
  exit 2
fi

if [ "$level" != "idle" ] && [ "$level" != "running" ] && [ "$level" != "error" ]; then
  echo "Invalid level '$level'. Must be idle|running|error." >&2
  exit 2
fi

# 读取 stdin 中的 JSON payload
payload=$(cat 2>/dev/null || true)

# 从 payload 提取文本，失败时使用空字符串
msg=$(python3 -c '
import sys, json

def first(d, keys):
    for k in keys:
        if isinstance(d, dict):
            v = d.get(k)
            if isinstance(v, str) and v:
                return v
    return None

def extract(data):
    # 顶层字段
    text = first(data, ("prompt", "content", "message", "text"))
    if text:
        return text

    # messages 数组最后一个
    msgs = data.get("messages") if isinstance(data, dict) else None
    if isinstance(msgs, list) and msgs:
        last = msgs[-1]
        text = first(last, ("content", "text", "prompt"))
        if text:
            return text

    # tool_input 里的命令
    ti = data.get("tool_input") if isinstance(data, dict) else None
    if isinstance(ti, dict):
        text = first(ti, ("command", "cmd", "code", "script"))
        if text:
            return text

    return ""

try:
    data = json.loads(sys.stdin.read())
except Exception:
    data = {}

print(extract(data)[:60].strip())
' <<<"$payload" 2>/dev/null || true)

# 如果提取不到，使用默认消息
if [ -z "$msg" ]; then
  msg="$default_msg"
fi

# 查找 webterm CLI：环境变量 > helper 同目录 > ~/.webterm/bin > PATH
resolve_webterm() {
  if [ -n "${WEBTERM_BIN:-}" ] && [ -x "$WEBTERM_BIN" ]; then
    echo "$WEBTERM_BIN"
    return 0
  fi

  local helper_dir
  helper_dir="$(cd "$(dirname "$0")" && pwd)"
  if [ -x "$helper_dir/webterm" ]; then
    echo "$helper_dir/webterm"
    return 0
  fi

  local home_dir
  home_dir="${HOME:-$(eval echo ~"$USER")}"
  if [ -x "$home_dir/.webterm/bin/webterm" ]; then
    echo "$home_dir/.webterm/bin/webterm"
    return 0
  fi

  if command -v webterm >/dev/null 2>&1; then
    echo "webterm"
    return 0
  fi

  return 1
}

WEBTERM="$(resolve_webterm)" || {
  # webterm CLI 未安装或不在预期位置；静默跳过，不中断 agent。
  exit 0
}

# 调用 webterm notify；失败也不应中断 agent 工作流
"$WEBTERM" notify --level "$level" --message "$msg" --source "$source" >/dev/null 2>&1 || true

exit 0
