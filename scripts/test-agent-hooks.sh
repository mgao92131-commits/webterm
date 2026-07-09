#!/usr/bin/env bash
set -euo pipefail

# 测试 agent hook 安装脚本和 notify helper。
# 使用临时 HOME 目录，避免污染真实配置。

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_SCRIPT="$REPO_ROOT/scripts/install-agent-hook-examples.sh"
HELPER_SCRIPT="$REPO_ROOT/scripts/webterm-notify-helper.sh"

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

# 缺少参数
test "$($HELPER_SCRIPT 2>&1; echo $?)" != "0" && pass=$((pass + 1)) || fail=$((fail + 1))
echo "  ✓ 缺少参数时退出非零"

# 非法 level
$HELPER_SCRIPT invalid "msg" src >/dev/null 2>&1 && fail=$((fail + 1)) || pass=$((pass + 1))
echo "  ✓ 非法 level 时退出非零"

# 空 stdin 时使用默认消息
clear_calls
$HELPER_SCRIPT running "Running" claude </dev/null
assert_contains "notify --level running --message Running --source claude" "$(last_call)" "空 stdin 时使用默认消息"

# 从顶层 prompt 字段提取
clear_calls
echo '{"prompt":"deploy to production"}' | $HELPER_SCRIPT running "Running" claude
assert_contains "--message deploy to production" "$(last_call)" "提取顶层 prompt"

# 从 messages 数组提取最后一条
clear_calls
echo '{"messages":[{"role":"user","content":"hello"},{"role":"assistant","content":"done"}]}' | $HELPER_SCRIPT running "Running" claude
assert_contains "--message done" "$(last_call)" "提取 messages 数组最后一条"

# 从 tool_input 提取
clear_calls
echo '{"tool_input":{"command":"git status"}}' | $HELPER_SCRIPT running "Running" claude
assert_contains "--message git status" "$(last_call)" "提取 tool_input.command"

# 超长文本截断到 60 字符
clear_calls
long_msg=$(python3 -c 'print("a"*100)')
echo "{\"prompt\":\"$long_msg\"}" | $HELPER_SCRIPT running "Running" claude
call_len=$(last_call | sed 's/.*--message //;s/ --source.*//' | tr -d '\n' | wc -c | tr -d '[:space:]')
assert_eq "60" "$call_len" "超长消息截断为 60 字符"

# 无效 JSON 回退到默认消息
clear_calls
echo 'not json' | $HELPER_SCRIPT error "Waiting for approval" kimi
assert_contains "--message Waiting for approval" "$(last_call)" "无效 JSON 回退默认消息"

# idle 状态
clear_calls
$HELPER_SCRIPT idle "Idle" codex </dev/null
assert_contains "notify --level idle --message Idle --source codex" "$(last_call)" "idle 状态调用正确"

# webterm 缺失时不应中断 agent（退出 0）
unset WEBTERM_BIN
result=$($HELPER_SCRIPT running "Running" claude </dev/null >/dev/null 2>&1; echo $?)
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
HOME="$TMP_HOME" "$helper_tmp_dir/webterm-notify-helper" running "Running" claude </dev/null
export WEBTERM_BIN="$MOCK_WEBTERM_DIR/webterm"
assert_contains "notify --level running --message Running --source claude" "$(last_call)" "helper 在同目录找到 webterm"

# helper 不再自己解析 session/socket；没有 env 时直接调用 webterm notify，由后端通过 PID 解析。
mkdir -p "$TMP_HOME/.webterm"
clear_calls
HOME="$TMP_HOME" env -u WEBTERM_SESSION_ID -u WEBTERM_SOCKET_PATH "$HELPER_SCRIPT" running "Running" claude </dev/null
assert_contains "notify --level running --message Running --source claude" "$(last_call)" "无 env 时 helper 仍调用 webterm notify"
session_line=$(tail -n 3 "$WEBTERM_CALLS" | head -n 1)
assert_eq "SESSION_ID=<unset>" "$session_line" "helper 不伪造 SESSION_ID"

echo ""
echo "== 测试 install-agent-hook-examples.sh =="

# 首次安装
bash "$INSTALL_SCRIPT" >/dev/null 2>&1

assert_file_contains "$TMP_HOME/.webterm/bin/webterm-notify-helper" "webterm notify" "helper 已安装到 ~/.webterm/bin"
if [ -x "$REPO_ROOT/go-core/webterm" ]; then
  link_target=$(readlink "$TMP_HOME/.webterm/bin/webterm" || true)
  assert_contains "$REPO_ROOT/go-core/webterm" "$link_target" "webterm CLI 已链接到 ~/.webterm/bin"
fi
assert_file_contains "$TMP_HOME/.claude/settings.json" "webterm-notify-helper" "Claude settings 已写入"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" "webterm-notify-helper" "Kimi config 已写入"
assert_file_contains "$TMP_HOME/.codex/hooks.json" "webterm-notify-helper" "Codex hooks 已写入"

# 验证统一的事件映射
assert_file_contains "$TMP_HOME/.claude/settings.json" '"Stop"' "Claude 包含 Stop 事件"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'event = "StopFailure"' "Kimi 包含 StopFailure 事件（Kimi 实际事件名）"
assert_file_contains "$TMP_HOME/.codex/hooks.json" '"Stop"' "Codex 包含 Stop 事件"
assert_file_contains "$TMP_HOME/.kimi-code/config.toml" 'event = "PermissionRequest"' "Kimi 包含 PermissionRequest"
assert_file_contains "$TMP_HOME/.codex/hooks.json" '"SessionEnd"' "Codex 包含 SessionEnd"

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

echo ""
echo "== 结果 =="
echo "通过: $pass"
echo "失败: $fail"

if [ "$fail" -gt 0 ]; then
  exit 1
fi
