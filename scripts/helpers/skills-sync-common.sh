#!/usr/bin/env bash
set -euo pipefail

DEFAULT_SKILLS=(
  "mcp-java-dev-tools-line-probe-run"
  "mcp-java-dev-tools-regression-suite"
  "mcp-java-dev-tools-regression-plan-crafter"
  "mcp-java-dev-tools-regression-result"
  "mcp-java-dev-tools-issue-report"
  "mcp-java-dev-tools-probe-registry-manager"
  "mcp-java-dev-tools-project-artifact-manager"
  "mcp-java-dev-tools-run-session-export"
)
RETIRED_SKILL_NAME="mcp-java-dev-tools-repro-orchestration"
MANAGED_SKILL_PREFIX="mcp-java-dev-tools-"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

CLIENT="codex"
CLIENT_FROM_ARG=0
CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"
KIRO_SKILLS_DIR=""
SKILL_NAMES=("${DEFAULT_SKILLS[@]}")
SKILL_NAME_OVERRIDE=0
RUN_BUILD_COMPILE=1
RUN_BUILD_JAVA=1
CONFIGURE_MCP_ENV=1
APPLY_MCP_ENV=1
MCP_SERVER_NAME="mcp-java-dev-tools"
MCP_JAVA_AGENT_JAR_INPUT=""

usage_common() {
  cat <<'EOF'
Options:
  --client <codex|kiro>       Target client. Default: codex
  --skill-name <name>         Sync only selected skill(s). Repeatable.
  --codex-home <path>         Override CODEX_HOME (default: ~/.codex)
  --kiro-skills-dir <path>    Override Kiro skills directory (default: ~/.kiro/skills)
  --no-build-compile          Skip `npm run build:compile`
  --no-build-java             Skip `mvn -f java-agent/pom.xml package`
  --configure-mcp-env         Prompt/collect MCP probe-registry env values and print config block (default: enabled)
  --no-configure-mcp-env      Skip MCP env input/output block generation
  --no-apply-mcp-env          Do not auto-write MCP env block to local config; print snippet only
  --mcp-server-name <name>    MCP server entry name for generated config block (default: mcp-java-dev-tools)
  --java-agent-jar <path>     Java agent jar path for MCP_JAVA_AGENT_JAR (required with --configure-mcp-env)
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
      --no-build-compile) RUN_BUILD_COMPILE=0; shift ;;
      --no-build-java) RUN_BUILD_JAVA=0; shift ;;
      --configure-mcp-env) CONFIGURE_MCP_ENV=1; shift ;;
      --no-configure-mcp-env) CONFIGURE_MCP_ENV=0; shift ;;
      --no-apply-mcp-env) APPLY_MCP_ENV=0; shift ;;
      --mcp-server-name) MCP_SERVER_NAME="${2:-}"; shift 2 ;;
      --java-agent-jar) MCP_JAVA_AGENT_JAR_INPUT="$(expand_home "${2:-}")"; shift 2 ;;
      --help|-h) return 99 ;;
      *)
        echo "Unknown argument: $1" >&2
        return 2
        ;;
    esac
  done
  return 0
}

validate_common_config() {
  if [[ "$CLIENT" != "codex" && "$CLIENT" != "kiro" ]]; then
    echo "Invalid --client: $CLIENT" >&2
    exit 1
  fi

  CODEX_HOME="$(expand_home "$CODEX_HOME")"
  if [[ -n "$KIRO_SKILLS_DIR" ]]; then
    KIRO_SKILLS_DIR="$(expand_home "$KIRO_SKILLS_DIR")"
  fi

  dedupe_skill_names
  if [[ "${#SKILL_NAMES[@]}" -eq 0 ]]; then
    echo "No skills selected. Provide --skill-name <name> or omit --skill-name for defaults." >&2
    exit 1
  fi

  if [[ "$CONFIGURE_MCP_ENV" -eq 1 ]]; then
    if [[ -z "$MCP_SERVER_NAME" ]]; then
      echo "--mcp-server-name must be non-empty when --configure-mcp-env is enabled." >&2
      exit 1
    fi
    if [[ -z "$MCP_JAVA_AGENT_JAR_INPUT" ]]; then
      MCP_JAVA_AGENT_JAR_INPUT="$(detect_default_java_agent_jar)"
    fi
    if [[ -z "$MCP_JAVA_AGENT_JAR_INPUT" ]]; then
      echo "--java-agent-jar is required when --configure-mcp-env is enabled." >&2
      exit 1
    fi
  fi
}

