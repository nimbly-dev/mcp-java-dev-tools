#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/helpers/skills-sync-common.sh"
# update.sh should not require MCP env inputs unless explicitly requested.
CONFIGURE_MCP_ENV=0

usage() {
  cat <<'EOF'
mcp-java-dev-tools update.sh

Builds TypeScript + Java agent and updates existing skills in Codex/Kiro.
Missing shipped skills are added. Existing skill folders are replaced.
This script can also apply MCP env config for Codex when --configure-mcp-env is enabled.
For Kiro only, stale installed skills matching `mcp-java-dev-tools-*` that are not present
in this repository's `skills/` folder are detected and deletion is confirmed interactively.

Usage:
  ./scripts/update.sh [options]

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
run_skill_sync "Update"
CURRENT_VERSION="$(read_package_version)"
print_jar_upgrade_note "$CURRENT_VERSION"
