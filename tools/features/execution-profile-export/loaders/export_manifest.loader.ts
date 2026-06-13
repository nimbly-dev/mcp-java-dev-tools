import { promises as fs } from "node:fs";
import path from "node:path";

import type { ExecutionProfileExportManifest, ExecutionProfileExportPlanRun } from "@tools-export-execution-profile/models/execution_profile_export.model";
import { readProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
import { readExecutionOrchestrationSuiteResult } from "@tools-regression-execution-plan-spec/regression_runtime_suite_executor.util";

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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function toPlanRuns(plans: Array<{ order: number; planName: string }>): ExecutionProfileExportPlanRun[] {
  return [...plans]
    .sort((a, b) => a.order - b.order)
    .map((plan) => ({ order: plan.order, planName: plan.planName, status: "executed" as const }));
}

function normalizeRuntimeConfig(
  value: unknown,
): { requestTimeoutMs?: number; retryMax?: number } | undefined {
  if (!isRecord(value)) return undefined;
  const runtimeConfig = {
    ...(typeof value.requestTimeoutMs === "number" ? { requestTimeoutMs: value.requestTimeoutMs } : {}),
    ...(typeof value.retryMax === "number" ? { retryMax: value.retryMax } : {}),
  };
  return Object.keys(runtimeConfig).length > 0 ? runtimeConfig : undefined;
}

function parsePlanRun(value: unknown): ExecutionProfileExportPlanRun | null {
  if (!isRecord(value)) return null;
  if (typeof value.order !== "number" || !Number.isInteger(value.order) || value.order <= 0) return null;
  if (typeof value.planName !== "string" || value.planName.trim().length === 0) return null;
  if (value.status !== "executed" && value.status !== "blocked" && value.status !== "skipped") return null;
  if (
    typeof value.runStatus !== "undefined" &&
    value.runStatus !== "pass" &&
    value.runStatus !== "fail" &&
    value.runStatus !== "blocked"
  ) {
    return null;
  }
  return {
    order: value.order,
    planName: value.planName.trim(),
    status: value.status,
    ...(typeof value.runStatus === "string" ? { runStatus: value.runStatus } : {}),
    ...(typeof value.blockedReasonCode === "string" ? { blockedReasonCode: value.blockedReasonCode } : {}),
    ...(typeof value.runId === "string" ? { runId: value.runId } : {}),
  };
}

function parseExportIdTimestamp(exportId: string): number | undefined {
  const match = exportId.match(/^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})-/);
  if (!match) return undefined;
  const [, year, month, day, hour, minute, second] = match;
  const epoch = Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second),
  );
  return Number.isFinite(epoch) ? epoch : undefined;
}

function deriveFallbackRunStatus(args: {
  executionPolicy: "stop_on_fail" | "continue_on_fail";
  planRuns: ExecutionProfileExportPlanRun[];
}): ExecutionProfileExportManifest["runStatus"] | null {
  const runStatuses = args.planRuns
    .map((plan) => plan.runStatus)
    .filter((status): status is "pass" | "fail" | "blocked" => typeof status === "string");
  if (runStatuses.length === 0) return null;
  if (runStatuses.includes("blocked")) return "blocked";
  if (runStatuses.includes("fail")) {
    if (args.executionPolicy === "continue_on_fail" && runStatuses.includes("pass")) {
      return "partial_fail";
    }
    return "fail";
  }
  return "pass";
}

