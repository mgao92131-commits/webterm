#!/usr/bin/env bash
set -euo pipefail

# 在同一 AVD、同一 fixture、同一测试源码下比较两个 git 提交。
# 用法：
#   tools/android-renderer/run-round1-benchmark.sh <base-sha> <head-sha>

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <base-sha> <head-sha>" >&2
  exit 2
fi

repo_root="$(git rev-parse --show-toplevel)"
base_sha="$1"
head_sha="$2"
harness_sha="$(git rev-parse HEAD)"
fixture_path="android-client/terminal-renderer/src/androidTest/java/com/webterm/terminal/renderer/RendererPerformanceFixtures.java"
test_path="android-client/terminal-renderer/src/androidTest/java/com/webterm/terminal/renderer/RendererRound1CommonBenchmarkTest.java"
sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

if [[ -z "$sdk_dir" ]]; then
  sdk_dir="${HOME}/Library/Android/sdk"
fi
if [[ ! -d "$sdk_dir" ]]; then
  echo "Android SDK directory not found: $sdk_dir" >&2
  exit 1
fi

work_root="$(mktemp -d "${TMPDIR:-/tmp}/webterm-round1-benchmark.XXXXXX")"
cleanup() {
  git -C "$repo_root" worktree remove --force "$work_root/base" >/dev/null 2>&1 || true
  git -C "$repo_root" worktree remove --force "$work_root/head" >/dev/null 2>&1 || true
  rm -rf "$work_root"
}
trap cleanup EXIT

prepare_worktree() {
  local label="$1"
  local sha="$2"
  local worktree="$work_root/$label"
  local fixture_target="$worktree/$fixture_path"
  local test_target="$worktree/$test_path"

  git -C "$repo_root" worktree add --detach "$worktree" "$sha" >/dev/null
  mkdir -p "$(dirname "$fixture_target")" "$(dirname "$test_target")"
  git -C "$repo_root" show "$harness_sha:$fixture_path" > "$fixture_target"
  git -C "$repo_root" show "$harness_sha:$test_path" > "$test_target"
  printf 'sdk.dir=%s\n' "$sdk_dir" > "$worktree/android-client/local.properties"

  echo "=== $label: $sha (harness $harness_sha) ==="
  (
    cd "$worktree/android-client"
    ./gradlew :terminal-renderer:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.webterm.terminal.renderer.RendererRound1CommonBenchmarkTest \
      -Pandroid.testInstrumentationRunnerArguments.webtermPerf=true \
      -Pandroid.testInstrumentationRunnerArguments.webtermPerfLabel="$label" \
      --no-daemon --console=plain
  )

  find "$worktree/android-client/terminal-renderer/build/outputs/androidTest-results/connected/debug" \
    -type f -name 'logcat-*.txt' -print0 \
    | xargs -0 rg 'System.out: \{"target"' || true
}

prepare_worktree base "$base_sha"
prepare_worktree head "$head_sha"
