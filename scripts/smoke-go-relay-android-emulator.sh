#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android-client"
GO_DIR="$ROOT_DIR/go-core"
ADB="${ADB:-/Users/gao/Library/Android/sdk/platform-tools/adb}"
ANDROID_HOME="${ANDROID_HOME:-/Users/gao/Library/Android/sdk}"
GOCACHE="${GOCACHE:-$GO_DIR/.gocache}"
PACKAGE="${PACKAGE:-com.webterm.mobile.new}"
ACTIVITY="${ACTIVITY:-com.webterm.mobile.MainActivity}"
RELAY_ADDR="${RELAY_ADDR:-127.0.0.1:19091}"
ANDROID_RELAY_URL="${ANDROID_RELAY_URL:-http://10.0.2.2:19091}"
RELAY_USER="${RELAY_USER:-admin}"
RELAY_PASSWORD="${RELAY_PASSWORD:-admin}"
DEVICE_NAME="${DEVICE_NAME:-Android E2E Agent}"
RUN_TERMINAL=false
EXPECT_MUX=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --terminal)
      RUN_TERMINAL=true
      shift
      ;;
    --mux)
      EXPECT_MUX=true
      shift
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

TMP_DIR="$(mktemp -d /private/tmp/webterm-android-relay-smoke.XXXXXX)"
RELAY_BIN="$TMP_DIR/webterm-relay"
AGENT_BIN="$TMP_DIR/webterm-agent"
STORE_PATH="$TMP_DIR/relay-store.json"
COOKIE_JAR="$TMP_DIR/cookies.txt"
PREFS_XML="$TMP_DIR/webterm.xml"
RELAY_LOG="$TMP_DIR/relay.log"
AGENT_LOG="$TMP_DIR/agent.log"
RELAY_PID=""
AGENT_PID=""

