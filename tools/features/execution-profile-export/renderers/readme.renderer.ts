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
    redactedSecretsGuidance:
      input.includeResolvedSecrets
        ? ""
        : [
            "> AUTH_BOOTSTRAP_HINT: includeResolvedSecrets=false",
            "> `project.env` is intentionally redacted. Replay may fail with 401/auth-refresh errors until credentials are supplied.",
            "> Next actions:",
            "> 1. Populate required auth/runtime credentials in `project.env` for your environment.",
            "> 2. Keep `includeResolvedSecrets=false` and provide credentials locally at replay time.",
            "> 3. Optional: rerun export with `includeResolvedSecrets=true` in a trusted local context; this is a manual choice and is never auto-enabled at runtime.",
          ].join("\n"),
  };
}
