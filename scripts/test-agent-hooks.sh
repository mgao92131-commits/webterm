#!/usr/bin/env bash
set -euo pipefail

# 测试 agent hook 安装脚本和 notify helper。
# 使用临时 HOME 目录，避免污染真实配置。

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_SCRIPT="$REPO_ROOT/scripts/install-agent-hook-examples.sh"
HELPER_SCRIPT="$REPO_ROOT/scripts/webterm-notify-helper.sh"
FIXTURE_DIR="$REPO_ROOT/scripts/testdata/agent-hooks"

TMP_HOME="$(mktemp -d)"
MOCK_WEBTERM_DIR="$TMP_HOME/mock-bin"
WEBTERM_CALLS="$TMP_HOME/webterm-calls.txt"
export HOME="$TMP_HOME"
export WEBTERM_BIN="$MOCK_WEBTERM_DIR/webterm"

pass=0
fail=0

assert_eq() {
  local expected="$1"
  local actual="$2"
  local msg="${3:-}"
  if [ "$expected" == "$actual" ]; then
    pass=$((pass + 1))
    echo "  ✓ ${msg}"
  else
    fail=$((fail + 1))
    echo "  ✗ ${msg}"
    echo "    expected: $expected"
    echo "    actual:   $actual"
  fi
}

assert_contains() {
  local needle="$1"
  local haystack="$2"
  local msg="${3:-}"
  if echo "$haystack" | grep -qF -- "$needle"; then
    pass=$((pass + 1))
    echo "  ✓ ${msg}"
  else
    fail=$((fail + 1))
    echo "  ✗ ${msg}"
    echo "    missing: $needle"
    echo "    in:      $haystack"
  fi
}

assert_file_contains() {
  local file="$1"
  local needle="$2"
  local msg="${3:-}"
  if [ -f "$file" ] && grep -qF -- "$needle" "$file"; then
    pass=$((pass + 1))
    echo "  ✓ ${msg}"
  else
    fail=$((fail + 1))
    echo "  ✗ ${msg}"
    echo "    file: $file"
    echo "    missing: $needle"
  fi
}

refute_file_contains() {
  local file="$1"
  local needle="$2"
  local msg="${3:-}"
  if [ -f "$file" ] && ! grep -qF -- "$needle" "$file"; then
    pass=$((pass + 1))
    echo "  ✓ ${msg}"
  else
    fail=$((fail + 1))
    echo "  ✗ ${msg}"
    echo "    file: $file"
    echo "    unexpected: $needle"
  fi
}

setup_mock_webterm() {
  mkdir -p "$MOCK_WEBTERM_DIR"
  cat > "$WEBTERM_BIN" <<'EOF'
#!/bin/bash
# mock webterm: record env + args and exit 0
{
  printf 'SESSION_ID=%s\n' "${WEBTERM_SESSION_ID:-<unset>}"
  printf 'SOCKET_PATH=%s\n' "${WEBTERM_SOCKET_PATH:-<unset>}"
  printf '%s\n' "$*"
} >> "$(dirname "$0")/../webterm-calls.txt"
EOF
  chmod +x "$WEBTERM_BIN"
}

clear_calls() {
  : > "$WEBTERM_CALLS"
}

last_call() {
  tail -n 1 "$WEBTERM_CALLS" || true
}

cleanup() {
  rm -rf "$TMP_HOME"
}
trap cleanup EXIT

echo "== 测试 webterm-notify-helper =="
setup_mock_webterm

# 默认给 helper 一个 session id，避免 TTY 解析警告干扰测试
export WEBTERM_SESSION_ID="test-session"

# 缺少参数
test "$($HELPER_SCRIPT 2>&1; echo $?)" != "0" && pass=$((pass + 1)) || fail=$((fail + 1))
echo "  ✓ 缺少参数时退出非零"

# 非法 level
$HELPER_SCRIPT invalid "msg" src >/dev/null 2>&1 && fail=$((fail + 1)) || pass=$((pass + 1))
echo "  ✓ 非法 level 时退出非零"

# Claude PreToolUse：显式 alert，并从官方契约 fixture 提取问题
clear_calls
"$HELPER_SCRIPT" alert "Question" claude < "$FIXTURE_DIR/claude-pre-ask-user-question.json"
assert_contains "notify --importance alert -m Question: 选择哪种认证方案？ -s claude" "$(last_call)" "Claude 提问开始显式映射 alert"