detect_default_java_agent_jar() {
  local target_dir="$REPO_ROOT/java-agent/core/core-probe/target"
  if [[ ! -d "$target_dir" ]]; then
    printf '%s' ""
    return
  fi
  local latest
  latest="$(ls -1t "$target_dir"/mcp-java-dev-tools-agent-*-all.jar 2>/dev/null | head -n 1 || true)"
  printf '%s' "$latest"
}

ensure_node_build_deps() {
  local tsc_bin="$REPO_ROOT/node_modules/.bin/tsc"
  local tsc_alias_bin="$REPO_ROOT/node_modules/.bin/tsc-alias"
  if [[ -f "$tsc_bin" && -f "$tsc_alias_bin" ]]; then
    return
  fi
  echo "- Installing Node dependencies for TypeScript compile"
  if [[ -f "$REPO_ROOT/package-lock.json" ]]; then
    (cd "$REPO_ROOT" && npm ci --include=dev)
  else
    (cd "$REPO_ROOT" && npm install --include=dev)
  fi
}

run_build_compile() {
  if [[ "$RUN_BUILD_COMPILE" -eq 0 ]]; then
    echo "- Skipping build compile (--no-build-compile)"
    return
  fi
  ensure_node_build_deps
  echo "- Running npm run build:compile"
  (cd "$REPO_ROOT" && npm run build:compile)
}

