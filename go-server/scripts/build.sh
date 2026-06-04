#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
GO_BIN="$ROOT_DIR/.tools/go/bin/go"

if [ ! -x "$GO_BIN" ]; then
  echo "Go toolchain not found at $GO_BIN" >&2
  echo "Install Go locally first or update GO_BIN in this script." >&2
  exit 1
fi

cd "$ROOT_DIR/go-server"
"$GO_BIN" fmt ./...
"$GO_BIN" test ./...
"$GO_BIN" build -o "$ROOT_DIR/.tools/webterm-go" ./cmd/webterm-server

echo "$ROOT_DIR/.tools/webterm-go"

