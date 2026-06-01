import { escapePsSingleQuoted } from "@tools-export-execution-profile/common";
import { toShellEnvKey } from "@tools-export-execution-profile/common";
import { asRecord } from "@tools-export-execution-profile/adapters/http/http_shared.util";
import { loadPlanContract } from "@tools-export-execution-profile/loaders/plan_contract.loader";
import type { ExecutionProfileExportPlanRun } from "@tools-export-execution-profile/models/execution_profile_export.model";
import type { PlanStep } from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import { resolveStepTransport } from "@tools-regression-execution-plan-spec/regression_execution_plan_spec.util";

function psDoubleQuoted(value: string): string {
  return `"${value.replace(/`/g, "``").replace(/"/g, '`"')}"`;
}

function psSingleQuoted(value: string): string {
  return `'${value.replace(/'/g, "''")}'`;
}

function normalizePlaceholders(value: string): string {
  return value.replace(/\$\{([^}]+)\}/g, (_full, key: string) => `\${env:${toShellEnvKey(key)}}`);
}

function startsWithAbsoluteUrl(value: string): boolean {
  return /^https?:\/\//i.test(value) || /^\$\{env:[A-Za-z_][A-Za-z0-9_]*\}/.test(value);
}

type PsHttpRequest = {
  method: string;
  url: string;
  headers: Array<{ key: string; value: string }>;
  body?: string;
};

function buildPsHttpRequest(http: Record<string, unknown>, context: Record<string, unknown>): PsHttpRequest | null {
  const method = typeof http.method === "string" && http.method.trim().length > 0 ? http.method.trim().toUpperCase() : "GET";
  const directUrl = typeof http.url === "string" && http.url.trim().length > 0 ? normalizePlaceholders(http.url.trim()) : undefined;
  const pathTemplate =
    typeof http.pathTemplate === "string" && http.pathTemplate.trim().length > 0 ? normalizePlaceholders(http.pathTemplate.trim()) : undefined;
  const apiBaseUrl =
    typeof context.apiBaseUrl === "string" && context.apiBaseUrl.trim().length > 0 ? normalizePlaceholders(context.apiBaseUrl.trim()) : undefined;

  let url = directUrl;
  if (!url && pathTemplate && apiBaseUrl) {
    url = startsWithAbsoluteUrl(pathTemplate)
      ? pathTemplate
      : `${apiBaseUrl.replace(/\/$/, "")}${pathTemplate.startsWith("/") ? "" : "/"}${pathTemplate}`;
  }
  if (!url && pathTemplate) {
    url = startsWithAbsoluteUrl(pathTemplate)
      ? pathTemplate
      : `\${env:API_BASE_URL}${pathTemplate.startsWith("/") ? "" : "/"}${pathTemplate}`;
  }
  if (!url) return null;

  const headersOut: Array<{ key: string; value: string }> = [];
  const headers = asRecord(http.headers);
  if (headers) {
    for (const [key, rawValue] of Object.entries(headers)) {
      headersOut.push({ key, value: normalizePlaceholders(String(rawValue)) });
    }
  }
  let body: string | undefined;
  if (typeof http.body === "string") {
    body = normalizePlaceholders(http.body);
  } else if (http.body !== null && typeof http.body === "object" && !Array.isArray(http.body)) {
    const bodyText = normalizePlaceholders(JSON.stringify(http.body));
    const hasContentType = headers
      ? Object.keys(headers).some((key) => key.toLowerCase() === "content-type")
      : false;
    if (!hasContentType) {
      headersOut.push({ key: "Content-Type", value: "application/json" });
    }
    body = bodyText;
  }
  return { method, url: normalizePlaceholders(url), headers: headersOut, ...(typeof body === "string" ? { body } : {}) };
}

function resolvePsHttpRequest(input: {
  step: PlanStep;
  contextResolved: Record<string, unknown>;
}): PsHttpRequest | null {
  let resolvedTransport: Record<string, unknown>;
  try {
    resolvedTransport = resolveStepTransport(input.step, input.contextResolved);
  } catch {
    resolvedTransport = input.step.transport as Record<string, unknown>;
  }
  const http = asRecord(resolvedTransport.http);
  if (!http) return null;
  return buildPsHttpRequest(http, input.contextResolved);
}

function renderRequestSetupLines(request: PsHttpRequest): string[] {
  const lines: string[] = [];
  lines.push(`$__step_uri = ${psDoubleQuoted(request.url)}`);
  lines.push("$__step_headers = @{}");
  for (const header of request.headers) {
    lines.push(`$__step_headers[${psSingleQuoted(header.key)}] = ${psDoubleQuoted(header.value)}`);
  }
  if (typeof request.body === "string") {
    lines.push('$__step_body = @"');
    lines.push(request.body);
    lines.push('"@');
  } else {
    lines.push("$__step_body = $null");
  }
  lines.push("$__step_request = @{ Method = " + psSingleQuoted(request.method) + "; Uri = $__step_uri; Headers = $__step_headers; UseBasicParsing = $true }");
  lines.push("if ($null -ne $__step_body) { $__step_request.Body = $__step_body }");
  return lines;
}

function appendPlanFailClosed(lines: string[], reason: string): void {
  lines.push(`Write-Error 'export_plan_blocked: ${escapePsSingleQuoted(reason)}'`);
  lines.push("exit 1");
  lines.push("");
}

