import type { Healthcheck } from "@tools-export-run-session/models/run_session_export.model";

export function applyHealthcheckPolicy(checks: Healthcheck[]): Healthcheck[] {
  return checks;
}
