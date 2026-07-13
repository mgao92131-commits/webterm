#!/usr/bin/env bash
set -euo pipefail

# WebTerm Agent Hook 辅助脚本。
# 从 stdin 读取 Agent 事件 JSON，提取最有意义的文本，然后调用 webterm agent-event。
#
# 目标 session 解析顺序：
#   1. 环境变量 WEBTERM_SESSION_ID
#   2. 调用者进程（PPID）所在 TTY 对应的 WebTerm session
#   3.  fallback：把调用者 PID 交给 webterm agent-event / agent 解析
#
# 用法：
#   webterm-notify-helper <importance> <default-message> <source>
#
# 示例：
#   echo '{"prompt":"hello world"}' | webterm-notify-helper quiet "Running" claude

level="${1:-}"
default_msg="${2:-}"
source="${3:-}"

# 注意：helper 由 agent hook 调用，绝不能 exit 2——Kimi/Claude 会把 exit 2
# 解释为阻止工具调用或阻止本轮结束（死循环）。参数错误是非阻塞错误，exit 1 跳过通知。
if [ -z "$level" ] || [ -z "$default_msg" ] || [ -z "$source" ]; then
  echo "Usage: $0 <importance> <default-message> <source>" >&2
  echo "Example: echo '{\"prompt\":\"hi\"}' | $0 quiet \"Running\" claude" >&2
  exit 1
fi

if [ "$level" != "alert" ] && [ "$level" != "normal" ] && [ "$level" != "quiet" ]; then
  echo "Invalid importance '$level' (expected alert|normal|quiet)." >&2
  exit 1
fi

# 读取 stdin 中的 JSON payload
payload=$(cat 2>/dev/null || true)

# 从 payload 提取文本，失败时使用默认消息
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
    if not isinstance(data, dict):
        return ""

    # 优先检查是否存在 questions 数组
    q_list = None
    if isinstance(data.get("questions"), list):
        q_list = data["questions"]
    else:
        ti = data.get("tool_input")
        if isinstance(ti, dict) and isinstance(ti.get("questions"), list):
            q_list = ti["questions"]

    if q_list and len(q_list) > 0:
        first_q = q_list[0]
        if isinstance(first_q, dict):
            question = first_q.get("question")
            if isinstance(question, str) and question:
                return f"Question: {question}"

    # 顶层字段
    text = first(data, ("prompt", "content", "message", "last_assistant_message", "text"))
    if text:
        return text

    # messages 数组最后一个
    msgs = data.get("messages")
    if isinstance(msgs, list) and msgs:
        last = msgs[-1]
        text = first(last, ("content", "text", "prompt"))
        if text:
            return text

    # tool_input 里的命令；带 tool_name 时输出 "工具名: 命令"
    ti = data.get("tool_input")
    if isinstance(ti, dict):
        text = first(ti, ("command", "cmd", "code", "script", "description"))
        if text:
            tool_name = data.get("tool_name")
            if isinstance(tool_name, str) and tool_name:
                return f"{tool_name}: {text}"
            return text

    return ""

try:
    data = json.loads(sys.stdin.read())
except Exception:
    data = {}

msg = extract(data)
print(msg.replace("\r", " ").replace("\n", " ")[:120].strip())
' <<<"$payload" 2>/dev/null || true)

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

# 通过调用者 PID 的 TTY 查找 WebTerm session ID。
# 返回：找到时打印 session id，找不到时打印空行。
resolve_session_by_tty() {
  local caller_pid="$1"
  local control_addr="${2:-http://127.0.0.1:18081}"

  local tty
  tty="$(ps -o tty= -p "$caller_pid" 2>/dev/null | tr -d ' ')" || true
  if [ -z "$tty" ] || [ "$tty" = "??" ] || [ "$tty" = "?" ]; then
    return 0
  fi

  # ps 在 macOS 上可能返回 s023，而 session.ttyPath 是 /dev/ttys023
  if [[ "$tty" != /dev/* ]]; then
    tty="/dev/$tty"
  fi

  if ! command -v curl >/dev/null 2>&1; then
    return 0
  fi

  python3 -c '
import sys, json, subprocess
tty = sys.argv[1]
addr = sys.argv[2]
try:
    out = subprocess.check_output(["curl", "-s", "--max-time", "1", f"{addr}/control/sessions"], text=True)
    sessions = json.loads(out)
    for s in sessions:
        if s.get("tty") == tty:
            print(s.get("id", ""))
            sys.exit(0)
except Exception:
    pass
' "$tty" "$control_addr" 2>/dev/null || true
}

# 确定目标 session / pid
session_id="${WEBTERM_SESSION_ID:-}"
caller_pid="${WEBTERM_HOOK_PID:-$PPID}"
control_addr="${WEBTERM_CONTROL_ADDR:-http://127.0.0.1:18081}"

if [ -z "$session_id" ]; then
  resolved_session="$(resolve_session_by_tty "$caller_pid" "$control_addr")" || true
  if [ -n "$resolved_session" ]; then
    session_id="$resolved_session"
  fi
fi

notify_args=(
  agent-event
  -i "$level"
  -m "$msg"
  -s "$source"
)

if [ -n "$session_id" ]; then
  notify_args+=(--session "$session_id")
else
  # 没有 session id，把调用者 PID 传过去，让 agent 沿父进程链解析
  notify_args+=(--pid "$caller_pid")
  echo "webterm-notify-helper: cannot resolve WebTerm session for pid $caller_pid (is this Agent started inside a WebTerm terminal?)" >&2
fi

# 调用 webterm agent-event；失败也不应中断 agent 工作流
"$WEBTERM" "${notify_args[@]}" >/dev/null 2>&1 || true

exit 0
