#!/usr/bin/env bash
set -euo pipefail

# 安装 WebTerm Agent Hook 示例配置到用户目录。
# 幂等：已安装过则跳过，已有配置会备份到 .webterm.bak。

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER_SRC="$REPO_ROOT/scripts/webterm-notify-helper.sh"

HOME_DIR="${HOME:-$(eval echo ~"$USER")}"
WEBTERM_BIN_DIR="$HOME_DIR/.webterm/bin"
HELPER_CMD="$WEBTERM_BIN_DIR/webterm-notify-helper"

CLAUDE_DIR="$HOME_DIR/.claude"
CLAUDE_SETTINGS="$CLAUDE_DIR/settings.json"
CLAUDE_BAK="$CLAUDE_SETTINGS.webterm.bak"

KIMI_DIR="$HOME_DIR/.kimi-code"
KIMI_CONFIG="$KIMI_DIR/config.toml"
KIMI_BAK="$KIMI_CONFIG.webterm.bak"

CODEX_DIR="$HOME_DIR/.codex"
CODEX_HOOKS="$CODEX_DIR/hooks.json"
CODEX_BAK="$CODEX_HOOKS.webterm.bak"

# agy（Antigravity CLI）的全局定制化根目录是 ~/.gemini/config/
AGY_DIR="$HOME_DIR/.gemini/config"
AGY_HOOKS="$AGY_DIR/hooks.json"
AGY_BAK="$AGY_HOOKS.webterm.bak"

backup_if_exists() {
  local path="$1"
  local bak="$2"
  if [ -f "$path" ] && [ ! -f "$bak" ]; then
    cp "$path" "$bak"
    echo "备份: $path -> $bak"
  fi
}

migrate_helper_path() {
  # 旧版本使用 ~/.webterm/bin，某些 agent（如 Codex）不会展开 ~，导致 hook 报 127。
  # 这里把旧路径迁移为绝对路径。
  local path="$1"
  if [ -f "$path" ] && grep -qF '~/.webterm/bin/webterm-notify-helper' "$path" 2>/dev/null; then
    sed -i.bak "s|~/.webterm/bin/webterm-notify-helper|$HELPER_CMD|g" "$path"
    rm -f "$path.bak"
    echo "  已迁移为绝对路径"
  fi
}

install_helper() {
  echo "安装 helper 脚本..."
  mkdir -p "$WEBTERM_BIN_DIR"
  cp "$HELPER_SRC" "$WEBTERM_BIN_DIR/webterm-notify-helper"
  chmod +x "$WEBTERM_BIN_DIR/webterm-notify-helper"
  echo "  -> $WEBTERM_BIN_DIR/webterm-notify-helper"

  # 把 go-core 里编译好的 webterm / webterm-agent 链接到 ~/.webterm/bin，
  # 让 helper 在没有 PATH 配置的 agent 环境里也能找到它们。
  local webterm_bin="$REPO_ROOT/go-core/webterm"
  local webterm_agent_bin="$REPO_ROOT/go-core/webterm-agent"
  if [ -x "$webterm_bin" ]; then
    ln -sf "$webterm_bin" "$WEBTERM_BIN_DIR/webterm"
    echo "  -> $WEBTERM_BIN_DIR/webterm"
  else
    echo "  警告: 未找到 $webterm_bin，请先在 go-core 目录执行 go build"
  fi
  if [ -x "$webterm_agent_bin" ]; then
    ln -sf "$webterm_agent_bin" "$WEBTERM_BIN_DIR/webterm-agent"
    echo "  -> $WEBTERM_BIN_DIR/webterm-agent"
  else
    echo "  警告: 未找到 $webterm_agent_bin，请先在 go-core 目录执行 go build"
  fi
}

