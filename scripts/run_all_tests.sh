#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[1/2] Go 全量测试"
(cd "$ROOT_DIR/go-core" && go test ./...)

echo "[2/2] Android JVM 测试"
(cd "$ROOT_DIR/android-client" && ./gradlew test --no-daemon)

echo "all tests passed"
