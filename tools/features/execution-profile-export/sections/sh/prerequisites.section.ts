import { promises as fs } from "node:fs";
import path from "node:path";

import { toShellEnvKey } from "@tools-export-execution-profile/common";
import { loadPlanContract } from "@tools-export-execution-profile/loaders/plan_contract.loader";
import type { ExecutionProfileExportPlanRun } from "@tools-export-execution-profile/models/execution_profile_export.model";
import { resolvePlanBaseUrls } from "@tools-export-execution-profile/sections/sh/plan_execution.section";
import { toWorkspaceShellPath } from "@tools-export-execution-profile/shell_path.util";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";

type RequiredInput = {
  envKey: string;
  defaultValue?: string;
};

type WorkspaceEnvBinding = {
  lines: string[];
  reloadCommand: string;
};

export type ShPrerequisitesSections = {
  prerequisitesSection: string[];
  postStartupAuthSection: string[];
};

function mergeRequiredInput(inputs: Map<string, RequiredInput>, input: RequiredInput): void {
  const existing = inputs.get(input.envKey);
  if (existing) {
    if (typeof existing.defaultValue === "undefined" && typeof input.defaultValue !== "undefined") {
      inputs.set(input.envKey, input);
    }
    return;
  }
  inputs.set(input.envKey, input);
}

function extractRequiredEnvVars(planExecutionLines: string[]): string[] {
  const combined = planExecutionLines.join("\n");
  const vars = new Set<string>();
  const regex = /\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g;
  let match: RegExpExecArray | null = regex.exec(combined);
  while (match) {
    const key = match[1];
    if (typeof key === "string" && key.trim().length > 0) vars.add(key);
    match = regex.exec(combined);
  }
  return [...vars].sort();
}

function defaultValueForEnvVar(key: string, contractDefault?: string): string {
  if (typeof contractDefault === "string") return contractDefault;
  if (key.includes("GATEWAYBASEURL")) return "api";
  if (key.includes("EMAIL")) return "regression@example.test";
  if (key === "AUTH_BEARER") return "";
  if (key.endsWith("ID")) return "1";
  if (key.includes("TITLE")) return "Regression Course";
  if (key.includes("AUTHOR")) return "Regression Author";
  if (key.includes("CONTENT")) return "Regression content";
  return "REDACTED_OR_SET_ME";
}

function requiresAuthBearer(requiredInputs: RequiredInput[]): boolean {
  return requiredInputs.some((input) => input.envKey === "AUTH_BEARER");
}

