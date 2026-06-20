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
  const suiteType = input.manifest.suiteType ?? "regression";
  const replayPackageType = input.manifest.replayPackageType ?? "request_replay_only";
  const scriptFileName = suiteType === "performance" ? "run-performance-profile.ps1" : "run-execution-profile.ps1";
  const replayDescription =
    suiteType === "performance"
      ? "This export replays workload execution and evaluates thresholds plus required Strict Line Keys. It does not require live MCP orchestration."
      : "This export replays generated requests only. It does not produce fresh canonical regression execution artifacts by itself.";
  const redactedSecretsGuidance =
    suiteType === "performance"
      ? [
          "> AUTH_BOOTSTRAP_HINT: includeResolvedSecrets=false",
          "> `project.env` is intentionally redacted. Workload replay may fail with 401/auth-refresh errors until credentials are supplied.",
          "> This package replays exported workload execution only; it does not depend on live MCP orchestration.",
          "> Next actions:",
          "> 1. Populate required auth/runtime credentials in `project.env` for your environment.",
          "> 2. Keep `includeResolvedSecrets=false` and provide credentials locally at replay time.",
          "> 3. Optional: rerun export with `includeResolvedSecrets=true` in a trusted local context; this is a manual choice and is never auto-enabled at runtime.",
        ].join("\n")
      : [
          "> AUTH_BOOTSTRAP_HINT: includeResolvedSecrets=false",
          "> `project.env` is intentionally redacted. Replay may fail with 401/auth-refresh errors until credentials are supplied.",
          "> This package replays exported requests only; it does not persist canonical regression execution artifacts.",
          "> Next actions:",
          "> 1. Populate required auth/runtime credentials in `project.env` for your environment.",
          "> 2. Keep `includeResolvedSecrets=false` and provide credentials locally at replay time.",
          "> 3. Optional: rerun export with `includeResolvedSecrets=true` in a trusted local context; this is a manual choice and is never auto-enabled at runtime.",
        ].join("\n");

  const orderedPlanLines = [...input.manifest.planRuns]
    .sort((left, right) => left.order - right.order)
    .map((plan) => {
      const sourceStatus = typeof plan.runStatus === "string" ? plan.runStatus : plan.status;
      return `[${plan.order}] ${plan.planName} (source_status=${sourceStatus})`;
    });

  return {
    exportId: input.manifest.exportId,
    suiteType,
    executionProfile: input.manifest.executionProfile,
    executionPolicy: input.manifest.executionPolicy,
    sourceRunStatus: input.manifest.runStatus,
    replayPackageType,
    scriptFileName,
    replayDescription,
    includeResolvedSecrets: input.includeResolvedSecrets,
    includeRuntimeStartup: input.defaults.includeRuntimeStartup,
    includeHealthcheckGate: input.defaults.includeHealthcheckGate,
    planLines: orderedPlanLines,
    planOrderMarkdown: orderedPlanLines.map((line) => `1. ${line}`).join("\n"),
    redactedSecretsGuidance: input.includeResolvedSecrets ? "" : redactedSecretsGuidance,
  };
}
