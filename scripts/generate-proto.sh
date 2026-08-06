#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROTOS_DIR="$ROOT/shared/proto"
GO_V3_OUT_DIR="$ROOT/go-core/internal/screenprotocol/generatedv3"
JAVA_OUT_DIR="$ROOT/android-client/terminal-protocol/src/main/java"
PROTOC="${PROTOC:-$ROOT/tools/protoc/bin/protoc}"

# 随仓库携带的 protoc 仅支持 macOS arm64；无法执行时（如 Linux CI）回退到
# PATH 中的 protoc，调用方需保证版本一致（生成物含 protoc 版本戳）。
if ! "$PROTOC" --version >/dev/null 2>&1; then
  if command -v protoc >/dev/null 2>&1; then
    PROTOC="$(command -v protoc)"
  fi
fi

if [[ ! -x "$PROTOC" ]] || ! "$PROTOC" --version >/dev/null 2>&1; then
  echo "protoc not found at $PROTOC" >&2
  echo "Download it from https://github.com/protocolbuffers/protobuf/releases" >&2
  exit 1
fi

if ! command -v protoc-gen-go >/dev/null 2>&1; then
  echo "protoc-gen-go not found in PATH" >&2
  echo "Install with: go install google.golang.org/protobuf/cmd/protoc-gen-go@latest" >&2
  exit 1
fi

PROTO_FILES=(
  "$PROTOS_DIR/terminal_screen_v3.proto"
  "$PROTOS_DIR/terminal_history.proto"
)

# Go screen.v3 + history
echo "Generating Go screen.v3 / history code..."
mkdir -p "$GO_V3_OUT_DIR"
rm -rf "$GO_V3_OUT_DIR"/*

"$PROTOC" \
  --proto_path="$ROOT" \
  --go_out="$GO_V3_OUT_DIR" \
  --go_opt=paths=source_relative \
  "${PROTO_FILES[@]}"

mv "$GO_V3_OUT_DIR/shared/proto/"*.pb.go "$GO_V3_OUT_DIR/"
rm -rf "$GO_V3_OUT_DIR/shared"

echo "  -> $GO_V3_OUT_DIR/*.pb.go"

# Java (Android)
echo "Generating Java code..."
mkdir -p "$JAVA_OUT_DIR"
# 仅删除当前生成文件，保留手写 mapper/validator。
rm -f \
  "$JAVA_OUT_DIR/com/webterm/terminal/protocol/generated/TerminalScreenV3Proto.java" \
  "$JAVA_OUT_DIR/com/webterm/terminal/protocol/generated/TerminalHistoryProto.java" \
  "$JAVA_OUT_DIR/com/webterm/terminal/protocol/generated/TerminalScreenV2Proto.java"

"$PROTOC" \
  --proto_path="$ROOT" \
  --java_out=lite:"$JAVA_OUT_DIR" \
  "${PROTO_FILES[@]}"

# protoc Java 输出的个别空行/泛型声明会带行尾空格，保持 git diff --check 可重现。
perl -pi -e 's/[ \t]+$//' \
  "$JAVA_OUT_DIR/com/webterm/terminal/protocol/generated/TerminalScreenV3Proto.java" \
  "$JAVA_OUT_DIR/com/webterm/terminal/protocol/generated/TerminalHistoryProto.java"

echo "  -> $JAVA_OUT_DIR/com/webterm/terminal/protocol/generated/TerminalScreenV3Proto.java"
echo "  -> $JAVA_OUT_DIR/com/webterm/terminal/protocol/generated/TerminalHistoryProto.java"

echo "Done."
