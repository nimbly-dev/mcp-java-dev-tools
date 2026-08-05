import { promises as fs } from "node:fs";
import path from "node:path";
import { readSecurityRunArtifact } from "@tools-feature-security-suite";
import { resolveSecurityPlansRootAbs } from "@tools-security-execution-plan-spec";
import type {
  ArtifactActionContext,
  ArtifactActionRequest,
  ArtifactActionResult,
} from "../actions/types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "../shared/fail_closed";
import { queryRunState } from "../state-store/run_state_query";
import { queryCorrelationState } from "../state-store/correlation_state_query";
import { queryWatcherState } from "../state-store/watcher_state_query";

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

async function querySecurityRunArtifacts(args: {
  workspaceRootAbs: string;
  projectName: string;
  query: NonNullable<ArtifactActionRequest<"run_result">["input"]["query"]>;
}): Promise<ArtifactActionResult> {
  const pageSize = args.query.pageSize;
  const plansRootAbs = await resolveSecurityPlansRootAbs(
    args.workspaceRootAbs,
    args.projectName,
  ).catch(() => undefined);
  const planEntries = plansRootAbs
    ? await fs.readdir(plansRootAbs, { withFileTypes: true }).catch(() => [])
    : [];
  const requestedPlanName = args.query.planName?.trim();
  const planNames = planEntries
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .filter((planName) => !requestedPlanName || planName === requestedPlanName)
    .sort();
  if (requestedPlanName && planNames.length === 0 && plansRootAbs)
    planNames.push(requestedPlanName);

  const requestedRunId = args.query.runId?.trim();
  const requestedSuiteRunId = args.query.suiteRunId?.trim();
  const statusFilter = args.query.status
    ? new Set(Array.isArray(args.query.status) ? args.query.status : [args.query.status])
    : undefined;
  const items: Array<Record<string, unknown>> = [];
  for (const planName of planNames.slice(0, 100)) {
    if (!plansRootAbs) break;
    const runsRootAbs = path.join(plansRootAbs, planName, "runs");
    const runEntries = await fs.readdir(runsRootAbs, { withFileTypes: true }).catch(() => []);
    const runIds = runEntries
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .filter((runId) => !requestedRunId || runId === requestedRunId)
      .sort();
    for (const runId of runIds.slice(0, 100)) {
      const read = await readSecurityRunArtifact({
        workspaceRootAbs: args.workspaceRootAbs,
        projectName: args.projectName,
        planName,
        runId,
      });
      if (!read.ok) continue;
      const artifact = read.artifact;
      if (requestedSuiteRunId && !artifact.runId.startsWith(`${requestedSuiteRunId}-`)) continue;
      if (statusFilter && !statusFilter.has(artifact.status)) continue;
      const updatedAtEpochMs = await fs
        .stat(read.pathAbs)
        .then((stat) => Math.trunc(stat.mtimeMs))
        .catch(() => 0);
      items.push({
        stateKind: "plan",
        projectName: args.projectName,
        planName: artifact.planName,
        runId: artifact.runId,
        ...(requestedSuiteRunId ? { suiteRunId: requestedSuiteRunId } : {}),
        status: artifact.status,
        executionProfile: artifact.executionProfile,
        reasonCode: artifact.reasonCode,
        updatedAtEpochMs,
        coverage: {
          plannedCount: artifact.coverage.plannedCount,
          executedCount: artifact.coverage.executedCount,
          blockedCount: artifact.coverage.blockedCount,
          complete: artifact.coverage.complete,
        },
      });
    }
  }
  items.sort((left, right) => {
    const leftTime = typeof left.updatedAtEpochMs === "number" ? left.updatedAtEpochMs : 0;
    const rightTime = typeof right.updatedAtEpochMs === "number" ? right.updatedAtEpochMs : 0;
    return args.query.sortDirection === "asc" ? leftTime - rightTime : rightTime - leftTime;
  });
  const page = items.slice(0, pageSize);
  return okArtifactResponse({
    resultType: "artifact",
    status: "ok",
    artifactType: "run_result",
    action: "query",
    stateSurface: "run_state",
    projectName: args.projectName,
    suiteType: "security",
    projectionVersion: 1,
    pageSize,
    hasMore: items.length > page.length,
    sort: { field: "updatedAtEpochMs", direction: args.query.sortDirection },
    items: page,
    operationalState: {
      source: "sqlite",
      status: "unavailable",
      reasonCode: "security_diagnostic_sqlite_unavailable",
      detail: "security_run_projection_not_populated",
    },
  });
}

