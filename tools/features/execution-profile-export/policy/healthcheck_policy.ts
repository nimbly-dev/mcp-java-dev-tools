import type { Healthcheck } from "../models/execution_profile_export.model";

export function applyHealthcheckPolicy(checks: Healthcheck[]): Healthcheck[] {
  return checks;
}
