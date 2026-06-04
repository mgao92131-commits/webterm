#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SERVER="$ROOT_DIR/.tools/webterm-go"

if [ ! -x "$SERVER" ]; then
  "$ROOT_DIR/go-server/scripts/build.sh" >/dev/null
fi

cd "$ROOT_DIR"
exec "$SERVER"

