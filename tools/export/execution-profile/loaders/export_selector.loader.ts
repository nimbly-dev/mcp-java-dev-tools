import path from "node:path";

import { readProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";

function asString(value: unknown): string | undefined {
  if (typeof value !== "string") {
    return undefined;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

function validateExportId(exportId: string): string {
  const normalized = exportId.trim();
  if (!normalized) {
    throw new Error("export_selector_missing");
  }
  if (!/^[A-Za-z0-9._-]+$/.test(normalized)) {
    throw new Error("export_id_invalid");
  }
  return normalized;
}

function parseWhenHint(when: string | undefined): number | undefined {
  if (!when) return undefined;
  const parsed = Date.parse(when);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function formatExportId(epochMs: number, profile: string): string {
  const date = new Date(epochMs);
  const pad = (n: number) => String(n).padStart(2, "0");
  const id = `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}-${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}`;
  return `${id}-${profile.replaceAll(/[^A-Za-z0-9._-]/g, "-")}`;
}


export async function resolveExportIdForExport(input: {
  workspaceRootAbs: string;
  exportId?: string;
  executionProfile?: string;
  planName?: string;
  when?: string;
}): Promise<string> {
  if (typeof input.exportId === "string" && input.exportId.trim().length > 0) {
    return validateExportId(input.exportId);
  }

  const executionProfileFilter = asString(input.executionProfile);
  const planNameFilter = asString(input.planName);
  const whenEpoch = parseWhenHint(asString(input.when));

  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs);
  const projectName = path.basename(path.dirname(path.dirname(plansRootAbs)));
  const projectsFileAbs = path.join(input.workspaceRootAbs, ".mcpjvm", projectName, "projects.json");
  const artifact = await readProjectArtifact(projectsFileAbs).catch(() => null);
  if (!artifact || !artifact.ok) {
    throw new Error("execution_profile_no_exports");
  }
  const workspace =
    artifact.artifact.workspaces.find((entry) => entry.projectRoot === input.workspaceRootAbs) ??
    artifact.artifact.workspaces[0];
  if (!workspace || !Array.isArray(workspace.executionProfiles) || workspace.executionProfiles.length === 0) {
    throw new Error("execution_profile_no_exports");
  }

  const nowEpoch = Date.now();
  const candidates: Array<{ exportId: string; score: number }> = [];
  for (const profile of workspace.executionProfiles) {
    if (executionProfileFilter && profile.executionProfile !== executionProfileFilter) {
      continue;
    }
    if (planNameFilter && !profile.plans.some((plan) => plan.planName === planNameFilter)) {
      continue;
    }

    const score = nowEpoch;
    candidates.push({ exportId: formatExportId(score, profile.executionProfile), score });
  }

  if (candidates.length === 0) {
    throw new Error(executionProfileFilter ? "execution_profile_no_exports" : "export_selector_no_match");
  }

  if (typeof whenEpoch === "number") {
    candidates.sort((a, b) => Math.abs(a.score - whenEpoch) - Math.abs(b.score - whenEpoch));
    const nearest = candidates[0];
    if (!nearest) throw new Error("export_selector_no_match");
    return nearest.exportId;
  }

  candidates.sort((a, b) => b.score - a.score);
  const latest = candidates[0];
  if (!latest) throw new Error("export_selector_no_match");
  return latest.exportId;
}
