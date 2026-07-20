#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
JQ="${JQ:-jq}"
PYTHON="${PYTHON:-python3}"
ADB_SERIAL="${ADB_SERIAL:-}"
PACKAGE="${PACKAGE:-com.webterm.mobile.c2}"
SESSION_ID="${SESSION_ID:-}"
TITLE_CONTAINS="${TITLE_CONTAINS:-}"
ITERATIONS="${ITERATIONS:-3}"
RECONNECT_WAIT_SECONDS="${RECONNECT_WAIT_SECONDS:-65}"
RECOVERY_TIMEOUT_SECONDS="${RECOVERY_TIMEOUT_SECONDS:-12}"
MAX_CLIENTS="${MAX_CLIENTS:-1}"
FAULT_COMMAND="${FAULT_COMMAND:-}"
MODE="exercise"
DRY_RUN=false

usage() {
  cat <<'EOF'
复现 Android 经 Relay 重连后返回列表、重开终端卡死的问题，并保存完整证据。

用法：
  scripts/repro-relay-android-resume-stuck.sh [选项]

选项：
  --check-only       只检查当前会话状态并取证，不操作手机 UI
  --exercise         执行重连等待、返回列表和重开流程（默认）
  --dry-run          打印将执行的 UI 操作，不点击手机
  --self-test        运行脚本内的 JSON/XML 解析测试
  --session ID       记录目标会话 ID（可选，仅用于证据标识）
  --title TEXT       用于匹配 Android 卡片的标题片段
  --iterations N     重复次数，默认 3
  --wait SECONDS     每轮保持终端打开、等待自然重连的时间，默认 65 秒
  --help             显示帮助

常用环境变量：
  ADB_SERIAL         adb devices 中的设备序列号；多设备时必须指定
  PACKAGE            Android 包名，默认 com.webterm.mobile.c2
  FAULT_COMMAND      可选故障注入命令；设置后替代自然重连等待
  ARTIFACT_DIR       证据目录；默认 /private/tmp/webterm-relay-resume-时间戳

示例：
  ADB_SERIAL=emulator-5554 SESSION_ID=s5 \
    scripts/repro-relay-android-resume-stuck.sh --check-only

  ADB_SERIAL=emulator-5554 SESSION_ID=s5 ITERATIONS=20 \
    RECONNECT_WAIT_SECONDS=65 \
    scripts/repro-relay-android-resume-stuck.sh --exercise

注意：FAULT_COMMAND 会通过 /bin/bash -lc 执行，仅应传入可信的本机测试命令。
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check-only) MODE="check"; shift ;;
    --exercise) MODE="exercise"; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    --self-test) MODE="self-test"; shift ;;
    --session) SESSION_ID="${2:?--session requires a value}"; shift 2 ;;
    --title) TITLE_CONTAINS="${2:?--title requires a value}"; shift 2 ;;
    --iterations) ITERATIONS="${2:?--iterations requires a value}"; shift 2 ;;
    --wait) RECONNECT_WAIT_SECONDS="${2:?--wait requires a value}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

timestamp() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

is_non_negative_integer() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

find_card_center() {
  local xml_file="$1"
  local title="$2"
  "$PYTHON" - "$xml_file" "$title" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
needle = sys.argv[2]
parents = {child: parent for parent in root.iter() for child in parent}
exact = [node for node in root.iter("node") if needle == node.attrib.get("text", "")]
matches = exact if len(exact) == 1 else [
    node for node in root.iter("node") if needle in node.attrib.get("text", "")
]
if len(matches) != 1:
    raise SystemExit(f"expected one title node containing {needle!r}, got {len(matches)}")
node = matches[0]
while node is not None and node.attrib.get("clickable") != "true":
    node = parents.get(node)
if node is None:
    raise SystemExit("matching title has no clickable ancestor")
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
if not match:
    raise SystemExit("clickable card has invalid bounds")
x1, y1, x2, y2 = map(int, match.groups())
print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
PY
}

run_self_test() {
  local tmp
  tmp="$(mktemp -d /private/tmp/webterm-resume-script-test.XXXXXX)"
  trap 'rm -rf "$tmp"' RETURN
  printf '%s\n' '<?xml version="1.0"?><hierarchy><node clickable="true" bounds="[32,777][1048,956]"><node text="Starting | target-plan | main" clickable="false" bounds="[69,809][1011,860]"/></node></hierarchy>' >"$tmp/window.xml"
  [[ "$(find_card_center "$tmp/window.xml" target-plan)" == "540 866" ]]
  echo "repro relay Android resume script self-test ok"
}

if [[ "$MODE" == "self-test" ]]; then
  run_self_test
  exit 0
