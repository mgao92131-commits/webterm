#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
GO_DIR="$REPO_ROOT/go-core"
HOME_DIR="${HOME:-}"
APP_DIR="$HOME_DIR/Library/Application Support/WebTerm"
BIN_DIR="$APP_DIR/bin"
LOCAL_BIN_DIR="$HOME_DIR/.local/bin"
WORKING_DIR="$HOME_DIR/Documents"
LOG_DIR="$HOME_DIR/Library/Logs/WebTerm"

die() {
  echo "错误：$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "找不到命令：$1"
}

select_mode() {
  if [[ $# -gt 1 ]]; then
    die "只接受一个可选参数：direct 或 relay"
  fi
  if [[ $# -eq 1 ]]; then
    case "$1" in
      direct|relay) MODE="$1" ;;
      *) die "运行模式必须是 direct 或 relay" ;;
    esac
    return
  fi

  [[ -t 0 ]] || die "非交互环境必须指定运行模式：$0 direct 或 $0 relay"
  echo "请选择 Agent 运行模式："
  echo "  1) Direct 直连"
  echo "  2) Relay 中转"
  local choice
  read -r -p "请输入选项 [1-2]: " choice || die "未选择运行模式"
  case "$choice" in
    1) MODE="direct" ;;
    2) MODE="relay" ;;
    *) die "无效选项：$choice" ;;
  esac
}

[[ "$(uname -s)" == "Darwin" ]] || die "该脚本只能在 macOS 上运行"
[[ -n "$HOME_DIR" ]] || die "无法确定 HOME"
[[ -d "$GO_DIR" ]] || die "找不到 Go 项目目录：$GO_DIR"
[[ -d "$WORKING_DIR" ]] || die "找不到默认终端工作目录：$WORKING_DIR"

# 安装包只有一个模式参数，不使用调用方残留的配置选择环境变量。
unset WEBTERM_AGENT_CONFIG WEBTERM_AGENT_MODE WEBTERM_IPC_ENDPOINT WEBTERM_SOCKET_PATH

require_command go
require_command git
require_command open
require_command install
select_mode "$@"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/webterm-macos.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

VERSION="$(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)"
COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain=v1 2>/dev/null)" ]]; then
  GIT_DIRTY=true
  SOURCE_TREE_HASH="$(
    {
      git -C "$REPO_ROOT" diff --binary HEAD
      git -C "$REPO_ROOT" status --porcelain=v1
      git -C "$REPO_ROOT" ls-files -co --exclude-standard |
        while IFS= read -r source_file; do
          (cd "$REPO_ROOT" && shasum -a 256 "$source_file")
        done
    } | shasum -a 256 | awk '{print $1}'
  )"
else
  GIT_DIRTY=false
  SOURCE_TREE_HASH="$(printf '%s' "$COMMIT" | shasum -a 256 | awk '{print $1}')"
fi
PROTOCOL_SCHEMA_HASH="$(shasum -a 256 "$REPO_ROOT/shared/proto/terminal_screen_v2.proto" | awk '{print $1}')"
BUILD_TIME="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
LDFLAGS="-s -w -X main.version=$VERSION -X main.gitCommit=$COMMIT -X main.gitDirty=$GIT_DIRTY -X main.sourceTreeHash=$SOURCE_TREE_HASH -X main.buildTime=$BUILD_TIME -X main.buildVariant=webterm_capture -X main.protocolSchemaHash=$PROTOCOL_SCHEMA_HASH"

echo "[1/4] 运行 Go 测试"
(cd "$GO_DIR" && go test ./...)

echo "[2/4] 编译 Agent 与 CLI"
(
  cd "$GO_DIR"
  go build -tags webterm_capture -ldflags "$LDFLAGS" -o "$TMP_DIR/webterm-agent" ./cmd/webterm-agent
  go build -ldflags "$LDFLAGS" -o "$TMP_DIR/webterm" ./cmd/webterm
)

CONFIG_PATH="$("$TMP_DIR/webterm-agent" config path --mode "$MODE")"
if [[ ! -f "$CONFIG_PATH" ]]; then
  echo "[3/4] 创建 $MODE 配置模板"
  "$TMP_DIR/webterm-agent" config init --mode "$MODE"
  echo "正在用 TextEdit 打开配置，请填写内容、保存并关闭窗口。"
  open -W -a TextEdit "$CONFIG_PATH"
else
  echo "[3/4] 使用已有配置：$CONFIG_PATH"
fi

if ! "$TMP_DIR/webterm-agent" config validate --mode "$MODE"; then
  echo "配置校验失败，将再次打开配置文件供修改：$CONFIG_PATH" >&2
  open -W -a TextEdit "$CONFIG_PATH"
  "$TMP_DIR/webterm-agent" config validate --mode "$MODE"
fi

echo "[4/4] 安装到固定目录"
mkdir -p "$BIN_DIR"
install -m 755 "$TMP_DIR/webterm-agent" "$BIN_DIR/webterm-agent"
install -m 755 "$TMP_DIR/webterm" "$BIN_DIR/webterm"
mkdir -p "$LOCAL_BIN_DIR"
ln -sfn "$BIN_DIR/webterm" "$LOCAL_BIN_DIR/webterm"
ln -sfn "$BIN_DIR/webterm-agent" "$LOCAL_BIN_DIR/webterm-agent"

echo
echo "安装完成！"
echo "Agent：$BIN_DIR/webterm-agent"
echo "CLI：  $BIN_DIR/webterm"
echo "命令： $LOCAL_BIN_DIR/webterm"
echo "命令： $LOCAL_BIN_DIR/webterm-agent"
echo "工作目录：$WORKING_DIR"
echo "日志目录：$LOG_DIR"
echo
echo "前台启动 Agent 命令："
echo "  webterm-agent run --mode $MODE"