async function deriveManifestFromLatestPlanRuns(args: {
  workspaceRootAbs: string;
  projectRootAbs: string;
  exportId: string;
  selectedProfile: {
    executionProfile: string;
    executionPolicy: "stop_on_fail" | "continue_on_fail";
    runtimeContextName?: string;
    runtimeConfig?: {
      requestTimeoutMs?: number;
      retryMax?: number;
    };
    plans: Array<{ order: number; planName: string }>;
  };
}): Promise<ExecutionProfileExportManifest | null> {
  const desiredEpoch = parseExportIdTimestamp(args.exportId);
  const planRuns: ExecutionProfileExportPlanRun[] = [];
  let earliestMs: number | null = null;
  let latestMs: number | null = null;

  for (const plan of [...args.selectedProfile.plans].sort((a, b) => a.order - b.order)) {
    const runsRootAbs = path.join(
      args.projectRootAbs,
      "plans",
      "regression",
      plan.planName,
      "runs",
    );
    const runEntries = await fs.readdir(runsRootAbs, { withFileTypes: true }).catch(() => []);
    const candidates: Array<{ mtimeMs: number; runId: string; runStatus: "pass" | "fail" | "blocked" }> = [];
    for (const entry of runEntries) {
      if (!entry.isDirectory()) continue;
      const resultPathAbs = path.join(runsRootAbs, entry.name, "execution.result.json");
      const stat = await fs.stat(resultPathAbs).catch(() => null);
      if (!stat) continue;
      const text = await fs.readFile(resultPathAbs, "utf8").catch(() => "");
      if (!text) continue;
      let parsed: unknown;
      try {
        parsed = JSON.parse(text);
      } catch {
        continue;
      }
      if (!isRecord(parsed)) continue;
      const status = parsed.status;
      if (status !== "pass" && status !== "fail" && status !== "blocked") continue;
      candidates.push({ mtimeMs: stat.mtimeMs, runId: entry.name, runStatus: status });
    }
    if (candidates.length === 0) {
      planRuns.push({
        order: plan.order,
        planName: plan.planName,
        status: "skipped",
      });
      continue;
    }
    candidates.sort((a, b) => {
      if (typeof desiredEpoch === "number") {
        return Math.abs(a.mtimeMs - desiredEpoch) - Math.abs(b.mtimeMs - desiredEpoch);
      }
      return b.mtimeMs - a.mtimeMs;
    });
    const selected = candidates[0];
    if (!selected) {
      planRuns.push({
        order: plan.order,
        planName: plan.planName,
        status: "skipped",
      });
      continue;
    }
    earliestMs = earliestMs === null ? selected.mtimeMs : Math.min(earliestMs, selected.mtimeMs);
    latestMs = latestMs === null ? selected.mtimeMs : Math.max(latestMs, selected.mtimeMs);
    planRuns.push({
      order: plan.order,
      planName: plan.planName,
      status: "executed",
      runStatus: selected.runStatus,
      runId: selected.runId,
    });
  }

  if (planRuns.every((plan) => typeof plan.runStatus !== "string")) return null;
  const runStatus = deriveFallbackRunStatus({
    executionPolicy: args.selectedProfile.executionPolicy,
    planRuns,
  });
  if (!runStatus) return null;
  const runtimeConfig = normalizeRuntimeConfig(args.selectedProfile.runtimeConfig);
  const startedAtIso = new Date(earliestMs ?? Date.now()).toISOString();
  const endedAtIso = new Date(latestMs ?? Date.now()).toISOString();
  return {
    schemaVersion: "1.0.0",
    exportId: args.exportId,
    generatedAt: new Date().toISOString(),
    startedAt: startedAtIso,
    endedAt: endedAtIso,
    executionProfile: args.selectedProfile.executionProfile,
    executionPolicy: args.selectedProfile.executionPolicy,
    runStatus,
    ...(asString(args.selectedProfile.runtimeContextName)
      ? { runtimeContextName: asString(args.selectedProfile.runtimeContextName)! }
      : {}),
    ...(runtimeConfig ? { runtimeConfig } : {}),
    planRuns: planRuns.sort((a, b) => a.order - b.order),
  };
}

async function readPersistedExportManifest(args: {
  projectRootAbs: string;
  exportId: string;
}): Promise<ExecutionProfileExportManifest | null> {
  const summaryPathAbs = path.join(args.projectRootAbs, "exports", `${args.exportId}.execution-profile.summary.json`);
  try {
    const text = await fs.readFile(summaryPathAbs, "utf8");
    const parsed = JSON.parse(text) as unknown;
    if (!isRecord(parsed)) return null;
    if (
      parsed.schemaVersion !== "1.0.0" ||
      parsed.exportId !== args.exportId ||
      typeof parsed.generatedAt !== "string" ||
      typeof parsed.startedAt !== "string" ||
      typeof parsed.endedAt !== "string" ||
      typeof parsed.executionProfile !== "string" ||
      (parsed.executionPolicy !== "stop_on_fail" && parsed.executionPolicy !== "continue_on_fail") ||
      (parsed.runStatus !== "pass" &&
        parsed.runStatus !== "fail" &&
        parsed.runStatus !== "blocked" &&
        parsed.runStatus !== "partial_fail") ||
      !Array.isArray(parsed.planRuns)
    ) {
      return null;
    }
    const planRuns = parsed.planRuns
      .map((entry) => parsePlanRun(entry))
      .filter((entry): entry is ExecutionProfileExportPlanRun => entry !== null);
    if (planRuns.length !== parsed.planRuns.length) return null;
    const runtimeConfig = normalizeRuntimeConfig(parsed.runtimeConfig);
    return {
      schemaVersion: "1.0.0",
      exportId: args.exportId,
      generatedAt: parsed.generatedAt,
      startedAt: parsed.startedAt,
      endedAt: parsed.endedAt,
      executionProfile: parsed.executionProfile,
      executionPolicy: parsed.executionPolicy,
      runStatus: parsed.runStatus,
      ...(asString(parsed.runtimeContextName) ? { runtimeContextName: asString(parsed.runtimeContextName)! } : {}),
      ...(runtimeConfig ? { runtimeConfig } : {}),
      planRuns: planRuns.sort((a, b) => a.order - b.order),
    };
  } catch {
    return null;
  }
}