export async function handleRunResultQuery(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"run_result">,
  projectName: string,
): Promise<ArtifactActionResult> {
  if (
    request.input.stateSurface === "run_state" &&
    (request.input.suiteType === "security" || request.input.query?.suiteType === "security")
  ) {
    return querySecurityRunArtifacts({
      workspaceRootAbs: ctx.workspaceRootAbs,
      projectName,
      query: request.input.query ?? {
        suiteType: "security",
        sortDirection: "desc",
        pageSize: 10,
      },
    });
  }
  if (request.input.stateSurface === "watcher_state") {
    const watcherQuery = request.input.query;
    const misplacedWatcherFilters = [
      "suiteRunId",
      "planName",
      "runId",
      "watcherName",
      "providerType",
      "status",
      "outcome",
      "reasonCode",
    ].some((key) => Object.prototype.hasOwnProperty.call(watcherQuery ?? {}, key));
    if (misplacedWatcherFilters) {
      return buildFailClosedArtifactResponse({
        reasonCode: "watcher_state_query_invalid",
        reason: "Watcher filters must be nested under query.filters",
      });
    }
    const queried = await queryWatcherState({
      workspaceRootAbs: ctx.workspaceRootAbs,
      input: {
        projectName,
        ...(request.input.query?.filters ? { filters: request.input.query.filters } : {}),
        ...(request.input.query?.sort ? { sort: request.input.query.sort } : {}),
        ...(request.input.query?.page ? { page: request.input.query.page } : {}),
        ...(request.input.query?.detail ? { detail: request.input.query.detail } : {}),
      },
    });
    if (!queried.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: queried.reasonCode,
        reason: queried.reason,
        ...(queried.reasonMeta ? { reasonMeta: queried.reasonMeta } : {}),
      });
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      stateSurface: queried.stateSurface,
      projectName: queried.projectName,
      projectionVersion: queried.projectionVersion,
      pageSize: queried.pageSize,
      hasMore: queried.hasMore,
      sort: queried.sort,
      items: queried.items,
      ...(queried.nextCursor ? { nextCursor: queried.nextCursor } : {}),
    });
  }
  if (request.input.stateSurface === "correlation_state") {
    const queried = await queryCorrelationState({
      workspaceRootAbs: ctx.workspaceRootAbs,
      input: {
        projectName,
        ...(request.input.query?.filters ? { filters: request.input.query.filters } : {}),
        ...(request.input.query?.sort ? { sort: request.input.query.sort } : {}),
        ...(request.input.query?.page ? { page: request.input.query.page } : {}),
        ...(request.input.query?.detail ? { detail: request.input.query.detail } : {}),
      },
    });
    if (!queried.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: queried.reasonCode,
        reason: queried.reason,
        ...(queried.reasonMeta ? { reasonMeta: queried.reasonMeta } : {}),
      });
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      stateSurface: queried.stateSurface,
      projectName: queried.projectName,
      projectionVersion: queried.projectionVersion,
      pageSize: queried.pageSize,
      sort: queried.sort,
      items: queried.items,
      ...(queried.nextCursor ? { nextCursor: queried.nextCursor } : {}),
    });
  }
  if (request.input.stateSurface !== "run_state") {
    return buildFailClosedArtifactResponse({
      reasonCode: "run_state_query_invalid",
      reason: "stateSurface must be 'run_state' for run_result query",
      reasonMeta: { artifactType: request.artifactType, action: request.action },
    });
  }
  const runQuery = asRecord(request.input.query);
  const runFilters = asRecord(runQuery?.filters);
  let suiteRunId: string | undefined;
  if (typeof runFilters?.suiteRunId === "string") suiteRunId = runFilters.suiteRunId;
  else if (typeof runQuery?.suiteRunId === "string") suiteRunId = runQuery.suiteRunId;
  const queried = await queryRunState({
    workspaceRootAbs: ctx.workspaceRootAbs,
    input: {
      projectName,
      ...(request.input.query?.planName ? { planName: request.input.query.planName } : {}),
      ...(request.input.query?.runId ? { runId: request.input.query.runId } : {}),
      ...(suiteRunId ? { suiteRunId } : {}),
      ...(request.input.query?.executionProfile
        ? { executionProfile: request.input.query.executionProfile }
        : {}),
      ...(request.input.query?.status ? { status: request.input.query.status } : {}),
      ...(request.input.query?.activePhase ? { activePhase: request.input.query.activePhase } : {}),
      ...(request.input.query?.startedFromEpochMs !== undefined
        ? { startedFromEpochMs: request.input.query.startedFromEpochMs }
        : {}),
      ...(request.input.query?.startedToEpochMs !== undefined
        ? { startedToEpochMs: request.input.query.startedToEpochMs }
        : {}),
      ...(request.input.query?.completedFromEpochMs !== undefined
        ? { completedFromEpochMs: request.input.query.completedFromEpochMs }
        : {}),
      ...(request.input.query?.completedToEpochMs !== undefined
        ? { completedToEpochMs: request.input.query.completedToEpochMs }
        : {}),
      ...(request.input.query?.sortDirection
        ? { sortDirection: request.input.query.sortDirection }
        : {}),
      ...(request.input.query?.pageSize !== undefined
        ? { pageSize: request.input.query.pageSize }
        : {}),
      ...(request.input.query?.cursor ? { cursor: request.input.query.cursor } : {}),
    },
  });
  if (!queried.ok) {
    return buildFailClosedArtifactResponse({
      reasonCode: queried.reasonCode,
      reason: queried.reason,
      ...(queried.reasonMeta ? { reasonMeta: queried.reasonMeta } : {}),
    });
  }
  return okArtifactResponse({
    resultType: "artifact",
    status: "ok",
    artifactType: request.artifactType,
    action: request.action,
    stateSurface: queried.stateSurface,
    projectName: queried.projectName,
    projectionVersion: queried.projectionVersion,
    pageSize: queried.pageSize,
    hasMore: queried.hasMore,
    sort: queried.sort,
    items: queried.items,
    ...(queried.nextCursor ? { nextCursor: queried.nextCursor } : {}),
  });
}
