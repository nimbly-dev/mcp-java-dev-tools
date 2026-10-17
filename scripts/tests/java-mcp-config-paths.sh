#!/usr/bin/env bash
set -euo pipefail

TEST_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$TEST_ROOT/scripts/helpers/skills-sync-common.sh"

test_parent="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
test_directory="$(mktemp -d "$test_parent/mcp-java-install-test.XXXXXX")"
cleanup() {
  local resolved
  resolved="$(cd "$test_directory" && pwd -P)"
  [[ "$resolved" == "$test_parent"/mcp-java-install-test.* ]] || return 1
  rm -rf -- "$resolved"
}
trap cleanup EXIT
mkdir -p "$test_directory/path with spaces" "$test_directory/workspace with spaces"
touch "$test_directory/path with spaces/server.jar"
MCP_WORKSPACE_ROOT_INPUT="$test_directory/workspace with spaces"
native_workspace="$(native_absolute_path "$MCP_WORKSPACE_ROOT_INPUT")"

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
    grep -Fq "args = [\"-jar\", \"$(escape_toml_basic_string "$expected")\", \"--workspace-root=$(escape_toml_basic_string "$native_workspace")\"]" "$CODEX_HOME/config.toml"
  )
  CLIENT=codex
  print_mcp_config | grep -Fq "args = [\"-jar\", \"$(escape_toml_basic_string "$expected")\", \"--workspace-root=$(escape_toml_basic_string "$native_workspace")\"]"
  for CLIENT in claude kiro; do
    print_mcp_config | grep -Fq "\"args\": [\"-jar\", \"$(escape_json_string "$expected")\", \"--workspace-root=$(escape_json_string "$native_workspace")\"]"
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

# Use only the supported isolated targets; never touch user-global client state.
cd "$TEST_ROOT"
isolated_codex="$test_directory/codex home"
printf 'model = "preserve-me"\n[mcp_servers.unrelated]\ncommand = "keep-me"\n' > "$test_directory/baseline.toml"
mkdir -p "$isolated_codex"
cp "$test_directory/baseline.toml" "$isolated_codex/config.toml"
for script in install update; do
  bash "$TEST_ROOT/scripts/$script.sh" --help >/dev/null
  for invalid in --unknown --workspace-root; do
    if bash "$TEST_ROOT/scripts/$script.sh" "$invalid" >/dev/null 2>&1; then
      printf 'Expected %s to reject %s\n' "$script" "$invalid" >&2
      exit 1
    fi
  done
done

jar_input="$test_directory/path with spaces/server.jar"
workspace_input="$test_directory/workspace with spaces"
bash "$TEST_ROOT/scripts/install.sh" --client codex --codex-home "$isolated_codex" \
  --no-build-server --server-jar "$jar_input" --workspace-root "$workspace_input" > "$test_directory/install.log"
grep -Fq 'command = "keep-me"' "$isolated_codex/config.toml"
grep -Fq -- '--workspace-root' "$isolated_codex/config.toml"
[[ "$(find "$isolated_codex/skills" -name SKILL.md | wc -l)" -eq "$(find "$TEST_ROOT/skills" -name SKILL.md | wc -l)" ]]
skill=mcp-java-dev-tools-regression-suite
printf 'stale\n' > "$isolated_codex/skills/$skill/stale-marker"
cp "$isolated_codex/config.toml" "$test_directory/before-update.toml"
bash "$TEST_ROOT/scripts/update.sh" --client codex --codex-home "$isolated_codex" \
  --no-build-server > "$test_directory/update.log"
cmp "$test_directory/before-update.toml" "$isolated_codex/config.toml"
[[ ! -e "$isolated_codex/skills/$skill/stale-marker" ]]
diff -r "$TEST_ROOT/skills/$skill" "$isolated_codex/skills/$skill"

# Explicit reconfiguration replaces the managed block, preserving unrelated config.
bash "$TEST_ROOT/scripts/update.sh" --client codex --codex-home "$isolated_codex" \
  --no-build-server --configure-mcp --server-jar "$jar_input" \
  --workspace-root "$workspace_input" > "$test_directory/reconfigure.log"
[[ "$(grep -Fc '[mcp_servers.mcp-java-dev-tools]' "$isolated_codex/config.toml")" -eq 1 ]]
grep -Fq 'command = "keep-me"' "$isolated_codex/config.toml"
cp "$isolated_codex/config.toml" "$test_directory/before-print.toml"
bash "$TEST_ROOT/scripts/install.sh" --client codex --codex-home "$isolated_codex" \
  --no-build-server --no-apply-mcp --server-jar "$jar_input" \
  --workspace-root "$workspace_input" > "$test_directory/print.log"
cmp "$test_directory/before-print.toml" "$isolated_codex/config.toml"

for client in claude kiro; do
  target="$test_directory/$client skills"
  for script in install update; do
    bash "$TEST_ROOT/scripts/$script.sh" --client "$client" "--$client-skills-dir" "$target" \
      --no-build-server --configure-mcp --server-jar "$jar_input" \
      --workspace-root "$workspace_input" > "$test_directory/$client-$script.log"
    grep -Fq -- '--workspace-root' "$test_directory/$client-$script.log"
  done
  diff -r "$TEST_ROOT/skills/$skill" "$target/$skill"
done
printf 'PASS: isolated install/update for Codex, Claude and Kiro; config preservation, refresh and print-only behavior\n'
