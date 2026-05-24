import path from "node:path";

import type { ExecutionProfileExportManifest, ExecutionProfileExportPlanRun } from "@tools-export-execution-profile/models/execution_profile_export.model";
import { readProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";

function asString(value: unknown): string | undefined {
  if (typeof value !== "string") {
    return undefined;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

function sanitizeExportId(exportId: string): string {
  const normalized = exportId.trim();
  if (!normalized) {
    throw new Error("export_id_missing");
  }
  if (!/^[A-Za-z0-9._-]+$/.test(normalized)) {
    throw new Error("export_id_invalid");
  }
  return normalized;
}

function toPlanRuns(plans: Array<{ order: number; planName: string }>): ExecutionProfileExportPlanRun[] {
  return [...plans]
    .sort((a, b) => a.order - b.order)
    .map((plan) => ({ order: plan.order, planName: plan.planName, status: "executed" as const }));
}

function extractExecutionProfileFromExportId(exportId: string): string | undefined {
  const match = exportId.match(/^\d{8}-\d{6}-(.+)$/);
  if (!match || typeof match[1] !== "string") {
    return undefined;
  }
  const profile = match[1].trim();
  return profile.length > 0 ? profile : undefined;
}

export async function loadExecutionProfileExportManifest(input: {
  workspaceRootAbs: string;
  exportId: string;
}): Promise<{ manifest: ExecutionProfileExportManifest; projectRootAbs: string }> {
  const safeExportId = sanitizeExportId(input.exportId);
  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs);
  const projectRootAbs = path.dirname(path.dirname(plansRootAbs));
  const projectName = path.basename(projectRootAbs);
  const projectsFileAbs = path.join(input.workspaceRootAbs, ".mcpjvm", projectName, "projects.json");
  const artifact = await readProjectArtifact(projectsFileAbs).catch(() => null);
  if (!artifact || !artifact.ok) {
    throw new Error("execution_profile_export_unresolvable");
  }

  const workspace =
    artifact.artifact.workspaces.find((entry) => entry.projectRoot === input.workspaceRootAbs) ??
    artifact.artifact.workspaces[0];
  if (!workspace || !Array.isArray(workspace.executionProfiles) || workspace.executionProfiles.length === 0) {
    throw new Error("execution_profile_export_unresolvable");
  }

  const requestedProfile = extractExecutionProfileFromExportId(safeExportId);
  const selectedProfile =
    (requestedProfile
      ? workspace.executionProfiles.find((entry) => entry.executionProfile === requestedProfile)
      : undefined) ?? workspace.executionProfiles[0];
  if (!selectedProfile) {
    throw new Error("execution_profile_export_unresolvable");
  }

  const nowIso = new Date().toISOString();
  const manifest: ExecutionProfileExportManifest = {
    schemaVersion: "1.0.0",
    exportId: safeExportId,
    generatedAt: nowIso,
    startedAt: nowIso,
    endedAt: nowIso,
    executionProfile: selectedProfile.executionProfile,
    executionPolicy: selectedProfile.executionPolicy,
    runStatus: "pass",
    ...(asString(selectedProfile.runtimeContextName)
      ? { runtimeContextName: asString(selectedProfile.runtimeContextName)! }
      : {}),
    ...(selectedProfile.runtimeConfig ? { runtimeConfig: selectedProfile.runtimeConfig } : {}),
    planRuns: toPlanRuns(selectedProfile.plans),
  };
  return { manifest, projectRootAbs };
}
