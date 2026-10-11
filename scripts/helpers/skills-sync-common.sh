#!/usr/bin/env bash
set -euo pipefail

RETIRED_SKILL_NAMES=(
  "mcp-java-dev-tools-repro-orchestration"
  "mcp-java-dev-tools-execution-profile-export"
)
MANAGED_SKILL_PREFIX="mcp-java-dev-tools-"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

CLIENT="codex"
CLIENT_FROM_ARG=0
CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"
KIRO_SKILLS_DIR=""
CLAUDE_SKILLS_DIR=""
SKILL_NAMES=()
SKILL_NAME_OVERRIDE=0
RUN_BUILD_SERVER=1
CONFIGURE_MCP=1
APPLY_MCP_CONFIG=1
MCP_SERVER_NAME="mcp-java-dev-tools"
MCP_SERVER_JAR_INPUT=""

usage_common() {
  cat <<'EOF'
Options:
  --client <codex|kiro|claude> Target client. Default: codex
  --skill-name <name>         Sync only selected skill(s). Repeatable.
  --codex-home <path>         Override CODEX_HOME (default: ~/.codex)
  --kiro-skills-dir <path>    Override Kiro skills directory (default: ~/.kiro/skills)
  --claude-skills-dir <path>  Override Claude Code skills directory (default: ~/.claude/skills)
  --no-build-server           Skip Java agent and MCP server packaging
  --configure-mcp             Configure the Java MCP server (default: enabled)
  --no-configure-mcp          Skip MCP server configuration
  --no-apply-mcp              Do not auto-write Codex config; print a snippet only
  --mcp-server-name <name>    MCP server entry name (default: mcp-java-dev-tools)
  --server-jar <path>         Executable Java MCP server jar (auto-detected by default)
  --help                      Show help
EOF
}

expand_home() {
  local p="$1"
  if [[ "$p" == "~" ]]; then
    printf '%s\n' "$HOME"
    return
  fi
  if [[ "$p" == "~/"* ]]; then
    printf '%s\n' "$HOME/${p#~/}"
    return
  fi
  if [[ "$p" == "~\\"* ]]; then
    printf '%s\n' "$HOME/${p#~\\}"
    return
  fi
  printf '%s\n' "$p"
}

detect_kiro_skills_dir() {
  printf '%s\n' "$HOME/.kiro/skills"
}

detect_claude_skills_dir() {
  printf '%s\n' "$HOME/.claude/skills"
}

dedupe_skill_names() {
  local -A seen=()
  local out=()
  local s
  for s in "${SKILL_NAMES[@]}"; do
    if [[ -z "$s" ]]; then
      continue
    fi
    if [[ -n "${seen[$s]+x}" ]]; then
      continue
    fi
    seen[$s]=1
    out+=("$s")
  done
  SKILL_NAMES=("${out[@]}")
}

parse_common_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --client)
        CLIENT="${2:-}"
        CLIENT_FROM_ARG=1
        shift 2
        ;;
      --skill-name)
        if [[ "$SKILL_NAME_OVERRIDE" -eq 0 ]]; then
          SKILL_NAMES=()
          SKILL_NAME_OVERRIDE=1
        fi
        SKILL_NAMES+=("${2:-}")
        shift 2
        ;;
      --codex-home) CODEX_HOME="$(expand_home "${2:-}")"; shift 2 ;;
      --kiro-skills-dir) KIRO_SKILLS_DIR="$(expand_home "${2:-}")"; shift 2 ;;
      --claude-skills-dir) CLAUDE_SKILLS_DIR="$(expand_home "${2:-}")"; shift 2 ;;
      --no-build-server) RUN_BUILD_SERVER=0; shift ;;
      --configure-mcp) CONFIGURE_MCP=1; shift ;;
      --no-configure-mcp) CONFIGURE_MCP=0; shift ;;
      --no-apply-mcp) APPLY_MCP_CONFIG=0; shift ;;
      --mcp-server-name) MCP_SERVER_NAME="${2:-}"; shift 2 ;;
      --server-jar) MCP_SERVER_JAR_INPUT="$(expand_home "${2:-}")"; shift 2 ;;
      --help|-h) return 99 ;;
      *)
        echo "Unknown argument: $1" >&2
        return 2
        ;;
    esac
  done
  return 0
}