async function deriveManifestFromLatestSuite(args: {
  workspaceRootAbs: string;
  projectName: string;
  projectRootAbs: string;
  exportId: string;
  selectedProfile: {
    executionProfile: string;
    executionPolicy: "stop_on_fail" | "continue_on_fail";
    runtimeContextName?: string;
    runtimeConfig?: {
      requestTimeoutMs?: number;
      retryMax?: number;
    };
  };
}): Promise<ExecutionProfileExportManifest | null> {
  const desiredEpoch = parseExportIdTimestamp(args.exportId);
  const suiteRunsRootAbs = path.join(args.projectRootAbs, "suite-runs");
  const entries = await fs.readdir(suiteRunsRootAbs, { withFileTypes: true }).catch(() => []);
  const candidates: Array<{
    endedAtIso: string;
    mtimeMs: number;
    planRuns: ExecutionProfileExportPlanRun[];
    runStatus: ExecutionProfileExportManifest["runStatus"];
  }> = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const suite = await readExecutionOrchestrationSuiteResult({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      suiteRunId: entry.name,
    });
    if (!suite) continue;
    if (suite.executionProfile !== args.selectedProfile.executionProfile) continue;
    if (
      suite.status !== "pass" &&
      suite.status !== "fail" &&
      suite.status !== "blocked" &&
      suite.status !== "partial_fail"
    ) {
      continue;
    }
    const resultPathAbs = path.join(suiteRunsRootAbs, entry.name, "execution_orchestration.result.json");
    const stat = await fs.stat(resultPathAbs).catch(() => null);
    const mtimeMs = stat?.mtimeMs ?? 0;
    candidates.push({
      endedAtIso: new Date(mtimeMs || Date.now()).toISOString(),
      mtimeMs,
      runStatus: suite.status,
      planRuns: [...suite.planRuns].sort((a, b) => a.order - b.order),
    });
  }
  candidates.sort((a, b) => {
    if (typeof desiredEpoch === "number") {
      return Math.abs(a.mtimeMs - desiredEpoch) - Math.abs(b.mtimeMs - desiredEpoch);
    }
    return b.mtimeMs - a.mtimeMs;
  });
  const latest = candidates[0];
  if (!latest) return null;
  const runtimeConfig = normalizeRuntimeConfig(args.selectedProfile.runtimeConfig);
  return {
    schemaVersion: "1.0.0",
    exportId: args.exportId,
    generatedAt: new Date().toISOString(),
    startedAt: latest.endedAtIso,
    endedAt: latest.endedAtIso,
    executionProfile: args.selectedProfile.executionProfile,
    executionPolicy: args.selectedProfile.executionPolicy,
    runStatus: latest.runStatus,
    ...(asString(args.selectedProfile.runtimeContextName)
      ? { runtimeContextName: asString(args.selectedProfile.runtimeContextName)! }
      : {}),
    ...(runtimeConfig ? { runtimeConfig } : {}),
    planRuns: latest.planRuns,
  };
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
  projectName?: string;
  exportId: string;
}): Promise<{ manifest: ExecutionProfileExportManifest; projectRootAbs: string }> {
  const safeExportId = sanitizeExportId(input.exportId);
  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs, input.projectName);
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
  const selectedProfileCandidates = requestedProfile
    ? workspace.executionProfiles.filter((entry) => entry.executionProfile === requestedProfile)
    : workspace.executionProfiles;
  if (selectedProfileCandidates.length === 0) throw new Error("execution_profile_export_unresolvable");
  if (selectedProfileCandidates.length > 1) throw new Error("execution_profile_ambiguous");
  const selectedProfile = selectedProfileCandidates[0];
  if (!selectedProfile) throw new Error("execution_profile_export_unresolvable");

  const persistedManifest = await readPersistedExportManifest({
    projectRootAbs,
    exportId: safeExportId,
  });
  if (persistedManifest) {
    return { manifest: persistedManifest, projectRootAbs };
  }

  const latestSuiteManifest = await deriveManifestFromLatestSuite({
    workspaceRootAbs: input.workspaceRootAbs,
    projectName,
    projectRootAbs,
    exportId: safeExportId,
    selectedProfile,
  });
  if (latestSuiteManifest) {
    return { manifest: latestSuiteManifest, projectRootAbs };
  }

  const latestPlanRunManifest = await deriveManifestFromLatestPlanRuns({
    workspaceRootAbs: input.workspaceRootAbs,
    projectRootAbs,
    exportId: safeExportId,
    selectedProfile,
  });
  if (latestPlanRunManifest) {
    return { manifest: latestPlanRunManifest, projectRootAbs };
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
    runStatus: "blocked",
    ...(asString(selectedProfile.runtimeContextName)
      ? { runtimeContextName: asString(selectedProfile.runtimeContextName)! }
      : {}),
    ...(selectedProfile.runtimeConfig ? { runtimeConfig: selectedProfile.runtimeConfig } : {}),
    planRuns: toPlanRuns(selectedProfile.plans),
  };
  return { manifest, projectRootAbs };
}