# Claude PostToolUse：显式 quiet，确定性复位
clear_calls
"$HELPER_SCRIPT" quiet "Running" claude </dev/null
assert_contains "notify --importance quiet -m Running -s claude" "$(last_call)" "Claude 回答完成显式映射 quiet 并清除旧问题正文"

# Kimi PreToolUse：显式 alert
clear_calls
"$HELPER_SCRIPT" alert "Question" kimi < "$FIXTURE_DIR/kimi-pre-ask-user-question.json"
assert_contains "notify --importance alert -m Question: 要继续部署吗？ -s kimi" "$(last_call)" "Kimi 提问开始显式映射 alert"

# Kimi PostToolUse：显式 quiet，确定性复位
clear_calls
"$HELPER_SCRIPT" quiet "Running" kimi </dev/null
assert_contains "notify --importance quiet -m Running -s kimi" "$(last_call)" "Kimi 回答完成显式映射 quiet 并清除旧问题正文"

# PostToolUse fixture 保留原 tool_input，冻结成对事件契约
if python3 - "$FIXTURE_DIR/claude-post-ask-user-question.json" "$FIXTURE_DIR/kimi-post-ask-user-question.json" <<'PY'
import json, sys
for path in sys.argv[1:]:
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    assert data["hook_event_name"] == "PostToolUse"
    assert data["tool_name"] == "AskUserQuestion"
    assert data["tool_input"]["questions"][0]["question"]
PY
then
  pass=$((pass + 1))
  echo "  ✓ PostToolUse fixture 保留提问工具契约"
else
  fail=$((fail + 1))
  echo "  ✗ PostToolUse fixture 契约无效"
fi

# auto 不再是合法 importance
"$HELPER_SCRIPT" auto "Running" claude </dev/null >/dev/null 2>&1 && fail=$((fail + 1)) || pass=$((pass + 1))
echo "  ✓ auto importance 已被拒绝"

# CR/LF 被清理为单行
clear_calls
printf '%s' '{"prompt":"第一行\r\n第二行"}' | "$HELPER_SCRIPT" quiet "Running" claude
assert_contains "-m 第一行  第二行" "$(last_call)" "CR/LF 被清理为单行"

# 空 stdin 时使用默认消息，并传递 --session
clear_calls
$HELPER_SCRIPT quiet "Running" claude </dev/null
assert_contains "notify --importance quiet -m Running -s claude --session test-session" "$(last_call)" "空 stdin 时使用默认消息并传递 session"

# 从顶层 prompt 字段提取
clear_calls
echo '{"prompt":"deploy to production"}' | $HELPER_SCRIPT quiet "Running" claude
assert_contains "-m deploy to production" "$(last_call)" "提取顶层 prompt"
assert_contains "--session test-session" "$(last_call)" "提取 prompt 时传递 session"

# 从 last_assistant_message 提取（Claude Stop payload）
clear_calls
echo '{"last_assistant_message":"已完成修改"}' | $HELPER_SCRIPT normal "Done" claude
assert_contains "-m 已完成修改" "$(last_call)" "提取 last_assistant_message"

# 从 messages 数组提取最后一条
clear_calls
echo '{"messages":[{"role":"user","content":"hello"},{"role":"assistant","content":"done"}]}' | $HELPER_SCRIPT quiet "Running" claude
assert_contains "-m done" "$(last_call)" "提取 messages 数组最后一条"

# 从 tool_input 提取（无 tool_name 时维持原样）
clear_calls
echo '{"tool_input":{"command":"git status"}}' | $HELPER_SCRIPT quiet "Running" claude
assert_contains "-m git status" "$(last_call)" "提取 tool_input.command"

# 带 tool_name 时输出 "工具名: 命令"
clear_calls
echo '{"tool_name":"Bash","tool_input":{"command":"git status"}}' | $HELPER_SCRIPT quiet "Running" claude
assert_contains "-m Bash: git status" "$(last_call)" "带 tool_name 时输出 工具名: 命令"

# 超长文本截断到 120 字符
clear_calls
long_msg=$(python3 -c 'print("a"*150)')
echo "{\"prompt\":\"$long_msg\"}" | $HELPER_SCRIPT quiet "Running" claude
call_len=$(last_call | sed 's/.*-m //;s/ -s.*//' | tr -d '\n' | wc -c | tr -d '[:space:]')
assert_eq "120" "$call_len" "超长消息截断为 120 字符"

