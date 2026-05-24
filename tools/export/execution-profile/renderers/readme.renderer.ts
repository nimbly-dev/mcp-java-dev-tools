import type {
  ExportRuntimeDefaults,
  ExecutionProfileExportManifest,
} from "@tools-export-execution-profile/models/execution_profile_export.model";

export type ReadmeTemplateInput = {
  manifest: ExecutionProfileExportManifest;
  defaults: ExportRuntimeDefaults;
  includeResolvedSecrets: boolean;
};

export function buildReadmeTemplateModel(input: ReadmeTemplateInput): Record<string, unknown> {
  const orderedPlanLines = [...input.manifest.planRuns]
    .sort((left, right) => left.order - right.order)
    .map((plan) => `[${plan.order}] ${plan.planName} (${plan.status})`);

  return {
    exportId: input.manifest.exportId,
    executionProfile: input.manifest.executionProfile,
    executionPolicy: input.manifest.executionPolicy,
    runStatus: input.manifest.runStatus,
    includeResolvedSecrets: input.includeResolvedSecrets,
    includeRuntimeStartup: input.defaults.includeRuntimeStartup,
    includeHealthcheckGate: input.defaults.includeHealthcheckGate,
    planLines: orderedPlanLines,
    planOrderMarkdown: orderedPlanLines.map((line) => `1. ${line}`).join("\n"),
  };
}
