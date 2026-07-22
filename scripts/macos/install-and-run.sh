#!/usr/bin/env bash
set -euo pipefail

LABEL="com.webterm.agent"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
GO_DIR="$REPO_ROOT/go-core"
HOME_DIR="${HOME:-}"
APP_DIR="$HOME_DIR/Library/Application Support/WebTerm"
BIN_DIR="$APP_DIR/bin"
LOCAL_BIN_DIR="$HOME_DIR/.local/bin"
WORKING_DIR="$HOME_DIR/Documents"
USER_NAME="${USER:-$(id -un)}"
AGENT_PATH="/usr/local/bin:/opt/homebrew/bin:$LOCAL_BIN_DIR:/usr/bin:/bin:/usr/sbin:/sbin"
PLIST="$HOME_DIR/Library/LaunchAgents/$LABEL.plist"
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

write_launch_agent() {
  local plist_tmp="$TMP_DIR/$LABEL.plist"
  mkdir -p "$HOME_DIR/Library/LaunchAgents" "$LOG_DIR"
  cat >"$plist_tmp" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$BIN_DIR/webterm-agent</string>
    <string>run</string>
    <string>--mode</string>
    <string>$MODE</string>
  </array>
  <key>WorkingDirectory</key>
  <string>$WORKING_DIR</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>HOME</key>
    <string>$HOME_DIR</string>
    <key>USER</key>
    <string>$USER_NAME</string>
    <key>LOGNAME</key>
    <string>$USER_NAME</string>
    <key>SHELL</key>
    <string>/bin/zsh</string>
    <key>PATH</key>
    <string>$AGENT_PATH</string>
    <key>LANG</key>
    <string>en_US.UTF-8</string>
    <key>LC_CTYPE</key>
    <string>UTF-8</string>
    <key>WEBTERM_AGENT_CONFIG</key>
    <string></string>
    <key>WEBTERM_AGENT_MODE</key>
    <string></string>
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ThrottleInterval</key>
  <integer>5</integer>
  <key>ProcessType</key>
  <string>Background</string>
  <key>StandardOutPath</key>
  <string>$LOG_DIR/agent.out.log</string>
  <key>StandardErrorPath</key>
  <string>$LOG_DIR/agent.err.log</string>
</dict>
</plist>
EOF
  plutil -lint "$plist_tmp" >/dev/null
  install -m 644 "$plist_tmp" "$PLIST"
}

stop_launch_agent() {
  if launchctl print "gui/$UID/$LABEL" >/dev/null 2>&1; then
    launchctl bootout "gui/$UID/$LABEL" >/dev/null 2>&1 || {
      launchctl print "gui/$UID/$LABEL" >/dev/null 2>&1 && die "无法停止旧 LaunchAgent：$LABEL"
    }
  fi
}

# relay_field 从 `webterm diagnostics state --json` 的输出中提取 relay.<field>。
# 用 JSON 解析而非 grep 人类可读文本：bool 归一化为 true/false，缺失输出空串。
relay_field() {
  python3 - "$1" "$2" <<'PY'
import json, sys

try:
    data = json.loads(sys.argv[1])
    value = data.get("relay", {}).get(sys.argv[2])
except Exception:
    sys.exit(1)
if isinstance(value, bool):
    print("true" if value else "false")
elif value is None:
    print("")
else:
    print(value)
PY
}

# verify_agent_ready 确认 Agent 不只是“进程存在”，而是真正连上了 Relay。
# launchctl print 只能证明 LaunchAgent 进程在运行；relay 模式必须轮询本地 IPC
# 的机器可读连接状态（最多 10 次、每次间隔 1 秒），凭据被拒或超时则明确失败。
verify_agent_ready() {
  [[ "$MODE" == "relay" ]] || return 0
  local attempt output
  for attempt in {1..10}; do
    if output="$("$BIN_DIR/webterm" diagnostics state --json 2>/dev/null)"; then
      if [[ "$(relay_field "$output" connected)" == "true" ]]; then
        echo "Relay 已连接"
        return 0
      fi
      if [[ "$(relay_field "$output" lastErrorKind)" == "auth_rejected" ]]; then
        die "Agent 已启动，但 Relay 凭据被拒绝。请检查 relay.json 的 credential 是否与 Relay 数据库登记一致。"
      fi
    fi
    sleep 1
  done
  die "Agent 进程已启动，但未能确认 Relay 连接成功。可运行：$BIN_DIR/webterm diagnostics state"
}