# 无效 JSON 回退到默认消息
clear_calls
echo 'not json' | $HELPER_SCRIPT alert "Waiting for approval" kimi
assert_contains "-m Waiting for approval" "$(last_call)" "无效 JSON 回退默认消息"

# normal 状态
clear_calls
$HELPER_SCRIPT normal "Done" codex </dev/null
assert_contains "notify --importance normal -m Done -s codex --session test-session" "$(last_call)" "normal 状态调用正确"

# webterm 缺失时不应中断 agent（退出 0）
unset WEBTERM_BIN
result=$($HELPER_SCRIPT quiet "Running" claude </dev/null >/dev/null 2>&1; echo $?)
export WEBTERM_BIN="$MOCK_WEBTERM_DIR/webterm"
assert_eq "0" "$result" "webterm 缺失时 helper 退出 0"

# helper 会在自身所在目录寻找 webterm
helper_tmp_dir="$TMP_HOME/helper-dir-test"
mkdir -p "$helper_tmp_dir"
cp "$HELPER_SCRIPT" "$helper_tmp_dir/webterm-notify-helper"
chmod +x "$helper_tmp_dir/webterm-notify-helper"
cat > "$helper_tmp_dir/webterm" <<'EOF'
#!/bin/bash
printf '%s\n' "$*" >> "$(dirname "$0")/../webterm-calls.txt"
EOF
chmod +x "$helper_tmp_dir/webterm"
clear_calls
unset WEBTERM_BIN
HOME="$TMP_HOME" "$helper_tmp_dir/webterm-notify-helper" quiet "Running" claude </dev/null
export WEBTERM_BIN="$MOCK_WEBTERM_DIR/webterm"
assert_contains "notify --importance quiet -m Running -s claude --session test-session" "$(last_call)" "helper 在同目录找到 webterm"

# 无 env 时 helper fallback 到 --pid，由后端通过调用者 PID 解析
mkdir -p "$TMP_HOME/.webterm"
clear_calls
HOME="$TMP_HOME" env -u WEBTERM_SESSION_ID -u WEBTERM_SOCKET_PATH WEBTERM_HOOK_PID=1 "$HELPER_SCRIPT" quiet "Running" claude </dev/null
assert_contains "notify --importance quiet -m Running -s claude --pid 1" "$(last_call)" "无 env 时 helper fallback 到 pid"

echo ""
echo "== 测试 install-agent-hook-examples.sh =="

# 首次安装
bash "$INSTALL_SCRIPT" >/dev/null 2>&1

assert_file_contains "$TMP_HOME/.webterm/bin/webterm-notify-helper" "notify" "helper 已安装到 ~/.webterm/bin"
if [ -x "$REPO_ROOT/go-core/webterm" ]; then
  link_target=$(readlink "$TMP_HOME/.webterm/bin/webterm" || true)
  assert_contains "$REPO_ROOT/go-core/webterm" "$link_target" "webterm CLI 已链接到 ~/.webterm/bin"
fi
assert_file_contains "$TMP_HOME/.claude/settings.json" "webterm-notify-helper" "Claude settings 已写入"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" "webterm-notify-helper" "Kimi config 已写入"
assert_file_contains "$TMP_HOME/.codex/hooks.json" "webterm-notify-helper" "Codex hooks 已写入"

if python3 -c 'import json, sys; json.load(open(sys.argv[1])); json.load(open(sys.argv[2]))' \
  "$TMP_HOME/.claude/settings.json" "$TMP_HOME/.codex/hooks.json" \
  && python3 - "$TMP_HOME/.kimi-code/config.toml" <<'PY'
import json
import sys

path = sys.argv[1]
try:
    import tomllib
except ImportError:
    # macOS 自带 Python 3.9 没有 tomllib；当前 Kimi 配置只使用
    # [[hooks]]、基本字符串和整数，用同一受限语法做无依赖校验。
    hooks = []
    current = None
    for raw in open(path, encoding="utf-8"):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line == "[[hooks]]":
            current = {}
            hooks.append(current)
            continue
        if current is None or "=" not in line:
            raise ValueError("invalid restricted TOML line: " + line)
        key, value = (part.strip() for part in line.split("=", 1))
        current[key] = json.loads(value) if value.startswith('"') else int(value)
    if not hooks or any("event" not in hook or "command" not in hook for hook in hooks):
        raise ValueError("missing hook fields")
