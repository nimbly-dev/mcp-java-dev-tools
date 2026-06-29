import { toShellEnvKey } from "@tools-export-execution-profile/common";
import { loadPlanContract } from "@tools-export-execution-profile/loaders/plan_contract.loader";
import type { ExecutionProfileExportPlanRun } from "@tools-export-execution-profile/models/execution_profile_export.model";
import { resolvePlanBaseUrls } from "@tools-export-execution-profile/sections/sh/plan_execution.section";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";

type RequiredInput = {
  envKey: string;
  defaultValue?: string;
};

function psSingleQuoted(value: string): string {
  return `'${value.replace(/'/g, "''")}'`;
}

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
  const regex = /\$\{env:([A-Za-z_][A-Za-z0-9_]*)\}/g;
  let match: RegExpExecArray | null = regex.exec(combined);
  while (match) {
    const key = match[1];
    if (typeof key === "string" && key.trim().length > 0) vars.add(key);
    match = regex.exec(combined);
  }
  return [...vars].sort();
}

function stringifyDefault(value: unknown): string | undefined {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return undefined;
}

function defaultValueForEnvVar(key: string, contractDefault?: string): string {
  if (typeof contractDefault === "string") return contractDefault;
  if (key.includes("GATEWAYBASEURL")) return "api";
  if (key.includes("EMAIL")) return "regression@example.test";
  if (key === "AUTH_BEARER") return "";
  if (key === "RUN_ID") return "$(Get-Date -Format 'yyyyMMddHHmmss')";
  if (key.endsWith("ID")) return "1";
  if (key.includes("TITLE")) return "Regression Course";
  if (key.includes("AUTHOR")) return "Regression Author";
  if (key.includes("CONTENT")) return "Regression content";
  return "REDACTED_OR_SET_ME";
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

function renderProjectEnvHelpers(workspace: Record<string, unknown> | undefined): string[] {
  const lines: string[] = [];
  lines.push("function Import-ProjectEnv {");
  lines.push("  if (-not (Test-Path -LiteralPath $script:McpJvmProjectEnv)) { return }");
  lines.push("  Get-Content -LiteralPath $script:McpJvmProjectEnv | ForEach-Object {");
  lines.push("    $line = $_.Trim()");
  lines.push("    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) { return }");
  lines.push("    $pair = $line -split '=', 2");
  lines.push("    if ($pair.Count -ne 2 -or $pair[0] -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { return }");
  lines.push("    $value = $pair[1]");
  lines.push("    if ($value.StartsWith('\"') -and $value.EndsWith('\"') -and $value.Length -ge 2) { $value = $value.Substring(1, $value.Length - 2) }");
  lines.push("    [Environment]::SetEnvironmentVariable($pair[0], $value, 'Process')");
  lines.push("  }");
  lines.push("}");
  lines.push("function Reload-WorkspaceEnv {");
  lines.push("  param([switch]$SkipAuthBearerFallback)");
  lines.push("  Import-ProjectEnv");
  lines.push("  if (-not $SkipAuthBearerFallback -and [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('AUTH_BEARER', 'Process')) -and -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('AUTH_BEARER_TOKEN', 'Process'))) {");
  lines.push("    [Environment]::SetEnvironmentVariable('AUTH_BEARER', [Environment]::GetEnvironmentVariable('AUTH_BEARER_TOKEN', 'Process'), 'Process')");
  lines.push("  }");
  const vars = workspace?.variables;
  if (vars && typeof vars === "object" && !Array.isArray(vars)) {
    const varsRecord = vars as Record<string, unknown>;
    const mappings: Array<{ sourceKey: string; targetVar: string }> = [
      { sourceKey: "keycloakClientIdEnv", targetVar: "KEYCLOAK_CLIENT_ID" },
      { sourceKey: "keycloakClientSecretEnv", targetVar: "KEYCLOAK_CLIENT_SECRET" },
      { sourceKey: "keycloakUsernameEnv", targetVar: "KEYCLOAK_USERNAME" },
      { sourceKey: "keycloakPasswordEnv", targetVar: "KEYCLOAK_PASSWORD" },
    ];
    for (const mapping of mappings) {
      const sourceEnv = typeof varsRecord[mapping.sourceKey] === "string" ? String(varsRecord[mapping.sourceKey]).trim() : "";
      if (!sourceEnv) continue;
      lines.push(`  if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('${mapping.targetVar}', 'Process')) -and -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('${sourceEnv}', 'Process'))) {`);
      lines.push(`    [Environment]::SetEnvironmentVariable('${mapping.targetVar}', [Environment]::GetEnvironmentVariable('${sourceEnv}', 'Process'), 'Process')`);
      lines.push("  }");
    }
  }
  lines.push("}");
  lines.push("Reload-WorkspaceEnv");
  return lines;
}

function renderJsonAndAuthHelpers(): string[] {
  return [
    "function Extract-JsonField([string]$Json, [string]$Path) {",
    "  try { $node = $Json | ConvertFrom-Json -ErrorAction Stop } catch { return '' }",
    "  foreach ($seg in ($Path -split '\\.')) {",
    "    if ([string]::IsNullOrWhiteSpace($seg)) { continue }",
    "    $remaining = $seg",
    "    while ($remaining.Length -gt 0) {",
    "      if ($remaining -match '^([^[\\]]+)(.*)$') {",
    "        $prop = $matches[1]",
    "        $remaining = $matches[2]",
    "        if ($null -eq $node.PSObject.Properties[$prop]) { return '' }",
    "        $node = $node.PSObject.Properties[$prop].Value",
    "        continue",
    "      }",
    "      if ($remaining -match '^\\[(\\d+)\\](.*)$') {",
    "        $index = [int]$matches[1]",
    "        $remaining = $matches[2]",
    "        if ($node -isnot [System.Collections.IList]) { return '' }",
    "        if ($index -lt 0 -or $index -ge $node.Count) { return '' }",
    "        $node = $node[$index]",
    "        continue",
    "      }",
    "      return ''",
    "    }",
    "  }",
    "  if ($null -eq $node) { return '' }",
    "  return [string]$node",
    "}",
    "function Can-RefreshAuthBearer {",
    "  $clientId = [Environment]::GetEnvironmentVariable('KEYCLOAK_CLIENT_ID', 'Process')",
    "  $clientSecret = [Environment]::GetEnvironmentVariable('KEYCLOAK_CLIENT_SECRET', 'Process')",
    "  $username = [Environment]::GetEnvironmentVariable('KEYCLOAK_USERNAME', 'Process')",
    "  $password = [Environment]::GetEnvironmentVariable('KEYCLOAK_PASSWORD', 'Process')",
    "  return ((-not [string]::IsNullOrWhiteSpace($clientId)) -and ((-not [string]::IsNullOrWhiteSpace($clientSecret)) -or ((-not [string]::IsNullOrWhiteSpace($username)) -and (-not [string]::IsNullOrWhiteSpace($password)))))",
    "}",
    "function Refresh-AuthBearer {",
    "  param([switch]$Force)",
    "  Reload-WorkspaceEnv -SkipAuthBearerFallback:$Force",
    "  $existing = [Environment]::GetEnvironmentVariable('AUTH_BEARER', 'Process')",
    "  if (-not $Force -and -not [string]::IsNullOrWhiteSpace($existing) -and $existing -ne 'REDACTED_TOKEN') { Write-Host 'auth_bootstrap_succeeded: AUTH_BEARER'; return }",
    "  $realm = [Environment]::GetEnvironmentVariable('KEYCLOAK_REALM', 'Process')",
    "  if ([string]::IsNullOrWhiteSpace($realm) -or -not (Can-RefreshAuthBearer)) {",
    "    if ($Force) { throw 'auth_refresh_unavailable: missing KEYCLOAK_* refresh prerequisites' }",
    "    return",
    "  }",
    "  $baseUrl = [Environment]::GetEnvironmentVariable('KEYCLOAK_BASE_URL', 'Process')",
    "  if ([string]::IsNullOrWhiteSpace($baseUrl)) { $baseUrl = 'http://127.0.0.1:8081' }",
    "  $scope = [Environment]::GetEnvironmentVariable('KEYCLOAK_SCOPE', 'Process')",
    "  if ([string]::IsNullOrWhiteSpace($scope)) { $scope = 'openid' }",
    "  $body = @{ client_id = [Environment]::GetEnvironmentVariable('KEYCLOAK_CLIENT_ID', 'Process'); scope = $scope }",
    "  $username = [Environment]::GetEnvironmentVariable('KEYCLOAK_USERNAME', 'Process')",
    "  $password = [Environment]::GetEnvironmentVariable('KEYCLOAK_PASSWORD', 'Process')",
    "  $clientSecret = [Environment]::GetEnvironmentVariable('KEYCLOAK_CLIENT_SECRET', 'Process')",
    "  if (-not [string]::IsNullOrWhiteSpace($username) -and -not [string]::IsNullOrWhiteSpace($password)) {",
    "    $body.grant_type = 'password'; $body.username = $username; $body.password = $password",
    "    if (-not [string]::IsNullOrWhiteSpace($clientSecret)) { $body.client_secret = $clientSecret }",
    "  } else {",
    "    $body.grant_type = 'client_credentials'; $body.client_secret = $clientSecret",
    "  }",
    "  try {",
    "    $response = Invoke-RestMethod -Method Post -Uri \"$baseUrl/realms/$realm/protocol/openid-connect/token\" -ContentType 'application/x-www-form-urlencoded' -Body $body",
    "    if ($response.access_token) {",
    "      [Environment]::SetEnvironmentVariable('AUTH_BEARER', [string]$response.access_token, 'Process')",
    "      [Environment]::SetEnvironmentVariable('AUTH_BEARER_TOKEN', [string]$response.access_token, 'Process')",
    "      Write-Host 'auth_bootstrap_succeeded: AUTH_BEARER'",
    "    }",
    "  } catch {",
    "    if ($Force) { throw 'auth_refresh_failed: token endpoint rejected credentials or request' }",
    "  }",
    "  if ($Force -and [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('AUTH_BEARER', 'Process'))) {",
    "    throw 'auth_refresh_failed: no access_token returned from token endpoint'",
    "  }",
    "}",
  ];
}

function renderRequiredInputsSection(requiredInputs: RequiredInput[]): string[] {
  if (requiredInputs.length === 0) {
    return ["Write-Host '[P00] no required placeholder inputs detected'"];
  }
  const lines: string[] = ["Write-Host '[P00] preparing required placeholder inputs'"];
  for (const input of requiredInputs) {
    const key = input.envKey;
    if (key === "AUTH_BEARER") {
      lines.push("if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('AUTH_BEARER', 'Process')) -and -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('AUTH_BEARER_TOKEN', 'Process'))) { [Environment]::SetEnvironmentVariable('AUTH_BEARER', [Environment]::GetEnvironmentVariable('AUTH_BEARER_TOKEN', 'Process'), 'Process') }");
      continue;
    }
    if (key.endsWith("BASE_URL") && typeof input.defaultValue !== "string") {
      lines.push(`if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('${key}', 'Process'))) { throw 'missing_required_input: ${key} (set ${key} or provide plan providedContext/probe-config runtime.port)' }`);
      continue;
    }
    const defaultValue = defaultValueForEnvVar(key, input.defaultValue);
    if (key === "RUN_ID" && typeof input.defaultValue !== "string") {
      lines.push(`if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('${key}', 'Process'))) { [Environment]::SetEnvironmentVariable('${key}', (Get-Date -Format 'yyyyMMddHHmmss'), 'Process'); Write-Warning 'auto_input_defaulted: ${key}' }`);
      continue;
    }
    lines.push(`if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('${key}', 'Process'))) { [Environment]::SetEnvironmentVariable('${key}', ${psSingleQuoted(defaultValue)}, 'Process'); Write-Warning 'auto_input_defaulted: ${key}' }`);
  }
  return lines;
}

function requiresAuthBearer(requiredInputs: RequiredInput[]): boolean {
  return requiredInputs.some((input) => input.envKey === "AUTH_BEARER");
}

function renderPostStartupAuthSection(requiredInputs: RequiredInput[]): string[] {
  if (!requiresAuthBearer(requiredInputs)) {
    return ["Write-Host '[A00] auth bootstrap skipped; no AUTH_BEARER placeholder detected'"];
  }
  return [
    "Write-Host '[A01] refreshing auth after runtime health gate'",
    "$authBearer = [Environment]::GetEnvironmentVariable('AUTH_BEARER', 'Process')",
    "if (([string]::IsNullOrWhiteSpace($authBearer) -or $authBearer -eq 'REDACTED_TOKEN') -and (Can-RefreshAuthBearer)) { Refresh-AuthBearer }",
    "$authBearer = [Environment]::GetEnvironmentVariable('AUTH_BEARER', 'Process')",
    "if ([string]::IsNullOrWhiteSpace($authBearer) -or $authBearer -eq 'REDACTED_TOKEN') { throw 'missing_required_input: AUTH_BEARER (set AUTH_BEARER or KEYCLOAK_* bootstrap vars)' }",
  ];
}

export async function buildPs1PrerequisitesSections(input: {
  workspaceRootAbs: string;
  projectName?: string;
  workspace: Record<string, unknown> | undefined;
  executionProfile: string;
  planRuns: ExecutionProfileExportPlanRun[];
  planExecutionSection: string[];
}): Promise<{ prerequisitesSection: string[]; postStartupAuthSection: string[] }> {
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
  const orderedRequiredInputs = [...requiredInputs.values()].sort((left, right) =>
    left.envKey.localeCompare(right.envKey),
  );
  return {
    prerequisitesSection: [
      ...renderProjectEnvHelpers(input.workspace),
      ...renderJsonAndAuthHelpers(),
      "",
      ...renderRequiredInputsSection(orderedRequiredInputs),
    ],
    postStartupAuthSection: renderPostStartupAuthSection(orderedRequiredInputs),
  };
}