fi

for value in "$ITERATIONS" "$RECONNECT_WAIT_SECONDS" "$RECOVERY_TIMEOUT_SECONDS" "$MAX_CLIENTS"; do
  if ! is_non_negative_integer "$value"; then
    echo "numeric option is not a non-negative integer: $value" >&2
    exit 2
  fi
done
if [[ -z "$TITLE_CONTAINS" && -z "$SESSION_ID" ]]; then
  TITLE_CONTAINS="android-go-authoritative-terminal-rendering-plan"
fi

for command in curl "$JQ" "$PYTHON"; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "required command not found: $command" >&2
    exit 1
  }
done
[[ -x "$ADB" ]] || {
  echo "adb is not executable: $ADB" >&2
  exit 1
}

resolve_device() {
  if [[ -n "$ADB_SERIAL" ]]; then
    "$ADB" -s "$ADB_SERIAL" get-state 2>/dev/null | grep -qx device || {
      echo "ADB device is not online: $ADB_SERIAL" >&2
      exit 1
    }
    return
  fi
  local mapfile_output
  local device_count
  mapfile_output="$($ADB devices | awk 'NR > 1 && $2 == "device" {print $1}')"
  device_count="$(printf '%s\n' "$mapfile_output" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [[ "$device_count" != "1" ]]; then
    echo "expected exactly one online ADB device, got $device_count; set ADB_SERIAL" >&2
    "$ADB" devices -l >&2
    exit 1
  fi
  ADB_SERIAL="$mapfile_output"
}

resolve_device
ARTIFACT_DIR="${ARTIFACT_DIR:-/private/tmp/webterm-relay-resume-$(date '+%Y%m%d-%H%M%S')}"
mkdir -p "$ARTIFACT_DIR"
EVENTS_FILE="$ARTIFACT_DIR/events.jsonl"

adb_cmd() {
  "$ADB" -s "$ADB_SERIAL" "$@"
}

record_event() {
  local event="$1"
  local iteration="${2:-0}"
  "$JQ" -nc --arg time "$(timestamp)" --arg event "$event" \
    --arg session "$SESSION_ID" --argjson iteration "$iteration" \
    '{time:$time,event:$event,sessionId:$session,iteration:$iteration}' >>"$EVENTS_FILE"
}

dump_ui() {
  local output_file="$1"
  adb_cmd shell uiautomator dump /sdcard/webterm-resume-window.xml >/dev/null
  adb_cmd pull /sdcard/webterm-resume-window.xml "$output_file" >/dev/null
}

capture_artifacts() {
  local label="$1"
  local dir="$ARTIFACT_DIR/$label"
  mkdir -p "$dir"
  adb_cmd logcat -d >"$dir/android-logcat.txt" 2>&1 || true
  adb_cmd shell dumpsys activity activities >"$dir/activities.txt" 2>&1 || true
  adb_cmd shell uiautomator dump /sdcard/webterm-resume-window.xml >/dev/null 2>&1 || true
  adb_cmd pull /sdcard/webterm-resume-window.xml "$dir/window.xml" >/dev/null 2>&1 || true
  adb_cmd shell screencap -p /sdcard/webterm-resume-screen.png >/dev/null 2>&1 || true
  adb_cmd pull /sdcard/webterm-resume-screen.png "$dir/screenshot.png" >/dev/null 2>&1 || true
}

fail() {
  local reason="$1"
  local label="failure-${2:-preflight}"
  local event_iteration="${2:-0}"
  if ! is_non_negative_integer "$event_iteration"; then
    event_iteration=0
  fi
  echo "FAILED: $reason" >&2
  record_event "failure:$reason" "$event_iteration" || true
  capture_artifacts "$label"
  echo "artifacts: $ARTIFACT_DIR" >&2
  exit 1
}

SESSION_TITLE="$TITLE_CONTAINS"

wait_for_card_and_get_center() {
  local iteration="$1"
  local deadline=$((SECONDS + RECOVERY_TIMEOUT_SECONDS))
  local xml="$ARTIFACT_DIR/window-$iteration.xml"
  while (( SECONDS < deadline )); do
    if dump_ui "$xml" 2>/dev/null; then
      local center
      if center="$(find_card_center "$xml" "$TITLE_CONTAINS" 2>/dev/null)"; then
        printf '%s\n' "$center"
        return 0
      fi
    fi
    sleep 0.4
  done
  return 1
}

