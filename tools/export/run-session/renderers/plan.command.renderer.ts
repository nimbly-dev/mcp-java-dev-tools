import { escapePsSingleQuoted } from "@tools-export-run-session/common";
import type { RunSessionExportPlanRun } from "@tools-export-run-session/models/run_session_export.model";

export function renderPs1PlanExecutionSection(planRuns: RunSessionExportPlanRun[]): string[] {
  const ordered = [...planRuns].sort((left, right) => left.order - right.order);
  const lines: string[] = [];

  for (const plan of ordered) {
    lines.push(
      `Write-Host '[E${String(plan.order).padStart(2, "0")}] ${escapePsSingleQuoted(plan.planName)} status=${escapePsSingleQuoted(plan.status)}'`,
    );
    lines.push(`& $ReplayCommand --plan-name '${escapePsSingleQuoted(plan.planName)}'`);
    lines.push("if ($LASTEXITCODE -ne 0) { throw 'plan execution failed' }");
    lines.push("");
  }

  return lines;
}