cleanup() {
  if [[ -n "$AGENT_PID" ]]; then
    kill "$AGENT_PID" >/dev/null 2>&1 || true
    wait "$AGENT_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "$RELAY_PID" ]]; then
    kill "$RELAY_PID" >/dev/null 2>&1 || true
    wait "$RELAY_PID" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

wait_for_http() {
  local url="$1"
  local deadline=$((SECONDS + 10))
  until curl -fsS "$url" >/dev/null 2>&1; do
    if [[ -n "$RELAY_PID" ]] && ! kill -0 "$RELAY_PID" >/dev/null 2>&1; then
      echo "relay process exited while waiting for $url" >&2
      tail -80 "$RELAY_LOG" >&2 || true
      exit 1
    fi
    if (( SECONDS >= deadline )); then
      echo "timed out waiting for $url" >&2
      exit 1
    fi
    sleep 0.2
  done
}

require_relay_port_free() {
  local host="${RELAY_ADDR%:*}"
  local port="${RELAY_ADDR##*:}"
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "relay port $RELAY_ADDR is already in use; set RELAY_ADDR to a free 127.0.0.1 port" >&2
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >&2 || true
    exit 1
  fi
  if [[ "$host" != "127.0.0.1" && "$host" != "localhost" ]]; then
    echo "warning: RELAY_ADDR host is $host; Android emulator default expects host access through 10.0.2.2" >&2
  fi
}

wait_for_ui_text() {
  local text="$1"
  local deadline=$((SECONDS + 20))
  until "$ADB" shell uiautomator dump /sdcard/webterm-window.xml >/dev/null 2>&1 \
    && "$ADB" pull /sdcard/webterm-window.xml "$TMP_DIR/window.xml" >/dev/null 2>&1 \
    && grep -q "$text" "$TMP_DIR/window.xml"; do
    if (( SECONDS >= deadline )); then
      echo "timed out waiting for Android UI text: $text" >&2
      "$ADB" shell screencap -p /sdcard/webterm-smoke-failed.png >/dev/null 2>&1 || true
      "$ADB" pull /sdcard/webterm-smoke-failed.png "$TMP_DIR/webterm-smoke-failed.png" >/dev/null 2>&1 || true
      echo "relay log:" >&2
      tail -80 "$RELAY_LOG" >&2 || true
      echo "agent log:" >&2
      tail -80 "$AGENT_LOG" >&2 || true
      exit 1
    fi
    sleep 0.5
  done
}

wait_for_terminal_stream() {
  local deadline=$((SECONDS + 15))
  local pattern='"Kind":"terminal"'
  if [[ "$EXPECT_MUX" == true ]]; then
    pattern='"Path":"/ws/sessions"'
  fi
  until curl -fsS "http://$RELAY_ADDR/debug/streams" | grep -q "$pattern"; do
    if (( SECONDS >= deadline )); then
      echo "terminal stream did not open" >&2
      curl -fsS "http://$RELAY_ADDR/debug/streams" >&2 || true
      exit 1
    fi
    sleep 0.5
  done
}

wait_for_terminal_traffic() {
  local deadline=$((SECONDS + 10))
  until curl -fsS "http://$RELAY_ADDR/debug/streams" \
    | grep -Eq '"Kind":"terminal".*"BytesIn":[1-9][0-9]*.*"BytesOut":[1-9][0-9]*'; do
    if (( SECONDS >= deadline )); then
      echo "terminal stream did not report bidirectional traffic" >&2
      curl -fsS "http://$RELAY_ADDR/debug/streams" >&2 || true
      exit 1
    fi
    sleep 0.5
  done
}

assert_single_mux_stream() {
  local streams
  streams="$(curl -fsS "http://$RELAY_ADDR/debug/streams")"
  local count
  count="$(printf '%s' "$streams" | grep -o '"Path":"/ws/sessions"' | wc -l | tr -d ' ')"
  if [[ "$count" != "1" ]]; then
    echo "expected exactly one /ws/sessions mux stream, got $count" >&2
    printf '%s\n' "$streams" >&2
    exit 1
  fi
}

echo "[1/9] checking adb device"
"$ADB" devices | grep -qE 'device$|device product:' || {
  "$ADB" devices -l
  echo "no online Android device/emulator found" >&2
  exit 1
}

echo "[2/9] building Go Relay and Agent"
(
  cd "$GO_DIR"
  GOCACHE="$GOCACHE" go build -o "$RELAY_BIN" ./cmd/webterm-relay
  GOCACHE="$GOCACHE" go build -o "$AGENT_BIN" ./cmd/webterm-agent
)

echo "[3/9] starting Go Relay"
require_relay_port_free
WEBTERM_RELAY_ADDR="$RELAY_ADDR" \
WEBTERM_RELAY_STORE_PATH="$STORE_PATH" \
WEBTERM_RELAY_BOOTSTRAP_USER="$RELAY_USER" \
WEBTERM_RELAY_BOOTSTRAP_PASSWORD="$RELAY_PASSWORD" \
"$RELAY_BIN" >"$RELAY_LOG" 2>&1 &
RELAY_PID="$!"
wait_for_http "http://$RELAY_ADDR/healthz"
if ! kill -0 "$RELAY_PID" >/dev/null 2>&1; then
  echo "relay process exited after startup" >&2
  tail -80 "$RELAY_LOG" >&2 || true
  exit 1
fi

echo "[4/9] creating relay device"
curl -fsS -c "$COOKIE_JAR" \
  -H 'content-type: application/json' \
  -d "{\"username\":\"$RELAY_USER\",\"password\":\"$RELAY_PASSWORD\"}" \
  "http://$RELAY_ADDR/api/auth/login" >/dev/null
device_response="$(curl -fsS -b "$COOKIE_JAR" \
  -H 'content-type: application/json' \
  -d "{\"deviceName\":\"$DEVICE_NAME\"}" \
  "http://$RELAY_ADDR/api/devices")"
device_id="$(printf '%s' "$device_response" | sed -n 's/.*"deviceId":"\([^"]*\)".*/\1/p')"
agent_credential="$(printf '%s' "$device_response" | sed -n 's/.*"agentSecret":"\([^"]*\)".*/\1/p')"
if [[ -z "$agent_credential" ]]; then
  agent_credential="$(printf '%s' "$device_response" | sed -n 's/.*"agentCredential":"\([^"]*\)".*/\1/p')"
fi
if [[ -z "$device_id" || -z "$agent_credential" ]]; then
  echo "failed to parse device response: $device_response" >&2
  exit 1
fi

echo "[5/9] starting Go Agent"
(
  cd "$GO_DIR"
  RELAY_URL="http://$RELAY_ADDR" \
  RELAY_SECRET="$agent_credential" \
  DEVICE_NAME="$DEVICE_NAME" \
  WEBTERM_RELAY_PROTOCOL=v2 \
  WEBTERM_CONTROL_ADDR=127.0.0.1:0 \
  WEBTERM_SHELL=/bin/sh \
  "$AGENT_BIN" --mode relay
) >"$AGENT_LOG" 2>&1 &
AGENT_PID="$!"

deadline=$((SECONDS + 10))
until curl -fsS -b "$COOKIE_JAR" "http://$RELAY_ADDR/api/devices" | grep -q '"online":true'; do
  if (( SECONDS >= deadline )); then
    echo "agent did not appear online" >&2
    tail -80 "$AGENT_LOG" >&2 || true
    exit 1
  fi
  sleep 0.2
done

echo "[6/9] building and installing Android debug APK"
(
  cd "$ANDROID_DIR"
  ANDROID_HOME="$ANDROID_HOME" ./gradlew :app:assembleDebug --no-daemon
)
"$ADB" install -r "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk" >/dev/null

echo "[7/9] injecting relay master config"
cat >"$PREFS_XML" <<XML
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="servers_list">[{&quot;id&quot;:&quot;relay_mst_e2e&quot;,&quot;name&quot;:&quot;中转服务&quot;,&quot;url&quot;:&quot;${ANDROID_RELAY_URL//\//\\/}&quot;,&quot;cookie&quot;:&quot;&quot;,&quot;username&quot;:&quot;$RELAY_USER&quot;,&quot;password&quot;:&quot;$RELAY_PASSWORD&quot;,&quot;isRelayMaster&quot;:true,&quot;isRelayDevice&quot;:false,&quot;deviceId&quot;:&quot;&quot;}]</string>
</map>
XML
"$ADB" push "$PREFS_XML" /data/local/tmp/webterm.xml >/dev/null
"$ADB" shell "run-as $PACKAGE sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/webterm.xml shared_prefs/webterm.xml && chmod 600 shared_prefs/webterm.xml'"

echo "[8/9] launching Android app and waiting for relay device"
"$ADB" logcat -c
"$ADB" shell am force-stop "$PACKAGE" >/dev/null
"$ADB" shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null
wait_for_ui_text "$DEVICE_NAME"
if "$ADB" logcat -d | grep -q 'ServerSessionMonitor.*ws/sessions'; then
  echo "unexpected legacy manager websocket probe detected" >&2
  "$ADB" logcat -d | grep 'ServerSessionMonitor.*ws/sessions' >&2
  exit 1
fi

if [[ "$RUN_TERMINAL" == true ]]; then
  echo "[9/9] creating terminal session from Android UI"
  device_tap_x="${DEVICE_TAP_X:-450}"
  device_tap_y="${DEVICE_TAP_Y:-260}"
  plus_tap_x="${PLUS_TAP_X:-1006}"
  plus_tap_y="${PLUS_TAP_Y:-120}"
  "$ADB" shell input tap "$device_tap_x" "$device_tap_y"
  wait_for_ui_text "还没有终端会话"
  "$ADB" shell input tap "$plus_tap_x" "$plus_tap_y"
  deadline=$((SECONDS + 15))
  until curl -fsS -b "$COOKIE_JAR" -H "x-device-id: $device_id" "http://$RELAY_ADDR/api/sessions" | grep -q '"id":"s'; do
    if (( SECONDS >= deadline )); then
      echo "Android UI did not create a relay session" >&2
      exit 1
    fi
    sleep 0.5
  done
  wait_for_ui_text "Terminal"
  wait_for_terminal_stream
  "$ADB" shell input text pwd
  "$ADB" shell input keyevent ENTER
  wait_for_terminal_traffic
  if [[ "$EXPECT_MUX" == true ]] && curl -fsS "http://$RELAY_ADDR/debug/streams" | grep -q '"/ws/terminal/'; then
    echo "unexpected legacy /ws/terminal stream while expecting mux" >&2
    curl -fsS "http://$RELAY_ADDR/debug/streams" >&2 || true
    exit 1
  fi
  if [[ "$EXPECT_MUX" == true ]]; then
    assert_single_mux_stream
  fi
else
  echo "[9/9] skipping optional terminal UI smoke"
fi

echo "android relay emulator smoke ok"
