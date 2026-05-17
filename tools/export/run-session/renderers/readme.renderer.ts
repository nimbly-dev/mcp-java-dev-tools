import type {
  ExportRuntimeDefaults,
  RunSessionExportManifest,
} from "@tools-export-run-session/models/run_session_export.model";

export type ReadmeTemplateInput = {
  manifest: RunSessionExportManifest;
  defaults: ExportRuntimeDefaults;
  includeResolvedSecrets: boolean;
};

export function buildReadmeTemplateModel(input: ReadmeTemplateInput): Record<string, unknown> {
  const orderedPlanLines = [...input.manifest.planRuns]
    .sort((left, right) => left.order - right.order)
    .map((plan) => `[${plan.order}] ${plan.planName} (${plan.status})`);

  return {
    sessionId: input.manifest.sessionId,
    executionProfile: input.manifest.executionProfile,
    executionPolicy: input.manifest.executionPolicy,
    runStatus: input.manifest.runStatus,
    includeResolvedSecrets: input.includeResolvedSecrets,
    includeRuntimeStartup: input.defaults.includeRuntimeStartup,
    includeHealthcheckGate: input.defaults.includeHealthcheckGate,
    planLines: orderedPlanLines,
  };
}