function renderAuthRefreshFunction(input: {
  reloadWorkspaceEnvCommand: string;
}): string[] {
  const lines: string[] = [];
  lines.push("can_refresh_auth_bearer() {");
  lines.push("  if [ -n \"${KEYCLOAK_CLIENT_ID:-}\" ] && [ -n \"${KEYCLOAK_USERNAME:-}\" ] && [ -n \"${KEYCLOAK_PASSWORD:-}\" ]; then return 0; fi");
  lines.push("  if [ -n \"${KEYCLOAK_CLIENT_ID:-}\" ] && [ -n \"${KEYCLOAK_CLIENT_SECRET:-}\" ]; then return 0; fi");
  lines.push("  return 1");
  lines.push("}");
  lines.push("");
  lines.push("refresh_auth_bearer() {");
  lines.push("  local force_refresh=\"${1:-}\"");
  lines.push(`  ${input.reloadWorkspaceEnvCommand} "\${force_refresh}"`);
  lines.push("  if [ \"${force_refresh}\" != \"force\" ] && [ -n \"${AUTH_BEARER:-}\" ] && [ \"${AUTH_BEARER}\" != \"REDACTED_TOKEN\" ]; then");
  lines.push("    export AUTH_BEARER");
  lines.push("    echo \"auth_bootstrap_succeeded: AUTH_BEARER\"");
  lines.push("    return 0");
  lines.push("  fi");
  lines.push("  KC_BASE_URL=\"${KEYCLOAK_BASE_URL:-http://127.0.0.1:8081}\"");
  lines.push("  KC_REALM=\"${KEYCLOAK_REALM:-}\"");
  lines.push("  KC_SCOPE=\"${KEYCLOAK_SCOPE:-openid}\"");
  lines.push("  if [ \"${force_refresh}\" = \"force\" ] && { [ -z \"${KC_REALM}\" ] || ! can_refresh_auth_bearer; }; then");
  lines.push("    echo \"auth_refresh_unavailable: missing KEYCLOAK_* refresh prerequisites\" >&2");
  lines.push("    return 2");
  lines.push("  fi");
  lines.push("  if [ -n \"${KC_REALM}\" ] && can_refresh_auth_bearer; then");
  lines.push("    KC_TOKEN_ARGS=(--data-urlencode \"client_id=${KEYCLOAK_CLIENT_ID}\" --data-urlencode \"scope=${KC_SCOPE}\")");
  lines.push("    if [ -n \"${KEYCLOAK_USERNAME:-}\" ] && [ -n \"${KEYCLOAK_PASSWORD:-}\" ]; then");
  lines.push("      KC_TOKEN_ARGS+=(--data-urlencode \"grant_type=password\" --data-urlencode \"username=${KEYCLOAK_USERNAME}\" --data-urlencode \"password=${KEYCLOAK_PASSWORD}\")");
  lines.push("      if [ -n \"${KEYCLOAK_CLIENT_SECRET:-}\" ]; then KC_TOKEN_ARGS+=(--data-urlencode \"client_secret=${KEYCLOAK_CLIENT_SECRET}\"); fi");
  lines.push("    else");
  lines.push("      KC_TOKEN_ARGS+=(--data-urlencode \"grant_type=client_credentials\" --data-urlencode \"client_secret=${KEYCLOAK_CLIENT_SECRET}\")");
  lines.push("    fi");
  lines.push("    KC_TOKEN_RESPONSE=\"$(curl -sS -X POST \"${KC_BASE_URL}/realms/${KC_REALM}/protocol/openid-connect/token\" -H \"Content-Type: application/x-www-form-urlencoded\" \"${KC_TOKEN_ARGS[@]}\" || true)\"");
  lines.push("    AUTH_BEARER=\"$(extract_json_field \"${KC_TOKEN_RESPONSE}\" \"access_token\")\"");
  lines.push("    if [ -n \"${AUTH_BEARER:-}\" ]; then AUTH_BEARER_TOKEN=\"${AUTH_BEARER}\"; export AUTH_BEARER AUTH_BEARER_TOKEN; echo \"auth_bootstrap_succeeded: AUTH_BEARER\"; fi");
  lines.push("  fi");
  lines.push("  if [ \"${force_refresh}\" = \"force\" ] && [ -z \"${AUTH_BEARER:-}\" ]; then");
  lines.push("    echo \"auth_refresh_failed: no access_token returned from token endpoint\" >&2");
  lines.push("    return 3");
  lines.push("  fi");
  lines.push("  return 0");
  lines.push("}");
  return lines;
}

function renderJsonHelperSection(): string[] {
  return [
    "extract_json_field() {",
    "  local json=\"$1\"",
    "  local path=\"$2\"",
    "  if command -v python3 >/dev/null 2>&1; then",
    "    python3 - \"$json\" \"$path\" <<'PY'",
    "import json, sys",
    "raw = sys.argv[1]",
    "path = sys.argv[2].split('.') if sys.argv[2] else []",
    "try:",
    "    node = json.loads(raw)",
    "except Exception:",
    "    print('')",
    "    sys.exit(0)",
    "for seg in path:",
    "    if isinstance(node, dict) and seg in node:",
    "        node = node[seg]",
    "    else:",
    "        print('')",
    "        sys.exit(0)",
    "print('' if node is None else str(node))",
    "PY",
    "    return",
    "  fi",
    "  local key=\"${path##*.}\"",
    "  printf '%s' \"$json\" | sed -n \"s/.*\\\"$key\\\"[[:space:]]*:[[:space:]]*\\\"\\{0,1\\}\\([^\\\",}]*\\).*/\\1/p\" | head -n 1",
    "}",
    "",
  ];
}

