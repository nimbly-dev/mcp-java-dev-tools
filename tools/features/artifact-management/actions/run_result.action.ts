import { promises as fs } from "node:fs";
import path from "node:path";
import { resolveRegressionRunDirAbs } from "@tools-feature-regression-suite";
import { rebuildRunStateStore } from "../state-store/rebuild/run_state_store_rebuild";
import { backfillLegacyCorrelationIndex } from "../state-store/legacy_backfill_state_store";
import { cutoverRunStateStore } from "../state-store/state_store_cutover";
import type { RunStateRebuildResult } from "../state-store/model/run_state_store.model";
import type { ArtifactActionContext, ArtifactActionRequest, ArtifactActionResult } from "./types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "../shared/fail_closed";
import { readJsonFile } from "../shared/json_io";
import { resolveProjectName } from "../shared/project_resolution";

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
      status !== "passed" &&
      status !== "ok" &&
      status !== "skipped_condition_false" &&
      status !== "pass"
    );
  }).length;
}

function filterWatcherRows(
  rows: Record<string, unknown>[],
  filter: { watcherId?: string; watcherStatus?: string } | undefined,
): Record<string, unknown>[] {
  if (!filter?.watcherId && !filter?.watcherStatus) return rows;
  return rows.filter((row) => {
    if (filter.watcherId) {
      const watcherId = typeof row.id === "string" ? row.id : "";
      if (watcherId !== filter.watcherId) return false;
    }
    if (filter.watcherStatus) {
      const watcherStatus = typeof row.status === "string" ? row.status : "";
      if (watcherStatus !== filter.watcherStatus) return false;
    }
    return true;
  });
}

function toWindowedSection<T>(
  items: T[],
  window: { offset: number; limit: number },
  filter?: Record<string, unknown>,
) {
  const start = Math.min(window.offset, items.length);
  const end = Math.min(start + window.limit, items.length);
  const page = items.slice(start, end);
  return {
    offset: start,
    limit: window.limit,
    returned: page.length,
    total: items.length,
    ...(filter && Object.keys(filter).length > 0 ? { filter } : {}),
    items: page,
  };
}

export async function handleRunResultArtifact(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"run_result">,
): Promise<ArtifactActionResult> {
  const projectName = await resolveProjectName(ctx.workspaceRootAbs, request.input.projectName);

  if (request.action === "backfill") {
    const backfill = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: ctx.workspaceRootAbs,
      projectName,
    });
    if (!backfill.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: backfill.reasonCode,
        reason: backfill.reason,
        ...(backfill.reasonMeta ? { reasonMeta: backfill.reasonMeta } : {}),
      });
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      summary: backfill.summary,
    });
  }

  if (request.action === "cutover") {
    const cutover = await cutoverRunStateStore({
      workspaceRootAbs: ctx.workspaceRootAbs,
      projectName,
    });
    if (!cutover.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: cutover.reasonCode,
        reason: cutover.reason,
        ...(cutover.reasonMeta ? { reasonMeta: cutover.reasonMeta } : {}),
      });
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      summary: cutover.cutover,
      ...(cutover.idempotent ? { idempotent: true } : {}),
    });
  }

  if (request.action === "rebuild") {
    const rebuilt = (await rebuildRunStateStore({
      workspaceRootAbs: ctx.workspaceRootAbs,
      projectName,
      ...(typeof request.input.strict === "boolean" ? { strict: request.input.strict } : {}),
    })) as RunStateRebuildResult;
    if (!rebuilt.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: rebuilt.reasonCode,
        reason: rebuilt.reason,
        ...(rebuilt.reasonMeta ? { reasonMeta: rebuilt.reasonMeta } : {}),
      });
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      databasePathAbs: rebuilt.databasePathAbs,
      ...(rebuilt.quarantinePathAbs ? { quarantinePathAbs: rebuilt.quarantinePathAbs } : {}),
      summary: rebuilt.summary,
    });
  }

  if (request.action === "list") {
    const planName = request.input.planName?.trim();
    if (!planName) {
      return buildFailClosedArtifactResponse({
        reasonCode: "plan_name_required",
        reason: "planName is required for run_result list",
        reasonMeta: { action: request.action },
      });
    }
    const runsRoot = path.join(
      ctx.workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      planName,
      "runs",
    );
    const runs = await fs.readdir(runsRoot, { withFileTypes: true }).catch(() => []);
    const runIds = runs
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort((a, b) => b.localeCompare(a));
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      planName,
      runIds,
    });
  }

  const runDirArgs: {
    workspaceRootAbs: string;
    projectName?: string;
    planName?: string;
    runId?: string;
  } = {
    workspaceRootAbs: ctx.workspaceRootAbs,
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
  const includeAll = selectors.length === 0;
  const executionResultRecord = asRecord(executionResult) ?? {};
  const evidenceRecord = asRecord(evidence) ?? {};
  const steps = asRecordArray(executionResultRecord.steps);
  const watcherRows = asRecordArray(executionResultRecord.watchers);
  const watcherExecutionEvidence = asRecordArray(evidenceRecord.watcherExecutions);

  if (includeAll) {
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      runDirAbs,
      summary: {
        runStatus:
          typeof executionResultRecord.status === "string"
            ? executionResultRecord.status
            : "unknown",
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
      },
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

  if (selectors.includes("summary")) {
    response.summary = {
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
  }
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
    const filteredWatchers = filterWatcherRows(watcherRows, watcherFilter);
    response.watchers = toWindowedSection(filteredWatchers, window, watcherFilter);
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
      if (typeof watcher.id === "string" && typeof watcher.status === "string") {
        watcherStatusById.set(watcher.id, watcher.status);
      }
    }
    const evidenceScopedById = watcherExecutionEvidence.filter((entry) => {
      if (watcherFilter?.watcherId) {
        const watcherId = typeof entry.id === "string" ? entry.id : "";
        if (watcherId !== watcherFilter.watcherId) return false;
      }
      return true;
    });
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
    const filteredEvidence = evidenceScopedById.filter((entry) => {
      if (watcherFilter?.watcherStatus) {
        const watcherId = typeof entry.id === "string" ? entry.id : "";
        if (watcherStatusById.get(watcherId) !== watcherFilter.watcherStatus) return false;
      }
      return true;
    });
    response.watcherEvidence = toWindowedSection(filteredEvidence, window, watcherFilter);
  }

  if (Object.keys(artifact).length > 0) {
    response.artifact = artifact;
  }

  return okArtifactResponse(response);
}
