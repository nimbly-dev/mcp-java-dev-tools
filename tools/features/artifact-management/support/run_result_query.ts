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

export async function handleRunResultQuery(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"run_result">,
  projectName: string,
): Promise<ArtifactActionResult> {
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
  const suiteRunId =
    typeof runFilters?.suiteRunId === "string"
      ? runFilters.suiteRunId
      : typeof runQuery?.suiteRunId === "string"
        ? runQuery.suiteRunId
        : undefined;
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
