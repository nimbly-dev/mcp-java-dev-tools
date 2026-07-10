import path from "node:path";
import { promises as fs } from "node:fs";

import { readProjectArtifact } from "@tools-feature-artifact-management";
import { resolveRegressionPlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";
import { readExecutionOrchestrationSuiteResult } from "@tools-feature-regression-suite";

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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

async function collectPersistedExportCandidates(args: {
  projectRootAbs: string;
  executionProfileFilter?: string;
  planNameFilter?: string;
}): Promise<Array<{ exportId: string; score: number; executionProfile: string }>> {
  const exportsRootAbs = path.join(args.projectRootAbs, "exports");
  const entries = await fs.readdir(exportsRootAbs, { withFileTypes: true }).catch(() => []);
  const candidates: Array<{ exportId: string; score: number; executionProfile: string }> = [];
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.endsWith(".execution-profile.summary.json")) continue;
    const filePathAbs = path.join(exportsRootAbs, entry.name);
    const text = await fs.readFile(filePathAbs, "utf8").catch(() => "");
    if (!text) continue;
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      continue;
    }
    if (!isRecord(parsed)) continue;
    const exportId = asString(parsed.exportId);
    const executionProfile = asString(parsed.executionProfile);
    if (!exportId || !executionProfile) continue;
    if (args.executionProfileFilter && executionProfile !== args.executionProfileFilter) continue;
    const planRuns = Array.isArray(parsed.planRuns) ? parsed.planRuns : [];
    if (
      args.planNameFilter &&
      !planRuns.some((plan) => isRecord(plan) && asString(plan.planName) === args.planNameFilter)
    ) {
      continue;
    }
    const score = Date.parse(asString(parsed.endedAt) ?? asString(parsed.generatedAt) ?? "");
    candidates.push({
      exportId,
      executionProfile,
      score: Number.isFinite(score) ? score : 0,
    });
  }
  return candidates;
}

async function collectSuiteCandidates(args: {
  workspaceRootAbs: string;
  projectRootAbs: string;
  projectName: string;
  executionProfileFilter?: string;
  planNameFilter?: string;
}): Promise<Array<{ exportId: string; score: number; executionProfile: string }>> {
  const suiteRunsRootAbs = path.join(args.projectRootAbs, "suite-runs");
  const entries = await fs.readdir(suiteRunsRootAbs, { withFileTypes: true }).catch(() => []);
  const candidates: Array<{ exportId: string; score: number; executionProfile: string }> = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const suite = await readExecutionOrchestrationSuiteResult({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      suiteRunId: entry.name,
    });
    if (!suite) continue;
    if (args.executionProfileFilter && suite.executionProfile !== args.executionProfileFilter) continue;
    if (args.planNameFilter && !suite.planRuns.some((plan) => plan.planName === args.planNameFilter)) continue;
    const resultPathAbs = path.join(suiteRunsRootAbs, entry.name, "execution_orchestration.result.json");
    const stat = await fs.stat(resultPathAbs).catch(() => null);
    const score = stat?.mtimeMs ?? 0;
    candidates.push({
      exportId: formatExportId(score || Date.now(), suite.executionProfile),
      score,
      executionProfile: suite.executionProfile,
    });
  }
  return candidates;
}

async function collectPlanRunCandidates(args: {
  projectRootAbs: string;
  executionProfileFilter?: string;
  planNameFilter?: string;
  profiles: Array<{ executionProfile: string; plans: Array<{ order: number; planName: string }> }>;
}): Promise<Array<{ exportId: string; score: number; executionProfile: string }>> {
  const candidates: Array<{ exportId: string; score: number; executionProfile: string }> = [];
  for (const profile of args.profiles) {
    if (args.executionProfileFilter && profile.executionProfile !== args.executionProfileFilter) continue;
    if (args.planNameFilter && !profile.plans.some((plan) => plan.planName === args.planNameFilter)) continue;
    let latestScore = 0;
    for (const plan of profile.plans) {
      const runsRootAbs = path.join(args.projectRootAbs, "plans", "regression", plan.planName, "runs");
      const runEntries = await fs.readdir(runsRootAbs, { withFileTypes: true }).catch(() => []);
      for (const entry of runEntries) {
        if (!entry.isDirectory()) continue;
        const resultPathAbs = path.join(runsRootAbs, entry.name, "execution.result.json");
        const stat = await fs.stat(resultPathAbs).catch(() => null);
        if (!stat) continue;
        latestScore = Math.max(latestScore, stat.mtimeMs);
      }
    }
    if (latestScore > 0) {
      candidates.push({
        exportId: formatExportId(latestScore, profile.executionProfile),
        score: latestScore,
        executionProfile: profile.executionProfile,
      });
    }
  }
  return candidates;
}


export async function resolveExportIdForExport(input: {
  workspaceRootAbs: string;
  projectName?: string;
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

  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs, input.projectName);
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

  const profiles = workspace.executionProfiles.map((profile) => ({
    executionProfile: profile.executionProfile,
    plans: profile.plans.map((plan) => ({ order: plan.order, planName: plan.planName })),
  }));
  const projectRootAbs = path.dirname(path.dirname(plansRootAbs));
  let candidates = await collectPersistedExportCandidates({
    projectRootAbs,
    ...(executionProfileFilter ? { executionProfileFilter } : {}),
    ...(planNameFilter ? { planNameFilter } : {}),
  });
  candidates = candidates.concat(await collectSuiteCandidates({
    workspaceRootAbs: input.workspaceRootAbs,
    projectRootAbs,
    projectName,
    ...(executionProfileFilter ? { executionProfileFilter } : {}),
    ...(planNameFilter ? { planNameFilter } : {}),
  }));
  candidates = candidates.concat(await collectPlanRunCandidates({
    projectRootAbs,
    ...(executionProfileFilter ? { executionProfileFilter } : {}),
    ...(planNameFilter ? { planNameFilter } : {}),
    profiles,
  }));

  if (candidates.length === 0) {
    const fallbackProfiles = profiles.filter((profile) => {
      if (executionProfileFilter && profile.executionProfile !== executionProfileFilter) return false;
      if (planNameFilter && !profile.plans.some((plan) => plan.planName === planNameFilter)) return false;
      return true;
    });
    if (fallbackProfiles.length === 0) {
      throw new Error(executionProfileFilter ? "execution_profile_no_exports" : "export_selector_no_match");
    }
    if (new Set(fallbackProfiles.map((profile) => profile.executionProfile)).size > 1) {
      throw new Error("execution_profile_ambiguous");
    }
    const fallback = fallbackProfiles[0];
    if (!fallback) throw new Error("export_selector_no_match");
    return formatExportId(Date.now(), fallback.executionProfile);
  }

  const uniqueProfiles = new Set(candidates.map((entry) => entry.executionProfile));
  if (uniqueProfiles.size > 1) {
    throw new Error("execution_profile_ambiguous");
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
