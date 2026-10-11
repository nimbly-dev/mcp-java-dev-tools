#!/usr/bin/env bash
set -euo pipefail

TEST_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$TEST_ROOT/scripts/helpers/skills-sync-common.sh"

test_directory="$(mktemp -d)"
cleanup() {
  rm -f -- "$test_directory/path with spaces/server.jar" "$test_directory/codex/config.toml"
  rmdir -- "$test_directory/path with spaces" "$test_directory/codex" "$test_directory"
}
trap cleanup EXIT
mkdir -p "$test_directory/path with spaces"
touch "$test_directory/path with spaces/server.jar"

check_configuration() {
  local expected="$1"
  local actual
  actual="$(native_absolute_server_jar_path)"
  [[ "$actual" == "$expected" ]] || {
    printf 'Expected native absolute path %s; got %s\n' "$expected" "$actual" >&2
    return 1
  }
  # Keep the real client configuration untouched.
  (
    parse_common_args --codex-home "$test_directory/codex"
    apply_codex_mcp_config >/dev/null
    grep -Fq "args = [\"-jar\", \"$(escape_toml_basic_string "$expected")\"]" "$CODEX_HOME/config.toml"
  )
  CLIENT=codex
  print_mcp_config | grep -Fq "args = [\"-jar\", \"$(escape_toml_basic_string "$expected")\"]"
  for CLIENT in claude kiro; do
    print_mcp_config | grep -Fq "\"args\": [\"-jar\", \"$(escape_json_string "$expected")\"]"
  done
}

MCP_SERVER_JAR_INPUT="$test_directory/path with spaces/server.jar"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) expected="$(cygpath -am -- "$MCP_SERVER_JAR_INPUT")" ;;
  *) expected="$(cd "$test_directory/path with spaces" && pwd -P)/server.jar" ;;
esac
check_configuration "$expected"

MCP_SERVER_JAR_INPUT="$expected"
check_configuration "$expected"

cd "$test_directory"
MCP_SERVER_JAR_INPUT="path with spaces/server.jar"
check_configuration "$expected"
printf 'PASS: native, shell, and relative paths with spaces; Codex TOML and Claude/Kiro JSON\n'
