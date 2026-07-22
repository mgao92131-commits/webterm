#!/usr/bin/env bash
set -euo pipefail

LABEL="com.webterm.agent"
HOME_DIR="${HOME:-}"
APP_DIR="$HOME_DIR/Library/Application Support/WebTerm"
BIN_DIR="$APP_DIR/bin"
LOCAL_BIN_DIR="$HOME_DIR/.local/bin"
PLIST="$HOME_DIR/Library/LaunchAgents/$LABEL.plist"

die() {
  echo "错误：$*" >&2
  exit 1
}

remove_owned_link() {
  local link="$1"
  local expected="$2"
  if [[ -L "$link" && "$(readlink "$link")" == "$expected" ]]; then
    rm -f "$link"
  fi
}

[[ "$(uname -s)" == "Darwin" ]] || die "该脚本只能在 macOS 上运行"
[[ -n "$HOME_DIR" ]] || die "无法确定 HOME"
command -v launchctl >/dev/null 2>&1 || die "找不到命令：launchctl"

if launchctl print "gui/$UID/$LABEL" >/dev/null 2>&1; then
  echo "停止 LaunchAgent：$LABEL"
  if ! launchctl bootout "gui/$UID/$LABEL" >/dev/null 2>&1; then
    launchctl print "gui/$UID/$LABEL" >/dev/null 2>&1 && die "无法停止 LaunchAgent：$LABEL"
  fi
fi

if [[ -f "$PLIST" ]]; then
  rm -f "$PLIST"
fi
rm -f "$BIN_DIR/webterm-agent" "$BIN_DIR/webterm"
remove_owned_link "$LOCAL_BIN_DIR/webterm-agent" "$BIN_DIR/webterm-agent"
remove_owned_link "$LOCAL_BIN_DIR/webterm" "$BIN_DIR/webterm"

echo "已卸载 Agent、CLI 和 LaunchAgent。"
echo "配置和日志已保留："
echo "  $HOME_DIR/Library/Application Support/WebTerm Agent"
echo "  $HOME_DIR/Library/Logs/WebTerm"