load_default_skills_from_repo() {
  local skills_root="$REPO_ROOT/skills"
  local -a discovered=()
  local entry
  if [[ ! -d "$skills_root" ]]; then
    echo "Skills root not found: $skills_root" >&2
    exit 1
  fi

  while IFS= read -r entry; do
    discovered+=("$entry")
  done < <(
    find "$skills_root" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' \
      | grep "^${MANAGED_SKILL_PREFIX}" \
      | sort
  )

  if [[ "${#discovered[@]}" -eq 0 ]]; then
    echo "No managed skills found in $skills_root" >&2
    exit 1
  fi

  SKILL_NAMES=("${discovered[@]}")
}

validate_common_config() {
  if [[ "$CLIENT" != "codex" && "$CLIENT" != "kiro" && "$CLIENT" != "claude" ]]; then
    echo "Invalid --client: $CLIENT" >&2
    exit 1
  fi

  CODEX_HOME="$(expand_home "$CODEX_HOME")"
  if [[ -n "$KIRO_SKILLS_DIR" ]]; then
    KIRO_SKILLS_DIR="$(expand_home "$KIRO_SKILLS_DIR")"
  fi
  if [[ -n "$CLAUDE_SKILLS_DIR" ]]; then
    CLAUDE_SKILLS_DIR="$(expand_home "$CLAUDE_SKILLS_DIR")"
  fi

  if [[ "$SKILL_NAME_OVERRIDE" -eq 0 ]]; then
    load_default_skills_from_repo
  fi
  dedupe_skill_names
  if [[ "${#SKILL_NAMES[@]}" -eq 0 ]]; then
    echo "No skills selected. Provide --skill-name <name> or omit --skill-name for defaults." >&2
    exit 1
  fi

  if [[ "$CONFIGURE_MCP" -eq 1 ]]; then
    if [[ -z "$MCP_SERVER_NAME" ]]; then
      echo "--mcp-server-name must be non-empty when --configure-mcp is enabled." >&2
      exit 1
    fi
  fi
}

detect_default_server_jar() {
  local target_dir="$REPO_ROOT/mcp-server/application/target"
  if [[ ! -d "$target_dir" ]]; then
    printf '%s' ""
    return
  fi
  local latest
  latest="$(ls -1t "$target_dir"/mcp-java-dev-tools-server-*.jar 2>/dev/null \
    | grep -v '\.original$' | head -n 1 || true)"
  printf '%s' "$latest"
}

run_build_server() {
  if [[ "$RUN_BUILD_SERVER" -eq 0 ]]; then
    echo "- Skipping Java MCP server build (--no-build-server)"
    return
  fi
  echo "- Packaging and verifying the Java MCP server"
  bash "$REPO_ROOT/scripts/package-java-mcp-server.sh"
}