[[ "$(uname -s)" == "Darwin" ]] || die "该脚本只能在 macOS 上运行"
[[ -n "$HOME_DIR" ]] || die "无法确定 HOME"
[[ -d "$GO_DIR" ]] || die "找不到 Go 项目目录：$GO_DIR"
[[ -d "$WORKING_DIR" ]] || die "找不到默认终端工作目录：$WORKING_DIR"

# 安装包只有一个模式参数，不使用调用方残留的配置选择环境变量。
unset WEBTERM_AGENT_CONFIG WEBTERM_AGENT_MODE

require_command go
require_command git
require_command launchctl
require_command open
require_command plutil
require_command install
select_mode "$@"
# relay 模式启动后用 python3 解析 `diagnostics state --json` 判断连接是否就绪。
if [[ "$MODE" == "relay" ]]; then
  require_command python3
fi

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/webterm-macos.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

VERSION="$(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)"
COMMIT="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
BUILD_TIME="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
LDFLAGS="-s -w -X main.version=$VERSION -X main.gitCommit=$COMMIT -X main.buildTime=$BUILD_TIME"

echo "[1/6] 运行 Go 测试"
(cd "$GO_DIR" && go test ./...)

echo "[2/6] 编译 Agent 与 CLI"
(
  cd "$GO_DIR"
  go build -ldflags "$LDFLAGS" -o "$TMP_DIR/webterm-agent" ./cmd/webterm-agent
  go build -ldflags "$LDFLAGS" -o "$TMP_DIR/webterm" ./cmd/webterm
)

CONFIG_PATH="$("$TMP_DIR/webterm-agent" config path --mode "$MODE")"
if [[ ! -f "$CONFIG_PATH" ]]; then
  echo "[3/6] 创建 $MODE 配置模板"
  "$TMP_DIR/webterm-agent" config init --mode "$MODE"
  echo "正在用 TextEdit 打开配置，请填写内容、保存并关闭窗口。"
  open -W -a TextEdit "$CONFIG_PATH"
else
  echo "[3/6] 使用已有配置：$CONFIG_PATH"
fi

if ! "$TMP_DIR/webterm-agent" config validate --mode "$MODE"; then
  echo "配置校验失败，将再次打开配置文件供修改：$CONFIG_PATH" >&2
  open -W -a TextEdit "$CONFIG_PATH"
  "$TMP_DIR/webterm-agent" config validate --mode "$MODE"
fi

echo "[4/6] 停止旧 Agent"
stop_launch_agent

echo "[5/6] 安装到固定目录"
mkdir -p "$BIN_DIR"
install -m 755 "$TMP_DIR/webterm-agent" "$BIN_DIR/webterm-agent"
install -m 755 "$TMP_DIR/webterm" "$BIN_DIR/webterm"
mkdir -p "$LOCAL_BIN_DIR"
ln -sfn "$BIN_DIR/webterm" "$LOCAL_BIN_DIR/webterm"
ln -sfn "$BIN_DIR/webterm-agent" "$LOCAL_BIN_DIR/webterm-agent"
write_launch_agent

echo "[6/6] 启动 LaunchAgent"
launchctl bootstrap "gui/$UID" "$PLIST"
launchctl kickstart -k "gui/$UID/$LABEL"
launchctl print "gui/$UID/$LABEL" >/dev/null
# 进程存在不等于 Relay 已连接：relay 模式轮询连接状态确认真正就绪。
verify_agent_ready

echo
echo "Agent 已启动：$MODE"
echo "Agent：$BIN_DIR/webterm-agent"
echo "CLI：  $BIN_DIR/webterm"
echo "命令： $LOCAL_BIN_DIR/webterm"
echo "命令： $LOCAL_BIN_DIR/webterm-agent"
echo "工作目录：$WORKING_DIR"
echo "日志： $LOG_DIR/agent.out.log"
echo "诊断：$BIN_DIR/webterm diagnostics summary"