else:
    with open(path, "rb") as stream:
        tomllib.load(stream)
PY
then
  pass=$((pass + 1))
  echo "  ✓ 安装后的 Agent 配置可被 JSON/TOML 解析"
else
  fail=$((fail + 1))
  echo "  ✗ 安装后的 Agent 配置无法解析"
fi

# 验证统一的事件映射
assert_file_contains "$TMP_HOME/.claude/settings.json" '"PreToolUse"' "Claude 包含 PreToolUse 事件"
assert_file_contains "$TMP_HOME/.claude/settings.json" '"PostToolUse"' "Claude 包含 PostToolUse 事件"
assert_file_contains "$TMP_HOME/.claude/settings.json" '"matcher": "AskUserQuestion"' "Claude 提问 Hook 使用精确 matcher"
assert_file_contains "$TMP_HOME/.claude/settings.json" '"Stop"' "Claude 包含 Stop 事件"
assert_file_contains "$TMP_HOME/.claude/settings.json" '"StopFailure"' "Claude 包含 StopFailure 事件"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'event = "PreToolUse"' "Kimi 包含 PreToolUse 事件"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'event = "PostToolUse"' "Kimi 包含 PostToolUse 事件"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'matcher = "^AskUserQuestion$"' "Kimi 提问 Hook 使用精确 matcher"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'event = "PermissionResult"' "Kimi 包含 PermissionResult 事件"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'event = "StopFailure"' "Kimi 包含 StopFailure 事件（Kimi 实际事件名）"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'event = "Notification"' "Kimi 包含 Notification 事件（后台任务完成）"
refute_file_contains "$TMP_HOME/.codex/hooks.json" '"PreToolUse"' "Codex 不写入未经验证的提问 PreToolUse"
assert_file_contains "$TMP_HOME/.codex/hooks.json" '"Stop"' "Codex 包含 Stop 事件"
assert_file_contains "$TMP_HOME/.codex/hooks.json" '"PermissionRequest"' "Codex 包含 PermissionRequest 事件"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'event = "PermissionRequest"' "Kimi 包含 PermissionRequest"
refute_file_contains "$TMP_HOME/.codex/hooks.json" '"SessionEnd"' "Codex 不含 SessionEnd（官方无此事件）"

# 验证 importance 映射（配置层唯一的事件→重要性映射）
assert_file_contains "$TMP_HOME/.claude/settings.json" 'quiet \"Running\" claude' "Claude UserPromptSubmit 映射 quiet"
assert_file_contains "$TMP_HOME/.claude/settings.json" 'alert \"Question\" claude' "Claude PreToolUse 显式映射 alert"
assert_file_contains "$TMP_HOME/.claude/settings.json" 'quiet \"Running\" claude </dev/null' "Claude PostToolUse 显式映射 quiet 并使用默认消息"
refute_file_contains "$TMP_HOME/.claude/settings.json" 'auto ' "Claude 配置不含 auto"
assert_file_contains "$TMP_HOME/.claude/settings.json" 'alert \"Waiting for approval\" claude' "Claude PermissionRequest 映射 alert"
assert_file_contains "$TMP_HOME/.claude/settings.json" 'normal \"Done\" claude' "Claude Stop 映射 normal"
assert_file_contains "$TMP_HOME/.claude/settings.json" 'alert \"Failed\" claude' "Claude StopFailure 映射 alert Failed"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'quiet \"Running\" kimi' "Kimi UserPromptSubmit 映射 quiet"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'alert \"Question\" kimi' "Kimi PreToolUse 显式映射 alert"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'quiet \"Running\" kimi </dev/null' "Kimi 复位 Hook 显式映射 quiet 并使用默认消息"
refute_file_contains "$TMP_HOME/.kimi-code/config.toml" 'auto ' "Kimi 配置不含 auto"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'quiet \"Running\" kimi' "Kimi PermissionResult 映射 quiet"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'alert \"Failed\" kimi' "Kimi StopFailure 映射 alert Failed"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'normal \"Done\" kimi' "Kimi Stop 映射 normal"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'matcher = "task\\.completed"' "Kimi Notification 匹配 task.completed"
assert_file_contains "$TMP_HOME/.codex/hooks.json" 'quiet \"Running\" codex' "Codex UserPromptSubmit 映射 quiet"
refute_file_contains "$TMP_HOME/.codex/hooks.json" 'auto ' "Codex 配置不含 auto"
assert_file_contains "$TMP_HOME/.codex/hooks.json" 'alert \"Waiting for approval\" codex' "Codex PermissionRequest 映射 alert"
assert_file_contains "$TMP_HOME/.codex/hooks.json" 'normal \"Done\" codex' "Codex Stop 映射 normal"

