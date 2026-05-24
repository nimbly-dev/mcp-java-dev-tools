import type { Healthcheck } from "@tools-export-execution-profile/models/execution_profile_export.model";

export function applyHealthcheckPolicy(checks: Healthcheck[]): Healthcheck[] {
  return checks;
}
