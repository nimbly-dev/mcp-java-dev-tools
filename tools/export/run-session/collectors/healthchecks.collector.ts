import { asString, isRecord } from "@tools-export-run-session/common";
import type { Healthcheck } from "@tools-export-run-session/models/run_session_export.model";

export function collectHealthchecks(workspace: Record<string, unknown> | undefined): Healthcheck[] {
  if (!workspace) {
    return [];
  }
  const externalSystems = Array.isArray(workspace.externalSystems) ? workspace.externalSystems : [];
  const checks: Healthcheck[] = [];

  for (const system of externalSystems) {
    if (!isRecord(system) || !Array.isArray(system.healthChecks)) {
      continue;
    }
    const systemName = asString(system.name) ?? "external-system";

    for (const check of system.healthChecks) {
      if (!isRecord(check)) {
        continue;
      }
      const checkId = asString(check.id) ?? `check-${checks.length + 1}`;
      const typeRaw = asString(check.type);
      const required = check.required === true;

      if (typeRaw === "tcp") {
        const target = asString(check.target);
        if (!target) {
          continue;
        }
        checks.push({
          id: `H${String(checks.length + 1).padStart(2, "0")}`,
          title: `${systemName}:${checkId} tcp ${target}`,
          required,
          type: "tcp",
          target,
        });
        continue;
      }

      if (typeRaw === "http") {
        const url = asString(check.url);
        if (!url) {
          continue;
        }
        checks.push({
          id: `H${String(checks.length + 1).padStart(2, "0")}`,
          title: `${systemName}:${checkId} http`,
          required,
          type: "http",
          url,
        });
      }
    }
  }

  return checks;
}
