#!/bin/bash
set -euo pipefail

# Read JSON payload from stdin
DATA=$(cat)

# Extract fields using jq
# 优先用 notification.level，没有再回退到 idle
# CWD 优先用 .cwd，兼容旧的 .workspace.current_dir
eval $(echo "$DATA" | jq -r '
  "STATE=\"\(.notification.level // .agent_state // "idle")\""
  "CWD=\"\(.cwd // .workspace.current_dir // "")\""
' 2>/dev/null || echo 'STATE="idle" CWD=""')

# Try to extract CitC workspace name from CWD
if [ -n "$CWD" ]; then
  if [[ "$CWD" =~ /google/src/cloud/[^/]+/([^/]+) ]]; then
    WORKSPACE="${BASH_REMATCH[1]}"
  else
    WORKSPACE=$(basename "$CWD")
  fi
else
  WORKSPACE="unknown"
fi

# Map notification level to emoji
case "$STATE" in
  idle)    EMOJI="😴" ;;
  running|working) EMOJI="🏃" ;;
  error)   EMOJI="⚠️" ;;
  *)       EMOJI="🤖" ;;
esac

TITLE="$EMOJI $STATE | $WORKSPACE"

echo "$TITLE"
