import path from "node:path";
import { resolveRegressionRunDirAbs } from "@tools-feature-regression-suite";
import type { ArtifactActionRequest, ArtifactActionResult } from "../actions/types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "../shared/fail_closed";
import { readJsonFile } from "../shared/json_io";

function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter(
    (entry): entry is string => typeof entry === "string" && entry.trim().length > 0,
  );
}
function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}
function asRecordArray(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) return [];
  return value.filter(
    (entry): entry is Record<string, unknown> =>
      typeof entry === "object" && entry !== null && !Array.isArray(entry),
  );
}
function countFailedSteps(steps: Record<string, unknown>[]): number {
  return steps.filter((entry) => {
    const status = entry.status;
    return (
      typeof status === "string" &&
      !["passed", "ok", "skipped_condition_false", "pass"].includes(status)
    );
  }).length;
}
function filterWatcherRows(
  rows: Record<string, unknown>[],
  filter: { watcherId?: string; watcherStatus?: string } | undefined,
) {
  if (!filter?.watcherId && !filter?.watcherStatus) return rows;
  return rows.filter((row) => {
    if (filter.watcherId && row.id !== filter.watcherId) return false;
    if (filter.watcherStatus && row.status !== filter.watcherStatus) return false;
    return true;
  });
}
function toWindowedSection<T>(
  items: T[],
  window: { offset: number; limit: number },
  filter?: Record<string, unknown>,
) {
  const start = Math.min(window.offset, items.length);
  const page = items.slice(start, Math.min(start + window.limit, items.length));
  return {
    offset: start,
    limit: window.limit,
    returned: page.length,
    total: items.length,
    ...(filter && Object.keys(filter).length > 0 ? { filter } : {}),
    items: page,
  };
}

export function workspaceRelativePath(
  workspaceRootAbs: string,
  pathAbs: string,
): string | undefined {
  const root = path.resolve(workspaceRootAbs);
  const resolved = path.resolve(pathAbs);
  if (resolved !== root && !resolved.startsWith(`${root}${path.sep}`)) return undefined;
  return path.relative(root, resolved).replaceAll("\\", "/");
}