function renderRequiredInputsSection(requiredInputs: RequiredInput[]): string[] {
  const lines: string[] = [];
  if (requiredInputs.length === 0) {
    lines.push("echo '[P00] no required placeholder inputs detected'");
    return lines;
  }
  lines.push("echo '[P00] preparing required placeholder inputs'");
  for (const input of requiredInputs) {
    const key = input.envKey;
    if (key === "AUTH_BEARER") {
      lines.push("if [ -z \"${AUTH_BEARER:-}\" ] && [ -n \"${AUTH_BEARER_TOKEN:-}\" ]; then AUTH_BEARER=\"${AUTH_BEARER_TOKEN}\"; fi");
      lines.push("export AUTH_BEARER");
      continue;
    }
    if (key.endsWith("BASE_URL") && typeof input.defaultValue !== "string") {
      lines.push(`if [ -z "\${${key}:-}" ]; then echo "missing_required_input: ${key} (set ${key} or provide plan providedContext/probe-config runtime.port)" >&2; exit 1; fi`);
      lines.push(`export ${key}`);
      continue;
    }
    const defaultValue = defaultValueForEnvVar(key, input.defaultValue).replace(/"/g, '\\"');
    lines.push(`if [ -z "\${${key}:-}" ]; then ${key}="${defaultValue}"; echo "auto_input_defaulted: ${key}" >&2; fi`);
    lines.push(`export ${key}`);
  }
  return lines;
}

function renderPostStartupAuthSection(requiredInputs: RequiredInput[]): string[] {
  if (!requiresAuthBearer(requiredInputs)) {
    return ["echo '[A00] auth bootstrap skipped; no AUTH_BEARER placeholder detected'"];
  }
  return [
    "echo '[A01] refreshing auth after runtime health gate'",
    "if { [ -z \"${AUTH_BEARER:-}\" ] || [ \"${AUTH_BEARER}\" = \"REDACTED_TOKEN\" ]; } && can_refresh_auth_bearer; then",
    "  refresh_auth_bearer",
    "fi",
    "if [ -z \"${AUTH_BEARER:-}\" ] || [ \"${AUTH_BEARER}\" = \"REDACTED_TOKEN\" ]; then echo \"missing_required_input: AUTH_BEARER (set AUTH_BEARER or KEYCLOAK_* bootstrap vars)\" >&2; exit 1; fi",
    "export AUTH_BEARER",
  ];
}

function renderWorkspaceBootstrapSection(input: { workspace: Record<string, unknown> | undefined }): WorkspaceEnvBinding {
  const lines: string[] = [];
  const reloadLines: string[] = [];
  reloadLines.push("  local force_refresh=\"${1:-}\"");
  reloadLines.push("  local preserved_auth_bearer=\"${AUTH_BEARER:-}\"");
  reloadLines.push("  local preserved_auth_bearer_token=\"${AUTH_BEARER_TOKEN:-}\"");
  reloadLines.push("  local preserved_keycloak_base_url=\"${KEYCLOAK_BASE_URL:-}\"");
  reloadLines.push("  local preserved_keycloak_client_id=\"${KEYCLOAK_CLIENT_ID:-}\"");
  reloadLines.push("  local preserved_keycloak_client_secret=\"${KEYCLOAK_CLIENT_SECRET:-}\"");
  reloadLines.push("  local preserved_keycloak_password=\"${KEYCLOAK_PASSWORD:-}\"");
  reloadLines.push("  local preserved_keycloak_realm=\"${KEYCLOAK_REALM:-}\"");
  reloadLines.push("  local preserved_keycloak_scope=\"${KEYCLOAK_SCOPE:-}\"");
  reloadLines.push("  local preserved_keycloak_username=\"${KEYCLOAK_USERNAME:-}\"");
  reloadLines.push("  local preserved_target_base_url=\"${TARGET_BASE_URL:-}\"");
  reloadLines.push("  if [ -f \"${__MCPJVM_PROJECT_ENV}\" ]; then set -a; . \"${__MCPJVM_PROJECT_ENV}\"; set +a; fi");
  reloadLines.push("  if [ -n \"${preserved_auth_bearer}\" ]; then AUTH_BEARER=\"${preserved_auth_bearer}\"; fi");
  reloadLines.push("  if [ -n \"${preserved_auth_bearer_token}\" ]; then AUTH_BEARER_TOKEN=\"${preserved_auth_bearer_token}\"; fi");
  reloadLines.push("  if [ -n \"${preserved_keycloak_base_url}\" ]; then KEYCLOAK_BASE_URL=\"${preserved_keycloak_base_url}\"; fi");
  reloadLines.push("  if [ -n \"${preserved_keycloak_client_id}\" ]; then KEYCLOAK_CLIENT_ID=\"${preserved_keycloak_client_id}\"; fi");
  reloadLines.push("  if [ -n \"${preserved_keycloak_client_secret}\" ]; then KEYCLOAK_CLIENT_SECRET=\"${preserved_keycloak_client_secret}\"; fi");
  reloadLines.push("  if [ -n \"${preserved_keycloak_password}\" ]; then KEYCLOAK_PASSWORD=\"${preserved_keycloak_password}\"; fi");
  reloadLines.push("  if [ -n \"${preserved_keycloak_realm}\" ]; then KEYCLOAK_REALM=\"${preserved_keycloak_realm}\"; fi");
  reloadLines.push("  if [ -n \"${preserved_keycloak_scope}\" ]; then KEYCLOAK_SCOPE=\"${preserved_keycloak_scope}\"; fi");
  reloadLines.push("  if [ -n \"${preserved_keycloak_username}\" ]; then KEYCLOAK_USERNAME=\"${preserved_keycloak_username}\"; fi");
  reloadLines.push("  if [ -n \"${preserved_target_base_url}\" ]; then TARGET_BASE_URL=\"${preserved_target_base_url}\"; fi");
  const vars = input.workspace?.variables;
  if (vars && typeof vars === "object" && !Array.isArray(vars)) {
    const varsRecord = vars as Record<string, unknown>;
    const bearerTokenEnv = typeof varsRecord.bearerTokenEnv === "string" ? String(varsRecord.bearerTokenEnv).trim() : "";
    if (bearerTokenEnv.length > 0) {
      reloadLines.push(`  if [ "\${force_refresh}" != "force" ] && [ -z "\${AUTH_BEARER:-}" ] && [ -n "\${${bearerTokenEnv}:-}" ]; then AUTH_BEARER="\${${bearerTokenEnv}}"; fi`);
      reloadLines.push("  export AUTH_BEARER");
    }
    const keycloakMap: Array<{ sourceKey: string; targetVar: string }> = [
      { sourceKey: "keycloakClientIdEnv", targetVar: "KEYCLOAK_CLIENT_ID" },
      { sourceKey: "keycloakClientSecretEnv", targetVar: "KEYCLOAK_CLIENT_SECRET" },
      { sourceKey: "keycloakUsernameEnv", targetVar: "KEYCLOAK_USERNAME" },
      { sourceKey: "keycloakPasswordEnv", targetVar: "KEYCLOAK_PASSWORD" },
    ];
    for (const mapping of keycloakMap) {
      const envKeyRef = typeof varsRecord[mapping.sourceKey] === "string" ? String(varsRecord[mapping.sourceKey]).trim() : "";
      if (envKeyRef.length === 0) continue;
      reloadLines.push(`  if [ -z "\${${mapping.targetVar}:-}" ] && [ -n "\${${envKeyRef}:-}" ]; then ${mapping.targetVar}="\${${envKeyRef}}"; fi`);
      reloadLines.push(`  export ${mapping.targetVar}`);
    }
  }
  lines.push("reload_workspace_env() {");
  lines.push(...reloadLines);
  lines.push("}");
  lines.push("reload_workspace_env");
  return { lines, reloadCommand: "reload_workspace_env" };
}

function shellQuote(value: string): string {
  return `"${value.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}

function resolveScriptCommand(input: { command: string; scriptPathAbs: string; args: string[] }): string {
  const argText = input.args.map((arg) => shellQuote(arg)).join(" ");
  const scriptArg = shellQuote(input.scriptPathAbs);
  if (input.command === "python") return `python ${scriptArg}${argText ? ` ${argText}` : ""}`;
  if (input.command === "node") return `node ${scriptArg}${argText ? ` ${argText}` : ""}`;
  if (input.command === "ps") return `powershell -NoProfile -ExecutionPolicy Bypass -File ${scriptArg}${argText ? ` ${argText}` : ""}`;
  return `bash ${scriptArg}${argText ? ` ${argText}` : ""}`;
}

function sanitizeAssetName(value: string, fallback: string): string {
  const sanitized = value
    .trim()
    .replace(/[^A-Za-z0-9._-]/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_+|_+$/g, "");
  return sanitized.length > 0 ? sanitized : fallback;
}

function renderEmbeddedScriptAsset(input: {
  id: string;
  order: number;
  scriptPathAbs: string;
  scriptPathForShell: string;
  scriptText: string | null;
}): { assetPathExpr: string; lines: string[] } {
  const assetName = sanitizeAssetName(`${String(input.order).padStart(2, "0")}-${input.id}`, `script-${input.order}`);
  const assetPathExpr = `"${"$"}__MCPJVM_EXPORT_TMP/${assetName}"`;
  if (input.scriptText === null) {
    return {
      assetPathExpr: shellQuote(input.scriptPathForShell),
      lines: [`echo "prerequisite_script_referenced: ${input.scriptPathForShell}" >&2`],
    };
  }

  const heredoc = `__MCPJVM_PREREQ_${String(input.order).padStart(2, "0")}__`;
  return {
    assetPathExpr,
    lines: [
      `cat > ${assetPathExpr} <<'${heredoc}'`,
      input.scriptText.replace(/\r\n/g, "\n").replace(/\r/g, "\n"),
      heredoc,
      `chmod +x ${assetPathExpr}`,
    ],
  };
}

function renderPrerequisiteFailure(onFail: string, id: string): string[] {
  if (onFail === "skip_remaining") return [`echo "prerequisite_failed_skip_remaining: ${id}" >&2`, "exit 0"];
  return [`echo "prerequisite_failed_block: ${id}" >&2`, "exit 1"];
}

async function renderRunPrerequisitesSection(input: {
  workspaceRootAbs: string;
  workspace: Record<string, unknown> | undefined;
}): Promise<string[]> {
  if (!input.workspace || !Array.isArray(input.workspace.runPrerequisites)) return [];
  const entries = input.workspace.runPrerequisites
    .filter((row): row is Record<string, unknown> => typeof row === "object" && row !== null && !Array.isArray(row))
    .sort((a, b) => Number(a.order ?? 0) - Number(b.order ?? 0));
  const lines: string[] = [];
  let needsTempDir = false;
  for (const entry of entries) {
    const id = typeof entry.id === "string" && entry.id.trim().length > 0 ? entry.id.trim() : "prerequisite";
    const onFail = typeof entry.onFail === "string" ? entry.onFail : "block";
    const type = typeof entry.type === "string" ? entry.type : "";
    lines.push(`echo "[PR] ${id}"`);
    if (type === "assert" && typeof entry.assert === "object" && entry.assert !== null && !Array.isArray(entry.assert)) {
      const assertObj = entry.assert as Record<string, unknown>;
      const kind = typeof assertObj.kind === "string" ? assertObj.kind : "";
      if (kind === "env_exists" || kind === "context_exists") {
        const rawKey = typeof assertObj.key === "string" ? assertObj.key : "";
        const envKey = toShellEnvKey(rawKey);
        lines.push(`if [ -z "\${${envKey}:-}" ] && [ -z "\${${rawKey}:-}" ]; then`);
        lines.push(...renderPrerequisiteFailure(onFail, id).map((line) => `  ${line}`));
        lines.push("fi");
      }
      lines.push("");
      continue;
    }
    if (type === "script" && typeof entry.script === "object" && entry.script !== null && !Array.isArray(entry.script)) {
      const scriptObj = entry.script as Record<string, unknown>;
      const command = typeof scriptObj.command === "string" ? scriptObj.command : "sh";
      const scriptPath = typeof scriptObj.scriptPath === "string" ? scriptObj.scriptPath : "";
      const scriptPathAbs = path.join(input.workspaceRootAbs, scriptPath).replaceAll("\\", "/");
      const scriptPathForShell = toWorkspaceShellPath({
        workspaceRootAbs: input.workspaceRootAbs,
        rawPath: scriptPath,
      });
      const args = Array.isArray(scriptObj.args) ? scriptObj.args.filter((a): a is string => typeof a === "string") : [];
      const cwd = typeof scriptObj.cwd === "string" && scriptObj.cwd.trim().length > 0
        ? toWorkspaceShellPath({ workspaceRootAbs: input.workspaceRootAbs, rawPath: scriptObj.cwd })
        : "";
      const scriptText = await fs.readFile(scriptPathAbs, "utf8").catch(() => null);
      const embedded = renderEmbeddedScriptAsset({
        id,
        order: Number(entry.order ?? 0),
        scriptPathAbs,
        scriptPathForShell,
        scriptText,
      });
      if (scriptText !== null) needsTempDir = true;
      lines.push(...embedded.lines);
      const baseCommand = resolveScriptCommand({
        command,
        scriptPathAbs: embedded.assetPathExpr.replace(/^"|"$/g, ""),
        args,
      });
      const wrappedCommand = cwd ? `(cd ${shellQuote(cwd)} && ${baseCommand})` : baseCommand;
      lines.push(`PR_OUT="$(${wrappedCommand} 2>&1)"`);
      lines.push("if [ $? -ne 0 ]; then");
      lines.push(...renderPrerequisiteFailure(onFail, id).map((line) => `  ${line}`));
      lines.push("fi");
      lines.push("while IFS= read -r __line; do");
      lines.push("  if printf '%s' \"$__line\" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]*='; then");
      lines.push("    __key=\"${__line%%=*}\"");
      lines.push("    __value=\"${__line#*=}\"");
      lines.push("    export \"${__key}=${__value}\"");
      lines.push("    echo \"prerequisite_exported: ${__key}\"");
      lines.push("  fi");
      lines.push("done <<__PR_EOF__");
      lines.push("${PR_OUT}");
      lines.push("__PR_EOF__");
      lines.push("");
    }
  }
  if (needsTempDir) {
    lines.unshift('mkdir -p "${__MCPJVM_EXPORT_TMP}"');
    lines.unshift('__MCPJVM_EXPORT_TMP="${TMPDIR:-/tmp}/mcpjvm-execution-profile-$$"');
    lines.unshift('trap \'rm -rf "${__MCPJVM_EXPORT_TMP:-}"\' EXIT');
  }
  return lines;
}

function stringifyDefault(value: unknown): string | undefined {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return undefined;
}

async function collectPlanRequiredInputs(input: {
  workspaceRootAbs: string;
  workspace: Record<string, unknown> | undefined;
  projectName?: string;
  executionProfile: string;
  planRuns: ExecutionProfileExportPlanRun[];
}): Promise<RequiredInput[]> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs, input.projectName);
  const planBaseUrls = await resolvePlanBaseUrls({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace: input.workspace,
    executionProfile: input.executionProfile,
    planRuns: input.planRuns,
  });
  const inputs = new Map<string, RequiredInput>();
  for (const plan of input.planRuns) {
    const contract = await loadPlanContract({ plansRootAbs, planName: plan.planName });
    if (!contract) continue;
    for (const prerequisite of contract.prerequisites) {
      if (prerequisite.required !== true && typeof prerequisite.default === "undefined") continue;
      const envKey = toShellEnvKey(prerequisite.key);
      const resolvedPlanBaseUrl = envKey === "TARGET_BASE_URL" || prerequisite.key === "targetBaseUrl"
        ? planBaseUrls[plan.planName]
        : undefined;
      const defaultValue = prerequisite.secret
        ? undefined
        : (resolvedPlanBaseUrl ?? stringifyDefault(prerequisite.default));
      mergeRequiredInput(inputs, { envKey, ...(typeof defaultValue === "string" ? { defaultValue } : {}) });
    }
  }
  return [...inputs.values()];
}

export async function buildShPrerequisitesSections(input: {
  workspaceRootAbs: string;
  projectName?: string;
  workspace: Record<string, unknown> | undefined;
  executionProfile: string;
  planRuns: ExecutionProfileExportPlanRun[];
  planExecutionSection: string[];
}): Promise<ShPrerequisitesSections> {
  const requiredInputs = new Map<string, RequiredInput>();
  for (const envKey of extractRequiredEnvVars(input.planExecutionSection)) {
    mergeRequiredInput(requiredInputs, { envKey });
  }
  for (const requiredInput of await collectPlanRequiredInputs({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace: input.workspace,
    ...(typeof input.projectName === "string" && input.projectName.trim().length > 0
      ? { projectName: input.projectName.trim() }
      : {}),
    executionProfile: input.executionProfile,
    planRuns: input.planRuns,
  })) {
    mergeRequiredInput(requiredInputs, requiredInput);
  }
  const workspaceEnvBinding = renderWorkspaceBootstrapSection({ workspace: input.workspace });
  const orderedRequiredInputs = [...requiredInputs.values()].sort((left, right) =>
    left.envKey.localeCompare(right.envKey),
  );
  const prerequisitesSection = [
    ...workspaceEnvBinding.lines,
    ...(await renderRunPrerequisitesSection({ workspaceRootAbs: input.workspaceRootAbs, workspace: input.workspace })),
    ...renderJsonHelperSection(),
    ...renderAuthRefreshFunction({
      reloadWorkspaceEnvCommand: workspaceEnvBinding.reloadCommand,
    }),
    "",
    ...renderRequiredInputsSection(orderedRequiredInputs),
  ];
  return {
    prerequisitesSection,
    postStartupAuthSection: renderPostStartupAuthSection(orderedRequiredInputs),
  };
}