install_claude() {
  echo "安装 Claude Code hook 配置..."
  mkdir -p "$CLAUDE_DIR"
  backup_if_exists "$CLAUDE_SETTINGS" "$CLAUDE_BAK"
  migrate_helper_path "$CLAUDE_SETTINGS"

  local new_hooks
  new_hooks='{
  "hooks": {
    "UserPromptSubmit": [{"matcher": "*", "hooks": [{"type": "command", "command": "__WEBTERM_HELPER__ quiet \"Running\" claude"}]}],
    "PermissionRequest": [{"matcher": "*", "hooks": [{"type": "command", "command": "__WEBTERM_HELPER__ alert \"Waiting for approval\" claude"}]}],
    "Stop": [{"matcher": "*", "hooks": [{"type": "command", "command": "__WEBTERM_HELPER__ normal \"Done\" claude"}]}],
    "StopFailure": [{"matcher": "*", "hooks": [{"type": "command", "command": "__WEBTERM_HELPER__ alert \"Failed\" claude"}]}],
    "SessionEnd": [{"matcher": "*", "hooks": [{"type": "command", "command": "__WEBTERM_HELPER__ quiet \"Session ended\" claude"}]}]
  }
}'
  new_hooks="${new_hooks//__WEBTERM_HELPER__/$HELPER_CMD}"

  if [ -f "$CLAUDE_SETTINGS" ]; then
    if ! python3 - "$CLAUDE_SETTINGS" "$new_hooks" <<'PY'
import sys, json
path, new_json = sys.argv[1], sys.argv[2]
with open(path, 'r') as f:
    existing = json.load(f)
new_hooks = json.loads(new_json).get('hooks', {})
existing_hooks = existing.setdefault('hooks', {})
for event, entries in list(existing_hooks.items()):
    existing_hooks[event] = [entry for entry in entries if 'webterm-notify-helper' not in json.dumps(entry)]
for event, entries in new_hooks.items():
    existing_hooks.setdefault(event, []).extend(entries)
with open(path, 'w') as f:
    json.dump(existing, f, indent=2)
    f.write('\n')
PY
    then
      echo "警告: 合并 $CLAUDE_SETTINGS 失败（可能是 JSON 格式问题），WebTerm hook 未写入，请检查该文件" >&2
    fi
  else
    echo "$new_hooks" > "$CLAUDE_SETTINGS"
  fi
  chmod 600 "$CLAUDE_SETTINGS"
  echo "  -> $CLAUDE_SETTINGS"
}

install_kimi() {
  echo "安装 Kimi Code hook 配置..."
  mkdir -p "$KIMI_DIR"
  backup_if_exists "$KIMI_CONFIG" "$KIMI_BAK"
  migrate_helper_path "$KIMI_CONFIG"

  local block
  block='[[hooks]]
event = "UserPromptSubmit"
matcher = ".*"
command = "__WEBTERM_HELPER__ quiet \"Running\" kimi"
timeout = 5

[[hooks]]
event = "PermissionRequest"
matcher = ".*"
command = "__WEBTERM_HELPER__ alert \"Waiting for approval\" kimi"
timeout = 5

[[hooks]]
event = "StopFailure"
matcher = ".*"
command = "__WEBTERM_HELPER__ alert \"Failed\" kimi"
timeout = 5

[[hooks]]
event = "Stop"
matcher = ".*"
command = "__WEBTERM_HELPER__ normal \"Done\" kimi"
timeout = 5

[[hooks]]
event = "Notification"
matcher = "task\\.completed"
command = "__WEBTERM_HELPER__ normal \"Done\" kimi"
timeout = 5

[[hooks]]
event = "SessionEnd"
matcher = ".*"
command = "__WEBTERM_HELPER__ quiet \"Session ended\" kimi"
timeout = 5
'
  block="${block//__WEBTERM_HELPER__/$HELPER_CMD}"

  if [ -f "$KIMI_CONFIG" ]; then
    if ! python3 - "$KIMI_CONFIG" "$block" <<'PY'
import sys
path, replacement = sys.argv[1], sys.argv[2]
text = open(path, 'r').read()
parts = text.split('[[hooks]]')
head, blocks = parts[0], parts[1:]
kept = [part for part in blocks if 'webterm-notify-helper' not in part]
with open(path, 'w') as f:
    f.write(head.rstrip())
    for part in kept:
        f.write('\n\n[[hooks]]' + part.rstrip())
    f.write('\n\n' + replacement)
    f.write('\n')
PY
    then
      echo "警告: 合并 $KIMI_CONFIG 失败，WebTerm hook 未写入，请检查该文件" >&2
    fi
  else
    printf '%s' "$block" > "$KIMI_CONFIG"
  fi
  chmod 600 "$KIMI_CONFIG"
  echo "  -> $KIMI_CONFIG"
}

