import type { PerformanceExportBundlePlan } from "@tools-export-execution-profile/performance_jmeter_export.util";

export function assertPerformanceExportProbeBindingsResolved(plans: PerformanceExportBundlePlan[]): void {
  for (const plan of plans) {
    const probeId = plan.contract.observationTargets.probeId;
    const requiredLineHits = plan.contract.observationTargets.requiredLineHits;
    if (typeof probeId !== "string" || probeId.trim().length === 0) {
      continue;
    }
    if (!Array.isArray(requiredLineHits) || requiredLineHits.length === 0) {
      continue;
    }
    if (typeof plan.probeBaseUrl === "string" && plan.probeBaseUrl.trim().length > 0) {
      continue;
    }
    throw new Error(`performance_export_probe_binding_missing:${plan.planName}:${probeId.trim()}`);
  }
}