replace_skill_dir() {
  local dest_dir="$1"
  local guard_root="$2"
  if [[ -z "$dest_dir" || "$dest_dir" == "/" ]]; then
    echo "Refusing unsafe destination: '$dest_dir'" >&2
    exit 1
  fi
  case "$dest_dir" in
    "$guard_root"/*) ;;
    *)
      echo "Refusing destination outside skills root: $dest_dir" >&2
      echo "Expected root: $guard_root" >&2
      exit 1
      ;;
  esac
  rm -rf "$dest_dir"
}

sync_one_skill() {
  local source_dir="$1"
  local dest_dir="$2"
  local label="$3"
  local guard_root="$4"

  if [[ ! -d "$source_dir" ]]; then
    echo "$label source not found: $source_dir" >&2
    exit 1
  fi

  if [[ -d "$dest_dir" ]]; then
    echo "- $label: replacing existing folder"
    replace_skill_dir "$dest_dir" "$guard_root"
  else
    echo "- $label: installing new folder"
  fi

  mkdir -p "$(dirname "$dest_dir")"
  cp -R "$source_dir" "$dest_dir"
}

remove_retired_skill_if_present() {
  local skills_root="$1"
  local retired_name=""
  local retired_dir=""
  for retired_name in "${RETIRED_SKILL_NAMES[@]}"; do
    retired_dir="$skills_root/$retired_name"
    if [[ ! -d "$retired_dir" ]]; then
      continue
    fi
    echo "- Removing retired skill: $retired_dir"
    replace_skill_dir "$retired_dir" "$skills_root"
  done
}

sync_client_skills() {
  local skills_root="$1"
  local client_label="$2"

  remove_retired_skill_if_present "$skills_root"

  local skill_name
  for skill_name in "${SKILL_NAMES[@]}"; do
    sync_one_skill \
      "$REPO_ROOT/skills/$skill_name" \
      "$skills_root/$skill_name" \
      "$client_label skill ($skill_name)" \
      "$skills_root"
  done
}

list_stale_managed_skill_dirs() {
  local skills_root="$1"
  local -A repo_managed=()
  local -a repo_skill_dirs=()
  local -a target_skill_dirs=()
  local -a stale_dirs=()
  local d name

  shopt -s nullglob
  repo_skill_dirs=("$REPO_ROOT"/skills/"$MANAGED_SKILL_PREFIX"*)
  for d in "${repo_skill_dirs[@]}"; do
    [[ -d "$d" ]] || continue
    name="$(basename "$d")"
    repo_managed["$name"]=1
  done

  target_skill_dirs=("$skills_root"/"$MANAGED_SKILL_PREFIX"*)
  shopt -u nullglob
  for d in "${target_skill_dirs[@]}"; do
    [[ -d "$d" ]] || continue
    name="$(basename "$d")"
    if [[ -z "${repo_managed[$name]+x}" ]]; then
      stale_dirs+=("$d")
    fi
  done

  printf '%s\n' "${stale_dirs[@]}"
}

validate_synced_skills() {
  local skills_root="$1"
  local client_label="$2"
  local skill_name dest_dir
  local -a missing_dirs=()
  local -a missing_skill_md=()
  local -a stale_dirs=()

  for skill_name in "${SKILL_NAMES[@]}"; do
    dest_dir="$skills_root/$skill_name"
    if [[ ! -d "$dest_dir" ]]; then
      missing_dirs+=("$dest_dir")
      continue
    fi
    if [[ ! -f "$dest_dir/SKILL.md" ]]; then
      missing_skill_md+=("$dest_dir/SKILL.md")
    fi
  done

  while IFS= read -r dest_dir; do
    [[ -n "$dest_dir" ]] || continue
    stale_dirs+=("$dest_dir")
  done < <(list_stale_managed_skill_dirs "$skills_root")

  if [[ "${#missing_dirs[@]}" -gt 0 ]]; then
    echo "$client_label skill sync validation failed: missing installed skill directories." >&2
    printf ' - %s\n' "${missing_dirs[@]}" >&2
    exit 1
  fi

  if [[ "${#missing_skill_md[@]}" -gt 0 ]]; then
    echo "$client_label skill sync validation failed: SKILL.md missing from installed skills." >&2
    printf ' - %s\n' "${missing_skill_md[@]}" >&2
    exit 1
  fi

  if [[ "${#stale_dirs[@]}" -gt 0 ]]; then
    echo "$client_label skill sync validation failed: stale managed skills remain after sync." >&2
    printf ' - %s\n' "${stale_dirs[@]}" >&2
    exit 1
  fi

  echo "- $client_label skill sync validation passed (${#SKILL_NAMES[@]} managed skill(s))"
}

print_kiro_refresh_note() {
  cat <<EOF
- Kiro refresh note:
  Restart or reload the Kiro session so its visible tools/skills list refreshes from $KIRO_SKILLS_DIR.
EOF
}

print_claude_refresh_note() {
  cat <<EOF
- Claude Code refresh note:
  Restart or reload Claude Code so its visible Skills list refreshes from $CLAUDE_SKILLS_DIR.
EOF
}

run_skill_sync() {
  local mode_label="$1"
  validate_common_config

  echo "$mode_label started (client=$CLIENT)"
  if [[ "$CONFIGURE_MCP" -eq 1 ]]; then
    echo "- Note: this flow syncs skills and configures the Java MCP server."
  else
    echo "- Note: this flow syncs skills only. MCP config installation is not performed."
  fi

  run_build_server

  if [[ "$CLIENT" == "codex" ]]; then
    sync_client_skills "$CODEX_HOME/skills" "Codex"
  elif [[ "$CLIENT" == "kiro" ]]; then
    if [[ -z "$KIRO_SKILLS_DIR" ]]; then
      KIRO_SKILLS_DIR="$(detect_kiro_skills_dir)"
    fi
    prompt_delete_stale_managed_skills
    sync_client_skills "$KIRO_SKILLS_DIR" "Kiro"
    validate_synced_skills "$KIRO_SKILLS_DIR" "Kiro"
    print_kiro_refresh_note
  else
    if [[ -z "$CLAUDE_SKILLS_DIR" ]]; then
      CLAUDE_SKILLS_DIR="$(detect_claude_skills_dir)"
    fi
    prompt_delete_stale_managed_skills
    sync_client_skills "$CLAUDE_SKILLS_DIR" "Claude Code"
    validate_synced_skills "$CLAUDE_SKILLS_DIR" "Claude Code"
    print_claude_refresh_note
  fi

  if [[ "$CONFIGURE_MCP" -eq 1 ]]; then
    prompt_mcp_config_if_missing
    apply_mcp_config_if_supported
    print_mcp_config
  fi

  echo "$mode_label completed."
}

prompt_client_if_not_set() {
  if [[ "$CLIENT_FROM_ARG" -eq 1 ]]; then
    return
  fi
  local input=""
  while true; do
    read -r -p "Target orchestrator client (codex|kiro|claude) [${CLIENT}]: " input
    if [[ -z "$input" ]]; then
      break
    fi
    if [[ "$input" == "codex" || "$input" == "kiro" || "$input" == "claude" ]]; then
      CLIENT="$input"
      break
    fi
    echo "Invalid value. Enter codex, kiro, or claude."
  done
}

resolve_target_skills_root() {
  if [[ "$CLIENT" == "codex" ]]; then
    printf '%s\n' "$CODEX_HOME/skills"
    return
  fi
  if [[ "$CLIENT" == "claude" ]]; then
    if [[ -z "$CLAUDE_SKILLS_DIR" ]]; then
      CLAUDE_SKILLS_DIR="$(detect_claude_skills_dir)"
    fi
    printf '%s\n' "$CLAUDE_SKILLS_DIR"
    return
  fi
  if [[ -z "$KIRO_SKILLS_DIR" ]]; then
    KIRO_SKILLS_DIR="$(detect_kiro_skills_dir)"
  fi
  printf '%s\n' "$KIRO_SKILLS_DIR"
}

prompt_yes_no_default_no() {
  local message="$1"
  local input=""
  while true; do
    read -r -p "$message [y/N]: " input
    if [[ -z "$input" ]]; then
      return 1
    fi
    if [[ "$input" =~ ^[Yy]$ ]]; then
      return 0
    fi
    if [[ "$input" =~ ^[Nn]$ ]]; then
      return 1
    fi
    echo "Please answer y or n."
  done
}

prompt_mcp_config_if_missing() {
  local input=""

  if [[ -z "$MCP_SERVER_JAR_INPUT" ]]; then
    local default_server_jar
    default_server_jar="$(detect_default_server_jar)"
    if [[ -n "$default_server_jar" ]]; then
      read -r -p "Java MCP server jar [${default_server_jar}]: " input
      if [[ -z "$input" ]]; then
        MCP_SERVER_JAR_INPUT="$default_server_jar"
      else
        MCP_SERVER_JAR_INPUT="$(expand_home "$input")"
      fi
    else
      read -r -p "Java MCP server jar: " input
      MCP_SERVER_JAR_INPUT="$(expand_home "$input")"
    fi
  fi
  if [[ -z "$MCP_SERVER_JAR_INPUT" ]]; then
    echo "Java MCP server jar is required when --configure-mcp is enabled." >&2
    exit 1
  fi
  if [[ ! -f "$MCP_SERVER_JAR_INPUT" ]]; then
    echo "Java MCP server jar not found: $MCP_SERVER_JAR_INPUT" >&2
    exit 1
  fi
}

apply_mcp_config_if_supported() {
  if [[ "$APPLY_MCP_CONFIG" -eq 0 ]]; then
    return
  fi

  if [[ "$CLIENT" == "codex" ]]; then
    apply_codex_mcp_config
    return
  fi

  echo "- Auto-apply MCP config is currently supported for Codex only; printing a manual $CLIENT note."
}

apply_codex_mcp_config() {
  local cfg="$CODEX_HOME/config.toml"
  mkdir -p "$CODEX_HOME"
  touch "$cfg"

  local tmp
  tmp="$(mktemp)"

  awk -v name="$MCP_SERVER_NAME" '
    BEGIN { skip=0 }
    {
      section="[mcp_servers." name "]"
      nested="[mcp_servers." name "."
      if ($0 == section || index($0, nested) == 1) { skip=1; next }
      if (skip == 1 && $0 ~ /^\[/) { skip=0 }
      if (skip == 0) print $0
    }
  ' "$cfg" > "$tmp"

  mv "$tmp" "$cfg"

  local toml_server_jar
  toml_server_jar="$(escape_toml_basic_string "$(native_absolute_server_jar_path)")"

  cat >> "$cfg" <<EOF

[mcp_servers.${MCP_SERVER_NAME}]
command = "java"
args = ["-jar", "${toml_server_jar}"]
EOF

  echo "- Applied Java MCP server config to $cfg"
}

print_mcp_config() {
  local native_server_jar
  local toml_server_jar
  local json_server_jar

  native_server_jar="$(native_absolute_server_jar_path)"
  toml_server_jar="$(escape_toml_basic_string "$native_server_jar")"
  json_server_jar="$(escape_json_string "$native_server_jar")"

  if [[ "$CLIENT" == "codex" ]]; then
    cat <<EOF

Java MCP server configured.
Codex config applied to ~/.codex/config.toml (unless --no-apply-mcp was used).
Reference block:

[mcp_servers.${MCP_SERVER_NAME}]
command = "java"
args = ["-jar", "${toml_server_jar}"]

Then restart Codex/MCP session and run:
1. operation_execute { "operationId": "artifact_management.probe_config.reload", "arguments": {}, "confirmed": true }
2. operation_execute { "operationId": "artifact_management.probe_config.read", "arguments": {} }
EOF
    return
  fi

  if [[ "$CLIENT" == "claude" ]]; then
    cat <<EOF

Java MCP server built. Claude Code skill sync does not auto-edit Claude configuration.
Add or merge this block into your project MCP configuration:

{
  "mcpServers": {
    "${MCP_SERVER_NAME}": {
      "command": "java",
      "args": ["-jar", "${json_server_jar}"]
    }
  }
}

Then restart Claude Code/MCP session and run:
1. operation_execute { "operationId": "artifact_management.probe_config.reload", "arguments": {}, "confirmed": true }
2. operation_execute { "operationId": "artifact_management.probe_config.read", "arguments": {} }
EOF
    return
  fi

  cat <<EOF

Java MCP server built.
Add or merge this block into your Kiro MCP settings:

{
  "mcpServers": {
    "${MCP_SERVER_NAME}": {
      "command": "java",
      "args": ["-jar", "${json_server_jar}"]
    }
  }
}

Then restart Kiro MCP session and run:
1. operation_execute { "operationId": "artifact_management.probe_config.reload", "arguments": {}, "confirmed": true }
2. operation_execute { "operationId": "artifact_management.probe_config.read", "arguments": {} }
EOF
}

native_absolute_server_jar_path() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      cygpath -am -- "$MCP_SERVER_JAR_INPUT"
      ;;
    *)
      local jar_directory
      jar_directory="$(cd "$(dirname "$MCP_SERVER_JAR_INPUT")" && pwd -P)"
      printf '%s/%s\n' "$jar_directory" "$(basename "$MCP_SERVER_JAR_INPUT")"
      ;;
  esac
}

escape_toml_basic_string() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "$s"
}

escape_json_string() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "$s"
}

prompt_delete_stale_managed_skills() {
  local skills_root
  skills_root="$(resolve_target_skills_root)"
  if [[ ! -d "$skills_root" ]]; then
    echo "- Skills root not found yet ($skills_root); stale cleanup skipped."
    return
  fi

  local -a stale_dirs=()
  local d
  while IFS= read -r d; do
    [[ -n "$d" ]] || continue
    stale_dirs+=("$d")
  done < <(list_stale_managed_skill_dirs "$skills_root")

  if [[ "${#stale_dirs[@]}" -eq 0 ]]; then
    echo "- No stale managed skills detected under $skills_root"
    return
  fi

  echo "- Detected stale managed skills to delete (prefix: $MANAGED_SKILL_PREFIX):"
  for d in "${stale_dirs[@]}"; do
    echo "  - $d"
  done

  if ! prompt_yes_no_default_no "Delete the stale managed skills listed above?"; then
    echo "- Stale managed skill cleanup skipped by user."
    return
  fi

  for d in "${stale_dirs[@]}"; do
    echo "- Deleting stale managed skill: $d"
    replace_skill_dir "$d" "$skills_root"
  done
}

read_server_version() {
  local version
  version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$REPO_ROOT/mcp-server/pom.xml" \
    | head -n 1)"
  if [[ -n "$version" ]]; then
    printf '%s\n' "$version"
    return
  fi
  printf '%s\n' "unknown"
}

print_server_upgrade_note() {
  local version="$1"
  cat <<EOF
Note:
- Java MCP server version: $version
- Restart the MCP client after updating so it launches the newly packaged server jar.
EOF
}
