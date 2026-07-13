#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/helpers/skills-sync-common.sh"

usage() {
  cat <<'EOF'
mcp-java-dev-tools install.sh

Builds TypeScript + Java agent and syncs local shipped skills into Codex, Kiro, or Claude Code skill folders.
Shipped skills include the regression, performance, project-artifact, and project-state migration workflows under `skills/mcp-java-dev-tools-*`.
This script can also apply MCP env config for Codex when --configure-mcp-env is enabled.

Usage:
  ./scripts/install.sh [options]

EOF
  usage_common
}

if ! parse_common_args "$@"; then
  code=$?
  if [[ "$code" -eq 99 ]]; then
    usage
    exit 0
  fi
  usage
  exit "$code"
fi

prompt_client_if_not_set
run_skill_sync "Install"