run_build_java() {
  if [[ "$RUN_BUILD_JAVA" -eq 0 ]]; then
    echo "- Skipping Java build (--no-build-java)"
    return
  fi
  echo "- Running Maven Java agent build"
  (cd "$REPO_ROOT" && mvn -f java-agent/pom.xml package)
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
  local retired_dir="$skills_root/$RETIRED_SKILL_NAME"
  if [[ ! -d "$retired_dir" ]]; then
    return
  fi
  echo "- Removing retired skill: $retired_dir"
  replace_skill_dir "$retired_dir" "$skills_root"
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

run_skill_sync() {
  local mode_label="$1"
  validate_common_config

  echo "$mode_label started (client=$CLIENT)"
  if [[ "$CONFIGURE_MCP_ENV" -eq 1 ]]; then
    echo "- Note: this flow syncs skills and generates MCP env config blocks."
  else
    echo "- Note: this flow syncs skills only. MCP config installation is not performed."
  fi

  run_build_compile
  run_build_java

  if [[ "$CLIENT" == "codex" ]]; then
    sync_client_skills "$CODEX_HOME/skills" "Codex"
  else
    if [[ -z "$KIRO_SKILLS_DIR" ]]; then
      KIRO_SKILLS_DIR="$(detect_kiro_skills_dir)"
    fi
    sync_client_skills "$KIRO_SKILLS_DIR" "Kiro"
  fi

  if [[ "$CONFIGURE_MCP_ENV" -eq 1 ]]; then
    prompt_mcp_env_if_missing
    apply_mcp_env_block_if_supported
    print_mcp_env_block
  fi

  echo "$mode_label completed."
}

prompt_client_if_not_set() {
  if [[ "$CLIENT_FROM_ARG" -eq 1 ]]; then
    return
  fi
  local input=""
  while true; do
    read -r -p "Target orchestrator client (codex|kiro) [${CLIENT}]: " input
    if [[ -z "$input" ]]; then
      break
    fi
    if [[ "$input" == "codex" || "$input" == "kiro" ]]; then
      CLIENT="$input"
      break
    fi
    echo "Invalid value. Enter codex or kiro."
  done
}

resolve_target_skills_root() {
  if [[ "$CLIENT" == "codex" ]]; then
    printf '%s\n' "$CODEX_HOME/skills"
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

prompt_mcp_env_if_missing() {
  local input=""


  if [[ -z "$MCP_JAVA_AGENT_JAR_INPUT" ]]; then
    local default_agent_jar
    default_agent_jar="$(detect_default_java_agent_jar)"
    if [[ -n "$default_agent_jar" ]]; then
      read -r -p "MCP Java agent jar [${default_agent_jar}]: " input
      if [[ -z "$input" ]]; then
        MCP_JAVA_AGENT_JAR_INPUT="$default_agent_jar"
      else
        MCP_JAVA_AGENT_JAR_INPUT="$(expand_home "$input")"
      fi
    else
      read -r -p "MCP Java agent jar: " input
      MCP_JAVA_AGENT_JAR_INPUT="$(expand_home "$input")"
    fi
  fi
  if [[ -z "$MCP_JAVA_AGENT_JAR_INPUT" ]]; then
    echo "MCP Java agent jar is required when --configure-mcp-env is enabled." >&2
    exit 1
  fi
}

apply_mcp_env_block_if_supported() {
  if [[ "$APPLY_MCP_ENV" -eq 0 ]]; then
    return
  fi

  if [[ "$CLIENT" == "codex" ]]; then
    apply_codex_mcp_env_block
    return
  fi

  echo "- Auto-apply MCP env is currently supported for Codex only; printing Kiro snippet."
}

apply_codex_mcp_env_block() {
  local cfg="$CODEX_HOME/config.toml"
  mkdir -p "$CODEX_HOME"
  touch "$cfg"

  local section="[mcp_servers.${MCP_SERVER_NAME}.env]"
  local tmp
  tmp="$(mktemp)"

  awk -v section="$section" '
    BEGIN { skip=0 }
    {
      if ($0 == section) { skip=1; next }
      if (skip == 1 && $0 ~ /^\[/) { skip=0 }
      if (skip == 0) print $0
    }
  ' "$cfg" > "$tmp"

  mv "$tmp" "$cfg"

  local toml_agent_jar
  toml_agent_jar="$(escape_toml_basic_string "$MCP_JAVA_AGENT_JAR_INPUT")"

  cat >> "$cfg" <<EOF

[mcp_servers.${MCP_SERVER_NAME}.env]
MCP_JAVA_AGENT_JAR = "${toml_agent_jar}"
EOF

  echo "- Applied MCP env block to $cfg"
}

print_mcp_env_block() {
  local toml_agent_jar
  local json_agent_jar

  toml_agent_jar="$(escape_toml_basic_string "$MCP_JAVA_AGENT_JAR_INPUT")"

  json_agent_jar="$(escape_json_string "$MCP_JAVA_AGENT_JAR_INPUT")"

  if [[ "$CLIENT" == "codex" ]]; then
    cat <<EOF

MCP registry env input captured.
Codex MCP env block applied to ~/.codex/config.toml (unless --no-apply-mcp-env was used).
Reference block:

[mcp_servers.${MCP_SERVER_NAME}.env]
MCP_JAVA_AGENT_JAR = "${toml_agent_jar}"

Then restart Codex/MCP session and run:
1. probe_registry_reload
2. probe_registry_list
EOF
    return
  fi

  cat <<EOF

MCP registry env input captured.
Add or merge this block into your Kiro MCP settings:

{
  "mcpServers": {
    "${MCP_SERVER_NAME}": {
      "command": "node",
      "args": ["C:\\\\path\\\\to\\\\mcp-jvm-debugger\\\\dist\\\\server.js"],
      "env": {
        "MCP_JAVA_AGENT_JAR": "${json_agent_jar}"
      }
    }
  }
}

Then restart Kiro MCP session and run:
1. probe_registry_reload
2. probe_registry_list
EOF
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

read_package_version() {
  local version
  version="$(node -p "require('./package.json').version" 2>/dev/null || true)"
  if [[ -n "$version" ]]; then
    printf '%s\n' "$version"
    return
  fi
  printf '%s\n' "unknown"
}

print_jar_upgrade_note() {
  local version="$1"
  cat <<EOF
Note:
- Latest MCP Java Dev Tools version: $version
- Update your target application's javaagent jar to this latest version.
- Example: replace older jar (e.g. 0.1.3) with latest version $version.
EOF
}