function renderExtractLines(step: PlanStep, responseVar: string): string[] {
  const lines: string[] = [];
  if (!Array.isArray(step.extract) || step.extract.length === 0) return lines;
  for (const mapping of step.extract) {
    if (!mapping || typeof mapping.from !== "string" || typeof mapping.as !== "string") continue;
    const from = mapping.from.trim();
    if (!from.startsWith("response.body.")) continue;
    const fieldPath = from.slice("response.body.".length);
    if (!fieldPath) continue;
    const envKey = toShellEnvKey(mapping.as);
    lines.push(`[Environment]::SetEnvironmentVariable('${envKey}', (Extract-JsonField $${responseVar} '${escapePsSingleQuoted(fieldPath)}'), 'Process')`);
    lines.push(`if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('${envKey}', 'Process'))) { throw 'extract_failed: ${envKey} from ${escapePsSingleQuoted(fieldPath)}' }`);
  }
  return lines;
}

export async function renderPs1PlanExecutionSection(input: {
  planRuns: ExecutionProfileExportPlanRun[];
  plansRootAbs: string;
  planBaseUrls?: Record<string, string>;
}): Promise<string[]> {
  const ordered = [...input.planRuns].sort((left, right) => left.order - right.order);
  const lines: string[] = [];

  for (const plan of ordered) {
    lines.push(`Write-Host '[E${String(plan.order).padStart(2, "0")}] ${escapePsSingleQuoted(plan.planName)} status=${escapePsSingleQuoted(plan.status)}'`);
    const contract = await loadPlanContract({
      plansRootAbs: input.plansRootAbs,
      planName: plan.planName,
    });
    if (!contract) {
      appendPlanFailClosed(lines, `plan contract unavailable: ${plan.planName}`);
      continue;
    }

    let emittedAnyStep = false;
    for (const step of [...contract.steps].sort((left, right) => left.order - right.order)) {
      lines.push(`Write-Host '[${escapePsSingleQuoted(plan.planName)}:${String(step.order).padStart(2, "0")}] ${escapePsSingleQuoted(step.id)} status=planned'`);
      const request = resolvePsHttpRequest({
        step,
        contextResolved: input.planBaseUrls?.[plan.planName]
          ? { apiBaseUrl: input.planBaseUrls[plan.planName] }
          : {},
      });
      if (!request) {
        appendPlanFailClosed(lines, `unsupported or unresolved transport at step ${step.id}`);
        emittedAnyStep = true;
        break;
      }
      const responseVar = `STEP_${String(plan.order).padStart(2, "0")}_${String(step.order).padStart(2, "0")}_RESPONSE`;
      lines.push(...renderRequestSetupLines(request));
      lines.push("$attempt = 0");
      lines.push("while ($true) {");
      lines.push("  try {");
      lines.push("    $__step_response = Invoke-WebRequest @__step_request");
      lines.push("    $__step_text = [string]$__step_response.Content");
      lines.push(`    $${responseVar} = $__step_text`);
      lines.push("    break");
      lines.push("  } catch {");
      lines.push("    $__step_text = [string]$_");
      lines.push("    if ($_.Exception.Response) {");
      lines.push("      try {");
      lines.push("        $__reader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())");
      lines.push("        $__response_body = $__reader.ReadToEnd()");
      lines.push("        if (-not [string]::IsNullOrWhiteSpace($__response_body)) { $__step_text = \"$__step_text $__response_body\" }");
      lines.push("      } catch { }");
      lines.push("    }");
      lines.push("  }");
      lines.push("  if ($__step_text -match '(?i)(^|[^0-9])401([^0-9]|$)|unauthorized') {");
      lines.push("    $__failed_auth = [Environment]::GetEnvironmentVariable('AUTH_BEARER', 'Process')");
      lines.push("    if (Get-Command Invoke-PostHealthcheckScripts -ErrorAction SilentlyContinue) { Invoke-PostHealthcheckScripts }");
      lines.push("    $__script_refreshed_auth = [Environment]::GetEnvironmentVariable('AUTH_BEARER', 'Process')");
      lines.push("    if (-not [string]::IsNullOrWhiteSpace($__script_refreshed_auth) -and $__script_refreshed_auth -ne $__failed_auth) {");
      lines.push("      $attempt += 1");
      lines.push("      if ($attempt -ge 30) { throw 'endpoint auth refresh failed after retries' }");
      lines.push("      Start-Sleep -Seconds 2");
      lines.push("      continue");
      lines.push("    }");
      lines.push("    if (Can-RefreshAuthBearer) {");
      lines.push("      [Environment]::SetEnvironmentVariable('AUTH_BEARER', '', 'Process')");
      lines.push("      Refresh-AuthBearer -Force");
      lines.push("      $__refreshed_auth = [Environment]::GetEnvironmentVariable('AUTH_BEARER', 'Process')");
      lines.push("      if ([string]::IsNullOrWhiteSpace($__refreshed_auth) -or $__refreshed_auth -eq $__failed_auth) { throw 'endpoint auth refresh failed: stale_or_missing_token' }");
      lines.push("      $attempt += 1");
      lines.push("      if ($attempt -ge 30) { throw 'endpoint auth refresh failed after retries' }");
      lines.push("      Start-Sleep -Seconds 2");
      lines.push("      continue");
      lines.push("    }");
      lines.push("    throw 'endpoint_auth_failed: received unauthorized response (401). Prerequisite auth scripts did not provide a usable credential.'");
      lines.push("  }");
      lines.push("  $attempt += 1");
      lines.push("  if ($attempt -ge 30) { throw \"endpoint execution failed after retries: $__step_text\" }");
      lines.push("  Start-Sleep -Seconds 2");
      lines.push("}");
      lines.push(...renderExtractLines(step, responseVar));
      lines.push("");
      emittedAnyStep = true;
    }

    if (!emittedAnyStep) {
      appendPlanFailClosed(lines, `no executable steps resolved for ${plan.planName}`);
    }
  }

  return lines;
}