# 每次覆盖真实配置前都生成新的时间戳备份
backup_count_before=$(find "$TMP_HOME/.claude" -maxdepth 1 -name 'settings.json.*.bak' | wc -l | tr -d '[:space:]')
bash "$INSTALL_SCRIPT" >/dev/null 2>&1
backup_count_after=$(find "$TMP_HOME/.claude" -maxdepth 1 -name 'settings.json.*.bak' | wc -l | tr -d '[:space:]')
if [ "$backup_count_after" -gt "$backup_count_before" ]; then
  pass=$((pass + 1))
  echo "  ✓ 重复安装前创建新的时间戳备份"
else
  fail=$((fail + 1))
  echo "  ✗ 重复安装前应创建新的时间戳备份"
fi

# 验证使用绝对路径（Codex 等 agent 不会展开 ~）
abs_helper="$TMP_HOME/.webterm/bin/webterm-notify-helper"
assert_file_contains "$TMP_HOME/.claude/settings.json" "$abs_helper" "Claude 使用绝对路径"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" "$abs_helper" "Kimi 使用绝对路径"
assert_file_contains "$TMP_HOME/.codex/hooks.json" "$abs_helper" "Codex 使用绝对路径"
refute_file_contains "$TMP_HOME/.codex/hooks.json" '~/.webterm/bin' "Codex 配置不含 ~ 路径"

# 幂等性：再次安装不应重复写入
claude_before=$(grep -c 'webterm-notify-helper' "$TMP_HOME/.claude/settings.json" || true)
bash "$INSTALL_SCRIPT" >/dev/null 2>&1
claude_after=$(grep -c 'webterm-notify-helper' "$TMP_HOME/.claude/settings.json" || true)
assert_eq "$claude_before" "$claude_after" "重复安装不会重复追加配置"

# 已存在配置时的合并行为（非 webterm 内容应保留）
rm -rf "$TMP_HOME/.codex"
mkdir -p "$TMP_HOME/.codex"
echo '{"existing":"value","hooks":{}}' > "$TMP_HOME/.codex/hooks.json"
bash "$INSTALL_SCRIPT" >/dev/null 2>&1
assert_file_contains "$TMP_HOME/.codex/hooks.json" '"existing": "value"' "合并安装保留已有字段"
assert_file_contains "$TMP_HOME/.codex/hooks.json" '"UserPromptSubmit"' "合并安装追加 webterm hooks"

# 旧版 Codex auto PreToolUse 应被完整移除，不留下空事件数组
python3 - "$TMP_HOME/.codex/hooks.json" <<'PY'
import json, sys
path = sys.argv[1]
data = json.load(open(path))
data.setdefault("hooks", {})["PreToolUse"] = [{
    "matcher": "*",
    "hooks": [{
        "type": "command",
        "command": "/tmp/webterm-notify-helper auto \"Running\" codex"
    }]
}]
with open(path, "w") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
PY
bash "$INSTALL_SCRIPT" >/dev/null 2>&1
refute_file_contains "$TMP_HOME/.codex/hooks.json" '"PreToolUse"' "Codex 旧 auto PreToolUse 被完整移除"

# 旧 ~ 路径迁移为绝对路径
rm -rf "$TMP_HOME/.codex"
mkdir -p "$TMP_HOME/.codex"
echo '{
  "hooks": {
    "UserPromptSubmit": [{"matcher": "*", "hooks": [{"type": "command", "command": "~/.webterm/bin/webterm-notify-helper running \"Running\" codex"}]}]
  }
}' > "$TMP_HOME/.codex/hooks.json"
bash "$INSTALL_SCRIPT" >/dev/null 2>&1
refute_file_contains "$TMP_HOME/.codex/hooks.json" '~/.webterm/bin' "旧 ~ 路径已被迁移"
assert_file_contains "$TMP_HOME/.codex/hooks.json" "$abs_helper" "迁移后使用绝对路径"