install_codex() {
  echo "安装 Codex hook 配置..."
  mkdir -p "$CODEX_DIR"
  backup_if_exists "$CODEX_HOOKS" "$CODEX_BAK"
  migrate_helper_path "$CODEX_HOOKS"

  local new_hooks
  new_hooks='{
  "hooks": {
    "UserPromptSubmit": [{"matcher": "*", "hooks": [{"type": "command", "command": "__WEBTERM_HELPER__ quiet \"Running\" codex"}]}],
    "PermissionRequest": [{"matcher": "*", "hooks": [{"type": "command", "command": "__WEBTERM_HELPER__ alert \"Waiting for approval\" codex"}]}],
    "Stop": [{"matcher": "*", "hooks": [{"type": "command", "command": "__WEBTERM_HELPER__ normal \"Done\" codex"}]}]
  }
}'
  new_hooks="${new_hooks//__WEBTERM_HELPER__/$HELPER_CMD}"

  if [ -f "$CODEX_HOOKS" ]; then
    if ! python3 - "$CODEX_HOOKS" "$new_hooks" <<'PY'
import sys, json
path, new_json = sys.argv[1], sys.argv[2]
with open(path, 'r') as f:
    existing = json.load(f)
new_hooks = json.loads(new_json).get('hooks', {})
existing_hooks = existing.setdefault('hooks', {})
for event, entries in list(existing_hooks.items()):
    existing_hooks[event] = [entry for entry in entries if 'webterm-notify-helper' not in json.dumps(entry)]
for event, entries in new_hooks.items():
    existing_hooks.setdefault(event, []).extend(entries)
with open(path, 'w') as f:
    json.dump(existing, f, indent=2)
    f.write('\n')
PY
    then
      echo "警告: 合并 $CODEX_HOOKS 失败（可能是 JSON 格式问题），WebTerm hook 未写入，请检查该文件" >&2
    fi
  else
    echo "$new_hooks" > "$CODEX_HOOKS"
  fi
  chmod 600 "$CODEX_HOOKS"
  echo "  -> $CODEX_HOOKS"
}

install_agy() {
  echo "安装 agy (Antigravity) hook 配置..."
  mkdir -p "$AGY_DIR"
  backup_if_exists "$AGY_HOOKS" "$AGY_BAK"
  migrate_helper_path "$AGY_HOOKS"

  # agy 的 hooks.json 顶层 key 是 hook 名字；PreInvocation/Stop 为扁平
  # handler 列表（不需要 matcher 分组）。agy 没有 PermissionRequest /
  # SessionEnd 等价事件，Stop 的 terminationReason 统一按 normal 处理。
  local new_hooks
  new_hooks='{
  "webterm-notify": {
    "PreInvocation": [
      {"type": "command", "command": "__WEBTERM_HELPER__ quiet \"Running\" agy", "timeout": 5}
    ],
    "Stop": [
      {"type": "command", "command": "__WEBTERM_HELPER__ normal \"Done\" agy", "timeout": 5}
    ]
  }
}'
  new_hooks="${new_hooks//__WEBTERM_HELPER__/$HELPER_CMD}"

  if [ -f "$AGY_HOOKS" ]; then
    if ! python3 - "$AGY_HOOKS" "$new_hooks" <<'PY'
import sys, json
path, new_json = sys.argv[1], sys.argv[2]
with open(path, 'r') as f:
    existing = json.load(f)
for name in [k for k, v in existing.items() if 'webterm-notify-helper' in json.dumps(v)]:
    del existing[name]
existing.update(json.loads(new_json))
with open(path, 'w') as f:
    json.dump(existing, f, indent=2)
    f.write('\n')
PY
    then
      echo "警告: 合并 $AGY_HOOKS 失败（可能是 JSON 格式问题），WebTerm hook 未写入，请检查该文件" >&2
    fi
  else
    echo "$new_hooks" > "$AGY_HOOKS"
  fi
  chmod 600 "$AGY_HOOKS"
  echo "  -> $AGY_HOOKS"
}

# main
install_helper

# 先清理旧版 webterm-agent-hook，避免与新 helper 并存导致
# "flag provided but not defined: -agent" 这类错误。
CLEANUP_SCRIPT="$REPO_ROOT/scripts/cleanup-old-agent-hooks.sh"
if [ -x "$CLEANUP_SCRIPT" ]; then
  bash "$CLEANUP_SCRIPT" >/dev/null 2>&1 || true
fi

install_claude
install_kimi
install_codex
install_agy

echo ""
echo "安装完成。请确保 ~/.webterm/bin 在你的 PATH 中，或在 shell 配置文件里 export WEBTERM_BIN=/path/to/webterm。"
