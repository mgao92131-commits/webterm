#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
docs="$root/docs/cli"
mkdir -p "$docs"
render() {
  local output="$1"
  local package="$2"
  shift 2
  {
    printf '# %s\n\n' "${package#./cmd/}"
    printf '此文档由 Cobra 命令定义自动生成。退出码：0 成功或帮助，1 运行失败，2 参数或配置使用错误。\n\n'
    (cd "$root" && go run "$package" "$@" --help | sed 's/[[:space:]]*$//')
  } >"$docs/$output"
}
append() {
  local output="$1"
  local title="$2"
  local package="$3"
  shift 3
  {
    printf '\n\n## %s\n\n' "$title"
    (cd "$root" && go run "$package" "$@" --help | sed 's/[[:space:]]*$//')
  } >>"$docs/$output"
}
render webterm.md ./cmd/webterm
render webterm-send.md ./cmd/webterm send
render webterm-devices.md ./cmd/webterm devices
render webterm-notify.md ./cmd/webterm notify
render webterm-agent.md ./cmd/webterm-agent
append webterm-agent.md 'run' ./cmd/webterm-agent run
append webterm-agent.md 'config init' ./cmd/webterm-agent config init
append webterm-agent.md 'config show' ./cmd/webterm-agent config show
append webterm-agent.md 'config validate' ./cmd/webterm-agent config validate
render webterm-relay.md ./cmd/webterm-relay
append webterm-relay.md 'run' ./cmd/webterm-relay run
append webterm-relay.md 'config init' ./cmd/webterm-relay config init
append webterm-relay.md 'config validate' ./cmd/webterm-relay config validate
append webterm-relay.md 'admin create' ./cmd/webterm-relay admin create