export async function readRunResultArtifact(
  request: ArtifactActionRequest<"run_result">,
  workspaceRootAbs: string,
  projectName: string,
): Promise<ArtifactActionResult> {
  const runDirArgs: {
    workspaceRootAbs: string;
    projectName?: string;
    planName?: string;
    runId?: string;
  } = {
    workspaceRootAbs,
    projectName,
  };
  if (typeof request.input.planName === "string") runDirArgs.planName = request.input.planName;
  if (typeof request.input.runId === "string") runDirArgs.runId = request.input.runId;
  const runDirAbs = await resolveRegressionRunDirAbs(runDirArgs);
  if (!runDirAbs) {
    return buildFailClosedArtifactResponse({
      reasonCode: "run_artifact_missing",
      reason: "run artifact directory not found",
      reasonMeta: { planName: request.input.planName, runId: request.input.runId },
    });
  }
  const executionResult = await readJsonFile(path.join(runDirAbs, "execution.result.json"));
  const evidence = await readJsonFile(path.join(runDirAbs, "evidence.json"));
  const selectors = asStringArray(request.input.query?.select);
  const executionResultRecord = asRecord(executionResult) ?? {};
  const evidenceRecord = asRecord(evidence) ?? {};
  const steps = asRecordArray(executionResultRecord.steps);
  const watcherRows = asRecordArray(executionResultRecord.watchers);
  const watcherExecutionEvidence = asRecordArray(evidenceRecord.watcherExecutions);
  const summary = {
    runStatus:
      typeof executionResultRecord.status === "string" ? executionResultRecord.status : "unknown",
    triggerStatus:
      typeof executionResultRecord.triggerStatus === "string"
        ? executionResultRecord.triggerStatus
        : "unknown",
    watcherStatus:
      typeof executionResultRecord.watcherStatus === "string"
        ? executionResultRecord.watcherStatus
        : "not_configured",
    stepCount: steps.length,
    failedStepCount: countFailedSteps(steps),
    watcherCount: watcherRows.length,
    watcherEvidenceCount: watcherExecutionEvidence.length,
  };
  if (selectors.length === 0) {
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      runDirAbs,
      summary,
    });
  }
  const artifact: Record<string, unknown> = {};
  const response: Record<string, unknown> = {
    resultType: "artifact",
    status: "ok",
    artifactType: request.artifactType,
    action: request.action,
    runDirAbs,
  };
  if (selectors.includes("summary")) response.summary = summary;
  if (selectors.includes("executionResult")) artifact.executionResult = executionResult;
  if (selectors.includes("evidence")) artifact.evidence = evidence;
  const watcherFilterRecord = asRecord(request.input.query?.watcherFilter);
  const watcherFilter = watcherFilterRecord
    ? {
        ...(typeof watcherFilterRecord.watcherId === "string"
          ? { watcherId: watcherFilterRecord.watcherId }
          : {}),
        ...(typeof watcherFilterRecord.watcherStatus === "string"
          ? { watcherStatus: watcherFilterRecord.watcherStatus }
          : {}),
      }
    : undefined;
  if (selectors.includes("watchers")) {
    if (watcherRows.length === 0) {
      return buildFailClosedArtifactResponse({
        reasonCode: "watcher_state_unavailable",
        reason: "watcher result state is unavailable for the selected run",
        reasonMeta: {
          projectName,
          planName: request.input.planName,
          runId: request.input.runId,
          section: "watchers",
        },
      });
    }
    const window = request.input.query?.watchers;
    if (!window) {
      return buildFailClosedArtifactResponse({
        reasonCode: "watcher_query_window_required",
        reason: "query.watchers window is required when selecting watchers",
        reasonMeta: { section: "watchers" },
      });
    }
    response.watchers = toWindowedSection(
      filterWatcherRows(watcherRows, watcherFilter),
      window,
      watcherFilter,
    );
  }
  if (selectors.includes("watcherEvidence")) {
    if (watcherExecutionEvidence.length === 0) {
      return buildFailClosedArtifactResponse({
        reasonCode: "watcher_state_unavailable",
        reason: "watcher execution evidence is unavailable for the selected run",
        reasonMeta: {
          projectName,
          planName: request.input.planName,
          runId: request.input.runId,
          section: "watcherEvidence",
        },
      });
    }
    const window = request.input.query?.watcherEvidence;
    if (!window) {
      return buildFailClosedArtifactResponse({
        reasonCode: "watcher_query_window_required",
        reason: "query.watcherEvidence window is required when selecting watcherEvidence",
        reasonMeta: { section: "watcherEvidence" },
      });
    }
    const watcherStatusById = new Map<string, string>();
    for (const watcher of watcherRows) {
      if (typeof watcher.id === "string" && typeof watcher.status === "string")
        watcherStatusById.set(watcher.id, watcher.status);
    }
    const evidenceScopedById = watcherExecutionEvidence.filter(
      (entry) => !watcherFilter?.watcherId || entry.id === watcherFilter.watcherId,
    );
    if (watcherFilter?.watcherStatus) {
      const missingStatusEvidence = evidenceScopedById.find((entry) => {
        const watcherId = typeof entry.id === "string" ? entry.id : "";
        return watcherId.length > 0 && !watcherStatusById.has(watcherId);
      });
      if (missingStatusEvidence) {
        return buildFailClosedArtifactResponse({
          reasonCode: "watcher_state_unavailable",
          reason: "watcher status provenance is unavailable for watcher evidence filtering",
          reasonMeta: {
            projectName,
            planName: request.input.planName,
            runId: request.input.runId,
            section: "watcherEvidence",
            watcherId:
              typeof missingStatusEvidence.id === "string" ? missingStatusEvidence.id : undefined,
          },
        });
      }
    }
    const filteredEvidence = evidenceScopedById.filter(
      (entry) =>
        !watcherFilter?.watcherStatus ||
        watcherStatusById.get(typeof entry.id === "string" ? entry.id : "") ===
          watcherFilter.watcherStatus,
    );
    response.watcherEvidence = toWindowedSection(filteredEvidence, window, watcherFilter);
  }
  if (Object.keys(artifact).length > 0) response.artifact = artifact;
  return okArtifactResponse(response);
}