wait_for_android_recovery() {
  local iteration="$1"
  local deadline=$((SECONDS + RECOVERY_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    local logs
    logs="$(adb_cmd logcat -d -s ScreenResume:I '*:S' 2>/dev/null || true)"
    # 已发布 APK 的旧日志格式没有 sync_state 字段；事件名才是跨版本稳定契约。
    # ScreenResume 是进程级指标且没有 sessionId，因此其它后台 runtime 可能同时
    # 产生 sync_timeout。只有目标打开窗口内始终没有成功事件时，timeout 才能作为
    # 本轮失败信号；目标隔离性由后续 clients 和 projected 断言补足。
    if grep -Eq 'event=(snapshot|exact_resume|cumulative_patch)' <<<"$logs"; then
      return 0
    fi
    if grep -q 'event=sync_timeout' <<<"$logs"; then
      return 2
    fi
    sleep 0.4
  done
  return 1
}

echo "target device:  $ADB_SERIAL"
echo "target package: $PACKAGE"
echo "target session: $SESSION_ID"
echo "target title:   $SESSION_TITLE"
echo "artifacts:      $ARTIFACT_DIR"
record_event "start"
capture_artifacts "preflight"

if [[ "$MODE" == "check" ]]; then
  record_event "check-ok"
  echo "relay Android resume check ok"
  exit 0
fi

if [[ "$DRY_RUN" == true ]]; then
  echo "dry-run: would perform $ITERATIONS reopen cycles"
  echo "dry-run: each cycle waits $RECONNECT_WAIT_SECONDS seconds before BACK and reopening the card"
  [[ -n "$FAULT_COMMAND" ]] && echo "dry-run: FAULT_COMMAND would replace the natural reconnect wait"
  record_event "dry-run-ok"
  exit 0
fi

adb_cmd shell pm path "$PACKAGE" >/dev/null 2>&1 || \
  fail "Android package is not installed: $PACKAGE"

# exercise 可以从终端页或会话列表页开始。若当前正位于列表页，先打开目标卡片，
# 确保故障等待窗口覆盖的是真实终端连接，而不是空闲的列表页面。
initial_xml="$ARTIFACT_DIR/window-initial.xml"
if dump_ui "$initial_xml" 2>/dev/null \
  && initial_center="$(find_card_center "$initial_xml" "$TITLE_CONTAINS" 2>/dev/null)"; then
  read -r initial_x initial_y <<<"$initial_center"
  echo "opening target card before the first reconnect window at $initial_x,$initial_y"
  adb_cmd logcat -c
  adb_cmd shell input tap "$initial_x" "$initial_y"
  if wait_for_android_recovery 0; then
    :
  else
    initial_status="$?"
    if [[ "$initial_status" == "2" ]]; then
      fail "Android reported sync_timeout during initial open" "initial"
    fi
    fail "Android did not complete the initial open within ${RECOVERY_TIMEOUT_SECONDS}s" "initial"
  fi
fi

for ((iteration = 1; iteration <= ITERATIONS; iteration++)); do
  echo "[$iteration/$ITERATIONS] keeping terminal open until reconnect/fault"
  record_event "iteration-start" "$iteration"
  if [[ -n "$FAULT_COMMAND" ]]; then
    /bin/bash -lc "$FAULT_COMMAND" >"$ARTIFACT_DIR/fault-$iteration.log" 2>&1 || \
      fail "fault command failed" "$iteration"
  else
    sleep "$RECONNECT_WAIT_SECONDS"
  fi
  # 保留故障/自然重连窗口的原始日志；后续为了精确识别重开事件会清空 logcat。
  adb_cmd logcat -d >"$ARTIFACT_DIR/reconnect-window-$iteration.log" 2>&1 || true

  echo "[$iteration/$ITERATIONS] returning to session cards"
  adb_cmd shell input keyevent BACK
  center="$(wait_for_card_and_get_center "$iteration")" || \
    fail "target session card did not become clickable" "$iteration"
  read -r tap_x tap_y <<<"$center"

  echo "[$iteration/$ITERATIONS] reopening target card at $tap_x,$tap_y"
  adb_cmd logcat -c
  adb_cmd shell input tap "$tap_x" "$tap_y"
  if wait_for_android_recovery "$iteration"; then
    :
  else
    recovery_status="$?"
    if [[ "$recovery_status" == "2" ]]; then
      fail "Android reported sync_timeout while reopening" "$iteration"
    fi
    fail "Android did not complete resume within ${RECOVERY_TIMEOUT_SECONDS}s" "$iteration"
  fi
  capture_artifacts "iteration-$iteration"
  record_event "iteration-ok" "$iteration"
done

record_event "success"
echo "relay Android resume exercise ok ($ITERATIONS iterations)"
echo "artifacts: $ARTIFACT_DIR"
