#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROTOS_DIR="$ROOT/shared/proto"
GO_OUT_DIR="$ROOT/go-core/internal/screenprotocol/generated"
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

# Go
echo "Generating Go code..."
mkdir -p "$GO_OUT_DIR"
rm -rf "$GO_OUT_DIR"/*

"$PROTOC" \
  --proto_path="$ROOT" \
  --go_out="$GO_OUT_DIR" \
  --go_opt=paths=source_relative \
  "$PROTOS_DIR/terminal_screen.proto"

# source_relative 会保留 shared/proto 目录层级，移动到目标目录后删除中间目录。
mv "$GO_OUT_DIR/shared/proto/terminal_screen.pb.go" "$GO_OUT_DIR/terminal_screen.pb.go"
rm -rf "$GO_OUT_DIR/shared"

echo "  -> $GO_OUT_DIR/terminal_screen.pb.go"

# Java (Android)
echo "Generating Java code..."
mkdir -p "$JAVA_OUT_DIR"
# 仅删除旧生成文件，保留手写 mapper/validator。
rm -f "$JAVA_OUT_DIR/com/webterm/terminal/protocol/generated/TerminalScreenProto.java"

"$PROTOC" \
  --proto_path="$ROOT" \
  --java_out="$JAVA_OUT_DIR" \
  "$PROTOS_DIR/terminal_screen.proto"

# protoc Java 输出的个别空行/泛型声明会带行尾空格，保持 git diff --check 可重现。
perl -pi -e 's/[ \t]+$//' \
  "$JAVA_OUT_DIR/com/webterm/terminal/protocol/generated/TerminalScreenProto.java"

echo "  -> $JAVA_OUT_DIR/com/webterm/terminal/protocol/generated/TerminalScreenProto.java"

echo "Done."