# 安装脚本应同时清理残留的旧 webterm-agent-hook
rm -rf "$TMP_HOME/.codex"
mkdir -p "$TMP_HOME/.codex"
echo '{
  "hooks": {
    "UserPromptSubmit": [
      {"matcher": "*", "hooks": [{"type": "command", "command": "$abs_helper running \"Running\" codex"}]},
      {"matcher": "*", "hooks": [{"type": "command", "command": "$TMP_HOME/.webterm/bin/webterm-agent-hook codex user_prompt_submit"}]}
    ]
  }
}' > "$TMP_HOME/.codex/hooks.json"
# 同时保留旧二进制，验证 install 也会触发 cleanup 删除它
mkdir -p "$TMP_HOME/.webterm/bin"
touch "$TMP_HOME/.webterm/bin/webterm-agent-hook"
chmod +x "$TMP_HOME/.webterm/bin/webterm-agent-hook"
bash "$INSTALL_SCRIPT" >/dev/null 2>&1
refute_file_contains "$TMP_HOME/.codex/hooks.json" "webterm-agent-hook" "install 脚本清理旧 hook 配置"
if [ -e "$TMP_HOME/.webterm/bin/webterm-agent-hook" ]; then
  fail=$((fail + 1))
  echo "  ✗ install 脚本应删除旧 webterm-agent-hook 二进制"
else
  pass=$((pass + 1))
  echo "  ✓ install 脚本删除旧 webterm-agent-hook 二进制"
fi

echo ""
echo "== 测试 cleanup-old-agent-hooks.sh =="

CLEANUP_SCRIPT="$REPO_ROOT/scripts/cleanup-old-agent-hooks.sh"

# Codex：混合新旧 hook
rm -rf "$TMP_HOME/.codex"
mkdir -p "$TMP_HOME/.codex"
cat > "$TMP_HOME/.codex/hooks.json" <<EOF
{
  "hooks": {
    "UserPromptSubmit": [
      {"matcher": "*", "hooks": [{"type": "command", "command": "$abs_helper running \"Running\" codex"}]},
      {"matcher": "*", "hooks": [{"type": "command", "command": "$TMP_HOME/.webterm/bin/webterm-agent-hook codex user_prompt_submit"}]}
    ]
  }
}
EOF
bash "$CLEANUP_SCRIPT" >/dev/null 2>&1
assert_file_contains "$TMP_HOME/.codex/hooks.json" "webterm-notify-helper" "Codex 保留新 hook"
refute_file_contains "$TMP_HOME/.codex/hooks.json" "webterm-agent-hook" "Codex 移除旧 hook"

# Kimi：混合新旧 hook
rm -rf "$TMP_HOME/.kimi-code"
mkdir -p "$TMP_HOME/.kimi-code"
cat > "$TMP_HOME/.kimi-code/config.toml" <<EOF
[[hooks]]
event = "UserPromptSubmit"
matcher = ".*"
command = "$abs_helper running \"Running\" kimi"
timeout = 5

[[hooks]]
event = "PreToolUse"
matcher = ".*"
command = "$TMP_HOME/.webterm/bin/webterm-agent-hook kimi pre_tool_use"
timeout = 5
EOF
bash "$CLEANUP_SCRIPT" >/dev/null 2>&1
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" "webterm-notify-helper" "Kimi 保留新 hook"
refute_file_contains "$TMP_HOME/.kimi-code/config.toml" "webterm-agent-hook" "Kimi 移除旧 hook"

# cleanup 脚本应删除遗留的旧二进制
mkdir -p "$TMP_HOME/.webterm/bin"
legacy_bin="$TMP_HOME/.webterm/bin/webterm-agent-hook"
touch "$legacy_bin"
chmod +x "$legacy_bin"
bash "$CLEANUP_SCRIPT" >/dev/null 2>&1
if [ -e "$legacy_bin" ]; then
  fail=$((fail + 1))
  echo "  ✗ cleanup 脚本应删除旧 webterm-agent-hook 二进制"
else
  pass=$((pass + 1))
  echo "  ✓ cleanup 脚本删除旧 webterm-agent-hook 二进制"
fi

echo ""
echo "== 结果 =="
echo "通过: $pass"
echo "失败: $fail"

if [ "$fail" -gt 0 ]; then
  exit 1
fi
