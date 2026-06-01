import { escapeShSingleQuoted } from "@tools-export-execution-profile/common";
import { toShellEnvKey } from "@tools-export-execution-profile/common";
import { loadPlanContract } from "@tools-export-execution-profile/loaders/plan_contract.loader";
import type { ExecutionProfileExportPlanRun } from "@tools-export-execution-profile/models/execution_profile_export.model";
import { renderShTransportStep } from "@tools-export-execution-profile/adapters/registry/transport_export_adapter.registry";
import type { PlanStep } from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";

function appendPlanFailClosed(lines: string[], reason: string): void {
  lines.push(`echo 'export_plan_blocked: ${escapeShSingleQuoted(reason)}' >&2`);
  lines.push("exit 1");
  lines.push("");
}

function renderExtractLines(step: PlanStep, responseVar: string): string[] {
  const lines: string[] = [];
  if (!Array.isArray(step.extract) || step.extract.length === 0) {
    return lines;
  }
  for (const mapping of step.extract) {
    if (!mapping || typeof mapping.from !== "string" || typeof mapping.as !== "string") {
      continue;
    }
    const from = mapping.from.trim();
    if (!from.startsWith("response.body.")) {
      continue;
    }
    const fieldPath = from.slice("response.body.".length);
    if (!fieldPath) continue;
    const envKey = toShellEnvKey(mapping.as);
    lines.push(`${envKey}="$(extract_json_field "$${responseVar}" "${fieldPath}")"`);
    lines.push(`if [ -z "\${${envKey}:-}" ]; then echo "extract_failed: ${envKey} from ${fieldPath}" >&2; exit 1; fi`);
    lines.push(`export ${envKey}`);
  }
  return lines;
}

export async function renderShPlanExecutionSection(input: {
  planRuns: ExecutionProfileExportPlanRun[];
  plansRootAbs: string;
  planBaseUrls?: Record<string, string>;
}): Promise<string[]> {
  const ordered = [...input.planRuns].sort((left, right) => left.order - right.order);
  const lines: string[] = [];

  for (const plan of ordered) {
    lines.push(`echo '[E${String(plan.order).padStart(2, "0")}] ${escapeShSingleQuoted(plan.planName)} status=${escapeShSingleQuoted(plan.status)}'`);
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
      lines.push(`echo '[${escapeShSingleQuoted(plan.planName)}:${String(step.order).padStart(2, "0")}] ${escapeShSingleQuoted(step.id)} status=planned'`);

      const rendered = renderShTransportStep({
        planName: plan.planName,
        step,
        contextResolved: input.planBaseUrls?.[plan.planName]
          ? { apiBaseUrl: input.planBaseUrls[plan.planName] }
          : {},
      });
      if (!rendered.handled || rendered.lines.length === 0) {
        appendPlanFailClosed(lines, `unsupported or unresolved transport at step ${step.id}`);
        emittedAnyStep = true;
        break;
      }
      const responseVar = `STEP_${String(plan.order).padStart(2, "0")}_${String(step.order).padStart(2, "0")}_RESPONSE`;
      lines.push("attempt=0");
      lines.push("while true; do");
      lines.push("  set +e");
      lines.push(`  __step_out="$(${rendered.lines[0]} 2>&1)"`);
      lines.push("  __step_rc=$?");
      lines.push("  set -e");
      lines.push(`  if [ $__step_rc -eq 0 ]; then ${responseVar}="$__step_out"; break; fi`);
      lines.push("  if printf '%s' \"$__step_out\" | grep -Eqi '(^|[^0-9])401([^0-9]|$)|unauthorized'; then");
      lines.push("    __failed_auth=\"${AUTH_BEARER:-}\"");
      lines.push("    if command -v invoke_posthealthcheck_scripts >/dev/null 2>&1; then invoke_posthealthcheck_scripts; fi");
      lines.push("    if [ -n \"${AUTH_BEARER:-}\" ] && [ \"${AUTH_BEARER}\" != \"${__failed_auth}\" ]; then");
      lines.push("      attempt=$((attempt+1))");
      lines.push("      if [ $attempt -ge 30 ]; then echo 'endpoint auth refresh failed after retries' >&2; exit 1; fi");
      lines.push("      sleep 2");
      lines.push("      continue");
      lines.push("    fi");
      lines.push("    if can_refresh_auth_bearer; then");
      lines.push("      AUTH_BEARER=\"\"");
      lines.push("      if ! refresh_auth_bearer force; then echo 'endpoint auth refresh failed: refresh_unavailable_or_failed' >&2; exit 1; fi");
      lines.push("      if [ -z \"${AUTH_BEARER:-}\" ] || [ \"${AUTH_BEARER}\" = \"${__failed_auth}\" ]; then echo 'endpoint auth refresh failed: stale_or_missing_token' >&2; exit 1; fi");
      lines.push("      attempt=$((attempt+1))");
      lines.push("      if [ $attempt -ge 30 ]; then echo 'endpoint auth refresh failed after retries' >&2; exit 1; fi");
      lines.push("      sleep 2");
      lines.push("      continue");
      lines.push("    fi");
      lines.push("    echo 'endpoint_auth_failed: received unauthorized response (401). Prerequisite auth scripts did not provide a usable credential.' >&2");
      lines.push("    exit 1");
      lines.push("  fi");
      lines.push("  attempt=$((attempt+1))");
      lines.push("  if [ $attempt -ge 30 ]; then echo 'endpoint execution failed after retries' >&2; exit 1; fi");
      lines.push("  sleep 2");
      lines.push("done");
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
