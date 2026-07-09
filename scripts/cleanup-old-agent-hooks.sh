#!/usr/bin/env bash
set -euo pipefail

# 清理旧的 webterm-agent-hook 配置，只保留 webterm-notify-helper。
# 会备份原配置到 .webterm.bak。

HOME_DIR="${HOME:-$(eval echo ~"$USER")}"

CLAUDE_DIR="$HOME_DIR/.claude"
CLAUDE_SETTINGS="$CLAUDE_DIR/settings.json"
CLAUDE_BAK="$CLAUDE_SETTINGS.webterm.bak"

KIMI_DIR="$HOME_DIR/.kimi-code"
KIMI_CONFIG="$KIMI_DIR/config.toml"
KIMI_BAK="$KIMI_CONFIG.webterm.bak"

CODEX_DIR="$HOME_DIR/.codex"
CODEX_HOOKS="$CODEX_DIR/hooks.json"
CODEX_BAK="$CODEX_HOOKS.webterm.bak"

backup_if_exists() {
  local path="$1"
  local bak="$2"
  if [ -f "$path" ] && [ ! -f "$bak" ]; then
    cp "$path" "$bak"
    echo "备份: $path -> $bak"
  fi
}

cleanup_json_hooks() {
  local path="$1"
  local name="$2"
  if [ ! -f "$path" ]; then
    echo "跳过 $name（配置文件不存在）"
    return
  fi

  backup_if_exists "$path" "${path}.webterm.bak"

  local tmp
  tmp="$(mktemp)"
  python3 - "$path" "$tmp" <<'PY'
import sys, json
src, dst = sys.argv[1], sys.argv[2]
with open(src, 'r') as f:
    data = json.load(f)

hooks = data.get('hooks', {})
if not isinstance(hooks, dict):
    with open(dst, 'w') as f:
        json.dump(data, f, indent=2)
        f.write('\n')
    sys.exit(0)

removed = 0
for event, entries in list(hooks.items()):
    if not isinstance(entries, list):
        continue
    kept = []
    for entry in entries:
        if not isinstance(entry, dict):
            kept.append(entry)
            continue
        inner_hooks = entry.get('hooks', [])
        if not isinstance(inner_hooks, list):
            kept.append(entry)
            continue
        kept_inner = []
        for h in inner_hooks:
            cmd = h.get('command', '') if isinstance(h, dict) else ''
            if isinstance(cmd, str) and 'webterm-agent-hook' in cmd:
                removed += 1
                continue
            kept_inner.append(h)
        if kept_inner:
            entry['hooks'] = kept_inner
            kept.append(entry)
        else:
            removed += 1
    if kept:
        hooks[event] = kept
    else:
        del hooks[event]

data['hooks'] = hooks
with open(dst, 'w') as f:
    json.dump(data, f, indent=2)
    f.write('\n')

print(f'  移除 {removed} 个旧 hook 条目')
PY
  mv "$tmp" "$path"
  chmod 600 "$path"
  echo "  -> $path"
}

cleanup_toml_hooks() {
  local path="$1"
  local name="$2"
  if [ ! -f "$path" ]; then
    echo "跳过 $name（配置文件不存在）"
    return
  fi

  backup_if_exists "$path" "${path}.webterm.bak"

  local tmp
  tmp="$(mktemp)"
  python3 - "$path" "$tmp" <<'PY'
import sys, re
src, dst = sys.argv[1], sys.argv[2]
with open(src, 'r') as f:
    text = f.read()

# 按 [[hooks]] 块分割，保留不包含 webterm-agent-hook 的块
pattern = re.compile(r'(\[\[hooks\]\]\n.*?)(?=\n\[\[hooks\]\]|\Z)', re.DOTALL)
blocks = pattern.findall(text)

# 前缀部分（[[hooks]] 之前的内容）
prefix = pattern.split(text)[0]

kept_blocks = []
removed = 0
for block in blocks:
    if 'webterm-agent-hook' in block:
        removed += 1
        continue
    kept_blocks.append(block)

# 重新组装，确保块之间有一个空行
result = prefix.rstrip() + '\n\n' + '\n'.join(b.rstrip() for b in kept_blocks)
if kept_blocks:
    result += '\n'

with open(dst, 'w') as f:
    f.write(result)

print(f'  移除 {removed} 个旧 hook 块')
PY
  mv "$tmp" "$path"
  chmod 600 "$path"
  echo "  -> $path"
}

echo "清理 Claude Code 旧 hook..."
cleanup_json_hooks "$CLAUDE_SETTINGS" "Claude"

echo "清理 Kimi Code 旧 hook..."
cleanup_toml_hooks "$KIMI_CONFIG" "Kimi"

echo "清理 Codex 旧 hook..."
cleanup_json_hooks "$CODEX_HOOKS" "Codex"

echo ""
echo "清理完成。如需恢复，备份文件在："
echo "  $CLAUDE_BAK"
echo "  $KIMI_BAK"
echo "  $CODEX_BAK"
